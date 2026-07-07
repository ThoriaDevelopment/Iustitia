package dev.iustitia.mixin

import dev.iustitia.VerboseLog
import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import dev.iustitia.tracking.EntityTrackerManager
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
 * legit/low-flag player shows a green tick, a player a single red-capable check has flagged shows a
 * yellow warning, and a player ≥3 distinct red-capable checks have corroborated shows a red skull
 * (see [dev.iustitia.history.FlagHistory.tierFor] — strict corroboration + ~10-min decay, so a
 * yellow/red nametag implies ≥95% cheating confidence).
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
 * ## Server coverage
 *
 * - **minemen.club + 1.8.9 servers**: vanilla populates `displayName` for living players and the
 *   base renderer calls `renderLabelIfPresent` → the vanilla path prefixes it. ✅
 * - **Team-hidden-nametag servers (mcpvp.club, stray.gg)**: the team sets `nametagVisibility` so
 *   `LivingEntityRenderer.hasLabel` returns false and the base renderer does NOT call
 *   `renderLabelIfPresent` during live play (the diagnostic only ever fired at death). This HEAD
 *   therefore never runs during combat, so prefixing `playerName` here would change nothing on
 *   screen — the on-screen name is a HOLOGRAM entity the server spawns, not the player's own
 *   label. The tier cue on these servers comes from [ArmorStandEntityRendererMixin] (vehicle-graph
 *   + proximity owner match), NOT this branch. The `playerName` fallback below only helps on the
 *   rarer servers that DO call `renderLabelIfPresent` with a null `displayName` but a populated
 *   BELOW_NAME score line.
 *
 * Which case a given server hits is disambiguated by the verbose `nametag-fallback-probe` log:
 * if it fires, this inject is being called; if it never fires on a server, the name is a hologram
 * and the armor-stand mixin is the only path.
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
     * state → cheat tier (+ owner uuid, for the fallback verbose log) for the entity currently
     * rendered by that state (set in updateRenderState, consumed+removed in renderLabelIfPresent).
     * Bounded by remove-on-read + [CAP] backstop.
     */
    private data class TierEntry(val tier: FlagHistory.Tier, val uuid: java.util.UUID)

    private val stateTier: IdentityHashMap<PlayerEntityRenderState, TierEntry> = IdentityHashMap()

    /**
     * Players for which we've already emitted the "fell back to BELOW_NAME score line" verbose
     * line this session — one log per player (the fallback fires every frame; without this it'd
     * spam `latest.log`). Diagnostic-only, gated on [VerboseLog.isEnabled]; CAP-bounded backstop
     * like [stateTier]. Independent of `FlagHistory.reset` (a reset doesn't un-discover a server).
     */
    private val loggedFallback =
        java.util.Collections.synchronizedSet(java.util.LinkedHashSet<java.util.UUID>())

    /** Players for which we've already emitted the fallback-probe diagnostic this session. */
    private val loggedProbe =
        java.util.Collections.synchronizedSet(java.util.LinkedHashSet<java.util.UUID>())

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
                stateTier[state] = TierEntry(
                    EntityTrackerManager.get(other.uuid)?.tier ?: FlagHistory.tierFor(other.uuid),
                    other.uuid)
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
            val entry = stateTier.remove(state) ?: return
            val tier = entry.tier
            if (tier == FlagHistory.Tier.GREEN && !cfg.nametagGreenEnabled) return

            val (color, glyph) = when (tier) {
                FlagHistory.Tier.GREEN -> "a" to "+"
                FlagHistory.Tier.YELLOW -> "e" to "!"
                FlagHistory.Tier.RED -> "c" to "X"
            }
            // Burst pulse: for ~3s (60 ticks) after a fresh red-capable alert, alternate the glyph
            // color between the tier color and white (§f) on a ~400ms cycle so the prefix "pulses"
            // mid-fight — a visual flag-burst cue. Pure text-color modulation, no render API. The
            // double-prefix guard below is color-agnostic so the §f pulse variant still blocks
            // intra-frame multi-pass re-prefixing.
            val glyphColor = if (cfg.nametagBurstPulse) {
                val lastRed = try { FlagHistory.lastRedAlertTick[entry.uuid] ?: -10000 } catch (_: Throwable) { -10000 }
                if (dev.iustitia.Iustitia.tickCounter - lastRed <= 60) {
                    if ((System.currentTimeMillis() / 200L) % 2L == 0L) color else "f"
                } else color
            } else color
            // Prefix: just the tier glyph in a bracket, tier-colored (including the burst-pulse §f
            // white variant). The numeric confidence score is NOT shown on the nametag — only the
            // glyph. (The score is still available in `/ius hist`, `/ius session`, the snapshot, and
            // the crosshair confidence HUD.)
            val prefix = Text.literal("§${glyphColor}[$glyph]§r ")

            val dnAcc = state as? EntityRenderStateAccessor ?: return
            val displayName = dnAcc.iustitia_getDisplayName()
            if (displayName != null) {
                // Vanilla path: prefix the name. The BELOW_NAME score line (playerName) is left
                // untouched. Double-prefix guard: skip if already prefixed this frame (multi-pass).
                // Color-agnostic so the burst-pulse §f variant (and the §7 badge) still match.
                if (alreadyPrefixed(displayName.string)) return
                dnAcc.iustitia_setDisplayName(Text.empty().append(prefix).append(displayName))
            } else {
                // FALLBACK: the server hid the vanilla name (displayName null via team
                // nametagVisibility). WHEN renderLabelIfPresent is being called, the only label the
                // vanilla player renderer still draws is the BELOW_NAME score line (playerName), so
                // we prefix that as a tier cue. On team-hidden servers the base renderer may skip
                // renderLabelIfPresent entirely (hasLabel == false) — then this HEAD never runs and
                // the on-screen name is a HOLOGRAM entity; the tier cue there comes from
                // ArmorStandEntityRendererMixin, not this branch. Skip GREEN — no [+] on every clean
                // player's score line; only YELLOW/RED surface a cue on the fallback path.
                val pnAcc = state as? PlayerEntityRenderStateAccessor
                val score = pnAcc?.iustitia_getPlayerName()
                // Probe (one-per-player, verbose-only): confirms (a) renderLabelIfPresent IS being
                // called for this player on this server, and (b) whether a BELOW_NAME score line
                // exists. If this never logs on a server, the base renderer is skipping
                // renderLabelIfPresent → the visible name is a hologram → the armor-stand mixin is
                // the only path. This is the diagnostic that pins the mcpvp/stray case.
                logFallbackProbeOnce(entry.uuid, tier, score != null)
                if (tier == FlagHistory.Tier.GREEN) return
                if (pnAcc == null || score == null) return   // no BELOW_NAME objective → nothing to prefix
                if (alreadyPrefixed(score.string)) return
                pnAcc.iustitia_setPlayerName(Text.empty().append(prefix).append(score))
                logFallbackOnce(entry.uuid, tier)
            }
        } catch (_: Throwable) {}
    }

    /**
     * Emit one verbose line the first time we fall back to prefixing a given player's BELOW_NAME
     * score line this session. This is the diagnostic that tells us (a) which servers actually
     * surface a BELOW_NAME score (vs none at all — stray.gg), and (b) whether mcpvp's visible label
     * really is the score line (if the log fires but no on-screen prefix appears, the label is a
     * hologram entity → the rider-coverage follow-up is the real fix). One-per-player to avoid
     * per-frame log spam; CAP-bounded. Writes to `latest.log` via [VerboseLog], not chat.
     */
    private fun logFallbackOnce(uuid: java.util.UUID, tier: FlagHistory.Tier) {
        try {
            if (!VerboseLog.isEnabled()) return
            if (loggedFallback.add(uuid)) {   // true only the first time per player
                if (loggedFallback.size > CAP) loggedFallback.clear()
                val name = FlagHistory.nameFor(uuid) ?: uuid.toString().take(8)
                VerboseLog.log(
                    "nametag-fallback: prefixed BELOW_NAME score for $name (tier=$tier) " +
                        "— displayName hidden by server"
                )
            }
        } catch (_: Throwable) {}
    }

    /**
     * One verbose line per player per session the first time we reach the displayName==null branch —
     * i.e. the first time `renderLabelIfPresent` is called for a player whose vanilla nametag the
     * server has hidden. Reports whether a BELOW_NAME score line (`playerName`) exists. This is the
     * diagnostic that disambiguates team-hidden-nametag servers:
     *  - if it fires → `renderLabelIfPresent` IS called here, so the visible label is the player's
     *    own `playerName` and the fallback (or GREEN-skip) explains what's on screen;
     *  - if it NEVER fires on a server → the base renderer is skipping `renderLabelIfPresent`
     *    (hasLabel==false), so the on-screen name is a hologram entity and the armor-stand mixin is
     *    the only path (and if that also never logs, the hologram is a TextDisplay — uncovered).
     * Verbose-only, writes to `latest.log` via [VerboseLog], not chat. CAP-bounded.
     */
    private fun logFallbackProbeOnce(uuid: java.util.UUID, tier: FlagHistory.Tier, hasScore: Boolean) {
        try {
            if (!VerboseLog.isEnabled()) return
            if (loggedProbe.add(uuid)) {
                if (loggedProbe.size > CAP) loggedProbe.clear()
                val name = FlagHistory.nameFor(uuid) ?: uuid.toString().take(8)
                VerboseLog.log(
                    "nametag-fallback-probe: $name displayName hidden by server (tier=$tier); " +
                        "BELOW_NAME score=${if (hasScore) "present" else "null"}; " +
                        "renderLabelIfPresent IS called for this player"
                )
            }
        } catch (_: Throwable) {}
    }

    private companion object {
        // Mixin requires all static fields to be private (it rejects non-private static fields at
        // apply time with InvalidMixinException). These are companion-object vals/consts = static
        // fields in bytecode, so they MUST be `private` — a green build does NOT catch this.
        /** Backstop size for [stateTier] — far above any realistic per-frame live-entity count. */
        private const val CAP = 512
    }

    /**
     * Color-agnostic "already prefixed" guard: strips any leading `§<code>` formatting codes then
     * checks for our tier-glyph bracket opening — `[` + one of `+`/`!`/`X` + `]`. The burst-pulse
     * modulates the glyph color (tier color ↔ `§f` white), so the leading-color strip is what makes
     * the match color-agnostic — on the shadow + main multi-pass per frame this stops a re-prefix
     * stacking `[X][X] Name`. Fail-safe: on any parse error returns false (prefix — safe side is to
     * prefix; worst case a one-frame double glyph, not a dropped tier cue).
     */
    private fun alreadyPrefixed(s: String): Boolean = try {
        var i = 0
        val n = s.length
        while (i + 1 < n && s[i] == '§' && s[i + 1] in "0123456789abcdefklmnorABCDEFKLMNOR") i += 2
        // `[` + glyph(+/!/X) + `]`.
        n - i >= 3 && s[i] == '[' && (s[i + 1] == '+' || s[i + 1] == '!' || s[i + 1] == 'X') && s[i + 2] == ']'
    } catch (_: Throwable) { false }
}