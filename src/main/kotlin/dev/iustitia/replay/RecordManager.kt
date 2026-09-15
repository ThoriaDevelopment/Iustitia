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
 * segment. `start` begins an incremental world capture ([ChunkRollingCapture]) and accumulates
 * per-tick frames + block deltas; `stop` bundles frames + alerts + totems + the captured world
 * (segments) into a [ReplayBuffer.Window] and writes a normal `.iusclip` via [ClipStore.save] — so
 * `/ius playclip <name>` round-trips a recording exactly like a clip.
 *
 * ## Auto-split (world change + 10-min cap)
 *
 * A genuine world/seed change is detected instantly by [dev.iustitia.mixin.ClientPlayNetworkHandlerMixin]
 * (seed + dimension comparison on `onPlayerRespawn` / `onGameJoin`) → [dev.iustitia.Iustitia.resetAll],
 * which calls [onWorldChange]. If a recording is active, the current segment auto-saves (silent) and a
 * FRESH capture starts for the new world, keeping `recording=true` — one segment per world, no data
 * lost. Hitting [MAX_RECORD_FRAMES] likewise auto-saves + starts a new segment (the 10-min cap, no
 * data loss). No polling — the existing instant detector is the only split signal.
 *
 * ## Merge provenance
 *
 * Iustitia's original recorder (frames + alerts + totems + equipment) extended with SnapClip's
 * incremental world + block-delta capture. **The alert timeline is preserved** — SnapClip's recorder
 * dropped it.
 *
 * ## Memory + threading
 *
 * [recordTick] runs on the client tick thread (from [dev.iustitia.Iustitia.onClientTick]);
 * [start]/[stop]/[saveSegment] run on the client thread (command handlers). The deques are
 * synchronized on the deque object — coarse but correct; the per-tick work is tiny. All paths
 * fail-open — a capture/IO error never stalls the tick or crashes the client.
 */
object RecordManager {

    /** Max recording length per segment: 10 min @ 20 tps. Hitting it auto-saves + starts a new segment. */
    private const val MAX_RECORD_FRAMES = 600 * 20
    private const val MAX_PLAYERS_PER_FRAME = 64
    private const val MAX_ALERTS = 40000
    private const val MAX_TOTEMS = 4000
    private const val MAX_RECORD_DELTAS = 120_000

    /** Synthetic segment id for the manual recording's rolling chunk capture (distinct from buffer segments). */
    private const val RECORD_SEGMENT_ID = 1_000_000

    private val frames: ArrayDeque<ReplayBuffer.Frame> = ArrayDeque()
    private val alerts: ArrayDeque<ReplayBuffer.AlertRec> = ArrayDeque()
    private val totems: ArrayDeque<ReplayBuffer.TotemRec> = ArrayDeque()
    private val blockDeltas: ArrayDeque<BlockDeltaBuffer.BlockDelta> = ArrayDeque()

    private var recording: Boolean = false
    private var segmentStartTick: Int = 0
    private var segmentIndex: Int = 0
    val isRecording: Boolean get() = recording

    /** Currently buffered frame count (diagnostic / feedback). Fail-open. */
    fun frameCount(): Int = try { synchronized(frames) { frames.size } } catch (_: Throwable) { 0 }

    /** Frame count captured BEFORE [stop] clears the deque (for the feedback line). */
    private var frameCountBeforeClear: Int = 0

    private val tag: String get() = "§8[§diustitia§8]"

