package dev.iustitia.render

import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.Perspective
import net.minecraft.util.math.Vec3d
import java.util.UUID

/**
 * Watch follow-cam state (Phase B): when [active], [CameraMixin] repoints the camera to a
 * third-person orbit view of the watched OTHER player, **mouse-controlled** (the orbit angle
 * follows the local player's yaw — move the mouse to rotate around the watched player, who stays
 * centered). Toggled by the `watch` keybind on the crosshair target.
 *
 * ## UX invariants (user-confirmed)
 *
 * - **No movement while watching:** if the local player moves > [MOVE_THRESHOLD] blocks from where
 *   watch started, or takes a hit (hurtTime rising edge), watch auto-exits. The camera then reverts
 *   to the local player's view (vanilla re-derives it next frame).
 * - **F1 + third-person during watch:** on enable the HUD is hidden (the F1 equivalent —
 *   `options.hudHidden = true`) and the perspective is forced to **third-person back** so the LOCAL
 *   player's own model renders in the scene (first-person suppresses your body — the user wants all
 *   entities visible, including themselves). Third-person also keeps the viewmodel hand hidden (so
 *   the earlier "hand pops in" bug stays fixed). The prior HUD-hidden + perspective state is saved
 *   and restored on exit; F5 is effectively ignored (re-clamped each tick). The camera itself is
 *   overridden to look at the watched player regardless of perspective.
 *
 * ## Safety (why the override can never get stuck)
 *
 * Vanilla `Camera.update` re-derives pos/rotation from the local player BEFORE our `@At("TAIL")`
 * inject every frame, so the override only persists for the frames we actively re-apply it. The
 * instant [active] goes false (keybind, auto-exit, target gone, an exception), vanilla's re-derive
 * wins and the camera snaps back to the local-player view — the safe state.
 *
 * ## Threading
 *
 * [enable]/[disableNow]/[tickSafety] run on the client thread (keybind / `Iustitia.onClientTick`).
 * [active]/[targetUuid] are `@Volatile` — read from the render thread by [CameraMixin]. The
 * target-gone case detected in [CameraMixin] (render thread) does NOT mutate options directly;
 * it only sets [requestExit] so the client-thread tick loop performs the HUD/perspective restore
 * (option writes stay off the render thread). Fail-open throughout.
 */
object WatchState {

    /** Auto-exit if the local player moves more than this (blocks) from the watch-start position. */
    private const val MOVE_THRESHOLD = 0.5
    private const val MOVE_THRESHOLD_SQ = MOVE_THRESHOLD * MOVE_THRESHOLD

    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    var targetUuid: UUID? = null
        private set

    /** Set by the render thread (target-gone) to ask the client-thread tick loop to restore + chat. */
    @Volatile
    private var pendingExitReason: String? = null

    // Client-thread-only state (written by enable/disable/tickSafety, all on the client thread).
    private var savedHudHidden: Boolean = false
    private var savedPerspective: Perspective? = null
    private var startPos: Vec3d? = null
    private var lastHurtTime: Int = 0

    /** Begin watching [uuid]. Client thread only (called from the keybind). */
    fun enable(uuid: UUID) {
        try {
            val mc = MinecraftClient.getInstance()
            val p = mc.player
            // Save + force the HUD/perspective state: F1 (hide HUD) + third-person back so the
            // local player's own model renders in the scene (first-person suppresses your body).
            savedHudHidden = mc.options.hudHidden
            savedPerspective = mc.options.perspective
            mc.options.hudHidden = true
            if (mc.options.perspective != Perspective.THIRD_PERSON_BACK) {
                mc.options.setPerspective(Perspective.THIRD_PERSON_BACK)
            }
            startPos = p?.getCameraPosVec(0f)
            lastHurtTime = p?.hurtTime ?: 0
            targetUuid = uuid
            pendingExitReason = null
            active = true
        } catch (_: Throwable) {
            // fail-open: if we couldn't save/force HUD state, don't start a broken watch
            active = false
        }
    }

    /**
     * Stop watching now, restoring the saved HUD/perspective state. Client thread only. Returns the
     * reason string (for the caller to chat). Idempotent.
     */
    fun disableNow(reason: String): String {
        try { restore() } catch (_: Throwable) {}
        active = false
        return reason
    }

    /**
     * Request an exit from a non-client thread (the render thread's target-gone path). Does NOT
     * mutate options (those writes must stay on the client thread); just flags the exit so the tick
     * loop performs the restore + chat. The camera reverts immediately because [active] goes false.
     */
    fun requestExit(reason: String) {
        pendingExitReason = reason
        active = false
    }

    /**
     * Per-tick safety + exit-restore, called from [dev.iustitia.Iustitia.onClientTick] (client
     * thread). Returns a non-null reason string when watch just exited (so the caller can chat it),
     * or null when watch is inactive / still running. Performs the movement/hurt auto-exit checks
     * and re-clamps the perspective to first-person (ignoring F5) each tick while active.
     */
    fun tickSafety(): String? {
        try {
            // Consume a render-thread-requested exit: restore + hand back the reason to chat.
            if (pendingExitReason != null) {
                val r = pendingExitReason!!
                pendingExitReason = null
                try { restore() } catch (_: Throwable) {}
                active = false
                return r
            }
            if (!active) return null

            val mc = MinecraftClient.getInstance()
            val p = mc.player ?: return null

            // Ignore change-perspective (F5) during watch: re-clamp to third-person back so the
            // local player's own model keeps rendering.
            if (mc.options.perspective != Perspective.THIRD_PERSON_BACK) {
                mc.options.setPerspective(Perspective.THIRD_PERSON_BACK)
            }

            // Movement auto-exit: too far from the start position → stop watching.
            val start = startPos
            if (start != null) {
                val cur = p.getCameraPosVec(0f)
                val dx = cur.x - start.x
                val dy = cur.y - start.y
                val dz = cur.z - start.z
                if (dx * dx + dy * dy + dz * dz > MOVE_THRESHOLD_SQ) {
                    try { restore() } catch (_: Throwable) {}
                    active = false
                    return "you moved — watch cancelled"
                }
            }

            // Hit auto-exit: hurtTime rising edge (0 → >0) → you were hit → stop watching.
            val ht = p.hurtTime
            if (ht > 0 && lastHurtTime == 0) {
                try { restore() } catch (_: Throwable) {}
                active = false
                return "you were hit — watch cancelled"
            }
            lastHurtTime = ht

            return null
        } catch (_: Throwable) {
            return null
        }
    }

    /** Restore the saved HUD-hidden + perspective state and clear the per-watch bookkeeping. */
    private fun restore() {
        val mc = MinecraftClient.getInstance()
        mc.options.hudHidden = savedHudHidden
        savedPerspective?.let { mc.options.setPerspective(it) }
        savedPerspective = null
        savedHudHidden = false
        targetUuid = null
        startPos = null
        lastHurtTime = 0
    }
}