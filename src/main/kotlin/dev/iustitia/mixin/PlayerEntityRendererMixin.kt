package dev.iustitia.mixin

import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.OtherClientPlayerEntity
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.PlayerEntityRenderer
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import net.minecraft.client.render.state.CameraRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.PlayerLikeEntity
import net.minecraft.text.Text
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import java.util.IdentityHashMap

/**
 * Prepends a cheat-tier prefix (green [+] / yellow [!] / red [X]) to OTHER players' nametags, so a
 * legit/low-flag player shows a green tick, a borderline player a yellow warning, and a player a
 * definitive check has proven a cheater a red skull.
 *
 * This is the second client mixin (the first, [ClientPlayNetworkHandlerMixin], is the read-only
 * packet observer). It is read-only on the render path: it rewrites the label *text* held in
 * `PlayerEntityRenderState.displayName` (the main nametag, inherited from `EntityRenderState`).
 * It does NOT touch `hasLabel`/visibility, so vanilla nametag visibility is respected (the prefix
 * only appears when vanilla would show the nametag — hidden while sneaking-behind-walls / out of
 * range; no wallhack). It never touches the camera or any outgoing packet. Whole body fail-open so
 * a render error never crashes the client.
 *
 * ## Draw-site prefixing
 *
 * The prefix is written at `@At("HEAD")` of `renderLabelIfPresent` — the actual draw method — NOT at
 * `updateRenderState` TAIL. `renderLabelIfPresent` reads `state.displayName` (offset 91 in the
 * bytecode) and submits it via `submitLabel` *after* our HEAD inject, so writing the prefix at HEAD
 * guarantees the field holds our prefix at the exact moment of draw — robust against any
 * intermediate reset/batch cache. (Verified by decompiling `PlayerEntityRenderer.renderLabelIfPresent`
 * on yarn 1.21.11: it draws two labels — `playerName` offset 20, `displayName` offset 91 — both read
 * straight off `state` via `getfield`, so a HEAD inject that mutates `displayName` is seen by the
 * draw.)
 *
 * ## Server coverage (investigated + concluded)
 *
 * - **minemen.club + 1.8.9 servers**: vanilla populates `displayName` for living players → prefix
 *   renders. ✅
 * - **stray.gg**: server hides the vanilla nametag (`displayName` never populated — team
 *   `nametagVisibility` = hideForOtherTeams) and shows a team-stylized custom hologram. Unfixable
 *   here — there's no `displayName` to prefix. ✗ (by design)
 * - **mcpvp.club**: `displayName` is NULL during live play (confirmed — the draw-site inject's DRAW
 *   diagnostic only ever fired at the instant of death, never during combat). The black-bg label
 *   you see during play is `playerName` — the server's BELOW_NAME health-indicator — not the name.
 *   Same unfixable class as stray.gg. ✗ (by design — user accepted; prefixing the health indicator
 *   was rejected as semantically wrong.)
 *
 * So the prefix only appears on servers that populate vanilla `displayName`. That's the intended
 * behavior; mcpvp/stray are left without a tier cue.
 *
 * ## Tier lookup at draw time
 *
 * `renderLabelIfPresent` receives only `state` (no entity/uuid), and `PlayerEntityRenderState`
 * carries no uuid — so we can't look up the tier there. We record a `state → Tier` mapping in
 * `updateRenderState` (which DOES get the entity) and read it back in `renderLabelIfPresent`.
 *
 * **State lifetime / leak avoidance:** `PlayerEntityRenderState` is NOT a single reused instance —
 * a fresh state is created per render cycle (confirmed during diagnosis: the per-state DRAW log
 * fired dozens of times for one player). So a plain `IdentityHashMap` would leak one entry per
 * render call. We bound it with **remove-on-read** in `renderLabelIfPresent` (each recorded entry
 * is consumed by the immediately-following draw for the same state) plus a **size backstop** in
 * `updateRenderState` that clears the map if it somehow grows past [CAP] (e.g. many culled entities
 * whose `updateRenderState` ran but `renderLabelIfPresent` never did). Both injects run on the
 * render thread — no concurrent access.
 *
 * ## Double-prefix guard
 *
 * Vanilla resets `displayName` to plain each `updateRenderState`, so there is no cross-frame
 * accumulation; but `renderLabelIfPresent` can be called more than once per frame for the same
 * state (e.g. shadow + main pass) without an intervening reset, so we skip if the field already
 * starts with our marker text.
 *
 * ## Local-player exclusion
 *
 * `ClientPlayerEntity` IS-A `OtherClientPlayerEntity`, so the naive `entity as? OtherClientPlayerEntity`
 * check does NOT exclude the local player — it would prefix your own `displayName` (harmless only
 * because you aren't drawn in first-person). We exclude the local player explicitly by uuid so F5
 * third-person doesn't show your own prefix.
 *
 * Target verified against yarn 1.21.11 mappings. `updateRenderState` = method_62604; the
 * `renderLabelIfPresent` override on `PlayerEntityRenderer` is `(PlayerEntityRenderState,
 * MatrixStack, OrderedRenderCommandQueue, CameraRenderState)V` (disambiguated from the base
 * `EntityRenderer.renderLabelIfPresent(EntityRenderState, …)` overload by the handler's first
 * param type — the same mechanism that lets the `updateRenderState` inject target one of its 3
 * overloads). `displayName` = `field_53337`, declared on `EntityRenderState` (accessor targets that
 * class — see [EntityRenderStateAccessor] and the GOTCHA recorded in the nametag memory note).
 */
