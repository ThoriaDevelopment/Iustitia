package dev.iustitia.replay

import java.util.UUID

/**
 * Active replay/clip-playback state (Phase 2 "instant replay"): holds an immutable snapshot of the
 * captured frames + alerts, advances a [playhead] through them at [speed] (0.5× / 0.25×), and is read
 * cross-thread by [dev.iustitia.render.ReplayRenderer] to draw ghosts at the playhead frame.
 *
 * ## Why the frames are an immutable snapshot
 *
 * [ReplayBuffer] keeps advancing every tick. A replay copies out its window once at [start], so the
 * live recording never mutates what's playing back — the playhead sweeps a fixed list. The list is
 * never mutated after [start], so the render thread can read it lock-free alongside the client-thread
 * [playhead] (a `@Volatile Float`).
 *
 * ## "Rewind feel" — hiding live players
 *
 * When [hideLive] is true (default; the chosen replay mode), [dev.iustitia.mixin.EntityRendererMixin]
 * cancels `shouldRender` for every OTHER player while [active], so only the buffered ghosts render —
 * a "rewind the world" feel. The live game + detection keep running under the hood; only rendering is
 * swapped, and it snaps back the instant [active] goes false. Toggle off in config to overlay ghosts
 * alongside live players instead.
 *
 * ## Safety
 *
 * There is no camera override here (unlike the watch follow-cam) — you keep your own view; the ghosts
 * render in-world around you. [active] is `@Volatile`; a stop (keybind re-run, world change, finished,
 * target-gone) flips it false and the very next frame renders live again. Fail-open throughout.
 */
object ReplayState {

    /** Replay camera mode. FREE keeps your own view (ghosts render around you); FOLLOW orbits the
     *  focus ghost (mouse-driven, like [dev.iustitia.render.WatchState]); POV puts the camera at the
     *  focus ghost's eye looking along its buffered yaw+pitch (see the world from their view). */
    enum class CameraMode { FREE, FOLLOW, POV }

    /** Supported playback speeds. Stored as "playhead-tick advance per real tick". */
    const val SPEED_FULL = 1.0f
    const val SPEED_HALF = 0.5f   // 0.5× → a 10s clip plays back over 20s
    const val SPEED_QUARTER = 0.25f

    @Volatile var active: Boolean = false
        private set
    @Volatile var focusUuid: UUID? = null
        private set
    @Volatile var hideLive: Boolean = true
        private set
    /** Current camera mode (read by the render-thread camera mixin + the ghost renderer). Ephemeral
     *  — not persisted; defaults to FREE each replay. */
    @Volatile var cameraMode: CameraMode = CameraMode.FREE
        private set
    /** Paused freezes the playhead ([tick] does not advance); seek/step/speed still apply. */
    @Volatile var paused: Boolean = false
        private set

    /** Immutable frame list — set once at [start], read cross-thread. Empty when inactive. */
    private var frames: List<ReplayBuffer.Frame> = emptyList()
    private var alerts: List<ReplayBuffer.AlertRec> = emptyList()

    @Volatile private var playhead: Float = 0f
    @Volatile private var speed: Float = SPEED_HALF
    @Volatile private var lastFrameTick: Int = 0

    /** Saved local-player perspective while POV forces first-person (mirrors [dev.iustitia.render.WatchState]).
     *  Null = nothing saved (not in POV). Client-thread-only writes. */
    private var savedPerspective: net.minecraft.client.option.Perspective? = null

    /**
     * Begin a replay/clip-playback of [window].frames at [speed]. [focus] is the highlighted player
     * (the `/ius replay <player>` subject; null for a generic clip). Returns false if there are no
     * frames (caller reports "no data" and does not activate). Idempotent: starting while active
     * first stops the prior one.
     */
    fun start(window: ReplayBuffer.Window, focus: UUID?, speed: Float, hideLive: Boolean): Boolean {
        try {
            if (window.frames.isEmpty()) return false
            // Idempotent: stop any prior replay first (restores perspective if it was in POV).
            if (active) stop("restarted")
            frames = window.frames
            alerts = window.alerts
            focusUuid = focus
            this.speed = speed
            this.hideLive = hideLive
            playhead = 0f
            paused = false
            cameraMode = CameraMode.FREE
            lastFrameTick = window.frames.last().tick
            active = true
            return true
        } catch (_: Throwable) {
            active = false
            return false
        }
    }

    /** Stop now and return the reason (for the caller to chat). Idempotent. */
    fun stop(reason: String): String {
        try {
            if (!active) return reason
            active = false
            frames = emptyList()
            alerts = emptyList()
            focusUuid = null
            playhead = 0f
            paused = false
            cameraMode = CameraMode.FREE
            restorePerspective()
        } catch (_: Throwable) {
            active = false
        }
        return reason
    }

    /**
     * Advance the playhead one real tick (called from [dev.iustitia.Iustitia.onClientTick], client
     * thread). Returns a non-null reason when the replay just finished (so the caller can chat it),
     * or null while still playing / inactive / paused.
     */
    fun tick(): String? {
        if (!active) return null
        if (paused) return null
        try {
            playhead += speed
            if (playhead >= frames.size) return stop("replay finished")
            return null
        } catch (_: Throwable) {
            return stop("replay error")
        }
    }

