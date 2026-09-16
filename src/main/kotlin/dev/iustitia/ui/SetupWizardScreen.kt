package dev.iustitia.ui

import dev.iustitia.config.ConfigManager
import dev.iustitia.config.PresetManager
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

/**
 * #13 (first-launch wizard): a one-shot manual-render screen shown when `wizardCompleted` is false
 * (driven from `onInitializeClient`). One button per non-diagnostic built-in preset
 * ([PresetManager.builtIns]), a "Skip / keep defaults" button, then it stamps `wizardCompleted`,
 * saves, and closes back to the game. Fail-open throughout.
 *
 * Driving the buttons from [PresetManager.builtIns] is the point of the current shape: the labels,
 * the blurbs and the profiles cannot drift from what `/ius preset <name>` applies, because there is
 * one list. Every button applies a WHOLE preset, so all of them set detection scope and calibration
 * and not just display fields. The old hand-rolled Moderation and Ranked Player field lists were
 * display/alert profiles that left detection scope wherever it was; they are gone.
 *
 * The one thing the wizard adds on top of a preset is persistence. `persistenceEnabled` is on
 * `ConfigManager.PRESET_EXCLUDED_KEYS` as per-user state, so no preset is allowed to write it, and
 * a wizard button has to layer it itself (moderation on, everyone else off). Moderation is the staff
 * profile and staff notes are worth keeping across restarts; for a player the roaming store is not
 * something to switch on without being asked.
 *
 * `debug` has no button. It turns everything on and drops both suppression timers, which is not
 * something to hand a first-launch user; it stays reachable by command (`/ius preset debug`). Blurb
 * lines are wrapped to the button width and truncated on a short window ([blurbBudget]), so four
 * buttons plus the skip row fit at GUI scale 3 and degrade to label-only at scale 4 instead of
 * running off the bottom of the screen.
 */
class SetupWizardScreen(private val parent: Screen?) : Screen(TITLE) {

    /** One wizard button: what it reads, the lines under the label, and what a click does. */
    private data class Preset(val label: String, val blurb: List<String>, val apply: () -> Unit)

    /** A clickable rectangle plus the blurb lines that fit inside it. [layout] produces these, and
     *  both [render] and [mouseClicked] read them, so hit-testing cannot disagree with the drawing. */
    private data class Box(val x: Int, val y: Int, val w: Int, val h: Int, val lines: List<String>)

    private data class Layout(val headerY: Int, val rows: List<Box>, val skip: Box)

    private val presets: List<Preset> = try {
        PresetManager.builtIns.filterNot { it.diagnostic }.map { bi ->
            Preset(
                label = if (bi.recommended) "§f§l${bi.label} §a§l(recommended)" else "§f§l${bi.label}",
                blurb = bi.blurb,
                apply = {
                    try { PresetManager.apply(bi.name) } catch (_: Throwable) {}
                    // Per-user state, which is why the preset cannot carry it: see the class KDoc.
                    try {
                        ConfigManager.config.persistenceEnabled = bi.name == "moderation"
                    } catch (_: Throwable) {}
                },
            )
        }
    } catch (_: Throwable) {
        emptyList()
    }

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

    /** How many blurb lines a button has room for on this window. GUI scale 4 on a 1080p display
     *  leaves about 270 GUI pixels of height, which fits four labels and nothing else; the labels
     *  still name every profile, and the header points at `/ius config` for the rest. */
    private fun blurbBudget(): Int = when {
        this.height >= 330 -> 2
        this.height >= 280 -> 1
        else -> 0
    }

