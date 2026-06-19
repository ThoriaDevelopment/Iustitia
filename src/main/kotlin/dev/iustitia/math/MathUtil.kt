package dev.iustitia.math

import kotlin.math.abs
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
}