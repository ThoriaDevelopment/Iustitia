package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.SwingSignal
import dev.iustitia.math.MathUtil
import dev.iustitia.tracking.EntityTrackerManager
import dev.iustitia.tracking.TrackedPlayer
import java.util.ArrayDeque
import java.util.UUID

/**
 * Autoclicker detector, ported from Nemesis `AutoClickerA/B/C/D` + `MathUtil` plus the
 * sub-10ms robot/click-assist fingerprint. Four independent signals, each flagging the
 * attacker on a swing:
 *
 *  - **CPS**: swings in the last 20 ticks (≈ swings/sec) > [threshold] (default 20).
 *  - **Robot**: a sub-10ms nano-delta whose predecessor was < 50ms — humans cannot
 *    double-click that fast, so this is the strongest single fingerprint (level 2).
 *  - **StDev**: population stDev of the last 40 tick-intervals < 0.45 (too uniform).
 *  - **Kurtosis**: excess kurtosis over a 600-sample window < -0.7 (near-uniform ⇒ blatant
 *    fixed-delay autoclicker). High-confidence, low-frequency: fires only on the
 *    false→true transition (one flag per autoclicker episode) at a level that clears
 *    setbackVL in a single flag, so a sustained autoclicker alerts ONCE, not every throttle
 *    window. The strict -0.7 bar (uniform distribution excess kurtosis is -1.2; humans
 *    essentially never sustain -0.7 over 600 clicks) excludes the mildly-platykurtic legit
 *    jitter/drag clicking that the old `< 0` bar flagged 100+ times per player. Borderline
 *    autoclickers (kurt -0.1..-0.5) are still caught by CPS/Robot/StDev.
 *
 * Same-tick swing batches (server/lag collapse, bundle packets) are recorded for the
 * CPS window but excluded from the nano/stat windows so lag never trips the robot/stDev
 * signals. setbackVL 5, decay 0.05/tick (≈1/s).
 */
class ClickStatisticsCheck : Check() {

    override val id: String = "clickStatistics"

    init {
        try {
            Iustitia.bus.subscribe<SwingSignal> { onSwing(it) }
        } catch (_: Throwable) {}
    }

    override fun newContext(uuid: UUID): CheckContext = ClickStatisticsContext()

    private fun onSwing(s: SwingSignal) {
        try {
            val tp = EntityTrackerManager.get(s.attacker) ?: return
            val ctx = contextOf(s.attacker) as ClickStatisticsContext

            // record into the CPS window regardless of lag batching
            pushFront(ctx.swingTicks, s.tick, 80)

            if (s.tick == ctx.lastSwingTick) {
                // same-tick batch: keep CPS count, skip nano/stat windows (lag-safe)
                return
            }

            val tickDelta = s.tick - ctx.lastSwingTick
            if (ctx.lastSwingTick > -10000 && tickDelta > 0) {
                pushFront(ctx.tickIntervals, tickDelta, KURT_WINDOW)
            }
            val nanoDelta = s.nanoTime - ctx.lastSwingNano
            if (nanoDelta in 1..(Long.MAX_VALUE / 2)) {
                pushFront(ctx.nanoIntervals, nanoDelta, 500)
            }
            ctx.lastSwingTick = s.tick
            ctx.lastSwingNano = s.nanoTime

            evaluate(tp, ctx, s.tick)
        } catch (_: Throwable) {}
    }

    private fun evaluate(tp: TrackedPlayer, ctx: ClickStatisticsContext, tick: Int) {
        // CPS over the last 20 ticks (≈ swings/sec)
        val cps = ctx.swingTicks.count { it >= tick - 20 }
        if (cps > cfg.threshold) {
            flag(tp, ctx, 1.0, "ClickStats(CPS)", tick)
        }

        // Robot / click-assist sub-10ms cluster
        if (ctx.nanoIntervals.size >= 2) {
            val a = ctx.nanoIntervals.first()
            val b = ctx.nanoIntervals.elementAt(1)
            if (a < 10_000_000L && b < 50_000_000L) {
                flag(tp, ctx, 2.0, "ClickStats(Robot)", tick)
            }
        }

        // StDev window (40 samples)
        if (ctx.tickIntervals.size >= 40) {
            val sample = ctx.tickIntervals.take(40).map { it.toDouble() }.toDoubleArray()
            if (MathUtil.populationStDev(sample) < 0.45) {
                flag(tp, ctx, 1.0, "ClickStats(StDev)", tick)
            }
        }

        // Kurtosis window — blatant fixed-delay autoclicker only. Strict bar + transition gate
        // + one-shot level: one flag (one alert) per autoclicker episode, not every swing.
        if (ctx.tickIntervals.size >= KURT_WINDOW) {
            val sample = ctx.tickIntervals.take(KURT_WINDOW).map { it.toDouble() }.toDoubleArray()
            val k = MathUtil.excessKurtosis(sample)
            if (k < KURT_STRICT) {
                if (!ctx.kurtActive) {
                    ctx.kurtActive = true
                    // One flag clears setbackVL so a single high-confidence episode alerts once,
                    // then vl decays away with no re-flag while the autoclicker holds the bar.
                    flag(tp, ctx, setbackVL + 1.0, "ClickStats(Kurt)", tick)
                }
            } else if (k >= KURT_RECOVER) {
                // kurt rose back toward human-like → re-arm so a stop-then-restart re-triggers.
                ctx.kurtActive = false
            }
        }
    }

    private fun <T> pushFront(deque: ArrayDeque<T>, value: T, cap: Int) {
        deque.addFirst(value)
        while (deque.size > cap) deque.removeLast()
    }

    private class ClickStatisticsContext : CheckContext() {
        var lastSwingTick: Int = -10000
        var lastSwingNano: Long = 0L
        val tickIntervals = ArrayDeque<Int>()
        val nanoIntervals = ArrayDeque<Long>()
        val swingTicks = ArrayDeque<Int>()
        /** Transition gate for the Kurt signal: true while kurt holds below KURT_STRICT, so
         *  we flag only the false→true edge (one flag per autoclicker episode), not every swing. */
        var kurtActive: Boolean = false
    }

    companion object {
        /** Kurtosis window size (samples) — larger = more confidence before flagging. */
        private const val KURT_WINDOW = 600
        /** Excess-kurtosis bar: only a near-uniform (blatant fixed-delay) click stream is below
         *  this. Uniform distribution excess kurtosis is -1.2; humans essentially never sustain
         *  -0.7 over 600 clicks. The old bar was 0.0 (any platykurtic stream, incl. legit jitter). */
        private const val KURT_STRICT = -0.7
        /** Recovery bar: kurt rising back to >= this re-arms the transition gate so a stop-then-
         *  restart autoclicker triggers a fresh alert. */
        private const val KURT_RECOVER = -0.2
    }
}