    /** Where everything goes. Character counts stand in for font metrics so this needs no
     *  [DrawContext] and both callers can share it. The block is centered vertically, and clamps to
     *  8px from the top rather than going negative on a window too short for it. */
    private fun layout(): Layout {
        val budget = blurbBudget()
        val rowH = 8 + (1 + budget) * 10
        val rowsH = presets.size * rowH + ROW_GAP * (presets.size - 1).coerceAtLeast(0)
        val total = HEADER.size * LINE_H + HEADER_GAP + rowsH + ROW_GAP + SKIP_H
        val top = ((this.height - total) / 2).coerceAtLeast(8)
        val x = (this.width - BOX_W) / 2
        val headerY = top
        var y = top + HEADER.size * LINE_H + HEADER_GAP
        val rows = presets.map { p ->
            val box = Box(x, y, BOX_W, rowH, p.blurb.flatMap { wrap(it, BLURB_CHARS) }.take(budget))
            y += rowH + ROW_GAP
            box
        }
        return Layout(headerY, rows, Box(x, y, BOX_W, SKIP_H, emptyList()))
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        try {
            context.fill(0, 0, this.width, this.height, BG)
            super.render(context, mouseX, mouseY, delta)
            val tr = this.textRenderer
            val cx = this.width / 2
            val l = layout()
            for ((i, line) in HEADER.withIndex()) {
                context.drawTextWithShadow(tr, Text.literal(line), cx - tr.getWidth(line) / 2, l.headerY + i * LINE_H, WHITE)
            }
            for ((i, box) in l.rows.withIndex()) {
                drawBox(context, box, mouseX, mouseY)
                context.drawTextWithShadow(tr, Text.literal(presets[i].label), box.x + 8, box.y + 6, WHITE)
                for ((j, line) in box.lines.withIndex()) {
                    context.drawTextWithShadow(tr, Text.literal("§7$line"), box.x + 8, box.y + 16 + j * LINE_H, WHITE)
                }
            }
            val s = l.skip
            drawBox(context, s, mouseX, mouseY)
            context.drawTextWithShadow(tr, Text.literal(SKIP_LABEL), s.x + 8, s.y + 6, WHITE)
        } catch (_: Throwable) {}
    }

    private fun drawBox(context: DrawContext, box: Box, mouseX: Int, mouseY: Int) {
        if (mouseX in box.x..(box.x + box.w) && mouseY in box.y..(box.y + box.h)) {
            context.fill(box.x, box.y, box.x + box.w, box.y + box.h, 0x40FFFFFF)
        }
        border(context, box.x, box.y, box.w, box.h, 0xFF333333.toInt())
    }

    override fun mouseClicked(click: Click, button: Boolean): Boolean {
        try {
            if (super.mouseClicked(click, button)) return true
            if (button) return false
            val mx = click.x().toInt(); val my = click.y().toInt()
            val l = layout()
            for ((i, box) in l.rows.withIndex()) {
                if (mx in box.x..(box.x + box.w) && my in box.y..(box.y + box.h)) {
                    presets.getOrNull(i)?.let { p -> try { p.apply() } catch (_: Throwable) {} }
                    finish()
                    return true
                }
            }
            val s = l.skip
            if (mx in s.x..(s.x + s.w) && my in s.y..(s.y + s.h)) { finish(); return true }
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
        private val TITLE = Text.literal("Iustitia setup")
        private const val WHITE = -1
        private val BG = 0xCC101010.toInt()
        private const val LINE_H = 10
        private const val BOX_W = 360
        private const val ROW_GAP = 8
        private const val SKIP_H = 20
        private const val HEADER_GAP = 12

        /** Longest blurb line a 360px button can hold at the default font (the widest glyphs run
         *  about 6px, so 56 characters stay inside the box's 8px inset with room to spare). */
        private const val BLURB_CHARS = 56

        /** Hand-split rather than wrapped: these carry color codes, and a wrap that broke between a
         *  `§` and its letter would print the letter as text. */
        private val HEADER = listOf(
            "§8[§diustitia§8] §f§lFirst-launch setup",
            "§7Pick the profile that matches how you play.",
            "§fStandard §7is the recommended starting point.",
            "§7You can change any of it later in §f/ius config§7.",
        )

        private const val SKIP_LABEL = "§7Skip / keep defaults §8(no preset applied)"

        /** Split [text] into lines of at most [maxChars] characters, breaking on spaces. Plain text
         *  only: a §-code counts as visible characters here, which shortens a line rather than
         *  overflowing it. */
        private fun wrap(text: String, maxChars: Int): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            for (word in text.split(' ')) {
                if (sb.isNotEmpty() && sb.length + 1 + word.length > maxChars) {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(word)
            }
            if (sb.isNotEmpty()) out.add(sb.toString())
            return out
        }
    }
}
