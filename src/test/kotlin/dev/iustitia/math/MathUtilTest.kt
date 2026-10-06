package dev.iustitia.math

import kotlin.test.Test
import kotlin.test.assertTrue

/** Pure-JVM tests for the non-finite terminal in [MathUtil.sensitivityGcd]. */
class MathUtilTest {
    @Test
    fun `sensitivity gcd returns for non-finite values on both recursion paths`() {
        val nonFinite = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        val finite = 0.25

        for (value in nonFinite) {
            assertTrue(MathUtil.sensitivityGcd(value, finite).isNaN())
            // This ordering enters the mutual-recursion swap path when the finite value is first.
            assertTrue(MathUtil.sensitivityGcd(finite, value).isNaN())
        }
    }
}