    /** The frame at the current playhead (floor), or null when inactive / concurrently stopped. */
    fun currentFrame(): ReplayBuffer.Frame? = try {
        if (!active) return null
        val idx = playhead.toInt()
        if (idx < 0 || idx >= frames.size) null else frames[idx]
    } catch (_: Throwable) { null }

    /** The buffered alerts in this replay (for the on-screen replay HUD / future overlay). */
    fun alerts(): List<ReplayBuffer.AlertRec> = if (active) alerts else emptyList()

    /** Playhead progress 0..1 across the frame list (for a HUD progress bar). */
    fun progress(): Float = try {
        if (!active || frames.isEmpty()) 0f
        else (playhead / frames.size).coerceIn(0f, 1f)
    } catch (_: Throwable) { 0f }

    fun frameCount(): Int = if (active) frames.size else 0
    fun currentSpeed(): Float = speed
    fun isPaused(): Boolean = paused
    fun focusName(): String? = try {
        val u = focusUuid ?: return null
        dev.iustitia.history.FlagHistory.nameFor(u) ?: u.toString().take(8)
    } catch (_: Throwable) { null }

    /** The focus player's snap at the current playhead, or null (no focus / inactive / not in frame).
     *  Read cross-thread by the camera mixin (POV/FOLLOW) + the ghost renderer (skip-focus-in-POV). */
    fun focusSnap(): ReplayBuffer.PlayerSnap? = try {
        if (!active) return null
        val u = focusUuid ?: return null
        currentFrame()?.snaps?.firstOrNull { it.uuid() == u }
    } catch (_: Throwable) { null }

    /** The replay window's first..last captured tick (for mapping alert ticks to HUD progress). */
    fun windowTickRange(): IntRange? = try {
        if (!active || frames.isEmpty()) null else frames.first().tick..frames.last().tick
    } catch (_: Throwable) { null }

    // ---- playback controls (client thread; called from /ius replay subcommands + keybinds) ----

    /** Toggle pause. Returns the new paused state (for chat feedback). */
    fun togglePause(): Boolean { paused = !paused; return paused }

    /** Seek the playhead to [seconds] into the window (clamped 0..frames). Works while playing or paused. */
    fun seekTo(seconds: Float) {
        try {
            if (!active || frames.isEmpty()) return
            val target = (seconds * 20f).coerceIn(0f, (frames.size - 1).toFloat())
            playhead = target
        } catch (_: Throwable) {}
    }

    /** Seek the playhead by [deltaSeconds] (signed; +forward / −back), clamped to the window. Works
     *  while playing OR paused — a live replay can be scrubbed without pausing first (used by the
     *  numpad +/- keybinds). */
    fun seekBy(deltaSeconds: Float) {
        try {
            if (!active || frames.isEmpty()) return
            val target = (playhead + deltaSeconds * 20f).coerceIn(0f, (frames.size - 1).toFloat())
            playhead = target
        } catch (_: Throwable) {}
    }

    /** Step the playhead [frames] ticks (signed; +forward / −back). Only effective while paused so a
     *  live replay isn't fought by the step keys. */
    fun step(frames: Int) {
        try {
            if (!active || this.frames.isEmpty() || !paused) return
            playhead = (playhead + frames).coerceIn(0f, (this.frames.size - 1).toFloat())
        } catch (_: Throwable) {}
    }

    /** Change playback speed mid-replay. Clamped to a supported speed. */
    fun setSpeed(s: Float) {
        try { speed = when (s) { SPEED_FULL, SPEED_HALF, SPEED_QUARTER -> s; else -> SPEED_HALF } } catch (_: Throwable) {}
    }

    /** Cycle playback speed FULL → HALF → QUARTER → FULL. Returns the new speed (for chat/keybind feedback). */
    fun cycleSpeed(): Float {
        speed = when (speed) { SPEED_FULL -> SPEED_HALF; SPEED_HALF -> SPEED_QUARTER; else -> SPEED_FULL }
        return speed
    }

    /** Switch camera mode. Entering POV forces first-person (so your own body doesn't float at your
     *  real position in the ghost's-eye view); leaving POV restores it. Client thread only. */
    fun setCameraMode(mode: CameraMode) {
        try {
            val prev = cameraMode
            cameraMode = mode
            if (prev == mode) return
            val mc = net.minecraft.client.MinecraftClient.getInstance()
            if (mode == CameraMode.POV && prev != CameraMode.POV) {
                if (savedPerspective == null) savedPerspective = mc.options.perspective
                mc.options.setPerspective(net.minecraft.client.option.Perspective.FIRST_PERSON)
            } else if (mode != CameraMode.POV && prev == CameraMode.POV) {
                restorePerspective()
            }
        } catch (_: Throwable) {}
    }

    /** Restore the saved local-player perspective (called on stop / leaving POV). Idempotent. */
    private fun restorePerspective() {
        try {
            val mc = net.minecraft.client.MinecraftClient.getInstance()
            savedPerspective?.let { mc.options.setPerspective(it) }
            savedPerspective = null
        } catch (_: Throwable) {}
    }
}