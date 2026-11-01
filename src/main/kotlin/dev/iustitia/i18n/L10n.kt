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
 */
object L10n {

    /** Translate [key] with [args] (`%s` placeholders) as a displayable [Text]. */
    fun t(key: String, vararg args: Any?): Text = Text.literal(s(key, *args))

    /** Translate [key] with [args] (`%s` placeholders) as a raw [String] (chat lines, logs). */
    fun s(key: String, vararg args: Any?): String = try {
        // `Language.get` returns the key itself when the entry is missing — the sentinel that
        // CheckInfo / FeatureInfo / Keybinds test for to fall back to their English text.
        formatEntry(Language.getInstance().get(key), args)
    } catch (_: Throwable) {
        key
    }

    /**
     * Apply [args] to an already-resolved [raw] entry, returning the entry untouched when its spec
     * and the arguments disagree.
     *
     * Split out of [s] so the fail-open formatting path is testable: a pure-JVM unit test has no
     * loaded language file, so every key it asks for comes back as the key and this branch is
     * otherwise unreachable. Formatting is ours rather than I18n's so that a bad spec — a
     * hand-edited lang file with a stray `%` — shows the sentence as written instead of
     * "Format error: …". `%%` still resolves to `%`, exactly as vanilla does it.
     */
    internal fun formatEntry(raw: String, args: Array<out Any?>): String = try {
        String.format(Locale.ROOT, raw, *args)
    } catch (_: Throwable) {
        raw
    }
}