@Mixin(PlayerEntityRenderer::class)
class PlayerEntityRendererMixin {

    /**
     * state → cheat tier for the entity currently rendered by that state (set in updateRenderState,
     * consumed+removed in renderLabelIfPresent). Bounded by remove-on-read + [CAP] backstop.
     */
    private val stateTier: IdentityHashMap<PlayerEntityRenderState, FlagHistory.Tier> = IdentityHashMap()

    @Inject(method = ["updateRenderState"], at = [At("TAIL")])
    private fun iustitia_recordTier(
        entity: PlayerLikeEntity,
        state: PlayerEntityRenderState,
        tickProgress: Float,
        ci: CallbackInfo,
    ) {
        try {
            // Backstop: if culled entities leaked entries (renderLabelIfPresent never called for
            // them), clear before the map grows unbounded. Normal operation is remove-on-read.
            if (stateTier.size > CAP) stateTier.clear()

            val other = entity as? OtherClientPlayerEntity
            val isSelf = other != null && other.uuid == MinecraftClient.getInstance().player?.uuid
            if (other == null || isSelf) {
                stateTier.remove(state)
            } else {
                stateTier[state] = FlagHistory.tierFor(other.uuid)
            }
        } catch (_: Throwable) {}
    }

    @Inject(method = ["renderLabelIfPresent"], at = [At("HEAD")])
    private fun iustitia_prefixNametagAtDraw(
        state: PlayerEntityRenderState,
        matrices: MatrixStack,
        queue: OrderedRenderCommandQueue,
        camera: CameraRenderState,
        ci: CallbackInfo,
    ) {
        try {
            val cfg = ConfigManager.config
            if (!cfg.nametagPrefixes) return

            // null = local player, non-player, or no updateRenderState for this state → skip.
            val tier = stateTier.remove(state) ?: return
            if (tier == FlagHistory.Tier.GREEN && !cfg.nametagGreenEnabled) return

            val acc = state as? EntityRenderStateAccessor ?: return
            val current = acc.iustitia_getDisplayName() ?: return   // null = vanilla drew no label (e.g. mcpvp/stray)

            // Double-prefix guard: skip if already prefixed this frame (multi-pass render).
            val s = current.string
            if (s.startsWith(GREEN_MARK) || s.startsWith(YELLOW_MARK) || s.startsWith(RED_MARK)) return

            val (color, glyph, mark) = when (tier) {
                FlagHistory.Tier.GREEN -> Triple("a", "[+]", GREEN_MARK)
                FlagHistory.Tier.YELLOW -> Triple("e", "[!]", YELLOW_MARK)
                FlagHistory.Tier.RED -> Triple("c", "[X]", RED_MARK)
            }
            val prefix = Text.literal("§${color}${glyph}§r ")
            acc.iustitia_setDisplayName(Text.empty().append(prefix).append(current))
        } catch (_: Throwable) {}
    }

    private companion object {
        // Mixin requires all static fields to be private (it rejects non-private static fields at
        // apply time with InvalidMixinException). These are companion-object vals/consts = static
        // fields in bytecode, so they MUST be `private` — a green build does NOT catch this.
        private const val GREEN_MARK = "§a[+]§r "
        private const val YELLOW_MARK = "§e[!]§r "
        private const val RED_MARK = "§c[X]§r "
        /** Backstop size for [stateTier] — far above any realistic per-frame live-entity count. */
        private const val CAP = 512
    }
}