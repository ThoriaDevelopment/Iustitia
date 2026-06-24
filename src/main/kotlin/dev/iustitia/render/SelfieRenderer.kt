package dev.iustitia.render

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.util.ScreenshotRecorder
import java.util.function.Consumer

/**
 * Captures the offender-selfie framebuffer (Phase B). Registers `WorldRenderEvents.END_MAIN`,
 * which fires on the render thread at the END of the main world render pass — i.e. in the SAME
 * frame [dev.iustitia.mixin.CameraMixin] repointed the camera at the offender, and BEFORE the HUD
 * is drawn, so the screenshot is a clean world view of the offender with no hotbar/chat overlay.
 *
 * On a frame where [OffenderCapture.frameActive] is raised, this screenshots the main framebuffer
 * to the armed request's `snapshots/<tick>_<name>.png` and echoes the vanilla "saved / error"
 * [net.minecraft.text.Text] to chat (clickable file link). Then it clears the capture state so the
 * camera reverts next frame. Fail-open throughout; the `finally` clear is what enforces the
 * single-frame guarantee even if the GL read throws.
 *
 * Runtime-only-verifiable: a build cannot confirm `END_MAIN` fires after the world render holds the
 * offender view, nor that the framebuffer is readable here. If PNGs come out blank/black, the
 * capture point may need to move to `AFTER_ENTITIES`/`BEFORE_TRANSLUCENT`.
 */
object SelfieRenderer {

    fun register() {
        try {
            WorldRenderEvents.END_MAIN.register(WorldRenderEvents.EndMain { _ ->
                try {
                    val req = OffenderCapture.activeReq
                    if (req == null || !OffenderCapture.frameActive) return@EndMain
                    val mc = MinecraftClient.getInstance()
                    val fb = mc.framebuffer ?: return@EndMain
                    ScreenshotRecorder.saveScreenshot(
                        req.dir, req.file, fb, 1,
                        Consumer { msg -> try { mc.inGameHud?.chatHud?.addMessage(msg) } catch (_: Throwable) {} }
                    )
                } catch (_: Throwable) {
                    // The finally clears state so the camera can't stay overridden.
                } finally {
                    OffenderCapture.frameActive = false
                    OffenderCapture.activeReq = null
                }
            })
        } catch (_: Throwable) {}
    }
}