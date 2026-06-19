package dev.iustitia.checks

import java.util.UUID

/**
 * Per-(player, check) mutable state. Holds the violation level + last-alert tick for
 * throttling. Subclasses add check-specific buffers (swing intervals, fall accum, ...).
 */
open class CheckContext {
    var vl: Double = 0.0
    var lastAlertTick: Int = -1000

    fun decay(amount: Double) {
        vl = (vl - amount).coerceAtLeast(0.0)
    }
}