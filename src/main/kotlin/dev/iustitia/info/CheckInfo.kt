package dev.iustitia.info

import dev.iustitia.i18n.L10n

/**
 * Human-readable one-line descriptions for every check id, used to make Iustitia self-documenting:
 * the alert hover tooltip shows the description of the check that fired, and `/ius help <check>`
 * prints it alongside the live config. Keeps users out of the docs — the alert explains itself.
 * The texts live in the language files (`iustitia.info.check.<checkId>`) so they follow the
 * client's language; an unknown id falls back to the generic English line.
 *
 * Also exposes the severity legend string and [isDefinitive] (delegates to the canonical set in
 * [dev.iustitia.history.FlagHistory]) so the nametag tier logic and help share one source of truth.
 */
object CheckInfo {

    /** One-line description for a check id, or a generic fallback. */
    fun describe(checkId: String): String {
        val key = "iustitia.info.check.$checkId"
        val t = L10n.s(key, checkId)
        return if (t == key) "Iustitia check '$checkId'." else t
    }

    /** Whether a check is primary red-capable: its alert can self-standing mark a player YELLOW
     *  (and contributes toward RED, which needs ≥2 distinct red-capable checks — see
     *  [dev.iustitia.history.FlagHistory.tierFor]). killAura is a corroborator, not primary. */
    fun isDefinitive(checkId: String): Boolean = dev.iustitia.history.FlagHistory.DEFINITIVE.contains(checkId)

    /** Severity color legend shown in alert hover + `/ius help` + `/ius status`.
     *  A getter (not an init-time val) so a runtime language switch applies immediately. */
    val SEVERITY_LEGEND: String get() = L10n.s("iustitia.info.severityLegend")
}
