package dev.iustitia.ui

import dev.iustitia.Iustitia
import dev.iustitia.history.Evidence
import dev.iustitia.history.FlagHistory
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

/**
 * #1 (timeline) + #2 (profile card) in one screen — opened by `/ius hist <name>` and by clicking
 * a [PlayerSearchScreen] row.
 *
 * **Header (the #2 profile card):** tier glyph + name, first→last flag tick, alerts vs flags,
 * max vl, top check, the one-line cheat-confidence summary, and a "max vl per check" bar
 * (block-bar per check, scaled to the session max). The thing you screenshot for yourself.
 *
 * **Filter row:** a check filter (cycle: all → each check that flagged) and a time filter
 * (all / last 200 / last 50 ticks) — clickable cycle labels.
 *
 * **Body (the #1 timeline):** the player's flags ([FlagHistory.flags], last 50), grouped by check
 * (groups ordered by flag count desc), chronological within each group. Each flag row shows
 * `@t{tick} {label} vl={vl}` and, when the flag carries an [Evidence] payload, a second line
 * with the sub-label · measurement/threshold · position · victim · extra — the "why".
 *
 * Manually rendered + scrolled (uniform 20px rows) for full control over the confirmed 1.21.11
 * `Click`-based input API. Whole body fail-open.
 */
