package dev.iustitia.hud

import dev.iustitia.Iustitia
import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import dev.iustitia.info.FpHint
import dev.iustitia.tracking.EntityTrackerManager
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import java.util.UUID

/**
 * Phase B HUD overlays — the two lowest-risk deferred render features, delivered via the stable
 * Fabric [HudRenderCallback] (HUD-text drawing only, no world render / no camera mixin / no mixin at
 * all → build-verifiable and launch-safe, unlike the WorldRenderEvents / camera-control pieces that
 * remain gated behind a runtime probe). Everything here is **display-only**: it reads existing
 * [FlagHistory] / [EntityTrackerManager] state and draws text; it touches no check, no packet, no
 * local-player state. Whole render body is fail-open (a HUD error must never crash the frame).
 *
 * Two overlays, each behind its own config toggle (default on, disable in `/ius config`):
 *
 * 1. **Server-lag indicator** (`cfg.lagHudIcon`): a small top-left `§c⚠ §7lag` marker drawn while a
 *    server-wide lag spike / lag burst is recent (within [LAG_RECENT_TICKS] of
 *    [EntityTrackerManager.lastServerLagTick] / [lastLagBurstTick]). This is the visible counterpart
 *    of `cfg.lagSuppressAlerts` — it shows the moderator WHY alerts are being `[lag]`-prefixed / the
 *    reach distance flags are suspect, without them opening a screen. Reuses the existing lag signal;
 *    no TPS estimator.
 *
 * 2. **Crosshair confidence panel** (`cfg.confidenceHud`): a compact panel drawn just below the
 *    crosshair for the other player currently under the crosshair (`Iustitia.currentTarget()`):
 *    `name  [X 82]` in the tier color, then the one-line `confidenceExplanation` ("RED: 3 primary
 *    checks (reach, backtrack) within 12s"), then the benign-cause [FpHint] for their first alerted
 *    check ("lag can inflate distance…"). This is the deferred nametag-hover confidence tooltip
 *    delivered as a robust HUD overlay — the world-hover tooltip render path is fragile on 1.21.11,
 *    and a HUD that reads the crosshair target is equivalent for the user's "what am I looking at"
 *    question. GREEN players show a one-line "clean" panel (no FP hint); the panel disappears the
 *    moment the crosshair leaves a player.
 *
 * The overlays are drawn ONLY when no fullscreen screen is open (the vanilla HUD render path that
 * fires this callback is skipped while a screen is up), so they never fight the keybind hub / config /
 * history screens.
 */
object HudOverlay {

    /** Lag is "recent" for this many ticks after the last spike/burst (8 = 0.4s — same window as the
     *  alert lag-soften so the indicator and the `[lag]` prefix agree). */
    private const val LAG_RECENT_TICKS = 8

    /** Crosshair must rest on the SAME player for this many ticks (1.5s) before the expanded hover
     *  tooltip appears — distinguishes a deliberate "inspect this player" from a passing glance. */
    private const val DWELL_TICKS = 30
    /** Max flag-count lines shown in the hover tooltip (most-flagged checks). */
    private const val HOVER_TOP_FLAGS = 3

    /** Dwell tracking for the hover tooltip: the uuid the crosshair has rested on, and for how long. */
    private var dwellUuid: UUID? = null
    private var dwellTicks: Int = 0

    /** Register the single HUD callback. Call once from `onInitializeClient`. Fail-open. */
    fun register() {
        try {
            HudRenderCallback.EVENT.register(HudRenderCallback { context, _ ->
                try { render(context) } catch (_: Throwable) { /* never crash the frame */ }
            })
        } catch (_: Throwable) {
            // fail-open: if the callback registration shape differs at runtime, the HUDs just don't
            // appear — detection/tiering/alerts are unaffected.
        }
    }

