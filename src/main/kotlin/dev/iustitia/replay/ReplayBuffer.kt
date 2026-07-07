package dev.iustitia.replay

import dev.iustitia.config.ConfigManager
import dev.iustitia.tracking.TrackedPlayer
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Rolling capture buffer of every OTHER player's server-space position (+ yaw + pose) and every
 * real alert event, kept for the last [MAX_SECONDS] (60s) of ticks. The single data source for
 * `/ius replay` (replay the last N seconds in-world) and `/ius clip` (dump the last N seconds to a
 * portable `.iusclip` file).
 *
 * ## Why this exists separately from [dev.iustitia.tracking.PositionRingBuffer]
 *
 * The reach-check ring buffer is 24 ticks (~1.2s) and per-player — far too short and not designed
 * for full-scene playback. Replay/clip need a continuous, scene-wide, multi-second buffer, so this
 * is its own bounded structure: a rolling [ArrayDeque] of [Frame]s (one per tick) + a rolling deque
 * of [AlertRec]s. All paths fail-open — a capture error is swallowed so it never stalls the tick.
 *
 * ## Memory bounds
 *
 * Frames cap at [MAX_FRAMES] (1200 = 60s @ 20 tps); adding past the cap drops the oldest. Each frame
 * holds at most [MAX_PLAYERS_PER_FRAME] snaps (the rest are dropped — a 64-player cap covers any
 * realistic PvP lobby and bounds the worst case at ~64 × 1200 ≈ 77k small data-class instances ≈ a
 * few MB). Alerts cap at [MAX_ALERTS] (drop oldest). Recording is gated by [IustitiaConfig.replayCapture]
 * (default on) so a user who never uses replay/clip can disable the per-tick work.
 *
 * ## Threading
 *
 * [recordTick] runs on the client tick thread (from [dev.iustitia.Iustitia.onClientTick]); [recordAlert]
 * runs on the client thread too (from [dev.iustitia.alert.AlertManager.alert]). Reads ([snapshot],
 * [dumpFrames]) run on the client thread (command handlers). The deques are synchronized on the
 * deque object itself — coarse but correct; the per-tick work is tiny.
 */
object ReplayBuffer {

    /** Max replay/clip window in seconds. `/ius replay|clip` clamp their `seconds` arg to this. */
    const val MAX_SECONDS = 60
    private const val MAX_FRAMES = MAX_SECONDS * 20
    private const val MAX_PLAYERS_PER_FRAME = 64
    private const val MAX_ALERTS = 4000

    /** Pose encoding for [PlayerSnap.pose] (1 byte). Mirrors [TrackedPlayer] pose derivation. */
    const val POSE_STAND: Byte = 0
    const val POSE_SNEAK: Byte = 1
    const val POSE_GLIDE: Byte = 2
    const val POSE_SWIM: Byte = 3
    const val POSE_RIPTIDE: Byte = 4

    /** One captured player at one tick. UUID stored as two longs (no [UUID] object retained per snap);
     *  [name] is captured so a ghost/clip can be labeled even after the player logs off. [pitch] is the
     *  look pitch (v3+ clips / live buffer); v2 clips default it to 0 on load — a v2 ghost won't tilt
     *  its head but still faces its yaw. [swingTicks] is the hand-swing phase (v4+ clips / live buffer);
     *  older clips default it to 0 — a pre-v4 ghost's arms hang still but still walk/face/tilt. TrackedPlayer
     *  has no separate head-yaw, so body yaw IS the look yaw. */
    data class PlayerSnap(
        val uuidMost: Long, val uuidLeast: Long,
        val x: Float, val y: Float, val z: Float, val yaw: Float, val pitch: Float,
        val swingTicks: Int, val pose: Byte,
        val name: String,
    ) {
        fun uuid(): UUID = UUID(uuidMost, uuidLeast)
    }

    /** One captured tick: the tick number + the snaps of the (≤ [MAX_PLAYERS_PER_FRAME]) players present. */
    data class Frame(val tick: Int, val snaps: List<PlayerSnap>)

    /** One captured real alert event (post-throttle). UUID stored as two longs. */
    data class AlertRec(
        val tick: Int, val uuidMost: Long, val uuidLeast: Long,
        val name: String, val checkId: String, val label: String, val vl: Float,
    ) {
        fun uuid(): UUID = UUID(uuidMost, uuidLeast)
    }

    private val frames: ArrayDeque<Frame> = ArrayDeque()
    private val alerts: ArrayDeque<AlertRec> = ArrayDeque()

