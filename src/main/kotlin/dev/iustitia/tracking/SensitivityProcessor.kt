package dev.iustitia.tracking

import dev.iustitia.math.MathUtil.mcpGcdValue
import dev.iustitia.math.MathUtil.sensitivityGcd
import kotlin.math.abs

/**
 * Per-player mouse-sensitivity substrate — faithful port of MX `SensitivityProcessor`
 * (millennium) **minus the flag/punish paths** (those are step 2: the `aimGcd` check).
 *
 * Converges to the player's mouse sensitivity (0..200) and its MCP table value from the
 * GCD structure of their pitch deltas. Two complementary paths, exactly as in MX:
 *
 * 1. **Strict path** (stability): when |deltaPitch| < 0.31, scan the 200-entry MCP table for
 *    the sensitivity whose `getGCDValue` step matches the observed delta within 1e-3. After
 *    10 such matches, take the min; if it agrees with the previous strict lock, lock to it.
 * 2. **GCD path**: `gcd(deltaPitch, lastDeltaPitch)` → `finalSensitivity = (1.666·cbrt(0.8333·gcd) - 0.3333)·200`.
 *    After 40 samples, take the mode; if it lands in 0..200, that's the estimate.
 *
 * Fed by `EntityTrackerManager.updateSnapshot` **only on full-float-look protocols** (1.21.2+),
 * and only while the player is **combat-relevant** (attacked recently); on pre-1.21.2
 * (byte-quantized) or for non-combat players the processor is never fed, so [valid] stays
 * false and the step-2 aimGcd check fails open (plan §1.1 / §8 step 1).
 *
 * This is a substrate: no `Check` base, no `CheckContext`, no config slice, no flags.
 *
 * ## v1.2.0 FPS pass #3 — zero-detection-change optimizations
 *
 * The dense-player FPS regression traced to this substrate running per tracked player per
 * tick for the whole crowd (the combat-gate now restricts it to active attackers). Three
 * boxing/recompute wastes removed with **no change to detection output**:
 * 1. **Precomputed GCD table.** `mcpGcdValue(MCP_SENSITIVITY[i])` is a pure function of a
 *    constant 201-entry table, so the strict path's 200-iteration loop recomputed the same
 *    200 floats per player per tick. The [MCP_GCD_VALUES] array is computed **once** at class
 *    init and the loop now reads it — identical values, no per-tick float math.
 * 2. **No per-tick boxing.** The strict + GCD sample buffers were `ArrayList<Int>`, boxing an
 *    `Integer` every tick per player (`mcpSamples.add(finalSens.toInt())`) → GC churn → the
 *    "unstable fps" hitches in dense crowds. They are now fixed [IntArray]s + counts
 *    ([strictSamples] / [mcpSamples]) — zero allocation per tick.
 * 3. **Exact mode/min preserved.** [modeOf] keeps the MX O(N²) selection with its original
 *    first-in-order tie-break (it feeds `KillAura.sensitivityTooLow`, where a tie-break flip
 *    could change the exemption, so the tie-break is load-bearing). On 40 primitives, now
 *    combat-gated, the O(N²) cost is negligible; the win was the boxing, not the comparisons.
 */
class SensitivityProcessor {

    // current + predecessor pitch delta (MX deltaPitch / lastDeltaPitch). lastDeltaPitch is
    // managed internally — MX sets it externally; here process() advances it at the end.
    // (deltaPitch itself is vestigial — written but only lastDeltaPitch is read by the next call.)
    private var deltaPitch: Double = 0.0
    private var lastDeltaPitch: Double = 0.0

    // FPS pass #4 convergence budget — counts FED ticks (real-movement ticks that passed the
    // rate-limit below) since the last [clearLastDelta] (combat-gate close / teleport). Capped at
    // [MAX_FEED_ATTEMPTS]; once exhausted an idle / non-converging player stops paying the per-tick
    // GCD. Reset in [clearLastDelta].
    private var feedAttempts: Int = 0

