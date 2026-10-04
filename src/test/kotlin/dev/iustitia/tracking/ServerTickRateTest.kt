package dev.iustitia.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure-clock tests for the MeteorClient-ported lag detector (no Minecraft objects). */
class ServerTickRateTest {

    @Test
    fun `unsynced session never reports lag`() {
        ServerTickRate.reset(now = 0L)
        assertFalse(ServerTickRate.synced)
        // Even a minute with no packet must read 0, or every check would exempt forever on a
        // server that never sends WorldTimeUpdateS2CPacket.
        assertEquals(0f, ServerTickRate.timeSinceLastTick(now = 60_000L))
    }

    @Test
    fun `four second warm-up after join is swallowed`() {
        ServerTickRate.reset(now = 1_000L)
        ServerTickRate.onWorldTimeUpdate(now = 1_100L)
        assertTrue(ServerTickRate.synced)
        assertEquals(0f, ServerTickRate.timeSinceLastTick(now = 4_999L))
        // Warm-up over at 5_000; a fresh sync lands there and the clock starts being read.
        ServerTickRate.onWorldTimeUpdate(now = 5_000L)
        assertEquals(0f, ServerTickRate.timeSinceLastTick(now = 5_000L))
        assertEquals(0.9f, ServerTickRate.timeSinceLastTick(now = 5_900L), 0.001f)
    }

    @Test
    fun `healthy second-long gap stays under the lag threshold`() {
        ServerTickRate.reset(now = 0L)
        ServerTickRate.onWorldTimeUpdate(now = 10_000L)
        // The next 20-tick sync arrives on time, 1000 ms later: just before it lands the elapsed
        // value is at its peak (~1.0 s) and must NOT be read as lag.
        val justBefore = ServerTickRate.timeSinceLastTick(now = 10_999L)
        assertTrue(justBefore < ServerTickRate.LAG_SECONDS, "healthy gap read as lag: $justBefore")
        // A 300 ms hitch pushes the gap to 1.3 s and must be read as lag.
        assertTrue(ServerTickRate.timeSinceLastTick(now = 11_300L) > ServerTickRate.LAG_SECONDS)
    }

    @Test
    fun `reset clears the signal`() {
        ServerTickRate.reset(now = 0L)
        ServerTickRate.onWorldTimeUpdate(now = 10_000L)
        assertTrue(ServerTickRate.synced)
        ServerTickRate.reset(now = 20_000L)
        assertFalse(ServerTickRate.synced)
        assertEquals(0f, ServerTickRate.timeSinceLastTick(now = 30_000L))
    }
}