    /** Snapshot frames + alerts for a replay/clip, copied out so live recording can't mutate playback.
     *  [terrain] is only set on clip exports ([TerrainCapture] runs in the `/ius clip` handler); a live
     *  `/ius replay` snapshot leaves it null — replay never bundles the map (you're already on it).
     *  [chunks] (v6+) is the full-chunk world capture ([ChunkCapture]) — also clip-only, null for replay
     *  and pre-v6 clips; supersedes [terrain] for the solid-world render path (terrain stays as the v5
     *  wireframe fallback). */
    data class Window(
        val frames: List<Frame>, val alerts: List<AlertRec>,
        val terrain: TerrainSnapshot? = null, val chunks: ChunkSnapshot? = null,
    )

    /** True when the capture buffer is turned on ([IustitiaConfig.replayCapture]). Fail-open. */
    private val enabled: Boolean get() = try { ConfigManager.config.replayCapture } catch (_: Throwable) { false }

    /** Record one tick of the scene. Call from the client tick thread after [dev.iustitia.tracking.EntityTrackerManager.poll]. */
    fun recordTick(tick: Int, tracked: Collection<TrackedPlayer>) {
        if (!enabled) return
        try {
            val snaps = ArrayList<PlayerSnap>(minOf(tracked.size, MAX_PLAYERS_PER_FRAME))
            for (tp in tracked) {
                if (snaps.size >= MAX_PLAYERS_PER_FRAME) break
                try {
                    val u = tp.uuid
                    val nm = tp.username().ifEmpty { u.toString().take(8) }
                    snaps.add(
                        PlayerSnap(
                            u.mostSignificantBits, u.leastSignificantBits,
                            tp.pos.x.toFloat(), tp.pos.y.toFloat(), tp.pos.z.toFloat(),
                            tp.yaw, tp.pitch, tp.handSwingTicks, poseOf(tp), nm,
                        )
                    )
                } catch (_: Throwable) {
                    // skip one bad player, keep going
                }
            }
            synchronized(frames) {
                frames.addLast(Frame(tick, snaps))
                while (frames.size > MAX_FRAMES) frames.removeFirst()
            }
        } catch (_: Throwable) {
            // fail-open: a capture error never stalls the tick
        }
    }

    /** Record a real alert event (called from [dev.iustitia.alert.AlertManager.alert] after throttle). */
    fun recordAlert(tick: Int, uuid: UUID, name: String, checkId: String, label: String, vl: Double) {
        if (!enabled) return
        try {
            synchronized(alerts) {
                alerts.addLast(
                    AlertRec(tick, uuid.mostSignificantBits, uuid.leastSignificantBits, name, checkId, label, vl.toFloat())
                )
                while (alerts.size > MAX_ALERTS) alerts.removeFirst()
            }
        } catch (_: Throwable) {
            // fail-open
        }
    }

    /**
     * Snapshot the last [seconds] of frames + alerts for a replay or clip. [seconds] is clamped to
     * [MAX_SECONDS]; frames older than the window are excluded. Returns a defensive copy (the deques
     * keep advancing live without affecting the returned lists). Fail-open: empty on error.
     */
    fun snapshot(seconds: Int, tick: Int): Window = try {
        val secs = seconds.coerceIn(1, MAX_SECONDS)
        val frameBudget = secs * 20
        val tickFloor = tick - frameBudget
        val outFrames: List<Frame>
        val outAlerts: List<AlertRec>
        synchronized(frames) {
            outFrames = if (frames.size <= frameBudget) frames.toList()
            else frames.toList().takeLast(frameBudget)
        }
        synchronized(alerts) {
            outAlerts = alerts.filter { it.tick >= tickFloor }
        }
        Window(outFrames, outAlerts)
    } catch (_: Throwable) {
        Window(emptyList(), emptyList())
    }

    /** Currently buffered frame count (diagnostic). */
    fun frameCount(): Int = try { synchronized(frames) { frames.size } } catch (_: Throwable) { 0 }

    /** Clear the buffer (wired to [dev.iustitia.Iustitia.resetAll] on world/dimension change). */
    fun reset() {
        try { synchronized(frames) { frames.clear() } } catch (_: Throwable) {}
        try { synchronized(alerts) { alerts.clear() } } catch (_: Throwable) {}
    }

    private fun poseOf(tp: TrackedPlayer): Byte = when {
        tp.gliding -> POSE_GLIDE
        tp.swimming -> POSE_SWIM
        tp.riptide -> POSE_RIPTIDE
        tp.sneaking -> POSE_SNEAK
        else -> POSE_STAND
    }
}