    // FPS pass #6 rate-limit: counts real-movement ticks (post idle-skip) since the last
    // [clearLastDelta]. The expensive `sensitivityGcd`+cbrt+mode work runs only every
    // [FEED_INTERVAL]-th such tick; in-between ticks just advance [lastDeltaPitch] so the next fed
    // delta still pairs with its predecessor (consecutive-delta GCD semantics preserved). This
    // halves the per-tick convergence cost — the dense-server residual (Stray.gg 31.7%) is the
    // continuous stream of newly-loaded combatants each paying the ~2s convergence phase, and on a
    // high-turnover server there is always a fresh batch converging. Reset in [clearLastDelta].
    private var feedTickCounter: Int = 0

    // Strict-path scratch: matched sensitivity indices. The strict loop breaks on the first
    // match per process() call, so ≤1 entry is added per call and the count never exceeds 10
    // (it resets at ≥10). Fixed IntArray + count → no Integer boxing (was ArrayList<Int>).
    private var strictCount: Int = 0
    private val strictSamples: IntArray = IntArray(STRICT_CAP)
    private var lastStrictSensitivity: Int = 0

    // GCD-path scratch: finalSens.toInt() per process() call. The count reaches 40, the mode is
    // taken, and the count resets (matching the original ArrayList clear). Fixed IntArray + count
    // → no Integer boxing (was ArrayList<Int>). MCP_CAP == 40 so indices 0..39 hold exactly the
    // 40 samples taken before a reset.
    private var mcpCount: Int = 0
    private val mcpSamples: IntArray = IntArray(MCP_CAP)

    /** Best estimate (0..200) once converged; 0 until then. */
    var sensitivity: Int = 0
        private set
    /** `MCP_SENSITIVITY[sensitivity]` — the MCP table value for the current estimate. */
    var mcpSensitivity: Double = 0.0
        private set
    /** Last GCD-derived raw per-sample estimate, exposed for step-2 diagnostics. */
    var finalSensitivity: Double = 0.0
        private set
    /** True once a strict lock or a valid (0..200) GCD mode has been reached. */
    var valid: Boolean = false
        private set

