package dev.iustitia

import java.util.Locale

/**
 * Locale-stable decimal formatting for every user-visible or machine-read number the mod emits
 * (chat lines, HUD text, clipboard/JSON/Markdown reports, evidence strings).
 *
 * `"%.Nf".format(v)` uses the *default* locale: on de_DE / fr_FR / tr_TR clients it emits a comma
 * decimal (`1,50`), which (a) breaks JSON parsing (`"vl": 1,5` is two tokens) and Markdown report
 * consumers, and (b) makes alert text inconsistent across locales. This helper forces
 * [Locale.US] so output is machine-stable and identical on every client locale.
 *
 * ## Non-finite input, and why there are two formatters
 *
 * `%.2f` renders a non-finite double as the bare word `NaN` / `Infinity` — which is not valid
 * JSON, so a single runaway division in any check's evidence would silently corrupt
 * `/ius report json` for that whole report. (This is the same class of failure the locale guard
 * above exists to prevent, and it is the *only* number-shaped failure that survives the
 * [java.util.Locale] fix.)
 *
 * The two consumers want different things, so they get different formatters rather than one
 * compromise:
 *
 * - [d] is for humans (chat, HUD, Markdown, the clipboard report). A non-finite value renders as
 *   [MISSING], because printing `0.00` for a value we could not compute states a measurement that
 *   was never taken.
 * - [json] is for machine readers. A non-finite value renders as the JSON literal `null`, which is
 *   valid, unambiguous, and keeps the field present so a consumer can tell "no value" from "field
 *   absent". It must never be used in a human-facing string: it reads as a typo there.
 *
 * Neither path throws. Both are pure and thread-safe (no shared state, no locale mutation).
 */
object NumFmt {
    /** The human-facing rendering of a value that could not be computed. */
    const val MISSING: String = "—"

    /** The machine-facing rendering of a value that could not be computed (a JSON literal). */
    const val JSON_MISSING: String = "null"

    /** Human-facing decimal. Non-finite input becomes [MISSING] instead of `NaN`/`Infinity`. */
    fun d(v: Double, digits: Int = 2): String =
        if (v.isFinite()) String.format(Locale.US, "%.${digits}f", v) else MISSING

    /** Same for Float values (e.g. replay/clip playback speeds). */
    fun d(v: Float, digits: Int = 2): String = d(v.toDouble(), digits)

    /**
     * Machine-facing decimal for a JSON number token: locale-stable like [d], but a non-finite
     * input becomes [JSON_MISSING] rather than a human marker, so the enclosing object stays
     * parseable.
     */
    fun json(v: Double, digits: Int = 2): String =
        if (v.isFinite()) String.format(Locale.US, "%.${digits}f", v) else JSON_MISSING
}