    /**
     * Begin a recording. Idempotent — a second `start` while recording returns an "already recording"
     * message and does nothing. Begins incremental world capture ([ChunkRollingCapture]) gated on
     * `clipChunkWorld`; fail-open to a ghosts-only segment. Returns a chat-feedback string.
     */
    fun start(): String = try {
        // Yield long-recording to SnapClip when it's installed (it owns /record).
        if (dev.iustitia.compat.CompanionMods.snapClip) return "$tag §7recording is handled by §fSnapClip§7 (installed) — use its §f/record start§7."
        if (recording) return "$tag §7already recording §8(§f${frameCount()}§7 frames so far) — §f/ius record stop§7 to save."
        val cfg = ConfigManager.config
        recording = true
        segmentStartTick = Iustitia.tickCounter
        segmentIndex += 1
        synchronized(frames) { frames.clear() }
        synchronized(alerts) { alerts.clear() }
        synchronized(totems) { totems.clear() }
        synchronized(blockDeltas) { blockDeltas.clear() }
        var mapOn = false
        if (cfg.clipChunkWorld) {
            try {
                val p = net.minecraft.client.MinecraftClient.getInstance().player
                if (p != null) {
                    val radius = try { cfg.clipChunkRadius } catch (_: Throwable) { 8 }
                    val pcx = Math.floorDiv(p.x.toInt(), 16)
                    val pcz = Math.floorDiv(p.z.toInt(), 16)
                    ChunkRollingCapture.clearSegment(RECORD_SEGMENT_ID)
                    ChunkRollingCapture.onSegmentStart(RECORD_SEGMENT_ID, pcx, pcz, radius)
                    mapOn = true
                }
            } catch (_: Throwable) {}
        }
        val mapTxt = if (mapOn) " §7+ map" else ""
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
        synchronized(blockDeltas) { blockDeltas.clear() }
        try { ChunkRollingCapture.clearSegment(RECORD_SEGMENT_ID) } catch (_: Throwable) {}
        if (saved == null) "$tag §cfailed to write recording (disk error)."
        else "$tag §7recording saved: §f$saved§7 §8(${frameCountBeforeClear} frames) §7→ §f${ClipStore.dirDisplay()}"
    } catch (_: Throwable) {
        recording = false
        "$tag §cfailed to save recording."
    }

