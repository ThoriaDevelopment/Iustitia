package dev.iustitia.alert

import dev.iustitia.history.FlagHistory
import dev.iustitia.info.CheckInfo
import net.minecraft.text.ClickEvent
import net.minecraft.text.HoverEvent
import net.minecraft.text.Style
import net.minecraft.text.Text
import java.util.UUID
import kotlin.math.ceil

/**
 * Renders the fixed alert layout:
 *   §8[§diustitia§8] §f(Name) §<sev>(Check) §<sev>(VL)
 * Severity color scales with the violation ratio (vl / setbackVL):
 *   < 2× → §e (yellow), < 3× → §6 (orange), >= 3× → §c (red).
 * The violation count is shown as the integer ceiling of the VL.
 *
 * The line is also self-documenting: hovering shows what the check detects + this player's
 * session alert count + the severity legend, and clicking runs `/ius hist <name>` to open that
 * player's flag history. Both are client-side chat-component events (no packet is sent). All
 * enrichment is fail-open — if the history/info lookup fails we still return the plain alert.
 */
object AlertFormatter {

    fun format(name: String, check: String, vl: Double, setbackVL: Double, uuid: UUID, checkId: String): Text {
        val sev = if (vl >= 3.0 * setbackVL) 'c'
                  else if (vl >= 2.0 * setbackVL) '6'
                  else 'e'
        val count = ceil(vl).toInt().coerceAtLeast(1)
        val raw = buildString {
            append("§8[§diustitia§8] §f")
            append(name)
            append(" §")
            append(sev)
            append('(')
            append(check)
            append(") §")
            append(sev)
            append('(')
            append(count)
            append(')')
        }
        val base = Text.literal(raw)
        return try {
            val hover = buildHover(uuid, checkId, check)
            val click = ClickEvent.RunCommand("/ius hist $name")
            base.styled { s: Style ->
                s.withClickEvent(click).withHoverEvent(HoverEvent.ShowText(hover))
            }
        } catch (_: Throwable) {
            // fail-open: if enrichment fails, return the plain alert with no interactivity
            base
        }
    }

    private fun buildHover(uuid: UUID, checkId: String, checkLabel: String): Text {
        val lines = Text.empty()
        lines.append(Text.literal("§7${CheckInfo.describe(checkId)}"))
        try {
            val alerts = FlagHistory.sessionAlertCount(uuid)
            val checkFlags = FlagHistory.flagsForCheck(uuid, checkId).size
            lines.append(Text.literal("\n§7alerts: §f$alerts §7| §7$checkLabel flags: §f$checkFlags"))
        } catch (_: Throwable) {
            // counts unavailable — skip the line
        }
        lines.append(Text.literal("\n${CheckInfo.SEVERITY_LEGEND}"))
        return lines
    }
}