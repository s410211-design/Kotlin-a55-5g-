package com.s410211.a55wuwa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuFrequencyParserTest {
    @Test
    fun parsesCpuFreqKHzAsHertz() {
        val reading = CpuFrequencyParser.parse("1632000\n", "test:policy0", 10L)
        assertTrue(reading is Reading.Value)
        val value = reading as Reading.Value
        assertEquals(1_632_000_000L, value.value)
        assertEquals("Hz", value.unit)
    }

    @Test
    fun parsesBothObservedPolicyRanges() {
        val littlePolicy = CpuFrequencyParser.parse("864000", "test:policy0", 11L)
        val otherPolicy = CpuFrequencyParser.parse("2750000", "test:policy4", 12L)
        assertEquals(864_000_000L, (littlePolicy as Reading.Value).value)
        assertEquals(2_750_000_000L, (otherPolicy as Reading.Value).value)
    }

    @Test
    fun rejectsZeroNegativeMalformedAndDecimalValues() {
        listOf("0", "-1", "1.5", "NaN", "", "12 kHz").forEach { raw ->
            assertTrue(
                "$raw should be invalid",
                CpuFrequencyParser.parse(raw, "test:policy0", 13L) is Reading.InvalidData
            )
        }
    }

    @Test
    fun rejectsLongAndConversionOverflow() {
        assertTrue(
            CpuFrequencyParser.parse(
                "999999999999999999999999",
                "test:policy0",
                14L
            ) is Reading.InvalidData
        )
        assertTrue(
            CpuFrequencyParser.parse(
                "9223372036854776",
                "test:policy0",
                15L
            ) is Reading.InvalidData
        )
    }
}