    /**
     * Record one tick of the scene into the growable buffer. Call from the client tick thread after
     * [ReplayBuffer.recordTick]. No-op when not recording. On hitting [MAX_RECORD_FRAMES] the current
     * segment auto-saves (silent) and a new segment begins — the 10-min cap, no data lost. Fail-open.
     */
    fun recordTick(tick: Int, tracked: Collection<TrackedPlayer>) {
        if (!recording) return
        // Defensive: start()/onWorldChange() already refuse to begin a recording while SnapClip owns
        // /record, but a recording begun before the companion was noticed must not keep buffering.
        if (dev.iustitia.compat.CompanionMods.snapClip) return
        try {
            val cfg = ConfigManager.config   // one config snapshot for the whole tick
            val snaps = ArrayList<ReplayBuffer.PlayerSnap>(minOf(tracked.size, MAX_PLAYERS_PER_FRAME))
            for (tp in tracked) {
                if (snaps.size >= MAX_PLAYERS_PER_FRAME) break
                val snap = ReplayBuffer.buildSnap(tp) ?: continue
                snaps.add(snap)
            }
            val mc = net.minecraft.client.MinecraftClient.getInstance()
            val entities = try {
                val world = mc.world
                if (world != null) ReplayBuffer.buildEntitySnaps(world, mc.player, cfg) else emptyList()
            } catch (_: Throwable) { emptyList() }
            try {
                val self = mc.player
                if (cfg.clipChunkWorld && self != null) {
                    val radius = try { cfg.clipChunkRadius } catch (_: Throwable) { 8 }
                    val pcx = Math.floorDiv(self.x.toInt(), 16)
                    val pcz = Math.floorDiv(self.z.toInt(), 16)
                    ChunkRollingCapture.tickCapture(RECORD_SEGMENT_ID, pcx, pcz, radius)
                }
            } catch (_: Throwable) {}
            synchronized(frames) { frames.addLast(ReplayBuffer.Frame(tick, snaps, entities, RECORD_SEGMENT_ID)) }
            if (frameCount() > MAX_RECORD_FRAMES) {
                saveSegment(null)
                synchronized(frames) { frames.clear() }
                synchronized(alerts) { alerts.clear() }
                synchronized(totems) { totems.clear() }
                synchronized(blockDeltas) { blockDeltas.clear() }
                segmentStartTick = tick
                segmentIndex += 1
                restartRollingCapture()
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

    /** Record one observed block change into the active recording (fed by
     *  [dev.iustitia.mixin.WorldChunkMixin]). No-op when not recording / unchanged. Fail-open. */
    fun recordDelta(tick: Int, x: Int, y: Int, z: Int, before: String, after: String) {
        if (!recording) return
        if (before == after) return
        try {
            synchronized(blockDeltas) {
                blockDeltas.addLast(BlockDeltaBuffer.BlockDelta(tick, x, y, z, before, after))
                while (blockDeltas.size > MAX_RECORD_DELTAS) blockDeltas.removeFirst()
            }
        } catch (_: Throwable) {}
    }

    /**
     * World-change auto-split (called from [dev.iustitia.Iustitia.resetAll]). If recording, silently
     * save the current segment, reset capture for the new world, and keep `recording=true` — one
     * segment per world. If not recording, no-op. Fail-open.
     */
    fun onWorldChange() {
        if (!recording) return
        try {
            saveSegment(null)
            synchronized(frames) { frames.clear() }
            synchronized(alerts) { alerts.clear() }
            synchronized(totems) { totems.clear() }
            synchronized(blockDeltas) { blockDeltas.clear() }
            segmentStartTick = Iustitia.tickCounter
            segmentIndex += 1
            restartRollingCapture()
        } catch (_: Throwable) {}
    }

    /** Re-init the recording's rolling chunk capture for a fresh segment (fail-open). */
    private fun restartRollingCapture() {
        try {
            val cfg = ConfigManager.config
            try { ChunkRollingCapture.clearSegment(RECORD_SEGMENT_ID) } catch (_: Throwable) {}
            if (!cfg.clipChunkWorld) return
            val p = net.minecraft.client.MinecraftClient.getInstance().player ?: return
            val radius = try { cfg.clipChunkRadius } catch (_: Throwable) { 8 }
            val pcx = Math.floorDiv(p.x.toInt(), 16)
            val pcz = Math.floorDiv(p.z.toInt(), 16)
            ChunkRollingCapture.onSegmentStart(RECORD_SEGMENT_ID, pcx, pcz, radius)
        } catch (_: Throwable) {}
    }

    /**
     * Bundle the current frames + alerts + totems + block deltas + the rolled-up world into a
     * [ReplayBuffer.Window] (one [ReplayBuffer.Segment]) and write `<name>.iusclip` (default
     * `record_<startTick>_<idx>`). Returns the saved display name, or null on any IO/codec error.
     * Caller owns clearing the deques after. Fail-open.
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

        val map = try { ChunkRollingCapture.snapshotForSegment(RECORD_SEGMENT_ID) } catch (_: Throwable) { null }
        val dimKey = try {
            net.minecraft.client.MinecraftClient.getInstance().world?.registryKey?.value?.toString() ?: "?"
        } catch (_: Throwable) { "?" }
        if (map != null) try { map.dimension = dimKey } catch (_: Throwable) {}

        val deltas: List<BlockDeltaBuffer.BlockDelta> = if (map == null) emptyList() else try {
            val minTick = outFrames.first().tick
            val maxTick = outFrames.last().tick
            val chunkKeys = buildSet {
                for (c in map.chunks) add((c.chunkX.toLong() shl 32) or (c.chunkZ.toLong() and 0xFFFFFFFFL))
            }
            synchronized(blockDeltas) {
                blockDeltas.filter { d ->
                    d.tick in minTick..maxTick && chunkKeys.contains((d.x shr 4).toLong().let { (it shl 32) or ((d.z shr 4).toLong() and 0xFFFFFFFFL) })
                }.toList()
            }
        } catch (_: Throwable) { emptyList() }

        val segments = listOf(
            ReplayBuffer.Segment(dimKey, 0, map, deltas, snapshotIsStart = true)
        )
        // The world lives in the segment (v13+). [ReplayBuffer.Window.chunks] stays null to avoid
        // writing the same chunk snapshot twice; the play path derives chunks from the segment.
        val window = ReplayBuffer.Window(
            outFrames, outAlerts,
            chunks = null, totems = outTotems, segments = segments,
        )
        val segName = name ?: "record_${segmentStartTick}_$segmentIndex"
        ClipStore.save(segName, window, null)
    } catch (_: Throwable) { null }
}
