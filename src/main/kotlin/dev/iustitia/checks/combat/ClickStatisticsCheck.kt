package dev.iustitia.checks.combat

import dev.iustitia.Iustitia
import dev.iustitia.checks.Check
import dev.iustitia.checks.CheckContext
import dev.iustitia.config.IustitiaConfig
import dev.iustitia.event.SwingSignal
import dev.iustitia.history.Evidence
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
 *  - **Record**: a recorded click pattern replayed on loop (LionClient `ClickPatternStore`)
 *    is exactly periodic at tick resolution — a hand never sustains an exact repeat. This
 *    catches the replay case Kurt/StDev MISS: a recorded real-human pattern has a human-like
 *    distribution (passes Kurt/StDev) but is exactly periodic (trips this). Detects a
 *    non-constant periodic prefix in the last 60 intervals (≥5 cycles of the period);
 *    transition-gated like Kurt (one flag per loop episode). The non-constant guard excludes
 *    a steady fixed-delay stream (all-equal intervals), which Kurt already owns.
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
            val sample = newestDoubles(ctx.tickIntervals, 40)
            if (MathUtil.populationStDev(sample) < 0.45) {
                flag(tp, ctx, 1.0, "ClickStats(StDev)", tick)
            }
        }

        // Kurtosis window — blatant fixed-delay autoclicker only. Strict bar + transition gate
        // + one-shot level: one flag (one alert) per autoclicker episode, not every swing.
        if (ctx.tickIntervals.size >= KURT_WINDOW) {
            val sample = newestDoubles(ctx.tickIntervals, KURT_WINDOW)
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

        // Record-loop fingerprint (LionClient ClickPatternStore replay, plan §3/§8 step 5): a
        // recorded click pattern replayed on loop is exactly periodic at tick resolution — a hand
        // never sustains an exact repeat. Catches the replay case Kurt/StDev miss (a recorded
        // real-human pattern has human-like distribution but is exactly periodic). Detect a
        // non-constant periodic prefix in the last RECORD_WINDOW intervals; transition-gated like
        // Kurt so a sustained loop alerts once per episode, not every swing.
        if (ctx.tickIntervals.size >= RECORD_WINDOW) {
            val period = detectLoop(newestInts(ctx.tickIntervals, RECORD_WINDOW))
            if (period > 0) {
                ctx.nonLoopStreak = 0
                if (!ctx.recordActive) {
                    ctx.recordActive = true
                    flag(tp, ctx, setbackVL + 1.0, "ClickStats(Record)", tick, Evidence(
                        subLabel = "loop", measurement = period.toDouble(),
                        threshold = RECORD_MIN_CYCLES.toDouble(),
                        extra = "period=$period cycles≥$RECORD_MIN_CYCLES"))
                }
            } else if (ctx.recordActive && ++ctx.nonLoopStreak >= RECORD_REARM) {
                // loop clearly gone (≥ RECORD_REARM consecutive non-loop swings) → re-arm.
                ctx.recordActive = false
                ctx.nonLoopStreak = 0
            }
        }
    }

    /**
     * Snapshot the newest [n] tick-intervals into a primitive DoubleArray, newest-first.
     *
     * Bit-identical to `deque.take(n).map { it.toDouble() }.toDoubleArray()` (the deque is
     * newest-first via [pushFront]/addFirst, and its iterator walks head→tail = newest→oldest,
     * exactly what `take(n)` yields), but skips the boxed-Double intermediate `List<Double>`
     * that `.map` allocates — up to 600 boxed doubles per swing on the autoclicker combat path.
     * Caller guarantees `deque.size >= n` (the call sites gate on it), so the array fills fully.
     */
    private fun newestDoubles(deque: ArrayDeque<Int>, n: Int): DoubleArray {
        val arr = DoubleArray(n)
        var i = 0
        val iter = deque.iterator()
        while (i < n && iter.hasNext()) { arr[i++] = iter.next().toDouble() }
        return arr
    }

    /**
     * Snapshot the newest [n] tick-intervals into a primitive IntArray, newest-first (a[0] =
     * most recent). Same allocation-avoidance intent as [newestDoubles]; caller guarantees
     * `deque.size >= n`. Newest-first so [detectLoop] reads the most-recent intervals as the
     * prefix (a loop currently active shows as a periodic prefix).
     */
    private fun newestInts(deque: ArrayDeque<Int>, n: Int): IntArray {
        val arr = IntArray(n)
        var i = 0
        val iter = deque.iterator()
        while (i < n && iter.hasNext()) arr[i++] = iter.next()
        return arr
    }

    /**
     * Record-loop detector (plan §3/§8 step 5). [a] is newest-first (a[0] = most recent
     * interval). Returns the period `p` of a non-constant periodic *prefix* of [a] with ≥
     * [RECORD_MIN_CYCLES] cycles, or 0 if none.
     *
     * For each candidate period p (2..w/2), find the first index where a[i] != a[i+p]; the
     * prefix [0..i-1] is then p-periodic (length i). Require i >= MIN_CYCLES*p (≥ MIN_CYCLES
     * exact repetitions — a hand never sustains that) and a non-constant base pattern a[0..p-1]
     * (a steady fixed-delay stream is all-equal → already owned by Kurt). The w/p < MIN_CYCLES
     * break is monotonic in p (a period too long to fit MIN_CYCLES cycles in the window can't
     * have a MIN_CYCLES-cycle prefix).
     */
    private fun detectLoop(a: IntArray): Int {
        val w = a.size
        for (p in 2..w / 2) {
            if (w / p < RECORD_MIN_CYCLES) break
            var firstBreak = w  // no break ⇒ whole array is p-periodic
            for (i in 0 until w - p) {
                if (a[i] != a[i + p]) { firstBreak = i; break }
            }
            if (firstBreak >= RECORD_MIN_CYCLES * p) {
                var minV = Int.MAX_VALUE
                var maxV = Int.MIN_VALUE
                for (k in 0 until p) {
                    val v = a[k]
                    if (v < minV) minV = v
                    if (v > maxV) maxV = v
                }
                if (minV != maxV) return p  // non-constant loop of period p
            }
        }
        return 0
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
        /** Transition gate for the Record signal: true while a periodic loop holds, so we flag
         *  only the false→true edge (one flag per loop episode). */
        var recordActive: Boolean = false
        /** Consecutive non-loop swings since the last detected loop — counts up to [RECORD_REARM]
         *  to debounce flicker before re-arming the Record transition gate. */
        var nonLoopStreak: Int = 0
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

        // -- Record-loop sub-signal (LionClient ClickPatternStore replay, plan §3/§8 step 5) --
        /** Window of recent tick-intervals scanned for a periodic prefix. */
        private const val RECORD_WINDOW = 60
        /** Min exact cycles of the period required in the prefix — a hand never sustains ≥5 exact
         *  repetitions of a click-interval pattern. Tuned in step 14. */
        private const val RECORD_MIN_CYCLES = 5
        /** Consecutive non-loop swings to re-arm the Record transition gate (debounces a one-off
         *  miss within an otherwise-loopy stream). */
        private const val RECORD_REARM = 8
    }
}