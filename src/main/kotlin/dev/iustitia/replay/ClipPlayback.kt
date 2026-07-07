package dev.iustitia.replay

import dev.iustitia.config.ConfigManager

/**
 * Shared "load a saved `.iusclip` and start playing it back" helper — the single place that turns a
 * clip name into a running replay. Backs both `/ius playclip <name> [speed]` (chat feedback) and the
 * clip-manager screen's left-click Play (closes the screen + chat feedback), so the two entry points
 * can't drift on the load → validate → start sequence. Pure client thread; fail-open — a missing or
 * empty clip returns [Result.LoadFailed]; a start-time throwable on an otherwise-valid (non-empty)
 * clip returns [Result.StartFailed]. Never throws to its caller.
 */
object ClipPlayback {

    /** Outcome of [start] — callers format their own feedback from this. */
    sealed class Result {
        /** Replay started: [frames] played back, [focus] player highlighted (null = none). */
        class Started(val frames: Int, val focus: java.util.UUID?) : Result()
        /** Clip missing, corrupt, or decoded to an empty window (no frames). */
        object LoadFailed : Result()
        /** Window was loaded but [ReplayState.start] refused it. */
        object StartFailed : Result()
    }

    /**
     * Load `<name>.iusclip`, validate it has frames, and start a replay at [speed] (one of
     * [ReplayState.SPEED_FULL] / [SPEED_HALF] / [SPEED_QUARTER]). Honours the `replayHideLive`
     * config. Never throws — returns a [Result] the caller formats.
     */
    fun start(name: String, speed: Float): Result = try {
        val clip = ClipStore.load(name)
        if (clip == null || clip.window.frames.isEmpty()) {
            Result.LoadFailed
        } else {
            val started = ReplayState.start(clip.window, clip.focus, speed, ConfigManager.config.replayHideLive, relocate = true)
            if (started) {
                // Auto-enter FREECAM so /ius playclip is immediately a free-spectate through the clip's
                // solid world (or the live world for a no-chunk clip — live terrain isn't suppressed
                // when chunks==null, which is fine). Revert on stop is already handled by
                // ReplayState.stop() -> exitFreecam() + restorePerspective() (next frame's camera mixin
                // FREECAM branch returns false -> vanilla re-derives the live-player view). /ius replay
                // never reaches here (it calls ReplayState.start directly), so it keeps its FREE default.
                // Fail-open: a throw leaves the camera in the FREE default set by start — never a crash.
                try { ReplayState.setCameraMode(ReplayState.CameraMode.FREECAM) } catch (_: Throwable) {}
                Result.Started(clip.window.frames.size, clip.focus)
            } else Result.StartFailed
        }
    } catch (_: Throwable) { Result.StartFailed }
}