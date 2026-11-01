package dev.iustitia.ui

import dev.iustitia.NumFmt
import dev.iustitia.i18n.L10n
import dev.iustitia.replay.ClipCodec
import dev.iustitia.replay.ClipPlayback
import dev.iustitia.replay.ClipStore
import dev.iustitia.replay.ReplayState
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

/**
 * `/ius clips` — the clip manager. A manually-rendered, scrollable list of every saved `.iusclip`
 * (from [ClipStore.list]) with per-clip frame/alert counts (read cheaply via [ClipCodec.readHeader]
 * — no per-snap data loaded just to show the list). **Left-click a row plays the clip** (loads it
 * fully + starts a replay, like `/ius playclip <name>`); **right-click deletes it** ([ClipStore.delete]).
 *
 * Manual rendering (not [net.minecraft.client.gui.widget.ElementListWidget]) mirrors
 * [PlayerSearchScreen] — keeps the whole interaction under our control via the confirmed 1.21.11
 * `Click`-based input APIs and avoids the `EntryListWidget$Entry`/`Click` dispatch uncertainty.
 * Whole body fail-open. Display-only: adds NO detection.
 */
class ClipManagerScreen(private val parent: Screen?) : Screen(TITLE) {

    private data class Row(val name: String, val meta: ClipCodec.ClipMeta?)

    private var rows: List<Row> = emptyList()
    private var scroll: Int = 0
    private var status: String? = null

    override fun shouldPause(): Boolean = false

    override fun init() {
        try { rebuild() } catch (_: Throwable) {}
    }

    private fun rebuild() {
        try {
            val names = ClipStore.list()
            rows = names.map { Row(it, ClipStore.metadata(it)) }
            scroll = scroll.coerceIn(0, maxScroll())
        } catch (_: Throwable) { rows = emptyList() }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        try {
            context.fill(0, 0, this.width, this.height, BG)
            val tr = this.textRenderer
            context.drawTextWithShadow(tr, L10n.t("iustitia.screen.clips.header"), 10, 10, WHITE)
            context.drawTextWithShadow(tr, L10n.t("iustitia.screen.clips.hint"), 10, 24, WHITE)
            context.drawTextWithShadow(tr, Text.literal("§7" + ClipStore.dirDisplay()), 10, 38, WHITE)
            if (rows.isEmpty()) {
                context.drawTextWithShadow(tr, L10n.t("iustitia.screen.clips.emptyHint"), 10, listTop + 2, WHITE)
                status?.let { context.drawTextWithShadow(tr, Text.literal(it), 10, this.height - 14, WHITE) }
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
                val counts = r.meta?.let {
                    val base = L10n.s("iustitia.screen.clips.counts", it.frameCount, it.alertCount)
                    val terrain = if (it.terrainBlocks > 0) L10n.s("iustitia.screen.clips.countsTerrain", it.terrainBlocks) else ""
                    val chunks = if (it.chunkSections > 0) L10n.s("iustitia.screen.clips.countsWorld", it.chunkSections) else ""
                    "$base$terrain$chunks"
                } ?: L10n.s("iustitia.screen.clips.unreadable")
                context.drawTextWithShadow(tr, L10n.t("iustitia.screen.clips.row", r.name, counts), listLeft + 2, y + 3, WHITE)
            }
            if (maxScroll > 0) drawScrollbar(context, visible, maxScroll)
            val foot = if (rows.size > visible) L10n.s("iustitia.screen.clips.footerMore", rows.size) else L10n.s("iustitia.screen.clips.footer", rows.size)
            context.drawTextWithShadow(tr, Text.literal(foot), 10, this.height - 14, WHITE)
            status?.let { context.drawTextWithShadow(tr, Text.literal(it), 10, this.height - 26, WHITE) }
        } catch (_: Throwable) {}
    }

    override fun mouseClicked(click: Click, button: Boolean): Boolean {
        try {
            if (super.mouseClicked(click, button)) return true
            val mx = click.x().toInt(); val my = click.y().toInt()
            if (mx !in listLeft..(listLeft + rowWidth()) || my !in listTop..listBottom) return false
            val i = (my - listTop) / ROW_H
            val idx = scroll + i
            if (i in 0 until visibleRows() && idx in rows.indices) {
                val r = rows[idx]
                if (button) delete(r.name) else play(r.name)
                return true
            }
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

    /** Play a clip: load it fully + start a replay (like `/ius playclip`), then close back to the game.
     *  Delegates the load → validate → start sequence to [ClipPlayback] (shared with `/ius playclip`)
     *  so the two entry points can't drift. */
    private fun play(name: String) {
        try {
            val mc = client ?: return
            when (val r = ClipPlayback.start(name, ReplayState.SPEED_FULL)) {
                is ClipPlayback.Result.Started -> {
                    mc.setScreen(null)
                    mc.player?.sendMessage(L10n.t("iustitia.screen.clips.playedMsg", name, NumFmt.d(digits = 2, v = ReplayState.SPEED_FULL), r.frames), false)
                }
                is ClipPlayback.Result.LoadFailed -> {
                    // The reason rides along (truncated — the status line is one row wide).
                    val why = r.reason?.take(60)?.let { " §8($it)" } ?: ""
                    status = L10n.s("iustitia.screen.clips.statusCouldntRead", name, why)
                }
                ClipPlayback.Result.StartFailed -> { status = L10n.s("iustitia.screen.clips.statusCouldntStart", name) }
            }
        } catch (_: Throwable) { status = L10n.s("iustitia.screen.clips.statusPlayFailed") }
    }

    /** Delete a clip + refresh the list. */
    private fun delete(name: String) {
        try {
            val ok = ClipStore.delete(name)
            status = if (ok) L10n.s("iustitia.screen.clips.statusDeleted", name) else L10n.s("iustitia.screen.clips.statusCouldntDelete", name)
            rebuild()
        } catch (_: Throwable) { status = L10n.s("iustitia.screen.clips.statusDeleteFailed") }
    }

    override fun close() { client?.setScreen(parent) }

    // ---- layout helpers ----
    private val listTop: Int get() = 56
    private val listBottom: Int get() = this.height - 30
    private val listLeft: Int get() = 10
    private fun rowWidth(): Int = this.width - 20
    private fun visibleRows(): Int = ((listBottom - listTop) / ROW_H).coerceAtLeast(1)
    private fun maxScroll(): Int = (rows.size - visibleRows()).coerceAtLeast(0)
    private fun drawScrollbar(context: DrawContext, visible: Int, maxScroll: Int) {
        val trackH = listBottom - listTop
        val knobH = (trackH * visible / rows.size).coerceAtLeast(10)
        val knobY = listTop + (trackH - knobH) * scroll / maxScroll
        val sx = listLeft + rowWidth() + 2
        context.fill(sx, listTop, sx + 4, listBottom, 0x33FFFFFF)
        context.fill(sx, knobY, sx + 4, knobY + knobH, 0x66FFFFFF)
    }

    companion object {
        private val TITLE get() = L10n.t("iustitia.screen.clips.title")
        private const val ROW_H = 14
        // Opaque white (ARGB 0xFFFFFFFF) — see PlayerSearchScreen for the 0xFFFFFF alpha-0 gotcha.
        private const val WHITE = -1
        private val BG = 0xCC101010.toInt()
    }
}