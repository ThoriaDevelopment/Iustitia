package dev.iustitia.mixin

import dev.iustitia.replay.ReplayState
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.client.render.WorldRenderer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Redirect

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
    private fun iustitia_freecamSpectatorNoBlackout(player: ClientPlayerEntity): Boolean =
        if (ReplayState.freecamActive) true else player.isSpectator()
}