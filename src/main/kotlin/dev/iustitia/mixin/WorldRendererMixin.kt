package dev.iustitia.mixin

import dev.iustitia.replay.ReplayState
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.WorldRenderer
import net.minecraft.client.render.command.OrderedRenderCommandQueueImpl
import net.minecraft.client.render.state.WorldRenderState
import net.minecraft.client.util.math.MatrixStack
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.Redirect
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * FREECAM spectator no-black-out (Task 6, cosmetic). While [ReplayState.freecamActive] the camera
 * flies noclip through the world; without this override, nosing it into a solid block makes that
 * block's face black out (vanilla culls the block the camera is inside for non-spectators). Forcing
 * the spectator flag to `true` in the terrain render path makes vanilla treat the camera like a
 * spectator's: when the camera block is an opaque full cube, the section-occlusion graph is updated
 * with `false` (render everything) instead of the default `true` — clearing the black-out.
 *
 * This mirrors the Zergatul FreeCam v26.2 reference, which in older yarn targeted
 * `Camera.extractRenderState` at `LocalPlayer.isSpectator()`. In yarn 1.21.11 `Camera` no longer
 * extracts a render-state / takes a spectator flag — `Camera.update(World, Entity, boolean, boolean,
 * float)` is called with `!isFirstPerson()` and `isFrontView()` (NOT spectator). The spectator check
 * moved to the call site in `WorldRenderer.render`, which passes `ClientPlayerEntity.isSpectator()`
 * as the third arg to `WorldRenderer.updateCamera(Camera, Frustum, boolean)`; that boolean is the
 * one that gates the opaque-full-cube occlusion flip (verified in the named-jar bytecode at
 * `WorldRenderer.updateCamera` offsets 263–289). Redirecting THAT call site to `true` while freecam
 * is active is the semantic equivalent of the reference.
 *
 * Target verified against the named jar
 * `minecraft-merged-496669bc46-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar`:
 *  - `WorldRenderer.render(ObjectAllocator, RenderTickCounter, boolean, Camera, Matrix4f, Matrix4f,
 *    Matrix4f, GpuBufferSlice, Vector4f, boolean)` — single public `render` (no overloads).
 *  - Exactly one `ClientPlayerEntity.isSpectator()` call in the whole class (offset 145 in `render`,
 *    passed to `updateCamera`), so the redirect is unambiguous without an ordinal.
 *
 * Fail-open: when freecam is NOT active we delegate to the original `player.isSpectator()`, so
 * vanilla behavior is unchanged. This is a cosmetic refinement; the core freecam (Task 5) works
 * without it. Runtime-only-verifiable: a build can't confirm the black-out actually clears in-game.
 */
@Mixin(WorldRenderer::class)
class WorldRendererMixin {

    @Redirect(
        method = ["render"],
        at = At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;isSpectator()Z",
            ordinal = 0,
        ),
    )
    private fun iustitia_freecamSpectatorNoBlackout(player: ClientPlayerEntity): Boolean = try {
        if (ReplayState.freecamActive) true else player.isSpectator()
    } catch (_: Throwable) {
        player.isSpectator()
    }

    /**
     * MODERN chunk-bearing playclip: suppress the LIVE world's block entities (signs, chests,
     * banners, ...). The recorded chunk world rendered by [dev.iustitia.render.ChunkWorldRenderer]
     * is relocated by `relocOffset` and renders only block *models* — it never renders block
     * entities. So every sign/chest/banner on screen during a chunk playclip is a LIVE block entity
     * at its LIVE block coord, detached from the relocated recorded world (the reported signs bug:
     * sign text floats at `-relocOffset` from the recorded sign block). Cancelling
     * `renderBlockEntities` at HEAD while the chunk gate is active hides them all, so only the
     * recorded chunk world + buffered ghosts show — the block-entity parallel of
     * [SectionRenderStateMixin]'s terrain cancel and [EntityRendererMixin]'s entity cancel, under
     * the exact same gate (`active && chunks != null && !legacyPlayclip`).
     *
     * Recorded block-entity NBT (sign text, chest contents) is a documented v1 follow-up
     * (`ChunkCapture.kt:22-23`); until then chunk playclip shows the recorded block models with no
     * block-entity overlay. The legacy `/ius playclip` + `/ius replay` paths (no chunks) skip this.
     *
     * Target verified against the named 1.21.11 jar: single public method
     * `renderBlockEntities(MatrixStack, WorldRenderState, OrderedRenderCommandQueueImpl)`.
     * `defaultRequire: 1` → a mismatch fails launch; render-time apply is runtime-only-verifiable.
     */
    @Inject(method = ["renderBlockEntities"], at = [At("HEAD")], cancellable = true)
    private fun iustitia_suppressLiveBlockEntities(
        matrices: MatrixStack,
        worldRenderState: WorldRenderState,
        queue: OrderedRenderCommandQueueImpl,
        ci: CallbackInfo,
    ) {
        try {
            if (ReplayState.active && ReplayState.chunks != null && !ReplayState.legacyPlayclip) {
                ci.cancel()
            }
        } catch (_: Throwable) {
            // fail-open: leave the live block entities drawing (worst case = show-through, never a crash)
        }
    }

    /**
     * Suppress the LIVE world's targeted-block outline (the black wireframe box around the block the
     * local player is looking at) during FREECAM or a MODERN chunk-bearing playclip. In those modes the
     * camera is detached (FREECAM) or the recorded chunk world replaces the live one (MODERN), so the
     * live player's targeted-block outline renders at a LIVE coord that has nothing to do with what the
     * user is spectating — it floats detached in the replay view (the reported "block outlines in the
     * wrong spot" in freecam). Cancelling `renderTargetBlockOutline` at HEAD removes it for that frame.
     *
     * Gate: `freecamActive` (any FREECAM — the camera isn't the live player, so the live target is
     * meaningless) OR the MODERN gate (`active && chunks != null && !legacyPlayclip` — the recorded
     * chunk world is what's on screen, the live target is wrong). The legacy `/ius replay` + FREE view
     * (camera IS the live player, live world on screen) is NOT gated → the outline stays correct there.
     *
     * Target verified against the named 1.21.11 jar: single private method
     * `renderTargetBlockOutline(VertexConsumerProvider$Immediate, MatrixStack, boolean, WorldRenderState)`.
     * `defaultRequire: 1` → a signature mismatch fails launch; render-time apply is runtime-only-verifiable.
     */
    @Inject(method = ["renderTargetBlockOutline"], at = [At("HEAD")], cancellable = true)
    private fun iustitia_suppressLiveBlockOutline(
        immediate: VertexConsumerProvider.Immediate,
        matrices: MatrixStack,
        flag: Boolean,
        worldRenderState: WorldRenderState,
        ci: CallbackInfo,
    ) {
        try {
            if (ReplayState.freecamActive ||
                (ReplayState.active && ReplayState.chunks != null && !ReplayState.legacyPlayclip)) {
                ci.cancel()
            }
        } catch (_: Throwable) {
            // fail-open: leave the live outline drawing (worst case = a stray box, never a crash)
        }
    }
}