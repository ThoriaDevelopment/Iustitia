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
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
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

    /** "uuid|source" keys for which we've already logged the hologram-prefix diagnostic this session. */
    private val loggedHologram =
        java.util.Collections.synchronizedSet(java.util.LinkedHashSet<String>())

    @Inject(method = ["updateRenderState"], at = [At("TAIL")])
    private fun iustitia_prefixHologramName(
        entity: ArmorStandEntity,
        state: ArmorStandEntityRenderState,
        tickProgress: Float,
        ci: CallbackInfo,
    ) {
        try {
            if (!ConfigManager.config.nametagPrefixes) return

            // Owner = the player the hologram stand belongs to. Two resolution strategies:
            //  1) vehicle graph (high precision): the stand rides the player, or the player rides
            //     the stand -> rootVehicle / firstPassenger yields the OtherClientPlayerEntity.
            //  2) proximity (fallback for teleport-follow holograms): many nametag plugins spawn a
            //     marker stand that teleports above the player each tick WITHOUT riding it, so the
            //     vehicle graph yields nothing. A name hologram sits ~2 blocks above the player's
            //     feet, horizontally centered, so a tight box + nearest-match recovers the owner.
            //     YELLOW/RED-only + a visible custom name (gated below) keeps false positives to
            //     near-zero (a decorative named stand would have to sit directly on a suspect's head).
            val self = MinecraftClient.getInstance().player?.uuid
            val vOwner = (entity.rootVehicle as? OtherClientPlayerEntity)
                ?: (entity.firstPassenger as? OtherClientPlayerEntity)
            val owner: OtherClientPlayerEntity?
            val source: String
            if (vOwner != null) { owner = vOwner; source = "vehicle" }
            else {
                val prox = proximityOwner(entity)
                if (prox == null) return
                owner = prox; source = "proximity"
            }
            if (owner.uuid == self) return

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
            logHologramOnce(owner.uuid, tier, source)
        } catch (_: Throwable) {
            // fail-open: a render error never crashes the client
        }
    }

    /**
     * Proximity owner resolution for a teleport-follow hologram stand (no vehicle link). Scans the
     * player entities in a tight box around the stand and returns the nearest one whose head-name
     * position aligns with the stand (horiz < [PROX_HORIZ], |stand.y - (player.y + PROX_NAME_Y)| <
     * [PROX_VERT]). Uses [World.getOtherEntities] (entity-section indexed) so it's cheap. Fail-open.
     */
    private fun proximityOwner(stand: ArmorStandEntity): OtherClientPlayerEntity? {
        val world = MinecraftClient.getInstance().world ?: return null
        val self = MinecraftClient.getInstance().player?.uuid
        val box = Box.of(Vec3d(stand.x, stand.y, stand.z), PROX_BOX_X, PROX_BOX_Y, PROX_BOX_X)
        var best: OtherClientPlayerEntity? = null
        var bestHoriz = Double.MAX_VALUE
        for (e in world.getOtherEntities(stand, box) { it is OtherClientPlayerEntity }) {
            val p = e as OtherClientPlayerEntity
            if (p.uuid == self) continue
            val dx = p.x - stand.x
            val dz = p.z - stand.z
            val horiz = dx * dx + dz * dz
            if (horiz > PROX_HORIZ * PROX_HORIZ) continue
            val dy = stand.y - (p.y + PROX_NAME_Y)
            if (dy * dy > PROX_VERT * PROX_VERT) continue
            if (horiz < bestHoriz) { bestHoriz = horiz; best = p }
        }
        return best
    }

    /**
     * One verbose line per player per session the first time we prefix their armor-stand hologram
     * name. [source] is `"vehicle"` (stand rides / is ridden by the player) or `"proximity"`
     * (teleport-follow stand matched by position) — that tag tells us which hologram style a server
     * uses, which is exactly the unknown for mcpvp-style servers where the vanilla nametag is
     * team-hidden. Writes to `latest.log` via [VerboseLog], not chat. CAP-bounded.
     */
    private fun logHologramOnce(uuid: java.util.UUID, tier: FlagHistory.Tier, source: String) {
        try {
            if (!VerboseLog.isEnabled()) return
            val key = uuid.toString() + "|" + source
            if (loggedHologram.add(key)) {
                if (loggedHologram.size > CAP) loggedHologram.clear()
                val nm = FlagHistory.nameFor(uuid) ?: uuid.toString().take(8)
                VerboseLog.log(
                    "nametag-hologram: prefixed armor-stand hologram name for $nm (tier=$tier, owner=$source)"
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
        // Proximity-match constants for teleport-follow hologram stands. A name hologram sits
        // ~2 blocks above the player's feet, horizontally centered on the player.
        private const val PROX_HORIZ = 0.8       // max horizontal offset (blocks) from the player
        private const val PROX_NAME_Y = 2.0      // expected stand.y = player.y + this
        private const val PROX_VERT = 0.7        // max vertical offset (blocks) from player.y + PROX_NAME_Y
        private const val PROX_BOX_X = 2.0       // getOtherEntities box half-sweep x/z (±1.0)
        private const val PROX_BOX_Y = 4.0       // box y sweep (±2.0)
    }
}