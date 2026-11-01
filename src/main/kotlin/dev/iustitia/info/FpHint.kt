package dev.iustitia.info

import dev.iustitia.i18n.L10n

/**
 * One-line benign-cause hints per check id, shown in the alert hover tooltip (Phase 2 #18 text
 * version) so users learn when a flag is likely a false positive and not a hackusation target:
 * "lag can inflate distance", "ice/boat/slime bounce", "Jump Boost raises step", etc.
 *
 * This is the textual version of the false-positive-reminder feature; the standalone HUD note near
 * alerts is a deferred Phase B render piece. Display-only — changes no check logic.
 * The texts live in the language files (`iustitia.info.fpHint.<checkId>`); a check without a
 * hint returns null (as before).
 */
object FpHint {

    fun hint(checkId: String): String? {
        val key = "iustitia.info.fpHint.$checkId"
        val t = L10n.s(key)
        return if (t == key) null else t
    }
}
