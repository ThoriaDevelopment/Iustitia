package dev.iustitia

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure-JVM tests for the human and JSON non-finite number representations. */
class NumFmtTest {
    @Test
    fun `human formatter uses missing marker for non-finite values`() {
        assertEquals(NumFmt.MISSING, NumFmt.d(Double.NaN))
        assertEquals(NumFmt.MISSING, NumFmt.d(Double.POSITIVE_INFINITY))
        assertEquals(NumFmt.MISSING, NumFmt.d(Double.NEGATIVE_INFINITY))
        assertEquals(NumFmt.MISSING, NumFmt.d(Float.NaN))
    }

    @Test
    fun `json formatter uses null for non-finite values`() {
        assertEquals(NumFmt.JSON_MISSING, NumFmt.json(Double.NaN))
        assertEquals(NumFmt.JSON_MISSING, NumFmt.json(Double.POSITIVE_INFINITY))
        assertEquals(NumFmt.JSON_MISSING, NumFmt.json(Double.NEGATIVE_INFINITY))
    }
}
