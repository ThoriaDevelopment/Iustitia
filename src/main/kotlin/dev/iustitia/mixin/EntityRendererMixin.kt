package dev.iustitia.mixin

import dev.iustitia.replay.ReplayState
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.OtherClientPlayerEntity
import net.minecraft.client.render.Frustum
import net.minecraft.client.render.entity.EntityRenderer
import net.minecraft.entity.Entity
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

/**
 * "Rewind feel" for the instant-replay feature: while [ReplayState] is active with [ReplayState.hideLive]
 * on (the default replay mode), cancel `shouldRender` for every OTHER player so only the buffered
 * ghost copies ([dev.iustitia.render.ReplayRenderer]) draw — a rewind-the-world look. The live game
 * + detection keep running underneath; only rendering is suppressed, and it snaps back the instant
 * the replay stops (`active` flips false). Render-only: nothing is sent, no entity is removed.
 *
 * ## Why `shouldRender` on the base `EntityRenderer`
 *
 * The per-player renderer (`PlayerEntityRenderer`) does NOT override `shouldRender` (it inherits it),
 * so a mixin there can't target it. `EntityRenderer` is where the method actually lives in bytecode
 * (verified: `public boolean shouldRender(T, Frustum, double, double, double)`), and it is the root
 * of the renderer hierarchy, so injecting here covers every player renderer that calls `super`.
 * `shouldRender` is the earliest cull gate — called once per entity per frame (before any model
 * work), so cancelling here is cheap and avoids the multi-pass flicker a `render`-targeting inject
 * would risk (shadow + main pass).
 *
 * ## Local-player exclusion
 *
 * `ClientPlayerEntity` IS-A `OtherClientPlayerEntity`, so the `as? OtherClientPlayerEntity` check
 * would also hide YOU in third-person during a replay. We exclude the local player by uuid (same
 * pattern as [PlayerEntityRendererMixin]).
 *
 * ## MODERN chunk-bearing playclip — hide ALL live entities
 *
 * While a chunk-bearing `/ius playclip` runs (`chunks != null && !legacyPlayclip`), the recorded
 * chunk world is relocated by `relocOffset` and rendered by [dev.iustitia.render.ChunkWorldRenderer],
 * but the LIVE world's non-player entities (boats, item frames, minecarts, armor stands, paintings,
 * ...) were never recorded and keep rendering at their LIVE positions via vanilla's dispatcher —
 * so they detached/jittered against the relocated recorded world (the reported signs/boats bug).
 * Under that gate we now cancel `shouldRender` for every entity that is NOT the local player (self
 * keeps the FREECAM hide below), so only the recorded chunk world + buffered ghosts show — the same
 * rewind-the-world suppression already applied to other players, extended to all live entities.
 * Recorded block *models* still render in the chunk world; recorded entity/block-entity NBT is a
 * documented v1 follow-up (`ChunkCapture.kt:22-23`). The legacy `/ius playclip` + `/ius replay` paths
 * (no chunks) skip this branch entirely and keep the original "only other players hide" behavior.
 *
 * ## Cost when no replay is running
 *
 * `shouldRender` is hot (every entity, every frame). When [ReplayState.active] is false (the
 * overwhelming common case), this mixin does one volatile read + an `as?` cast + early return —
 * negligible. Whole-body fail-open so a render error never crashes the frame.
 *
 * Target verified against yarn 1.21.11: `EntityRenderer.shouldRender(Lnet/minecraft/entity/Entity;
 * Lnet/minecraft/client/render/Frustum;DDD)Z` (T erases to `Entity`).
 */
@Mixin(EntityRenderer::class)
abstract class EntityRendererMixin {

    @Inject(method = ["shouldRender"], at = [At("HEAD")], cancellable = true)
    private fun iustitia_hideLiveDuringReplay(
        entity: Entity,
        frustum: Frustum,
        x: Double,
        y: Double,
        z: Double,
        cir: CallbackInfoReturnable<Boolean>,
    ) {
        try {
            if (!ReplayState.active) return
            val mc = MinecraftClient.getInstance()
            val isSelf = entity.uuid == mc.player?.uuid
            // MODERN chunk-bearing playclip: hide EVERY live entity that isn't the local player —
            // boats/item-frames/minecarts/armor-stands/paintings/etc. previously fell through here
            // and rendered at LIVE positions, detached from the relocated recorded chunk world.
            // Self keeps the FREECAM hide; everything else cancels. (See class doc.)
            if (ReplayState.chunks != null && !ReplayState.legacyPlayclip) {
                if (isSelf) {
                    if (ReplayState.cameraMode == ReplayState.CameraMode.FREECAM) cir.setReturnValue(false)
                } else {
                    cir.setReturnValue(false)
                }
                return
            }
            // Non-MODERN (legacy replay / wireframe clip): original behavior — only OTHER players hide.
            val other = entity as? OtherClientPlayerEntity ?: return
            if (isSelf) {
                // FREECAM: the detached camera flies away from the local player, so the player's own
                // body would float at its real (now-irrelevant) spot in the clip world. Hide it for
                // a clean ReplayMod-style free spectate. Restored the instant FREECAM ends (next
                // frame `active`/mode flips back). The player still ticks + receives input normally —
                // only rendering is suppressed.
                if (ReplayState.cameraMode == ReplayState.CameraMode.FREECAM) {
                    cir.setReturnValue(false)
                }
                return
            }
            // Not self: hide OTHER players during a hide-live replay so only the buffered ghosts
            // draw (the rewind-the-world feel). When hideLive is off the live players overlay the
            // ghosts as before.
            if (ReplayState.hideLive) cir.setReturnValue(false)
        } catch (_: Throwable) {
            // fail-open: never block rendering on an error
        }
    }
}