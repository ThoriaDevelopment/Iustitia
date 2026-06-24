package dev.iustitia.ui

import dev.iustitia.config.ConfigManager
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

/**
 * #13 (first-launch wizard): a one-shot manual-render screen shown when `wizardCompleted` is false
 * (driven from `onInitializeClient`). Three preset buttons — General / Moderation / Ranked Player —
 * each write a curated set of display/UX fields (NOT check calibration) to the config, stamp
 * `wizardCompleted`, save, react to the persistence toggle, and close to the game. A "Skip / keep
 * defaults" button just stamps the flag and closes without changing anything. Everything the wizard
 * touches is display-only; no check threshold/decay/VL is ever changed here. Fail-open.
 *
 * Preset rationale:
 *  - **General** — everyday play: normal alerts, nametag on, no audio, batching on, no persistence.
 *  - **Moderation** — staff reviewing a server: verbose alerts, audio cues, no batching (every
 *    individual flag), nametag-suspects-only (no green ticks), persistence on so notes + history
 *    survive restarts, transcript panel on.
 *  - **Ranked Player** — competitive: quiet (red-band only), compact one-liners, audio + nuclear,
 *    batching on, no persistence, no transcript panel.
 */
class SetupWizardScreen(private val parent: Screen?) : Screen(TITLE) {

    private data class Preset(val label: String, val blurb: String, val apply: () -> Unit)

    private val presets: List<Preset> = listOf(
        Preset("§aGeneral", "Everyday play. Normal alerts, nametag on, silent, no persistence.") {
            val c = ConfigManager.config
            c.alertLevel = 1
            c.alertsEnabled = true
            c.nametagPrefixes = true
            c.nametagGreenEnabled = true
            c.nametagBadge = true
            c.nametagBurstPulse = true
            c.audioCues = false
            c.audioNuclear = true
            c.audioVolume = 0.6
            c.alertBatching = true
            c.compactMode = false
            c.persistenceEnabled = false
            c.lagSuppressAlerts = true
            c.transcriptPanel = false
        },
        Preset("§6Moderation", "Staff review. Verbose, audio, every flag, suspects-only, persistence on.") {
            val c = ConfigManager.config
            c.alertLevel = 2
            c.alertsEnabled = true
            c.nametagPrefixes = true
            c.nametagGreenEnabled = false
            c.nametagBadge = true
            c.nametagBurstPulse = true
            c.audioCues = true
            c.audioNuclear = true
            c.audioVolume = 0.8
            c.alertBatching = false
            c.compactMode = false
            c.persistenceEnabled = true
            c.lagSuppressAlerts = true
            c.transcriptPanel = true
        },
        Preset("§cRanked Player", "Competitive. Quiet (red only), compact, audio + nuclear, no persistence.") {
            val c = ConfigManager.config
            c.alertLevel = 0
            c.alertsEnabled = true
            c.nametagPrefixes = true
            c.nametagGreenEnabled = false
            c.nametagBadge = true
            c.nametagBurstPulse = true
            c.audioCues = true
            c.audioNuclear = true
            c.audioVolume = 1.0
            c.alertBatching = true
            c.compactMode = true
            c.persistenceEnabled = false
            c.lagSuppressAlerts = true
            c.transcriptPanel = false
        },
    )

    override fun shouldPause(): Boolean = true

    override fun init() { try {} catch (_: Throwable) {} }

    private fun finish() {
        try {
            ConfigManager.config.wizardCompleted = true
            ConfigManager.save()
            try { dev.iustitia.Iustitia.onConfigReloaded() } catch (_: Throwable) {}
        } catch (_: Throwable) {}
        client?.setScreen(parent)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        try {
            context.fill(0, 0, this.width, this.height, BG)
            super.render(context, mouseX, mouseY, delta)
            val tr = this.textRenderer
            val cx = this.width / 2
            context.drawTextWithShadow(tr, Text.literal("§8[§diustitia§8] §f§lFirst-launch setup"), cx - tr.getWidth("§8[§diustitia§8] §f§lFirst-launch setup") / 2, 20, WHITE)
            context.drawTextWithShadow(tr, Text.literal("§7How do you use Iustitia? Pick a preset — you can tweak everything later in §f/ius config§7."), cx - 180, 40, WHITE)
            context.drawTextWithShadow(tr, Text.literal("§7(this only changes display + alert settings; detection calibration is never touched)"), cx - 180, 52, WHITE)
            val bw = 360; val bh = 52
            var y = 78
            for ((i, p) in presets.withIndex()) {
                val x = cx - bw / 2
                val hovered = mouseX in x..(x + bw) && mouseY in y..(y + bh)
                if (hovered) context.fill(x, y, x + bw, y + bh, 0x40FFFFFF)
                border(context, x, y, bw, bh, 0xFF333333.toInt())
                context.drawTextWithShadow(tr, Text.literal(p.label), x + 8, y + 6, WHITE)
                context.drawTextWithShadow(tr, Text.literal("§7${p.blurb}"), x + 8, y + 22, WHITE)
                context.drawTextWithShadow(tr, Text.literal("§8click to apply"), x + 8, y + 36, WHITE)
                y += bh + 8
            }
            val sx = cx - bw / 2; val sy = y
            val skipHover = mouseX in sx..(sx + bw) && mouseY in sy..(sy + 20)
            if (skipHover) context.fill(sx, sy, sx + bw, sy + 20, 0x40FFFFFF)
            border(context, sx, sy, bw, 20, 0xFF333333.toInt())
            context.drawTextWithShadow(tr, Text.literal("§7Skip / keep defaults"), sx + 8, sy + 6, WHITE)
        } catch (_: Throwable) {}
    }

    override fun mouseClicked(click: Click, button: Boolean): Boolean {
        try {
            if (super.mouseClicked(click, button)) return true
            if (button) return false
            val mx = click.x().toInt(); val my = click.y().toInt()
            val cx = this.width / 2
            val bw = 360; val bh = 52
            var y = 78
            for (p in presets) {
                val x = cx - bw / 2
                if (mx in x..(x + bw) && my in y..(y + bh)) {
                    try { p.apply() } catch (_: Throwable) {}
                    finish(); return true
                }
                y += bh + 8
            }
            val sx = cx - bw / 2; val sy = y
            if (mx in sx..(sx + bw) && my in sy..(sy + 20)) { finish(); return true }
        } catch (_: Throwable) {}
        return false
    }

    override fun close() { finish() }

    /** 1.21.11 DrawContext has no drawBorder; draw a 1px outline with four fills. */
    private fun border(context: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
        context.fill(x, y, x + w, y + 1, color)
        context.fill(x, y + h - 1, x + w, y + h, color)
        context.fill(x, y, x + 1, y + h, color)
        context.fill(x + w - 1, y, x + w, y + h, color)
    }

    companion object {
        private val TITLE = Text.literal("Iustitia — setup")
        private const val WHITE = -1
        private val BG = 0xCC101010.toInt()
    }
}