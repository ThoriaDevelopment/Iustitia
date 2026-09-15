package dev.iustitia.checks

import java.util.UUID

/**
 * Per-(player, check) mutable state. Holds the violation level; subclasses add check-specific
 * buffers (swing intervals, fall accum, ...). Alert throttling lives in [dev.iustitia.alert.AlertManager]
 * (its own per-(player, check) map), not here — an earlier `lastAlertTick` copy on this class had
 * no readers.
 */
open class CheckContext {
    /**
     * Violation level, in milli-VL fixed point backed by an atomic. Written from flag sites
     * (packet-thread bus handlers, pre-defer-queue) and decayed from the tick driver, so a
     * plain `var` lost updates: a cheater could flag forever and never cross setbackVL.
     * The defer queue now makes all writers client-thread, but the atomic keeps the RMW
     * safe by construction — any future off-thread flag site can't reintroduce the race.
     * Exposed as the original `var vl: Double` API so call sites stay unchanged.
     */
    private val vlBits = java.util.concurrent.atomic.AtomicLong(0L)

    var vl: Double
        get() = vlBits.get() / 1000.0
        set(value) { vlBits.set((value * 1000.0).toLong()) }

    /** Atomic `vl += amount` (flag sites). */
    fun addVl(amount: Double) { vlBits.addAndGet((amount * 1000.0).toLong()) }

    /** Atomic `vl = max(vl - amount, 0)` (tick decay) — a single CAS, so no lost update vs addVl. */
    fun decayVl(amount: Double) {
        val dec = (amount * 1000.0).toLong()
        vlBits.updateAndGet { cur -> (cur - dec).coerceAtLeast(0L) }
    }

    fun decay(amount: Double) {
        decayVl(amount)
    }

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
}