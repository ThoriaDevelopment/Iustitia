package dev.iustitia.mixin

import dev.iustitia.replay.ReplayState
import net.minecraft.client.render.GameRenderer
import net.minecraft.client.util.math.MatrixStack
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * FREECAM: kill the walking view-bob while the detached camera is flying (Task: freecam polish).
 * While [ReplayState.freecamActive] the camera is detached from the local player and WASD drives the
 * pose, not the player — but vanilla `GameRenderer.bobView` still applies the bob offset to the view
 * matrix whenever the player's horizontal speed is non-zero (and the user has View Bobbing on), so
 * the view bobs as if walking even though you're free-flying. Cancelling `bobView` at HEAD that frame
 * skips the matrix offset entirely, so the freecam view is rock-steady. The internal bob state
 * (`tickBobView`) may keep ticking harmlessly but is never applied, so nothing visible bobs. The
 * instant freecam ends the redirect stops and vanilla bobbing resumes unchanged.
 *
 * ## Why `bobView` (and not a redirect of `GameOptions.getBobView`)
 *
 * `bobView` is the single point that applies the bob to the view matrix each frame, already gated
 * internally by the user's View Bobbing option. A HEAD `@Inject` + `ci.cancel()` is the simplest
 * surgical kill — it mirrors the existing [GameRendererMixin] hand-hide idiom (fail-open,
 * freecam-gated) and avoids the generic-`SimpleOption.getValue()` descriptor ambiguity a redirect of
 * the option read would hit. Mixins can inject into `private` methods of their target class.
 *
 * ## Target verified against the named jar
 *
 * `minecraft-merged-496669bc46-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar`:
 *  - `private void bobView(net.minecraft.client.util.math.MatrixStack, float)` — the bob apply point.
 *    (Note: it takes a `MatrixStack`, not a `Matrix4f` — verified by javap, not assumed.)
 *  - Called once per frame from `GameRenderer.renderWorld`. `defaultRequire: 1` means a mismatch
 *    fails launch; render-time apply is runtime-only-verifiable per project convention.
 *
 * ## Cost when freecam is off
 *
 * When [ReplayState.freecamActive] is false (the overwhelming common case) this does one volatile
 * read + returns — vanilla bobbing is entirely unchanged. Whole-body fail-open so a render error
 * never crashes the frame.
 */
@Mixin(GameRenderer::class)
class GameRendererBobViewMixin {

    @Inject(method = ["bobView"], at = [At("HEAD")], cancellable = true)
    private fun iustitia_killBobInFreecam(matrices: MatrixStack, tickDelta: Float, ci: CallbackInfo) {
        try {
            if (ReplayState.freecamActive) ci.cancel()
        } catch (_: Throwable) {
            // fail-open: leave vanilla bobbing applied (never crash the frame)
        }
    }
}