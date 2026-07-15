package dev.iustitia.replay

import dev.iustitia.Iustitia
import dev.iustitia.config.ConfigManager
import dev.iustitia.tracking.TrackedPlayer
import java.util.ArrayDeque
import java.util.UUID

/**
 * Manual long-recording buffer for `/ius record start|stop`. Unlike the always-on 60 s rolling
 * [ReplayBuffer] (which `/ius replay` + `/ius clip` dump the last N seconds of), this is an
 * **explicit, opt-in** capture of arbitrary length — bounded at [MAX_RECORD_FRAMES] (10 min) per
 * segment. `start` snapshots the loaded world map once (the same synchronous [ChunkCapture.capture]
 * `/ius clip` uses) and begins accumulating per-tick frames; `stop` bundles frames + map into a
 * [ReplayBuffer.Window] and writes a normal `.iusclip` via [ClipStore.save] — so `/ius playclip
 * <name>` round-trips a recording exactly like a clip.
 *
 * ## Auto-split (world change + 10-min cap)
 *
 * A genuine world/seed change is detected instantly by [dev.iustitia.mixin.ClientPlayNetworkHandlerMixin]
 * (seed + dimension comparison on `onPlayerRespawn` / `onGameJoin`) → [dev.iustitia.Iustitia.resetAll],
 * which calls [onWorldChange]. If a recording is active, the current segment auto-saves (silent) and
 * a FRESH map is captured for the new world, keeping `recording=true` — one segment per world, no
 * data lost. Block-only updates within the same world are ignored (the first captured map is kept).
 * Hitting [MAX_RECORD_FRAMES] likewise auto-saves + starts a new segment (the 10-min cap, no data
 * loss). No 5 s poll — the existing instant detector is the only split signal.
 *
 * ## Memory + threading
 *
 * Reuses [ReplayBuffer.Frame] / [ReplayBuffer.PlayerSnap] / [ReplayBuffer.AlertRec] /
 * [ReplayBuffer.TotemRec] — no new snap type. The per-tick work is identical to
 * [ReplayBuffer.recordTick] (already profiled as negligible); a second growable buffer only matters
 * while a recording is active. Worst case at the 10-min cap is ~64 players × 12000 frames ≈ ~770k
 * small data-class instances; the cap auto-save+clear bounds it. [recordTick] runs on the client
 * tick thread (from [dev.iustitia.Iustitia.onClientTick]); [start]/[stop]/[saveSegment] run on the
 * client thread (command handlers). The deques are synchronized on the deque object — coarse but
 * correct; the per-tick work is tiny. All paths fail-open — a capture/IO error never stalls the tick
 * or crashes the client.
 */
object RecordManager {

    /** Max recording length per segment: 10 min @ 20 tps. Hitting it auto-saves + starts a new segment. */
    private const val MAX_RECORD_FRAMES = 600 * 20
    private const val MAX_PLAYERS_PER_FRAME = 64
    private const val MAX_ALERTS = 40000
    private const val MAX_TOTEMS = 4000

    private val frames: ArrayDeque<ReplayBuffer.Frame> = ArrayDeque()
    private val alerts: ArrayDeque<ReplayBuffer.AlertRec> = ArrayDeque()
    private val totems: ArrayDeque<ReplayBuffer.TotemRec> = ArrayDeque()

    private var recording: Boolean = false
    private var segmentStartTick: Int = 0
    private var segmentIndex: Int = 0
    /** The one-shot world map captured at [start] (or re-captured on a world-change auto-split). Null
     *  when [dev.iustitia.config.IustitiaConfig.clipChunkWorld] is off → a ghosts-only segment. */
    private var chunkMap: ChunkSnapshot? = null

    val isRecording: Boolean get() = recording

    /** Currently buffered frame count (diagnostic / feedback). Fail-open. */
    fun frameCount(): Int = try { synchronized(frames) { frames.size } } catch (_: Throwable) { 0 }

    /**
     * Begin a recording. Idempotent — a second `start` while recording returns a "already
     * recording" message and does nothing. Captures the loaded world map once (synchronous, same
     * one-shot hitch `/ius clip` has) gated on `clipChunkWorld`; fail-open to a ghosts-only segment.
     * Returns a chat-feedback string.
     */
    fun start(): String = try {
        if (recording) return "$tag §7already recording §8(§f${frameCount()}§7 frames so far) — §f/ius record stop§7 to save."
        val cfg = ConfigManager.config
        recording = true
        segmentStartTick = Iustitia.tickCounter
        segmentIndex += 1
        synchronized(frames) { frames.clear() }
        synchronized(alerts) { alerts.clear() }
        synchronized(totems) { totems.clear() }
        chunkMap = if (cfg.clipChunkWorld) {
            try { ChunkCapture.capture(try { cfg.clipChunkRadius } catch (_: Throwable) { 8 }) } catch (_: Throwable) { null }
        } else null
        val mapTxt = if (chunkMap != null) " §7+ map" else ""
        "$tag §7recording started §8(segment §f$segmentIndex§8)§7 — §f/ius record stop§7 to save$mapTxt§7."
    } catch (_: Throwable) {
        recording = false
        "$tag §cfailed to start recording."
    }

