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
 */
object NumFmt {
    fun d(v: Double, digits: Int = 2): String = String.format(Locale.US, "%.${digits}f", v)

    /** Same for Float values (e.g. replay/clip playback speeds). */
    fun d(v: Float, digits: Int = 2): String = String.format(Locale.US, "%.${digits}f", v)
}