    /**
     * Port of MX `processSensitivity()`. Feeds one pitch delta (degrees). Both paths run,
     * exactly as in MX. The caller advances [lastDeltaPitch] implicitly — here we do it at the
     * end of each call so the next call's GCD pairs the current delta with this one.
     */
    fun process(deltaPitch: Double) {
        // FPS pass #4 (live-profiler-confirmed root cause): the dense-PvP render-thread hog was
        // this method's `sensitivityGcd` call running every tick per combat-relevant player —
        // BOTH for already-converged players (the feed never stopped once `valid`) AND for idle
        // players that NEVER converge (|Δpitch| too small for the strict table match and the GCD
        // path's finalSens lands out of 0..200), so they were fed forever. In the 112s dense-crowd
        // profile, `MathUtil.sensitivityGcd` was 32.5% of ALL render time / 95.9% of iustitia time.
        //
        // Gate the work here, before the GCD:
        //  - once [valid], the converged estimate is stable and is ALL either consumer reads
        //    (KillAura/RotationTracking gcdComponent both gate on `valid` and read `sensitivity`),
        //    so further samples change nothing they read → early-return, zero `sensitivityGcd`
        //    work. ZERO detection change for converged players.
        //  - once [feedAttempts] exhausts [MAX_FEED_ATTEMPTS], an idle/non-converging player stops
        //    paying the per-tick GCD → early-return. They were never `valid`, so consumers were
        //    already fail-open (`!valid` → clear + return) → ZERO detection change. The cap resets
        //    in [clearLastDelta] (combat-gate close / teleport) so a player who re-enters combat
        //    after idling gets a fresh convergence attempt.
        // Convergence itself is unchanged: an actively-looking combatant still gets fed every tick
        // until `valid` (≈2s for the GCD path's 40-sample mode), then stops — a transient 2s cost
        // per combat stint instead of the sustained per-tick cost.
        if (valid) return
        if (feedAttempts >= MAX_FEED_ATTEMPTS) return
        // FPS pass #5 (live-profiler round 2): an essentially-idle delta (|Δpitch| below
        // [MIN_USEFUL_DELTA]) carries NO GCD signal — it's below the strict table's minimum
        // matchable value (~0.0086 = MCP_GCD_VALUES[0] 0.0096 − 1e-3 tol), and the GCD path's
        // finalSens lands out of 0..200 for ≈0 deltas — so feeding it can NEVER converge. Skip
        // the GCD entirely. This is the dominant remaining cost after pass #4: a crowd loading
        // at once is mostly idle players (pitch unchanged → Δpitch = 0.0 exactly), each burning
        // up to MAX_FEED_ATTEMPTS useless `sensitivityGcd` calls (≈10s) before the cap stopped
        // them — the "fps spike when many players load at once" that v1.1.0 (no substrate) never
        // had. Clear the predecessor so the next real movement isn't GCD-paired with a stale delta
        // across the idle gap (matches the teleport/combat-close clear). Don't count toward the
        // attempt cap — the player is just idle, not failing to converge; when they move again
        // they get the full budget. ZERO detection change: idle deltas couldn't converge anyway
        // (no signal), so consumers were going to stay fail-open regardless.
        val ad = abs(deltaPitch)
        if (ad < MIN_USEFUL_DELTA) {
            lastDeltaPitch = 0.0
            return
        }
        // FPS pass #6: rate-limit the expensive GCD path to every [FEED_INTERVAL]-th real-movement
        // tick. Convergence takes [FEED_INTERVAL]× longer in wall-time but the per-tick cost (the
        // frame stall — the thing that moves FPS) is 1/[FEED_INTERVAL]. The converged value is
        // statistically identical for a constant-sensitivity player: the same Δpitch samples feed
        // the 40-sample mode, just gathered over a longer window, so the mode is the same and
        // `KillAura.sensitivityTooLow` / the aimGcd sub-flag read the same `sensitivity`. Both
        // consumers fail-open until `valid`, so the longer convergence is a detection-LATENCY
        // change only (~4s vs ~2s at FEED_INTERVAL=2), never a value change — and `gcdComponent`
        // needs a SUSTAINED low-GCD window after `valid` anyway, so a few extra seconds before the
        // window can start is absorbed. On in-between ticks just advance the predecessor so the
        // next fed delta pairs with this one (consecutive-delta GCD pairing preserved).
        if (feedTickCounter++ % FEED_INTERVAL != 0) {
            lastDeltaPitch = deltaPitch
            return
        }
        feedAttempts++
        this.deltaPitch = deltaPitch

        // -- Strict path: stability grid match (|deltaPitch| < 0.31). --
        // Reads the precomputed [MCP_GCD_VALUES] table (was: 200× mcpGcdValue recomputation/call).
        if (ad < 0.31) {
            for (i in 0 until 200) {
                if (abs(MCP_GCD_VALUES[i] - ad) < 1e-3) {
                    if (strictCount < STRICT_CAP) strictSamples[strictCount++] = i
                    if (strictCount >= 10) {
                        val finalSens = minOf(strictSamples, strictCount)
                        if (finalSens == lastStrictSensitivity) {
                            // MX locks by clearing the external smoothing list and seeding 5
                            // copies. Here a strict lock simply fixes the estimate + marks valid.
                            sensitivity = finalSens
                            mcpSensitivity = MCP_SENSITIVITY[finalSens]
                            valid = true
                        }
                        strictCount = 0
                        lastStrictSensitivity = finalSens
                    }
                    break
                }
            }
        }

        // -- GCD path: cbrt-formula estimate, mode over 40 samples. --
        val gcd = sensitivityGcd(deltaPitch, lastDeltaPitch)
        val sensitivityModifier = Math.cbrt(0.8333 * gcd)
        val finalSens = (1.666 * sensitivityModifier - 0.3333) * 200.0
        this.finalSensitivity = finalSens
        if (mcpCount < MCP_CAP) mcpSamples[mcpCount++] = finalSens.toInt()
        if (mcpCount >= 40) {
            val mode = modeOf(mcpSamples, mcpCount)
            if (mode in 0..200) {
                sensitivity = mode
                mcpSensitivity = MCP_SENSITIVITY[mode]
                valid = true
            }
            mcpCount = 0
        }

        lastDeltaPitch = deltaPitch
    }