    /**
     * Stop + save the current segment as `<name>.iusclip` (default `record_<startTick>_<idx>`).
     * Returns a chat-feedback string. Idempotent — `stop` while not recording just says so.
     */
    fun stop(name: String?): String = try {
        if (!recording) return "$tag §7not recording. §f/ius record start§7 to begin."
        val saved = saveSegment(name)
        recording = false
        synchronized(frames) { frames.clear() }
        synchronized(alerts) { alerts.clear() }
        synchronized(totems) { totems.clear() }
        chunkMap = null
        if (saved == null) "$tag §cfailed to write recording (disk error)."
        else "$tag §7recording saved: §f$saved§7 §8(${frameCountBeforeClear} frames) §7→ §f${ClipStore.dirDisplay()}"
    } catch (_: Throwable) {
        recording = false
        "$tag §cfailed to save recording."
    }

    /** Frame count captured BEFORE [stop] clears the deque (for the feedback line). */
    private var frameCountBeforeClear: Int = 0

    /**
     * Record one tick of the scene into the growable buffer. Call from the client tick thread after
     * [ReplayBuffer.recordTick]. No-op when not recording. On hitting [MAX_RECORD_FRAMES] the current
     * segment auto-saves (silent, no command ctx) and a new segment begins — the 10-min cap, no data
     * lost. Fail-open: a capture error never stalls the tick.
     */
    fun recordTick(tick: Int, tracked: Collection<TrackedPlayer>) {
        if (!recording) return
        try {
            val snaps = ArrayList<ReplayBuffer.PlayerSnap>(minOf(tracked.size, MAX_PLAYERS_PER_FRAME))
            for (tp in tracked) {
                if (snaps.size >= MAX_PLAYERS_PER_FRAME) break
                val snap = ReplayBuffer.buildSnap(tp) ?: continue
                snaps.add(snap)
            }
            synchronized(frames) { frames.addLast(ReplayBuffer.Frame(tick, snaps)) }
            // 10-min cap: auto-save this segment silently, then start a fresh one. Done outside the
            // frames lock so [saveSegment] (which re-locks) and [ChunkCapture.capture] (a one-shot
            // world read) don't hold the deque lock during their work. A one-frame race with [stop]
            // is benign — worst case a frame or two extra lands in the saved segment.
            if (frameCount() > MAX_RECORD_FRAMES) {
                saveSegment(null)
                synchronized(frames) { frames.clear() }
                synchronized(alerts) { alerts.clear() }
                synchronized(totems) { totems.clear() }
                segmentStartTick = tick
                segmentIndex += 1
                val cfg = ConfigManager.config
                chunkMap = if (cfg.clipChunkWorld) {
                    try { ChunkCapture.capture(try { cfg.clipChunkRadius } catch (_: Throwable) { 8 }) } catch (_: Throwable) { null }
                } else null
            }
        } catch (_: Throwable) {
            // fail-open
        }
    }

    /** Record a real alert event into the active recording (mirrors [ReplayBuffer.recordAlert]). */
    fun recordAlert(tick: Int, uuid: UUID, name: String, checkId: String, label: String, vl: Double) {
        if (!recording) return
        try {
            synchronized(alerts) {
                alerts.addLast(
                    ReplayBuffer.AlertRec(tick, uuid.mostSignificantBits, uuid.leastSignificantBits, name, checkId, label, vl.toFloat())
                )
                while (alerts.size > MAX_ALERTS) alerts.removeFirst()
            }
        } catch (_: Throwable) {}
    }

    /** Record a Totem-of-Undying pop into the active recording (mirrors [ReplayBuffer.recordTotemPop]). */
    fun recordTotemPop(tick: Int, uuid: UUID) {
        if (!recording) return
        try {
            synchronized(totems) {
                totems.addLast(ReplayBuffer.TotemRec(tick, uuid.mostSignificantBits, uuid.leastSignificantBits))
                while (totems.size > MAX_TOTEMS) totems.removeFirst()
            }
        } catch (_: Throwable) {}
    }

    /**
     * World-change auto-split (called from [dev.iustitia.Iustitia.resetAll]). If recording, silently
     * save the current segment, capture a FRESH map for the new world, and keep `recording=true` —
     * one segment per world. If not recording, no-op. Fail-open.
     */
    fun onWorldChange() {
        if (!recording) return
        try {
            saveSegment(null)
            synchronized(frames) { frames.clear() }
            synchronized(alerts) { alerts.clear() }
            synchronized(totems) { totems.clear() }
            segmentStartTick = Iustitia.tickCounter
            segmentIndex += 1
            val cfg = ConfigManager.config
            chunkMap = if (cfg.clipChunkWorld) {
                try { ChunkCapture.capture(try { cfg.clipChunkRadius } catch (_: Throwable) { 8 }) } catch (_: Throwable) { null }
            } else null
        } catch (_: Throwable) {}
    }

    /**
     * Bundle the current frames + alerts + totems + the captured map into a [ReplayBuffer.Window]
     * and write `<name>.iusclip` (default `record_<startTick>_<idx>`). Returns the saved display
     * name, or null on any IO/codec error. Caller owns clearing the deques after. Fail-open.
     */
    private fun saveSegment(name: String?): String? = try {
        frameCountBeforeClear = frameCount()
        val outFrames: List<ReplayBuffer.Frame>
        val outAlerts: List<ReplayBuffer.AlertRec>
        val outTotems: List<ReplayBuffer.TotemRec>
        synchronized(frames) { outFrames = frames.toList() }
        synchronized(alerts) { outAlerts = alerts.toList() }
        synchronized(totems) { outTotems = totems.toList() }
        if (outFrames.isEmpty()) return null
        val window = ReplayBuffer.Window(outFrames, outAlerts, chunks = chunkMap, totems = outTotems)
        val segName = name ?: "record_${segmentStartTick}_$segmentIndex"
        ClipStore.save(segName, window, null)
    } catch (_: Throwable) { null }

    private val tag: String get() = "§8[§diustitia§8]"
}