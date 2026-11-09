package com.s410211.a55wuwa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuFrequencyParserTest {
    @Test
    fun convertsExplicitKhzToHertz() {
        val reading = GpuFrequencyParser.parse(
            "840000",
            "test:gpu_clock",
            100L,
            FrequencyUnit.KHZ
        )
        assertTrue(reading is Reading.Value)
        val value = reading as Reading.Value
        assertEquals(840_000_000L, value.value)
        assertEquals("Hz", value.unit)
    }

    @Test
    fun convertsDecimalMhzExactly() {
        val reading = GpuFrequencyParser.parse(
            "742.5",
            "test:gpu_clock",
            101L,
            FrequencyUnit.MHZ
        )
        assertTrue(reading is Reading.Value)
        assertEquals(742_500_000L, (reading as Reading.Value).value)
    }

    @Test
    fun doesNotGuessUnitFromSuffixOrMagnitude() {
        listOf("840000 kHz", "840000Hz", "8.4e5", "840,000").forEach { raw ->
            assertTrue(
                "$raw must be rejected",
                GpuFrequencyParser.parse(raw, "test:gpu_clock", 102L, FrequencyUnit.KHZ)
                    is Reading.InvalidData
            )
        }
    }

    @Test
    fun rejectsZeroNegativeNonFiniteAndMalformedValues() {
        listOf("0", "-1", "NaN", "Infinity", "", "not-a-frequency").forEach { raw ->
            assertTrue(
                "$raw must be rejected",
                GpuFrequencyParser.parse(raw, "test:gpu_clock", 103L, FrequencyUnit.HZ)
                    is Reading.InvalidData
            )
        }
    }

    @Test
    fun rejectsOverflowAndFractionalHertz() {
        assertTrue(
            GpuFrequencyParser.parse(
                "999999999999999999999999",
                "test:gpu_clock",
                104L,
                FrequencyUnit.MHZ
            ) is Reading.InvalidData
        )
        assertTrue(
            GpuFrequencyParser.parse("0.1", "test:gpu_clock", 105L, FrequencyUnit.HZ)
                is Reading.InvalidData
        )
    }
}
