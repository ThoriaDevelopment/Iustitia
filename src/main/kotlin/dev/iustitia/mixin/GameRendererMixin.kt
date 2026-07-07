package dev.iustitia.mixin

import dev.iustitia.replay.ReplayState
import net.minecraft.client.option.Perspective
import net.minecraft.client.render.GameRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Redirect

/**
 * FREECAM hide first-person hand (Task 6, cosmetic). While [ReplayState.freecamActive] the camera is
 * detached from the local player, but vanilla still renders the player's hand/item in first person
 * (the view is first-person by default). This redirects the `Perspective.isFirstPerson()` gate inside
 * `GameRenderer.renderHand` to `false` while freecam is active, so the hand render is skipped that
 * frame — mirroring the Zergatul FreeCam v26.2 reference (which redirects `CameraType.isFirstPerson`
 * in `GameRenderer.renderItemInHand`).
 *
 * Target verified against the named jar
 * `minecraft-merged-496669bc46-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar`:
 *  - `GameRenderer.renderHand(float, boolean, Matrix4f)` — private, but mixins can inject into
 *    private methods of their target class. (Yarn renamed the reference's `renderItemInHand` to
 *    `renderHand`.)
 *  - `renderHand` calls `GameOptions.getPerspective().isFirstPerson()` exactly once (offset 116);
 *    `ifeq 200` skips the hand render when it returns false. Redirecting it to `false` while
 *    freecamActive skips the hand render.
 *  - Scoped to `method = ["renderHand"]` so the THREE other `Perspective.isFirstPerson()` call sites
 *    in `GameRenderer` (`getFov`-area, two in `renderWorld` for camera positioning) are untouched.
 *
 * Fail-open: when freecam is NOT active we delegate to the original `perspective.isFirstPerson()`.
 * Cosmetic only; the core freecam (Task 5) works without it. Runtime-only-verifiable.
 */
@Mixin(GameRenderer::class)
class GameRendererMixin {

    @Redirect(
        method = ["renderHand"],
        at = At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/option/Perspective;isFirstPerson()Z",
            ordinal = 0,
        ),
    )
    private fun iustitia_hideHandInFreecam(perspective: Perspective): Boolean =
        if (ReplayState.freecamActive) false else perspective.isFirstPerson()
}