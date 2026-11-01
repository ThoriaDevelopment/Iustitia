package dev.iustitia.ui

import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import dev.iustitia.i18n.L10n
import dev.iustitia.tracking.EntityTrackerManager
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import java.util.UUID

/**
 * #12 (session screen): the dense one-screen session summary opened by `/ius session screen` and
 * the session keybind. Built on demand from the [FlagHistory] readers: tier counts over the union
 * of tracked + flagged uuids, the peak-confidence player, the top offenders by alert count, and
 * the session totals. Compact-mode renders the same data as condensed one-liners. Display-only and
 * fail-open — a reader error shows "(unavailable)" rather than crashing the screen.
 */
class SessionScreen(private val parent: Screen?) : Screen(TITLE) {

    private data class Summary(
        val total: Int, val green: Int, val yellow: Int, val red: Int,
        val totalAlerts: Int, val peakName: String?, val peakScore: Int,
        val top: List<Pair<String, Int>>,
    )

    private var summary: Summary = Summary(0, 0, 0, 0, 0, null, 0, emptyList())
    private var compact: Boolean = false

    override fun shouldPause(): Boolean = false

    override fun init() {
        try { compact = ConfigManager.config.compactMode; rebuild() } catch (_: Throwable) {}
    }

    private fun rebuild() {
        try {
            val uuids = LinkedHashSet<UUID>()
            try { EntityTrackerManager.all().forEach { uuids.add(it.uuid) } } catch (_: Throwable) {}
            try { FlagHistory.knownUuids().forEach { uuids.add(it) } } catch (_: Throwable) {}
            var green = 0; var yellow = 0; var red = 0
            var peakName: String? = null; var peakScore = -1
            for (u in uuids) {
                when (FlagHistory.tierFor(u)) {
                    FlagHistory.Tier.GREEN -> green++
                    FlagHistory.Tier.YELLOW -> yellow++
                    FlagHistory.Tier.RED -> red++
                }
                val s = try { FlagHistory.confidenceScore(u) } catch (_: Throwable) { 0 }
                if (s > peakScore) { peakScore = s; peakName = FlagHistory.nameFor(u) ?: u.toString().take(8) }
            }
            val top = try { FlagHistory.topOffenders(8) } catch (_: Throwable) { emptyList() }
            summary = Summary(uuids.size, green, yellow, red, FlagHistory.totalAlerts, peakName, peakScore, top)
        } catch (_: Throwable) {
            summary = Summary(0, 0, 0, 0, 0, null, 0, emptyList())
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        try {
            context.fill(0, 0, this.width, this.height, BG)
            super.render(context, mouseX, mouseY, delta)
            val tr = this.textRenderer
            val s = summary
            context.drawTextWithShadow(tr, L10n.t("iustitia.screen.session.header"), 10, 10, WHITE)
            if (compact) {
                context.drawTextWithShadow(tr, Text.literal(
                    L10n.s("iustitia.screen.session.compact", s.total, s.green, s.yellow, s.red, s.totalAlerts) +
                    (s.peakName?.let { L10n.s("iustitia.screen.session.peak", it, s.peakScore) } ?: "")
                ), 10, 28, WHITE)
                return
            }
            var y = 30
            context.drawTextWithShadow(tr, L10n.t("iustitia.screen.session.playersTracked", s.total), 10, y, WHITE); y += 12
            context.drawTextWithShadow(tr, L10n.t("iustitia.screen.session.tierCounts", s.green, s.yellow, s.red), 10, y, WHITE); y += 16
            context.drawTextWithShadow(tr, L10n.t("iustitia.screen.session.alerts", s.totalAlerts), 10, y, WHITE); y += 12
            if (s.peakName != null && s.peakScore > 0) {
                context.drawTextWithShadow(tr, L10n.t("iustitia.screen.session.peakLine", s.peakName, s.peakScore), 10, y, WHITE); y += 16
            } else y += 8
            context.drawTextWithShadow(tr, L10n.t("iustitia.screen.session.topOffenders"), 10, y, WHITE); y += 12
            if (s.top.isEmpty()) {
                context.drawTextWithShadow(tr, L10n.t("iustitia.screen.session.noAlerts"), 14, y, WHITE)
            } else {
                s.top.forEach { (name, count) ->
                    context.drawTextWithShadow(tr, Text.literal(" §f$name §7$count"), 14, y, WHITE); y += 12
                }
            }
        } catch (_: Throwable) {}
    }

    override fun mouseClicked(click: Click, button: Boolean): Boolean {
        try { if (super.mouseClicked(click, button)) return true } catch (_: Throwable) {}
        return false
    }

    override fun close() { client?.setScreen(parent) }

    companion object {
        private val TITLE get() = L10n.t("iustitia.screen.session.title")
        private const val WHITE = -1
        private val BG = 0xCC101010.toInt()
    }
}