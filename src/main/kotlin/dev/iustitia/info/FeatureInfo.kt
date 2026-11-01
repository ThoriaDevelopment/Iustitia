package dev.iustitia.info

import dev.iustitia.i18n.L10n

/**
 * One-paragraph explanations for the Phase 2 commands + screens, surfaced by `/ius help <feature>`
 * (mirrors [CheckInfo] for checks). Keeps users out of the docs — the command explains itself.
 * The texts live in the language files (`iustitia.info.feature.<feature>`) so they follow the
 * client's language; an unknown feature still returns null (the caller prints its own fallback).
 */
object FeatureInfo {

    fun describe(feature: String): String? {
        val key = "iustitia.info.feature.${feature.lowercase()}"
        val t = L10n.s(key)
        return if (t == key) null else t
    }
}