class PlayerHistoryScreen(private val uuid: java.util.UUID, private val parent: Screen?) :
    Screen(Text.literal("Iustitia — player report")) {

    private sealed class Item {
        data class Group(val checkId: String, val count: Int, val maxVl: Double) : Item()
        data class Row(val flag: FlagHistory.Flag) : Item()
    }

    // filter state
    private var checkFilter: String? = null   // null = all
    private var timeFilter: Int = 0           // 0=all 1=last200 2=last50
    private var scroll: Int = 0

    private val name: String get() = FlagHistory.nameFor(uuid) ?: uuid.toString().take(8)
    private val tier get() = FlagHistory.tierFor(uuid)
    private val counts: Map<String, Int> get() = FlagHistory.flagCounts(uuid)
    private val maxVlMap: Map<String, Double> get() = FlagHistory.maxVlByCheck(uuid)

    override fun shouldPause(): Boolean = false

    override fun init() { /* fully data-driven; rebuilt each render */ }

    private fun timeMatch(tick: Int): Boolean {
        val now = Iustitia.tickCounter
        val window = when (timeFilter) { 1 -> 200; 2 -> 50; else -> Int.MAX_VALUE }
        return tick >= now - window
    }

    private fun items(): List<Item> {
        val flags = FlagHistory.flags(uuid).filter { timeMatch(it.tick) }
        val grouped = flags.groupBy { it.checkId }
            .toList()
            .sortedByDescending { it.second.size }
            .let { if (checkFilter != null) it.filter { it.first == checkFilter } else it }
        val out = ArrayList<Item>(flags.size + 8)
        for ((checkId, list) in grouped) {
            out += Item.Group(checkId, list.size, list.maxOf { it.vl })
            for (f in list.sortedBy { it.tick }) out += Item.Row(f)
        }
        return out
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        try {
            // F1-equivalent backdrop: a dark, mostly-opaque fill behind the content so the
            // in-game HUD (hotbar/chat) and world don't bleed through this transparent screen
            // and hurt the report's readability. Fills respect alpha (unlike drawText), so this
            // is visible. No global state is touched — closing the screen resumes normal
            // rendering, so the "F1" effect auto-restores.
            context.fill(0, 0, this.width, this.height, BG)
            super.render(context, mouseX, mouseY, delta)
            val tr = this.textRenderer
            val x = 10
            var y = 12
            // ---- profile card (#2) ----
            context.drawTextWithShadow(tr, Text.literal(glyphFor(tier) + " §f§l" + name + " §7(" + tierLabel(tier) + ")"), x, y, WHITE); y += 14
            val sp = FlagHistory.span(uuid)
            val spanTxt = if (sp == null) "§7no flags this session" else "§7first §f@t${sp.first} §7last §f@t${sp.second}"
            context.drawTextWithShadow(tr, Text.literal(spanTxt), x, y, WHITE); y += 12
            val total = counts.values.sum()
            val maxVl = maxVlMap.values.maxOrNull() ?: 0.0
            context.drawTextWithShadow(tr, Text.literal(
                "§7alerts §f${FlagHistory.sessionAlertCount(uuid)} §7flags §f$total §7max vl §f${"%.1f".format(maxVl)}" +
                    (FlagHistory.topCheck(uuid)?.let { " §7top §b$it" } ?: "")), x, y, WHITE); y += 12
            context.drawTextWithShadow(tr, Text.literal("§7confidence: §f" + FlagHistory.confidenceLine(uuid)), x, y, WHITE); y += 14
            // max vl per check bar (top 8)
            context.drawTextWithShadow(tr, Text.literal("§7max vl per check:"), x, y, WHITE); y += 11
            val maxVlForScale = (maxVlMap.values.maxOrNull() ?: 0.0).coerceAtLeast(0.001)
            maxVlMap.entries.take(8).forEach { (cid, vl) ->
                val filled = (vl / maxVlForScale * 10.0).toInt().coerceIn(0, 10)
                val bar = "▓".repeat(filled) + "░".repeat(10 - filled)
                context.drawTextWithShadow(tr, Text.literal("§b" + cid.padEnd(16).take(16) + " §7$bar §f${"%.1f".format(vl)}"), x, y, WHITE); y += 11
            }
            y += 4
            // ---- filter row ----
            val checkLabel = "§7[§fCheck: ${checkFilter ?: "all"}§7]"
            val timeLabel = "§7[§fTime: ${timeLabel()}§7]"
            val checkW = tr.getWidth(Text.literal(checkLabel)) + 2
            context.drawTextWithShadow(tr, Text.literal(checkLabel), x, y, WHITE)
            context.drawTextWithShadow(tr, Text.literal(timeLabel), x + checkW + 12, y, WHITE)
            filterY = y
            filterCheckX = x; filterCheckW = checkW
            filterTimeX = x + checkW + 12; filterTimeW = tr.getWidth(Text.literal(timeLabel)) + 2
            y += 16
            // ---- timeline body (#1) ----
            val bodyTop = y
            val bodyBottom = this.height - 12
            val list = items()
            val visible = ((bodyBottom - bodyTop) / ROW_H).coerceAtLeast(1)
            val maxScroll = (list.size - visible).coerceAtLeast(0)
            scroll = scroll.coerceIn(0, maxScroll)
            context.enableScissor(x, bodyTop, this.width - 4, bodyBottom)
            for (i in 0 until visible) {
                val idx = scroll + i
                if (idx >= list.size) break
                val item = list[idx]
                val ry = bodyTop + i * ROW_H
                val hovered = mouseX in x..(this.width - 4) && mouseY in ry..(ry + ROW_H)
                when (item) {
                    is Item.Group -> {
                        if (hovered) context.fill(x, ry, this.width - 4, ry + ROW_H, 0x30FFFF00)
                        context.drawTextWithShadow(tr, Text.literal("§e§l${item.checkId} §r§7(${item.count} flags, max ${"%.1f".format(item.maxVl)})"), x + 2, ry + 4, WHITE)
                    }
                    is Item.Row -> {
                        if (hovered) context.fill(x, ry, this.width - 4, ry + ROW_H, 0x20FFFFFF)
                        val f = item.flag
                        context.drawTextWithShadow(tr, Text.literal("§7  @t${f.tick} §f${f.label} §7vl§f${"%.1f".format(f.vl)}"), x + 2, ry + 2, WHITE)
                        val ev = f.evidence
                        if (ev != null) context.drawTextWithShadow(tr, Text.literal("§7    " + evidenceLine(ev)), x + 2, ry + 12, WHITE)
                    }
                }
            }
            context.disableScissor()
            if (maxScroll > 0) {
                val trackH = bodyBottom - bodyTop
                val knobH = (trackH * visible / list.size).coerceAtLeast(10)
                val knobY = bodyTop + (trackH - knobH) * scroll / maxScroll
                val sx = this.width - 6
                context.fill(sx, bodyTop, sx + 3, bodyBottom, 0x33FFFFFF)
                context.fill(sx, knobY, sx + 3, knobY + knobH, 0x66FFFFFF)
                context.drawTextWithShadow(tr, Text.literal("§7scroll"), x, this.height - 11, WHITE)
            }
        } catch (_: Throwable) {}
    }

    private var filterY: Int = 0
    private var filterCheckX: Int = 0; private var filterCheckW: Int = 0
    private var filterTimeX: Int = 0; private var filterTimeW: Int = 0

    override fun mouseClicked(click: Click, button: Boolean): Boolean {
        try {
            if (super.mouseClicked(click, button)) return true
            if (button) return false
            val mx = click.x().toInt(); val my = click.y().toInt()
            // filter buttons
            if (my in filterY..(filterY + 12)) {
                if (mx in filterCheckX..(filterCheckX + filterCheckW)) { cycleCheck(); scroll = 0; return true }
                if (mx in filterTimeX..(filterTimeX + filterTimeW)) { timeFilter = (timeFilter + 1) % 3; scroll = 0; return true }
            }
        } catch (_: Throwable) {}
        return false
    }

    override fun mouseScrolled(x: Double, y: Double, h: Double, v: Double): Boolean {
        // Lower clamp only here; render() recomputes maxScroll from the live layout and re-clamps
        // to [0, maxScroll] before drawing each frame, so an over-scroll never produces empty frames.
        try { scroll = (scroll - v.toInt()).coerceAtLeast(0); return true } catch (_: Throwable) {}
        return super.mouseScrolled(x, y, h, v)
    }

    override fun close() { client?.setScreen(parent) }

    private fun cycleCheck() {
        val keys = counts.keys.toList()  // already count-desc from FlagHistory.flagCounts
        if (checkFilter == null) { checkFilter = keys.firstOrNull(); return }
        val i = keys.indexOf(checkFilter)
        checkFilter = if (i < 0 || i + 1 >= keys.size) null else keys[i + 1]
    }
    private fun timeLabel(): String = when (timeFilter) { 1 -> "last 200t"; 2 -> "last 50t"; else -> "all" }

    private fun evidenceLine(e: Evidence): String {
        val parts = ArrayList<String>()
        e.subLabel?.let { parts += it }
        if (e.measurement != null || e.threshold != null) parts += "${e.measurement ?: "?"}/${e.threshold ?: "?"}"
        e.pos?.let { parts += "(${it.x.toInt()},${it.y.toInt()},${it.z.toInt()})" }
        e.victim?.let { parts += "victim=" + (FlagHistory.nameFor(it) ?: it.toString().take(8)) }
        e.extra?.let { parts += it }
        return parts.joinToString(" · ")
    }

    companion object {
        private const val ROW_H = 20
        // Opaque white (ARGB 0xFFFFFFFF). 0xFFFFFF is 0x00FFFFFF (alpha 0) and DrawContext.drawText
        // no-ops when getAlpha(color)==0 (verified in 1.21.11), so 0xFFFFFF text is invisible. -1
        // is the signed-int form of 0xFFFFFFFF.
        private const val WHITE = -1
        /** Dark, ~80%-opaque backdrop (ARGB 0xCC101010) covering the HUD/world behind the screen.
         *  `.toInt()` because the 8-hex literal's high alpha (≥0x80) makes it overflow Int→Long
         *  in Kotlin (same gotcha as WHITE = -1 for 0xFFFFFFFF). */
        private val BG = 0xCC101010.toInt()
        private fun glyphFor(t: FlagHistory.Tier): String = when (t) {
            FlagHistory.Tier.GREEN -> "§a[+]§r"; FlagHistory.Tier.YELLOW -> "§e[!]§r"; FlagHistory.Tier.RED -> "§c[X]§r"
        }
        private fun tierLabel(t: FlagHistory.Tier): String = when (t) {
            FlagHistory.Tier.GREEN -> "clean"; FlagHistory.Tier.YELLOW -> "suspect"; FlagHistory.Tier.RED -> "blatant"
        }
    }
}