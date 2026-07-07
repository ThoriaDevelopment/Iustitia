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
 * pattern as [PlayerEntityRendererMixin]). Non-player entities are passed through untouched — only
 * OTHER players are hidden (a rewind of the players' positions, not the whole world).
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
            val other = entity as? OtherClientPlayerEntity ?: return
            val mc = MinecraftClient.getInstance()
            val isSelf = other.uuid == mc.player?.uuid
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