    private fun render(context: DrawContext) {
        val cfg = ConfigManager.config
        val mc = MinecraftClient.getInstance()
        // Don't draw over an open screen (HUD render is normally skipped then, but guard anyway — a
        // paused-game HUD over the keybind hub / config screen would be visual noise).
        if (mc.currentScreen != null) return
        val tr = mc.textRenderer ?: return

        // Dwell tracking for the hover tooltip: increment while the crosshair rests on the SAME
        // player, reset on a change / no target.
        val target = Iustitia.currentTarget()
        if (target == null) {
            dwellUuid = null; dwellTicks = 0
        } else if (target.first == dwellUuid) {
            dwellTicks += 1
        } else {
            dwellUuid = target.first; dwellTicks = 1
        }
        val hoverActive = cfg.hoverTooltip && target != null && dwellTicks >= DWELL_TICKS

        if (cfg.lagHudIcon) {
            try { renderLagIcon(context, tr) } catch (_: Throwable) {}
        }
        // The compact confidence panel and the expanded hover tooltip overlap in content — when the
        // hover tooltip is up (deliberate dwell), suppress the compact panel to avoid duplication.
        if (cfg.confidenceHud && !hoverActive) {
            try { renderConfidencePanel(context, tr, mc) } catch (_: Throwable) {}
        }
        if (hoverActive) {
            try { renderHoverTooltip(context, tr, mc, target!!) } catch (_: Throwable) {}
        }
    }

    /** Top-left `⚠ lag` while a server-lag spike / burst is recent. */
    private fun renderLagIcon(context: DrawContext, tr: TextRenderer) {
        val tick = Iustitia.tickCounter
        val lagTick = EntityTrackerManager.lastServerLagTick
        val burstTick = EntityTrackerManager.lastLagBurstTick
        val recent = (tick - lagTick) in 0..LAG_RECENT_TICKS ||
            (tick - burstTick) in 0..LAG_RECENT_TICKS
        if (!recent) return
        val line = Text.literal("§c⚠ §7lag")
        val w = tr.getWidth(line) + 8
        context.fill(2, 2, 2 + w, 14, BG)
        context.drawTextWithShadow(tr, line, 6, 5, WHITE)
    }

    /** Compact crosshair-target confidence panel, drawn just below the screen centre. */
    private fun renderConfidencePanel(context: DrawContext, tr: TextRenderer, mc: MinecraftClient) {
        val target = Iustitia.currentTarget() ?: return
        val (uuid, name) = target

        val tier = FlagHistory.tierFor(uuid)
        val score = try { FlagHistory.confidenceScore(uuid) } catch (_: Throwable) { 0 }
        val (color, glyph) = when (tier) {
            FlagHistory.Tier.GREEN -> "a" to "+"
            FlagHistory.Tier.YELLOW -> "e" to "!"
            FlagHistory.Tier.RED -> "c" to "X"
        }
        val head = Text.literal("§8[§diustitia§8] §f$name §${color}[$glyph $score]")

        val explanation = try { FlagHistory.confidenceExplanation(uuid) } catch (_: Throwable) { "" }
        val explLine = if (explanation.isEmpty()) null else Text.literal("§7$explanation")

        // FP hint for the first alerted check (any alerted check's benign-cause is useful context).
        val fpLine = try {
            val checks = FlagHistory.alertedChecksOf(uuid)
            val first = checks.firstOrNull { FpHint.hint(it) != null }
            val hint = first?.let { FpHint.hint(it) }
            if (hint != null) Text.literal("§7FP: §f$hint") else null
        } catch (_: Throwable) { null }

        val lines = ArrayList<Text>(3)
        lines.add(head)
        if (explLine != null) lines.add(explLine)
        if (fpLine != null) lines.add(fpLine)
        if (lines.size <= 1) return  // head only + nothing else → skip (don't draw a bare header)

        // Layout: centered horizontally, just below the crosshair (screen centre). Each line 11px;
        // pad 6px sides / 4px top+bottom. Semi-transparent backdrop so it reads over any scene.
        val padX = 6
        val padY = 4
        val lineH = 11
        val panelW = (lines.maxOf { tr.getWidth(it) } + padX * 2)
        val panelH = lineH * lines.size + padY * 2
        val sw = mc.getWindow().scaledWidth
        val x = (sw - panelW) / 2
        val y = mc.getWindow().scaledHeight / 2 + 14

        context.fill(x, y, x + panelW, y + panelH, BG)
        var ty = y + padY
        for (ln in lines) {
            context.drawTextWithShadow(tr, ln, x + padX, ty, WHITE)
            ty += lineH
        }
    }

