package dev.iustitia.ui

import dev.iustitia.history.FlagHistory
import dev.iustitia.tracking.EntityTrackerManager
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import java.util.UUID

/**
 * #1 (search half): the `/ius hist` landing screen. A filter text field + a manually-rendered,
 * scrollable list of every player known this session — the **union** of players currently
 * tracked ([EntityTrackerManager.all], even those with zero flags) and players who ever
 * flagged ([FlagHistory.knownNames], even after despawn). This fixes the gap that
 * [FlagHistory.resolveName] alone misses a freshly-joined player who hasn't flagged yet.
 *
 * Each row shows the cheat-tier glyph + name + alert count + most-flagged check. Clicking a row
 * opens [PlayerHistoryScreen] for that player. Typing filters by case-insensitive `contains`.
 *
 * Manual rendering (not [net.minecraft.client.gui.widget.ElementListWidget]) keeps the whole
 * interaction under our control via the confirmed 1.21.11 `Click`-based input APIs and avoids
 * the `EntryListWidget$Entry`/`Click` dispatch uncertainty. Whole body fail-open.
 */
class PlayerSearchScreen(private val parent: Screen?) : Screen(TITLE) {

    private data class Row(val uuid: UUID, val name: String, val tier: FlagHistory.Tier, val alerts: Int, val top: String?)

    private var searchField: TextFieldWidget? = null
    private var filter: String = ""
    private var rows: List<Row> = emptyList()
    private var scroll: Int = 0

    override fun shouldPause(): Boolean = false

    override fun init() {
        try {
            val w = this.width
            searchField = TextFieldWidget(this.textRenderer, 10, 36, w - 20, 16, Text.literal("Search players"))
                .also { it.setMaxLength(32); it.text = filter; it.setChangedListener { f -> filter = f; rebuild(); scroll = 0 } }
            addDrawableChild(searchField)
            setInitialFocus(searchField)
            rebuild()
        } catch (_: Throwable) {}
    }

    private fun rebuild() {
        try {
            val q = filter.trim()
            // union: tracked players (live, may have no flags) + flagged players (may have despawned)
            val map = LinkedHashMap<UUID, String>()
            for (tp in EntityTrackerManager.all()) {
                val nm = FlagHistory.nameFor(tp.uuid) ?: tp.username()
                if (nm.length >= 2) map[tp.uuid] = nm
            }
            for (name in FlagHistory.knownNames()) {
                val uuid = FlagHistory.resolveName(name) ?: continue
                if (uuid !in map) map[uuid] = name
            }
            rows = map.entries.map { (uuid, name) ->
                Row(uuid, name, FlagHistory.tierFor(uuid), FlagHistory.sessionAlertCount(uuid), FlagHistory.topCheck(uuid))
            }.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
                .sortedWith(compareByDescending<Row> { it.tier.ordinal }.thenByDescending { it.alerts }.thenBy { it.name })
        } catch (_: Throwable) { rows = emptyList() }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        try {
            // F1-equivalent backdrop: fill the whole screen with a dark, mostly-opaque rectangle
            // BEFORE drawing content, so the in-game HUD (hotbar/chat/crosshair) and the world
            // behind the transparent screen don't bleed through and hurt the history's
            // readability. Fills respect alpha (unlike drawText), so this is visible. Nothing is
            // mutated globally — when the screen closes, normal game rendering resumes, so the
            // "F1" state auto-restores without touching the vanilla hudHidden flag.
            context.fill(0, 0, this.width, this.height, BG)
            super.render(context, mouseX, mouseY, delta)
            val tr = this.textRenderer
            context.drawTextWithShadow(tr, Text.literal("§8[§diustitia§8] §f§lPlayer history"), 10, 10, WHITE)
            context.drawTextWithShadow(tr, Text.literal("§7Search players by name — click a row for its flag history"), 10, 24, WHITE)
            if (rows.isEmpty()) {
                context.drawTextWithShadow(tr, Text.literal("§7No players match. (tracked+flagged this session)"), 10, listTop + 2, WHITE)
                return
            }
            val maxScroll = maxScroll()
            scroll = scroll.coerceIn(0, maxScroll)
            val visible = visibleRows()
            for (i in 0 until visible) {
                val idx = scroll + i
                if (idx >= rows.size) break
                val r = rows[idx]
                val y = listTop + i * ROW_H
                val hovered = mouseX in listLeft..(listLeft + rowWidth()) && mouseY in y..(y + ROW_H)
                if (hovered) context.fill(listLeft, y, listLeft + rowWidth(), y + ROW_H, 0x40FFFFFF)
                val glyph = glyphFor(r.tier)
                context.drawTextWithShadow(tr, Text.literal(glyph + " §f" + r.name), listLeft + 2, y + 2, WHITE)
                val right = listLeft + rowWidth() - 4
                val alertsTxt = Text.literal("§7alerts §f${r.alerts}" + (r.top?.let { " §7top §b$it" } ?: ""))
                context.drawTextWithShadow(tr, alertsTxt, right - tr.getWidth(alertsTxt), y + 2, WHITE)
            }
            // scrollbar
            if (maxScroll > 0) drawScrollbar(context, visible, maxScroll)
            if (rows.size > visible) {
                context.drawTextWithShadow(tr, Text.literal("§7${rows.size} players · scroll to see more · click a row"), 10, this.height - 14, WHITE)
            } else {
                context.drawTextWithShadow(tr, Text.literal("§7${rows.size} players · click a row"), 10, this.height - 14, WHITE)
            }
        } catch (_: Throwable) {}
    }

