package dev.iustitia.i18n

import java.util.Locale
import net.minecraft.text.Text
import net.minecraft.util.Language

/**
 * Presentation-only translation chokepoint (see README "Language").
 *
 * Everything Iustitia shows the player — chat alerts, command feedback, config screens, HUD,
 * nametag copy — goes through here so it follows the client's own language setting. Detection
 * never reads these strings: check ids, flag labels and command names stay raw identifiers.
 *
 * Fail-open by construction: an unknown key renders as the key itself (vanilla behavior), a
 * malformed `%` spec degrades to that entry's raw text instead of vanilla's `Format error: …`
 * line, and neither can throw into a render or tick path.
 *
 * Lookup is deliberately not cached: [Language] is swapped on a language change and every caller
 * — including the per-frame HUD lines — must see the new language without a restart.
 */
object L10n {

    /** Translate [key] with [args] (`%s` placeholders) as a displayable [Text]. */
    fun t(key: String, vararg args: Any?): Text = Text.literal(s(key, *args))

    /** Translate [key] with [args] (`%s` placeholders) as a raw [String] (chat lines, logs). */
    fun s(key: String, vararg args: Any?): String = try {
        // `Language.get` returns the key itself when the entry is missing — the sentinel that
        // CheckInfo / FeatureInfo / Keybinds test for to fall back to their English text.
        val raw = Language.getInstance().get(key)
        // Formatting is ours rather than I18n's so that a bad spec — a hand-edited lang file with
        // a stray `%` — shows the sentence as written instead of "Format error: …". `%%` still
        // resolves to `%`, exactly as vanilla does it.
        try {
            String.format(Locale.ROOT, raw, *args)
        } catch (_: Throwable) {
            raw
        }
    } catch (_: Throwable) {
        key
    }
}