    /**
     * Expanded hover tooltip: appears once the crosshair has dwelt on one player for [DWELL_TICKS],
     * drawn as a top-center banner with the tier glyph + score, the one-line "why this tier"
     * explanation, the benign-cause [FpHint] for their top alerted check, and their top
     * [HOVER_TOP_FLAGS] most-flagged checks this session. This is the deferred nametag-hover tooltip
     * rendered as a robust HUD overlay (the world-space hover render path is fragile on 1.21.11; a
     * top-center HUD that reads the dwelt-on crosshair target is the equivalent, and suppresses the
     * compact crosshair panel while active so the two don't duplicate). GREEN players show a one-line
     * "clean" banner; it disappears the moment the crosshair leaves or changes target.
     */
    private fun renderHoverTooltip(
        context: DrawContext, tr: TextRenderer, mc: MinecraftClient,
        target: Pair<UUID, String>,
    ) {
        val (uuid, name) = target
        val tier = FlagHistory.tierFor(uuid)
        val score = try { FlagHistory.confidenceScore(uuid) } catch (_: Throwable) { 0 }
        val (color, glyph) = when (tier) {
            FlagHistory.Tier.GREEN -> "a" to "+"
            FlagHistory.Tier.YELLOW -> "e" to "!"
            FlagHistory.Tier.RED -> "c" to "X"
        }
        val lines = ArrayList<Text>(5)
        lines.add(Text.literal("§8[§diustitia§8] §f$name §${color}[$glyph $score]"))
        val explanation = try { FlagHistory.confidenceExplanation(uuid) } catch (_: Throwable) { "" }
        if (explanation.isNotEmpty()) lines.add(Text.literal("§7$explanation"))
        try {
            val checks = FlagHistory.alertedChecksOf(uuid)
            val first = checks.firstOrNull { FpHint.hint(it) != null }
            val hint = first?.let { FpHint.hint(it) }
            if (hint != null) lines.add(Text.literal("§7FP: §f$hint"))
        } catch (_: Throwable) {}
        // Top most-flagged checks this session — the "what are they actually doing" detail.
        try {
            val counts = FlagHistory.flagCounts(uuid)
            if (counts.isNotEmpty()) {
                val summary = counts.entries.take(HOVER_TOP_FLAGS).joinToString("§8, §7") { (cid, c) ->
                    "§7$cid §8×§f$c"
                }
                lines.add(Text.literal(summary))
            }
        } catch (_: Throwable) {}

        val padX = 6
        val padY = 4
        val lineH = 11
        val panelW = (lines.maxOf { tr.getWidth(it) } + padX * 2)
        val panelH = lineH * lines.size + padY * 2
        val sw = mc.getWindow().scaledWidth
        val x = (sw - panelW) / 2
        // Top-center, below the lag icon row.
        val y = 18

        context.fill(x, y, x + panelW, y + panelH, BG)
        var ty = y + padY
        for (ln in lines) {
            context.drawTextWithShadow(tr, ln, x + padX, ty, WHITE)
            ty += lineH
        }
    }

    // Opaque white (0xFFFFFFFF; 0xFFFFFF is alpha-0 = invisible — see PlayerSearchScreen).
    private const val WHITE = -1
    // 56% dark backdrop (alpha 0x90). 8-hex literal > Int.MAX → .toInt() to get the signed value,
    // kept as a `val` (NOT `const`) per the 8-hex-alpha gotcha.
    private val BG = 0x90000000.toInt()
}