    /** Zero the predecessor so the next fed delta isn't GCD-paired with a stale value
     *  (e.g. across a teleport yaw/pitch jump, or after the combat-relevance gate closes).
     *  Also resets the convergence attempt budget ([feedAttempts]) so a player who re-enters
     *  combat (or lands after a teleport) gets a FRESH convergence attempt rather than staying
     *  exhausted from an earlier idle stint. Does NOT reset [valid] — a converged estimate stays
     *  stable across the gap (consumers keep reading it). */
    fun clearLastDelta() {
        deltaPitch = 0.0
        lastDeltaPitch = 0.0
        feedAttempts = 0
        feedTickCounter = 0
    }

    /** MX `getMode`: the most frequent value, ties broken by first-in-order-wins. Operates on a
     *  fixed [IntArray] slice (no boxing). O(N²) is preserved verbatim — the tie-break feeds
     *  `KillAura.sensitivityTooLow`, where a flip could change the exemption, so it is
     *  load-bearing; on 40 primitives (combat-gated) the cost is negligible. */
    private fun modeOf(samples: IntArray, count: Int): Int {
        if (count == 0) return 0
        var mode = samples[0]
        var maxCount = 0
        for (vi in 0 until count) {
            val value = samples[vi]
            var c = 1
            for (ii in 0 until count) {
                if (samples[ii] == value) c++
                if (c > maxCount) {
                    mode = value
                    maxCount = c
                }
            }
        }
        return mode
    }

    /** MX `getMin` (Statistics): the smallest sample. Operates on a fixed [IntArray] slice. */
    private fun minOf(samples: IntArray, count: Int): Int {
        var m = samples[0]
        for (i in 1 until count) {
            if (samples[i] < m) m = samples[i]
        }
        return m
    }

