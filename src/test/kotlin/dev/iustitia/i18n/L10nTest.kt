package dev.iustitia.i18n

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-JVM tests for [L10n]'s fail-open contract — the guarantee the whole migration rests on.
 *
 * A unit test never has a language file loaded, so [L10n.s] resolves every key to itself here: that
 * is the key-miss path, not a stub of it. The formatting branch is reached through
 * [L10n.formatEntry], which exists as a separate function for exactly this reason.
 */
class L10nTest {

    @Test
    fun `an unknown key resolves to the key itself`() {
        // Vanilla's sentinel — CheckInfo, FeatureInfo, Keybinds and Tier compare against it to fall
        // back, so a missing key must come back verbatim rather than as empty text or a throw.
        assertEquals<String>("iustitia.test.noSuchKey", L10n.s("iustitia.test.noSuchKey"))
        // Arguments must not turn a missing entry into an exception either.
        assertEquals<String>("iustitia.test.noSuchKey", L10n.s("iustitia.test.noSuchKey", "x", 3))
    }

    @Test
    fun `a matching spec substitutes its arguments`() {
        assertEquals<String>("Steve has 3 flags", L10n.formatEntry("%s has %d flags", arrayOf<Any?>("Steve", 3)))
        assertEquals<String>("", L10n.formatEntry("", emptyArray<Any?>()))
    }

    @Test
    fun `a malformed spec degrades to the raw entry instead of throwing`() {
        // A hand-edited lang file with a stray % must never reach a render or tick path as a throw.
        assertEquals<String>("100% sure", L10n.formatEntry("100% sure", emptyArray<Any?>()))
        assertEquals<String>("%s and %q", L10n.formatEntry("%s and %q", arrayOf<Any?>("x")))
    }

    @Test
    fun `a missing argument degrades to the raw entry`() {
        assertEquals<String>("%s has %d flags", L10n.formatEntry("%s has %d flags", arrayOf<Any?>("Steve")))
    }

    @Test
    fun `percent escapes resolve like vanilla`() {
        assertEquals<String>("100% done", L10n.formatEntry("100%% done", emptyArray<Any?>()))
    }
}
