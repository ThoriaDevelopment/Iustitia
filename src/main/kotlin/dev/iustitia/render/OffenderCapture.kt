package dev.iustitia.render

import java.util.UUID

/**
 * One-shot hand-off state for the **offender selfie** screenshot (Phase B): the local player's
 * main framebuffer only ever holds the *local* camera view, so to capture another player from the
 * front ("F5-selfie" angle) we override [net.minecraft.client.render.Camera] for exactly ONE render
 * frame and grab the framebuffer at the end of that frame's world render.
 *
 * ## Threading / single-frame guarantee
 *
 * - [arm] is called on the **client thread** (the snapshot keybind / `/ius snapshot` path).
 * - The [dev.iustitia.mixin.CameraMixin] `Camera.update` TAIL inject runs on the **render thread**;
 *   it consumes [pending] (sets it to null) and, if the offender entity is loaded, repoints the
 *   camera at the offender and stashes the request in [activeReq] + raises [frameActive].
 * - [dev.iustitia.render.SelfieRenderer] registers `WorldRenderEvents.END_MAIN`, which fires on the
 *   render thread at the END of the SAME frame's world render; it reads [activeReq]/[frameActive],
 *   screenshots the main framebuffer, then clears both.
 *
 * Because [pending] is consumed in exactly one `Camera.update` call, the camera is overridden for
 * **one frame only** — vanilla `Camera.update` re-derives pos/rotation from the local player
 * before our TAIL inject every frame, so the moment [pending] is null our inject no-ops and the view
 * reverts. The camera can therefore NEVER get stuck pointing at an offender, regardless of whether
 * `END_MAIN` fires or the capture throws. A [Req.deadlineTick] backstop also clears a request whose
 * offender never loads (e.g. despawned) within ~2s so we don't keep probing.
 *
 * All fields are `@Volatile`: the only cross-thread handoff is client→render on [pending] and
 * render→render on [activeReq]/[frameActive] (same render thread, but volatile keeps it honest).
 * Fail-open everywhere — a thrown override leaves the camera at its vanilla (player-derived) state.
 */
object OffenderCapture {

    /** A pending selfie request. */
    data class Req(
        val uuid: UUID,
        val name: String,
        val dir: java.io.File,
        val file: String,
        val deadlineTick: Int,
    )

    /** Set by [arm] (client thread); consumed (nulled) in one `Camera.update` (render thread). */
    @Volatile var pending: Req? = null

    /** The request whose one-frame camera override is in progress; read+cleared by `END_MAIN`. */
    @Volatile var activeReq: Req? = null

    /** Raised by the Camera mixin when it has applied the override this frame; read by `END_MAIN`. */
    @Volatile var frameActive: Boolean = false

    /** Arm a selfie. Drops silently if a capture is already in flight (no stacking). */
    fun arm(req: Req) {
        try {
            if (activeReq != null || pending != null) return
            pending = req
        } catch (_: Throwable) {}
    }

    /** Clear all state (e.g. on disconnect). */
    fun reset() {
        pending = null
        activeReq = null
        frameActive = false
    }
}