    companion object {
        /** Strict-path buffer cap — the count resets at 10, so 16 is comfortable headroom. */
        private const val STRICT_CAP: Int = 16
        /** GCD-path buffer cap — 40 samples taken before a mode + reset. */
        private const val MCP_CAP: Int = 40
        /** Run the expensive GCD path (sensitivityGcd + cbrt + mode accumulation) only every
         *  [FEED_INTERVAL]-th real-movement tick (FPS pass #6). 2 halves the per-tick convergence
         *  cost (the dense-server Stray.gg residual) at the cost of ~2× longer convergence
         *  wall-time — detection-safe because the converged value is unchanged and both consumers
         *  fail-open until `valid` (see [process]). 1 = every tick (original behavior). Tunable;
         *  runtime-only-verifiable. */
        private const val FEED_INTERVAL: Int = 2
        /** Max FED ticks (real-movement ticks that passed the [FEED_INTERVAL] rate-limit) per combat
         *  stint before an idle / non-converging player stops paying the per-tick `sensitivityGcd`
         *  (FPS pass #4, retuned #6). At [FEED_INTERVAL]=2 this is ~2× the GCD path's 40-sample mode
         *  window: an actively-looking combatant converges (`valid`) within one 40-feed batch
         *  (~4s wall), and only idle/non-converging players exhaust it (~8s wall). Lowered from 200
         *  in pass #6 because rate-limiting spreads feeds over 2× wall-time — without lowering the
         *  cap a non-converger would burn for 20s instead of 10s. A slow converger needing a 3rd
         *  mode batch is cut off, but such a player (noisy, non-constant sensitivity) wouldn't
         *  produce the sustained low-GCD deltas the gcdComponent flags anyway → fail-open-safe.
         *  Reset on combat-gate close / teleport ([clearLastDelta]). Tunable; runtime-only-verifiable. */
        private const val MAX_FEED_ATTEMPTS: Int = 80
        /** Minimum useful |Δpitch| to feed the GCD (FPS pass #5). Below this the strict table can't
         *  match (its minimum matchable value is ~0.0086 = `MCP_GCD_VALUES[0]` 0.0096 − 1e-3 tol)
         *  and the GCD path's finalSens lands out of 0..200, so the feed can't converge — an idle
         *  player (pitch unchanged → Δpitch = 0.0 exactly) would otherwise burn up to
         *  [MAX_FEED_ATTEMPTS] useless `sensitivityGcd` calls. 0.008 is safely below the minimum
         *  matchable (preserves every strict match incl. sens=0) and well above idle (~0). */
        private const val MIN_USEFUL_DELTA = 0.008

        /** MX `SENSITIVITY_MCP_VALUES` — the 201-entry Minecraft sensitivity table (index 0..200).
         *  Shared across all per-player instances (was per-instance — 201 doubles × N players). */
        private val MCP_SENSITIVITY = doubleArrayOf(
            0.0, 0.00704225, 0.01408451, 0.01760563, 0.02112676,
            0.02816901, 0.02816902, 0.03521127, 0.04225352, 0.04929578,
            0.04929577, 0.05633803, 0.06338028, 0.06690141, 0.07042254,
            0.07746479, 0.08450704, 0.08802817, 0.09154929, 0.09859155,
            0.10211267, 0.1056338, 0.11267605, 0.11971831, 0.12323943,
            0.12676056, 0.13380282, 0.13732395, 0.14084508, 0.14788732,
            0.15492958, 0.15845071, 0.16197184, 0.16901408, 0.17253521,
            0.17605634, 0.18309858, 0.18661971, 0.19014084, 0.1971831,
            0.20422535, 0.20774647, 0.2112676, 0.21830986, 0.22183098,
            0.22535211, 0.23239437, 0.23943663, 0.24295775, 0.24647887,
            0.2535211, 0.25704224, 0.26056337, 0.26760563, 0.2746479,
            0.27816902, 0.28169015, 0.28873238, 0.29225351, 0.29577464,
            0.3028169, 0.30985916, 0.31338029, 0.31690142, 0.32394367,
            0.32746478, 0.3309859, 0.33802816, 0.34507042, 0.34859155,
            0.35211268, 0.35915494, 0.36267605, 0.36619717, 0.37323943,
            0.37676056, 0.3802817, 0.38732395, 0.3943662, 0.39788733,
            0.40140846, 0.4084507, 0.41197183, 0.41549295, 0.4225352,
            0.42957747, 0.4330986, 0.43661973, 0.44366196, 0.44718309,
            0.45070422, 0.45774648, 0.46478873, 0.46830986, 0.471831,
            0.47887325, 0.48239436, 0.48591548, 0.49295774, 0.5,
            0.5, 0.5070422, 0.5140845, 0.51760563, 0.52112675,
            0.52816904, 0.53169015, 0.53521127, 0.5422535, 0.5492958,
            0.5528169, 0.556338, 0.5633803, 0.56690142, 0.57042253,
            0.57746476, 0.58450705, 0.58802818, 0.5915493, 0.59859157,
            0.60211269, 0.6056338, 0.6126761, 0.6197183, 0.62323942,
            0.62676054, 0.63380283, 0.63732395, 0.64084506, 0.64788735,
            0.6549296, 0.6584507, 0.6619718, 0.6690141, 0.6725352,
            0.6760563, 0.6830986, 0.68661972, 0.69014084, 0.6971831,
            0.70422536, 0.70774648, 0.7112676, 0.7183099, 0.7253521,
            0.7253521, 0.73239434, 0.7394366, 0.74295773, 0.74647886,
            0.75352114, 0.75704227, 0.7605634, 0.76760566, 0.7746479,
            0.778169, 0.7816901, 0.7887324, 0.79225352, 0.79577464,
            0.8028169, 0.80985916, 0.81338028, 0.8169014, 0.8239437,
            0.8274648, 0.8309859, 0.8380282, 0.8415493, 0.8450704,
            0.85211265, 0.85915494, 0.86267605, 0.86619717, 0.87323946,
            0.87676058, 0.8802817, 0.8873239, 0.8943662, 0.89788731,
            0.90140843, 0.9084507, 0.91197182, 0.91549295, 0.92253524,
            0.92957747, 0.93309858, 0.9366197, 0.943662, 0.9471831,
            0.9507042, 0.9577465, 0.96478873, 0.96830985, 0.97183096,
            0.97887325, 0.98239437, 0.9859155, 0.9929578, 1.0,
            1.0
        )

        /** Precomputed `mcpGcdValue(MCP_SENSITIVITY[i])` for every table index — the constant the
         *  strict path's 200-iteration loop previously recomputed per player per tick. Computed once
         *  at class init; the strict loop now reads [MCP_GCD_VALUES][i] instead. Identical values to
         *  the per-call computation (pure function of the constant table) → zero detection change. */
        private val MCP_GCD_VALUES: FloatArray =
            FloatArray(MCP_SENSITIVITY.size) { mcpGcdValue(MCP_SENSITIVITY[it]) }
    }
}