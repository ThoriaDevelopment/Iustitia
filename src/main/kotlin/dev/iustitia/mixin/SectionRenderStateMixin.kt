package dev.iustitia.mixin

import dev.iustitia.replay.ReplayState
import net.minecraft.client.render.SectionRenderState
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Hides the **live world's terrain** while a chunk-bearing `/ius playclip` runs, so the clip's
 * captured chunk world (rendered as solid textured blocks by [dev.iustitia.render.ChunkWorldRenderer])
 * replaces it instead of z-fighting / bleeding through. The Iustitia-native equivalent of ReplayMod's
 * "no live world" without ReplayMod's connection-replacement — we coexist with the live server.
 *
 * ## Target + why this is the surgical point
 *
 * `WorldRenderer.method_62214` (the main render pass, unrenamed in yarn 1.21.11) draws every terrain
 * layer — SOLID / CUTOUT / CUTOUT_MIPPED / TRANSLUCENT — by calling `SectionRenderState.renderSection(
 * BlockRenderLayerGroup, GpuSampler)` (three calls: the opaque layers, then the translucent layer
 * after entities). Canceling `renderSection` at HEAD skips ALL terrain layers, while **entities keep
 * rendering** (they dispatch via `RenderDispatcher.render`, a separate call in `method_62214`) and the
 * Fabric `WorldRenderEvents.AFTER_ENTITIES` hook still fires — so the clip's chunk world + the buffered
 * ghosts (both drawn at AFTER_ENTITIES by [dev.iustitia.render.ReplayRenderer]) keep working. Sky / fog
 * / clouds are separate passes (`renderSky` etc.), left untouched so the background stays natural.
 *
 * ## Gating — back-compat + fail-open
 *
 * Cancels **only** when `ReplayState.active && ReplayState.chunks != null` — i.e. a v6+ chunk-bearing
 * playclip. A wireframe-terrain clip (`chunks == null`, terrain-only) and a plain `/ius replay` (no map)
 * are completely unaffected: the live world renders as normal and the ghosts / wireframe overlay sit
 * on top, exactly as before. The whole body is try/caught (project fail-open posture): a throw leaves
 * the live terrain running (worst case = live world shows through the clip, never a crash).
 *
 * ## Runtime-only-verifiable (per project convention for render-path mixins)
 *
 * The `SectionRenderState.renderSection` target + signature are verified against the named
 * 1.21.11 jar (it's a `final record` with `public void renderSection(BlockRenderLayerGroup, GpuSampler)`).
 * `defaultRequire: 1` means a mismatch fails launch — the build can't confirm mixin-apply, only a live
 * client run can. If it ever fails at runtime, the gate is the single point to flip.
 */
@Mixin(SectionRenderState::class)
class SectionRenderStateMixin {

    @Inject(method = ["renderSection"], at = [At("HEAD")], cancellable = true)
    private fun iustitia_suppressLiveTerrain(ci: CallbackInfo) {
        try {
            // Legacy playclip never has chunks (ReplayState.start nulls them) and must show the live
            // world (v1.1.0 = ghosts over live terrain), so the !legacyPlayclip guard is belt-and-
            // suspenders against any future path that re-populates chunks during a Legacy playclip.
            if (ReplayState.active && ReplayState.chunks != null && !ReplayState.legacyPlayclip) {
                ci.cancel()
            }
        } catch (_: Throwable) {
            // fail-open: leave the live terrain drawing (worst case = show-through, never a crash)
        }
    }
}