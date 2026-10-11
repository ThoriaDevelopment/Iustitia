package dev.iustitia.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the shipped-default lookup behind
 * `/ius threshold <check> default|reset` (issue #5). The command restores
 * `IustitiaConfig().slice(id).threshold`, so these pin that lookup as the single
 * source of truth. No Minecraft/Fabric objects (`IustitiaConfig` has no imports).
 */
class ThresholdDefaultTest {

    /** The restore lookup must resolve every registered check to its own slice —
     *  a check added to [IustitiaConfig.checks] without a [IustitiaConfig.slice]
     *  branch would fall into the disabled MAX_VALUE fallback, and restoring its
     *  "default" would silently turn the check off. */
    @Test
    fun `slice resolves every registered check to its own slice`() {
        val cfg = IustitiaConfig()
        assertTrue(cfg.checks().isNotEmpty(), "checks() must not be empty")
        for ((id, slice) in cfg.checks()) {
            assertSame(slice, cfg.slice(id), "slice(\"$id\") did not return the registered slice")
        }
    }

    /** A fresh instance must be unaffected by live edits — restoring the default
     *  reads shipped values, not whatever the running session currently holds. */
    @Test
    fun `fresh instance still holds shipped defaults after live edits`() {
        val live = IustitiaConfig()
        val before = live.checks().associate { (id, slice) -> id to slice.threshold }
        for ((_, slice) in live.checks()) slice.threshold = -123.456
        val fresh = IustitiaConfig()
        for ((id, _) in live.checks()) {
            assertEquals(before.getValue(id), fresh.slice(id).threshold, "fresh default for \"$id\" moved")
        }
    }

    /** Shipped thresholds must be sane numbers (finite, non-negative). Exact values
     *  are deliberately not pinned: recalibration changes them under CONFIG_VERSION. */
    @Test
    fun `shipped thresholds are finite and non-negative`() {
        for ((id, slice) in IustitiaConfig().checks()) {
            assertTrue(slice.threshold.isFinite(), "\"$id\" default threshold is not finite")
            assertTrue(slice.threshold >= 0.0, "\"$id\" default threshold is negative")
        }
    }
}