    override fun mouseClicked(click: Click, button: Boolean): Boolean {
        try {
            if (super.mouseClicked(click, button)) return true
            if (button) return false
            val mx = click.x().toInt(); val my = click.y().toInt()
            if (mx !in listLeft..(listLeft + rowWidth()) || my !in listTop..listBottom) return false
            val i = (my - listTop) / ROW_H
            val idx = scroll + i
            if (i in 0 until visibleRows() && idx in rows.indices) {
                val r = rows[idx]
                client?.setScreen(PlayerHistoryScreen(r.uuid, this))
                return true
            }
            // scrollbar drag area
            if (maxScroll() > 0 && mx > listLeft + rowWidth()) {
                scroll = ((my - listTop).coerceIn(0, (listBottom - listTop)) * maxScroll() / (listBottom - listTop))
                return true
            }
        } catch (_: Throwable) {}
        return false
    }

    override fun mouseScrolled(x: Double, y: Double, h: Double, v: Double): Boolean {
        try {
            val ms = maxScroll()
            if (ms > 0) { scroll = (scroll - v.toInt()).coerceIn(0, ms); return true }
        } catch (_: Throwable) {}
        return super.mouseScrolled(x, y, h, v)
    }

    override fun close() { client?.setScreen(parent) }

    // ---- layout helpers ----
    private val listTop: Int get() = 58
    private val listBottom: Int get() = this.height - 24
    private val listLeft: Int get() = 10
    private fun rowWidth(): Int = this.width - 20
    private fun visibleRows(): Int = ((listBottom - listTop) / ROW_H).coerceAtLeast(1)
    private fun maxScroll(): Int = (rows.size - visibleRows()).coerceAtLeast(0)
    private fun drawScrollbar(context: DrawContext, visible: Int, maxScroll: Int) {
        val trackH = listBottom - listTop
        val knobH = (trackH * visible / (rows.size)).coerceAtLeast(10)
        val knobY = listTop + (trackH - knobH) * scroll / maxScroll
        val sx = listLeft + rowWidth() + 2
        context.fill(sx, listTop, sx + 4, listBottom, 0x33FFFFFF)
        context.fill(sx, knobY, sx + 4, knobY + knobH, 0x66FFFFFF)
    }

    companion object {
        private val TITLE = Text.literal("Iustitia — player history")
        private const val ROW_H = 12
        private fun glyphFor(tier: FlagHistory.Tier): String = when (tier) {
            FlagHistory.Tier.GREEN -> "§a[+]§r"
            FlagHistory.Tier.YELLOW -> "§e[!]§r"
            FlagHistory.Tier.RED -> "§c[X]§r"
        }
        // Opaque white (ARGB 0xFFFFFFFF). Do NOT use 0xFFFFFF — that is 0x00FFFFFF (alpha 0),
        // and DrawContext.drawText no-ops when ColorHelper.getAlpha(color) == 0 (verified in the
        // 1.21.11 bytecode), so any text drawn with 0xFFFFFF is completely invisible. -1 is the
        // signed-int form of 0xFFFFFFFF. Every drawTextWithShadow color in this screen uses this.
        private const val WHITE = -1
        /** Dark, ~80%-opaque backdrop (ARGB 0xCC101010) covering the HUD/world behind the screen.
         *  `.toInt()` because the 8-hex literal's high alpha (≥0x80) makes it overflow Int→Long
         *  in Kotlin (same gotcha as WHITE = -1 for 0xFFFFFFFF). */
        private val BG = 0xCC101010.toInt()
    }
}