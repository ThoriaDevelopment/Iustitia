package dev.iustitia.math

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pure-Kotlin statistics helpers ported from Nemesis `util/MathUtil.java`.
 * Used by ClickStatisticsCheck (CPS / stDev / kurtosis) and (later) rotation checks.
 */
object MathUtil {

    fun mean(samples: DoubleArray): Double {
        if (samples.isEmpty()) return 0.0
        var s = 0.0
        for (v in samples) s += v
        return s / samples.size
    }

    /** Population standard deviation: sqrt(Σ(x-mean)²/n). */
    fun populationStDev(samples: DoubleArray): Double {
        if (samples.size < 2) return 0.0
        val m = mean(samples)
        var acc = 0.0
        for (v in samples) {
            val d = v - m
            acc += d * d
        }
        return sqrt(acc / samples.size)
    }

    /**
     * Excess kurtosis with the (n-1)(n-2)(n-3) sample correction, matching Nemesis
     * `MathUtil.getKurtosis`. Negative ⇒ platykurtic (too uniform ⇒ autoclicker).
     * Returns 0.0 when there are fewer than 4 samples or zero variance.
     */
    fun excessKurtosis(samples: DoubleArray): Double {
        val n = samples.size
        if (n < 4) return 0.0
        val m = mean(samples)
        val stDev = populationStDev(samples)
        if (stDev == 0.0) return 0.0
        var m4 = 0.0
        for (v in samples) {
            val d = v - m
            m4 += d * d * d * d
        }
        m4 /= n
        val denom = (n - 1).toDouble() * (n - 2) * (n - 3)
        val correction = 3.0 * (n - 1) * (n - 1) / ((n - 2).toDouble() * (n - 3))
        return (n * (n + 1).toDouble() / denom) * (m4 / stDev.pow(4.0)) - correction
    }

    /** CPS from inter-arrival samples: 20 / avg. */
    fun cps(samples: DoubleArray): Double {
        val avg = mean(samples)
        return if (avg <= 0.0) 0.0 else 20.0 / avg
    }

    /** Double GCD (Euclidean), stopping when the remainder drops below [base]. */
    fun gcd(a: Double, b: Double, base: Double = 0.001): Double {
        var x = abs(a)
        var y = abs(b)
        while (y > base) {
            val t = y
            y = x % y
            x = t
        }
        return x
    }

    // -- MX millennium primitives (Statistics.java / SensitivityProcessor.java) ----------
    // Substrate for Axis A (plan §8 step 1). consumed by SensitivityProcessor now and by the
    // step-2 aimGcd check (AimConstantCheck Type1 / Nemesis AimAssistD) later.

    /** `Statistics.EXPANDER` = 2^24 = 16_777_216. Scales float look deltas to longs for
     *  integer GCD — the magnification that turns sub-degree float deltas into a grid. */
    val EXPANDER: Double = 2.0.pow(24)

    /** `Statistics.getGcd(long, long)`: Euclidean over longs with a 2^14 = 16384 floor — the
     *  remainder below which a "GCD" is just float noise. Recursion depth is bounded by the
     *  long bit width (< 64). */
    fun gcdLong(current: Long, previous: Long): Long =
        if (previous <= 16384L) current else gcdLong(previous, current % previous)

    /** `Statistics.getAbsoluteGcd(float, float)`: the AimConstantCheck Type1 / Nemesis pitch-gcd
     *  primitive — scale both deltas by EXPANDER, take the integer GCD. Returns the GCD in
     *  EXPANDER units (a Long). */
    fun expandedGcd(current: Float, previous: Float): Long =
        gcdLong((current * EXPANDER).toLong(), (previous * EXPANDER).toLong())

    /** `Statistics.getGCDValue(s)`: the per-sensitivity yaw/pitch step the MCP sensitivity table
     *  is built around — `((s*0.6+0.2)^3) * 8 * 0.15`. Used by SensitivityProcessor to match an
     *  observed pitch delta back to a sensitivity. */
    fun mcpGcdValue(sensitivity: Double): Float {
        val f = (sensitivity * 0.6 + 0.2).toFloat()
        return (f * f * f) * 8.0f * 0.15f
    }

    /** `SensitivityProcessor.getGcd(double, double)` — the processor's OWN recursive double GCD
     *  (NOT `Statistics.getGcd(double,double)`): stops when |b| < 0.001 or a == b, swaps to keep
     *  a >= b, recurses on (b, a - floor(a/b)*b). Faithful to the cbrt formula's dependency. */
    fun sensitivityGcd(a: Double, b: Double): Double {
        if (abs(b) < 0.001 || a == b) return a
        if (a < b) return sensitivityGcd(b, a)
        return sensitivityGcd(b, a - floor(a / b) * b)
    }
}