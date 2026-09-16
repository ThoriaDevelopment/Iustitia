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
 * ## Telling self apart from the other players
 *
 * `ClientPlayerEntity` and `OtherClientPlayerEntity` are SIBLINGS in yarn 1.21.11, both extending
 * `AbstractClientPlayerEntity` (verified with javap against the mapped jar). An
 * `as? OtherClientPlayerEntity` cast is therefore null for the local player: it identifies the OTHER
 * players only. That is convenient for the other-player rule, but it means any self branch placed
 * after such a cast is unreachable dead code, which is the trap the shape below is written to avoid.
 * `isSelf` comes from the entity's uuid, and the branching itself is delegated to
 * [ReplayState.shouldHideEntity].
 *
 * ## Show-self: hiding your own body too
 *
 * The buffer captures the local player as an ordinary snap ([dev.iustitia.replay.ReplayBuffer.buildSelfSnap]),
 * so a replay draws your own recorded body as a ghost alongside everyone else's. The live body is then
 * hidden in EVERY camera mode, not just freecam, or you would appear twice: once standing where you
 * really are and once walking the replayed path (and in POV the live body would sit in front of the
 * camera). This is gated on [ReplayState.selfGhost] rather than applied unconditionally, because a clip
 * recorded before self capture carries no self ghost: hiding the live body there would leave nothing at
 * all on screen. Freecam hides the live body regardless, as it always has. The rule itself lives in
 * [ReplayState.shouldHideEntity] so the gametest suite can drive it; see that method for the exact
 * conditions.
 *
 * ## MODERN chunk-bearing playclip — hide ALL live entities
 *
 * While a chunk-bearing `/ius playclip` runs (`chunks != null && !legacyPlayclip`), the recorded
 * chunk world is relocated by `relocOffset` and rendered by [dev.iustitia.render.ChunkWorldRenderer],
 * but the LIVE world's non-player entities (boats, item frames, minecarts, armor stands, paintings,
 * ...) were never recorded and keep rendering at their LIVE positions via vanilla's dispatcher —
 * so they detached/jittered against the relocated recorded world (the reported signs/boats bug).
 * Under that gate every entity that is NOT the local player is hidden (self keeps the FREECAM/
 * show-self rule above), so only the recorded chunk world + buffered ghosts show — the same
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
            // The decision itself lives in ReplayState.shouldHideEntity so the gametest suite can drive
            // every branch without a live render pass (the scenario replay-show-self does). Only the
            // facts that need the entity are computed here, and `isOtherPlayer` is passed as a flag
            // rather than used as an early return: an `as? OtherClientPlayerEntity` guard placed above a
            // self branch would make that branch unreachable (see the class doc).
            if (ReplayState.shouldHideEntity(
                    isModernChunkWorld = ReplayState.chunks != null && !ReplayState.legacyPlayclip,
                    isSelf = entity.uuid == MinecraftClient.getInstance().player?.uuid,
                    isOtherPlayer = entity is OtherClientPlayerEntity,
                )
            ) {
                cir.setReturnValue(false)
            }
        } catch (_: Throwable) {
            // fail-open: never block rendering on an error
        }
    }
}