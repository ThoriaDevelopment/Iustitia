package dev.iustitia.mixin

import dev.iustitia.VerboseLog
import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.OtherClientPlayerEntity
import net.minecraft.client.render.entity.ArmorStandEntityRenderer
import net.minecraft.client.render.entity.state.ArmorStandEntityRenderState
import net.minecraft.entity.decoration.ArmorStandEntity
import net.minecraft.text.Text
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Prefixes the floating name of an armor-stand **hologram** that belongs to another player, so the
 * cheat-tier cue appears on servers that render a player's name via a marker ArmorStand riding
 * (or ridden by) the player instead of via the player's own `displayName` — common on ranked-PvP /
 * nametag-plugin servers where the vanilla nametag is team-hidden. The armor stand has its OWN uuid
 * with no `FlagHistory`, so without this the hologram name is never prefixed (its `tierFor` is GREEN).
 *
 * This is the rider-coverage follow-up to [PlayerEntityRendererMixin] (which covers the player's own
 * `displayName`, with a `playerName` fallback). Read-only like it: mutates only the label text held in
 * `state.displayName` (inherited from `EntityRenderState`, accessed via [EntityRenderStateAccessor]).
 * No visibility/camera/packet touches. Whole body fail-open.
 *
 * ## Why a single `updateRenderState` inject (not `renderLabelIfPresent`)
 *
 * `ArmorStandEntityRenderer` does NOT override `renderLabelIfPresent` — it inherits the base
 * `EntityRenderer.renderLabelIfPresent(EntityRenderState, …)`. Injecting an inherited method from a
 * subclass mixin is fragile (the method body lives on the shared base class, so an inject would
 * affect every non-overriding renderer). We avoid that entirely: `ArmorStandEntityRenderer.
 * updateRenderState` calls `super.updateRenderState(entity, state, f)` first, and that base call
 * sets `state.displayName = getDisplayName(entity)` (the armor stand's custom name) when
 * `customNameVisible && within 64 blocks` (verified in the decompiled 1.21.11 source). So by the
 * TAIL of the subclass `updateRenderState`, `state.displayName` already holds the hologram text — we
 * just prefix it there. `renderLabelIfPresent` later reads `state.displayName` at draw time and sees
 * our prefix. The base `updateRenderState` re-sets `displayName` to the plain name every cycle, so
 * there is no cross-frame accumulation; the double-prefix guard is kept only as a belt-and-braces.
 *
 * ## Owner resolution (vehicle graph, read live — no tracking map needed)
 *
 * A hologram armor stand rides the player (stand = passenger, player = vehicle); stacked holograms
 * ride each other, so [ArmorStandEntity.getRootVehicle] walks up to the player. We also handle the
 * reverse (player rides the stand) via [ArmorStandEntity.getFirstPassenger]. Only an `OtherClient-
 * PlayerEntity` owner (and not the local player) is accepted. Non-riding teleport-follow holograms
 * are NOT covered by this (proximity matching is deferred — see the follow-up scope); only the
 * vehicle-attached case, which is the high-precision common case.
 *
 * ## GREEN is skipped entirely
 *
 * Only YELLOW/RED owners get a prefix on their hologram. A clean player shows no cue (consistent
 * with the hologram being the *only* label on those servers); and skipping GREEN means a decorative
 * / custom-named armor stand that happens to ride (or be ridden by) a clean player never gets a
 * spurious `[+]`. The risk of a decorative stand being vehicle-attached to a *suspect* player is
 * near-zero (decorative stands are static), so YELLOW/RED here is high-confidence.
 *
 * Target verified against yarn 1.21.11: `ArmorStandEntityRenderer.updateRenderState(ArmorStandEntity,
 * ArmorStandEntityRenderState, float)V` (declared on the subclass — the handler's param types
 * disambiguate it from the generic bridge overloads, same mechanism as the player mixin's
 * `updateRenderState` inject).
 */
@Mixin(ArmorStandEntityRenderer::class)
class ArmorStandEntityRendererMixin {

    /** Players for which we've already logged the hologram-prefix diagnostic this session. */
    private val loggedHologram =
        java.util.Collections.synchronizedSet(java.util.LinkedHashSet<java.util.UUID>())

    @Inject(method = ["updateRenderState"], at = [At("TAIL")])
    private fun iustitia_prefixHologramName(
        entity: ArmorStandEntity,
        state: ArmorStandEntityRenderState,
        tickProgress: Float,
        ci: CallbackInfo,
    ) {
        try {
            if (!ConfigManager.config.nametagPrefixes) return

            // Owner = the player the hologram stand is attached to via the vehicle graph.
            val self = MinecraftClient.getInstance().player?.uuid
            val owner = (entity.rootVehicle as? OtherClientPlayerEntity)
                ?: (entity.firstPassenger as? OtherClientPlayerEntity)
            if (owner == null || owner.uuid == self) return

            val tier = FlagHistory.tierFor(owner.uuid)
            if (tier == FlagHistory.Tier.GREEN) return   // only YELLOW/RED on holograms

            val acc = state as? EntityRenderStateAccessor ?: return
            // Base updateRenderState already set this to the stand's custom name (customNameVisible &&
            // within 64 blocks); null means the server didn't make the stand's name visible.
            val name = acc.iustitia_getDisplayName() ?: return
            val s = name.string
            if (s.startsWith(GREEN_MARK) || s.startsWith(YELLOW_MARK) || s.startsWith(RED_MARK)) return

            val (color, glyph) = when (tier) {
                FlagHistory.Tier.GREEN -> "a" to "[+]"
                FlagHistory.Tier.YELLOW -> "e" to "[!]"
                FlagHistory.Tier.RED -> "c" to "[X]"
            }
            val prefix = Text.literal("§${color}${glyph}§r ")
            acc.iustitia_setDisplayName(Text.empty().append(prefix).append(name))
            logHologramOnce(owner.uuid, tier)
        } catch (_: Throwable) {
            // fail-open: a render error never crashes the client
        }
    }

    /**
     * One verbose line per player per session the first time we prefix their armor-stand hologram
     * name — confirms which servers use vehicle-attached armor-stand holograms (and that the owner
     * resolution + tier lookup are firing). Writes to `latest.log` via [VerboseLog], not chat.
     * CAP-bounded. If a server uses teleport-follow (non-riding) holograms instead, this never fires
     * → that's the signal to add the proximity follow-up.
     */
    private fun logHologramOnce(uuid: java.util.UUID, tier: FlagHistory.Tier) {
        try {
            if (!VerboseLog.isEnabled()) return
            if (loggedHologram.add(uuid)) {
                if (loggedHologram.size > CAP) loggedHologram.clear()
                val nm = FlagHistory.nameFor(uuid) ?: uuid.toString().take(8)
                VerboseLog.log(
                    "nametag-hologram: prefixed armor-stand hologram name for $nm (tier=$tier)"
                )
            }
        } catch (_: Throwable) {}
    }

    private companion object {
        // Mixin requires all static fields to be private (rejects non-private static fields at apply
        // time). Companion vals/consts are static fields in bytecode, so they MUST be `private`.
        private const val GREEN_MARK = "§a[+]§r "
        private const val YELLOW_MARK = "§e[!]§r "
        private const val RED_MARK = "§c[X]§r "
        private const val CAP = 512
    }
}