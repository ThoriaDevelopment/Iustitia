package dev.iustitia.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for [ThresholdBounds] (issue #32): a ratio threshold must not accept the value
 * that turns its check into always-flag, and the shipped defaults must stay inside the bounds
 * (otherwise the load path would silently ignore a user's saved default-valued slice).
 */
class ThresholdBoundsTest {

    @Test
    fun `zero is rejected for ratio thresholds whose zero flags everything`() {
        for (id in listOf("throughWalls", "rotationTracking", "keepSprint")) {
            assertFalse(ThresholdBounds.accepts(id, 0.0), "\"$id\" accepted 0")
            assertTrue(ThresholdBounds.accepts(id, ThresholdBounds.RATIO_MIN), "\"$id\" rejected RATIO_MIN")
        }
    }

    @Test
    fun `rate thresholds are capped at one`() {
        for (id in listOf("throughWalls", "rotationTracking", "noKnockback")) {
            assertTrue(ThresholdBounds.accepts(id, 1.0), "\"$id\" rejected 1.0")
            assertFalse(ThresholdBounds.accepts(id, 1.01), "\"$id\" accepted 1.01")
        }
    }

    @Test
    fun `noKnockback zero is allowed because it only silences the check`() {
        assertTrue(ThresholdBounds.accepts("noKnockback", 0.0))
    }

    @Test
    fun `non-ratio thresholds keep the generic range`() {
        assertNull(ThresholdBounds.ratioRangeFor("reach"))
        assertEquals(ThresholdBounds.GENERIC, ThresholdBounds.rangeFor("reach"))
        assertTrue(ThresholdBounds.accepts("reach", 0.0))
        assertTrue(ThresholdBounds.accepts("aimWrap", 165.0))
    }

    @Test
    fun `non-finite values are rejected`() {
        assertFalse(ThresholdBounds.accepts("reach", Double.NaN))
        assertFalse(ThresholdBounds.accepts("throughWalls", Double.POSITIVE_INFINITY))
    }

    @Test
    fun `every shipped default threshold is inside its bounds`() {
        for ((id, slice) in IustitiaConfig().checks()) {
            assertTrue(ThresholdBounds.accepts(id, slice.threshold),
                "\"$id\" default ${slice.threshold} is outside ${ThresholdBounds.rangeFor(id)}")
        }
    }

    @Test
    fun `every bounded id is a registered check`() {
        val ids = IustitiaConfig().checks().map { it.first }.toSet()
        for (id in listOf("throughWalls", "rotationTracking", "keepSprint", "noKnockback")) {
            assertTrue(id in ids, "\"$id\" is not a registered check")
        }
    }
}
