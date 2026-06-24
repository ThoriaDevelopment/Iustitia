package dev.iustitia.ui

import dev.iustitia.Iustitia
import dev.iustitia.config.ConfigManager
import dev.iustitia.history.FlagHistory
import dev.iustitia.persistence.NoteStore
import dev.iustitia.session.SessionStats
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import java.util.UUID

/**
 * #4 (transcript panel): a narrow side-panel overlay that stays open alongside the game (does not
 * pause; [shouldPause] = false) and shows the live transcript of a single watched player — last
 * ~15 events drawn from [SessionStats] (swings/hits/velocity) and [FlagHistory.flags] (checks
 * fired, with evidence), plus the tier, confidence score, and any moderator note. Refreshed every
 * render; toggled by the transcript keybind, `/ius transcript panel`, or `cfg.transcriptPanel`.
 *
 * This is a non-fullscreen overlay Screen — build-verifiable. The world-visible ghost trail is the
 * deferred Phase B render piece. Whole body fail-open; a reader error shows "(unavailable)".
 */
class TranscriptPanelScreen(
    private val uuid: UUID,
    private val name: String,
    private val parent: Screen?,
) : Screen(TITLE) {

    override fun shouldPause(): Boolean = false

    override fun init() { try {} catch (_: Throwable) {} }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        try {
            val w = this.width
            // Right-edge narrow panel (~150px or 1/5 of width, whichever is smaller).
            val panelW = minOf(150, w / 5)
            val x0 = w - panelW
            context.fill(x0, 0, w, this.height, BG)
            super.render(context, mouseX, mouseY, delta)
            val tr = this.textRenderer
            var y = 6
            val tier = FlagHistory.tierFor(uuid)
            val glyph = when (tier) { FlagHistory.Tier.GREEN -> "§a[+]"; FlagHistory.Tier.YELLOW -> "§e[!]"; FlagHistory.Tier.RED -> "§c[X]" }
            context.drawTextWithShadow(tr, Text.literal("§8[§diustitia§8]"), x0 + 4, y, WHITE); y += 11
            context.drawTextWithShadow(tr, Text.literal("$glyph §f$name"), x0 + 4, y, WHITE); y += 11
            val score = FlagHistory.confidenceScore(uuid)
            context.drawTextWithShadow(tr, Text.literal("§7tier §f${tier.name} §7[$score]"), x0 + 4, y, WHITE); y += 13
            // session taps
            val st = SessionStats.stats(uuid)
            context.drawTextWithShadow(tr, Text.literal("§7swing §f${st.swings} §7hit §f${st.hits} §7vel §f${st.velocity}"), x0 + 4, y, WHITE); y += 11
            context.drawTextWithShadow(tr, Text.literal("§7alerts §f${FlagHistory.sessionAlertCount(uuid)} §7flags §f${FlagHistory.flagCounts(uuid).values.sum()}"), x0 + 4, y, WHITE); y += 13
            // note if any
            val note = NoteStore.get(uuid)
            if (note != null) {
                context.drawTextWithShadow(tr, Text.literal("§7note: ${NoteStore.categoryLabel(note.category)}"), x0 + 4, y, WHITE); y += 11
            }
            context.drawTextWithShadow(tr, Text.literal("§8last events:"), x0 + 4, y, WHITE); y += 11
            // Merge a small recent-events timeline: flags (with evidence) newest-first.
            val flags = FlagHistory.flags(uuid).take(15)
            if (flags.isEmpty()) {
                context.drawTextWithShadow(tr, Text.literal("§7(no flags)"), x0 + 4, y, WHITE)
            } else {
                val now = Iustitia.tickCounter
                for (f in flags) {
                    val ageSec = (now - f.tick) / 20
                    val meas = f.evidence?.measurement
                    val measTxt = if (meas != null) " §b${String.format(java.util.Locale.US, "%.2f", meas)}" else ""
                    val line = "§7${ageSec}s §f${f.checkId}$measTxt"
                    context.drawTextWithShadow(tr, Text.literal(line), x0 + 4, y, WHITE)
                    y += 11
                    if (y > this.height - 12) break
                }
            }
            context.drawTextWithShadow(tr, Text.literal("§8${if (ConfigManager.config.compactMode) "compact" else ""}"), x0 + 4, this.height - 12, WHITE)
        } catch (_: Throwable) {}
    }

    override fun close() { client?.setScreen(parent) }

    companion object {
        private val TITLE = Text.literal("Iustitia — transcript")
        private const val WHITE = -1
        private val BG = 0xCC101010.toInt()
    }
}