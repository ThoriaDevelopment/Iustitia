package dev.iustitia.world

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure geometry tests for the hitbox-footprint columns behind the edge-support ground test (#35). */
class FootprintTest {

    @Test
    fun `centered in a block only covers that column`() {
        assertEquals(listOf(5 to 5), Footprint.columns(5.5, 5.5))
    }

    @Test
    fun `center past a ledge still covers the supporting column`() {
        // Ledge at x = 43.0 (block 42 is the last solid column). Center 0.2 past the edge: the
        // box spans 42.9..43.5 and still overlaps column 42 -- the sneak-edge stance from #35.
        val cols = Footprint.columns(43.2, 0.5)
        assertEquals(43 to 0, cols.first(), "center column is tested first")
        assertEquals(setOf(43 to 0, 42 to 0), cols.toSet())
    }

    @Test
    fun `corner stance covers four distinct columns`() {
        assertEquals(setOf(0 to 0, -1 to 0, 0 to -1, -1 to -1), Footprint.columns(0.1, 0.1).toSet())
        assertEquals(4, Footprint.columns(0.1, 0.1).size)
    }

    @Test
    fun `negative coordinates floor rather than truncate`() {
        assertEquals(setOf(-1 to -1, -2 to -1), Footprint.columns(-1.1, -0.5).toSet())
    }

    @Test
    fun `a box that only touches a neighbouring face does not count it`() {
        // Box spans 4.4..5.0 exactly: it touches column 5's face but overlaps nothing there.
        assertEquals(listOf(4 to 0), Footprint.columns(4.7, 0.5))
        // Box spans 5.0..5.6: same on the low side.
        assertEquals(listOf(5 to 0), Footprint.columns(5.3, 0.5))
    }
}
