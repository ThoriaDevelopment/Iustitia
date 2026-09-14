package dev.iustitia.checks

import java.util.UUID

/**
 * Per-(player, check) mutable state. Holds the violation level + last-alert tick for
 * throttling. Subclasses add check-specific buffers (swing intervals, fall accum, ...).
 */
open class CheckContext {
    var vl: Double = 0.0
    var lastAlertTick: Int = -1000

    /**
     * Recent per-event violation verdicts (newest first), for the **sustained-episode** gate.
     *
     * Event-driven combat checks can only flag when something happens (a hit, a swing
     * transition, a stale-position snap), which at vanilla combat cadence is roughly once per
     * 12 ticks — a `1.0` flag per 12 ticks loses to the `0.5`/tick decay, so a cheater who
     * repeats the violation *every single hit* could never accumulate any VL at all and the
     * check recorded in `/ius hist` while never raising a chat alert. The ring lets those
     * checks require a *pattern* (e.g. 3 of the last 4 hits) and then alert once for the
     * episode at a level that clears setbackVL.
     *
     * Concurrent deque: pushed from `onAttack`/`onHurt` (packet thread) on some checks and read
     * on the client tick on others; iteration is weakly consistent, which is fine for a gate that
     * only decides whether to flag.
     */
    val episodeRing: java.util.concurrent.ConcurrentLinkedDeque<Boolean> =
        java.util.concurrent.ConcurrentLinkedDeque()

    /** True while a sustained episode is latched, so one episode produces one alert. */
    @Volatile
    var episodeActive: Boolean = false

    fun decay(amount: Double) {
        vl = (vl - amount).coerceAtLeast(0.0)
    }
}