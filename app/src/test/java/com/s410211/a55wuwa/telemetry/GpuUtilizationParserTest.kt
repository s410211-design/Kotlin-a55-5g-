package com.s410211.a55wuwa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuUtilizationParserTest {
    @Test
    fun instantPercentageAcceptsDecimalAndPercentSuffix() {
        val reading = InstantPercentageParser.parse("42.5 %", "test:gpu_busy", 10L)
        assertTrue(reading is Reading.Value)
        val value = reading as Reading.Value
        assertEquals(42.5, value.value, 0.0001)
        assertEquals("%", value.unit)
    }

    @Test
    fun instantPercentageRejectsOutOfRangeAndNonFiniteValues() {
        listOf("-0.1", "100.01", "NaN", "Infinity", "not-a-number").forEach { raw ->
            assertTrue(
                "$raw should be invalid",
                InstantPercentageParser.parse(raw, "test:gpu_busy", 11L) is Reading.InvalidData
            )
        }
    }

    @Test
    fun cumulativeParserWaitsForSecondSampleAndUsesDeltas() {
        val parser = CumulativeBusyTotalParser("test:busy_total")
        assertTrue(parser.parse("100 200", 100L) is Reading.AwaitingSample)
        val reading = parser.parse("130 300", 200L)
        assertTrue(reading is Reading.Value)
        assertEquals(30.0, (reading as Reading.Value).value, 0.0001)
    }

    @Test
    fun cumulativeParserRejectsCounterRollbackAndRebaselines() {
        val parser = CumulativeBusyTotalParser("test:busy_total")
        parser.parse("100 200", 100L)
        assertTrue(parser.parse("10 20", 200L) is Reading.InvalidData)
        val next = parser.parse("20 40", 300L)
        assertTrue(next is Reading.Value)
        assertEquals(50.0, (next as Reading.Value).value, 0.0001)
    }

    @Test
    fun cumulativeParserRejectsZeroTotalDelta() {
        val parser = CumulativeBusyTotalParser("test:busy_total")
        parser.parse("100 200", 100L)
        assertTrue(parser.parse("110 200", 200L) is Reading.InvalidData)
    }

    @Test
    fun cumulativeParserRejectsBusyDeltaGreaterThanTotalDelta() {
        val parser = CumulativeBusyTotalParser("test:busy_total")
        parser.parse("100 200", 100L)
        assertTrue(parser.parse("140 220", 200L) is Reading.InvalidData)
    }

    @Test
    fun cumulativeParserRejectsMalformedAndNegativeCounters() {
        val parser = CumulativeBusyTotalParser("test:busy_total")
        assertTrue(parser.parse("x 100", 100L) is Reading.InvalidData)
        assertTrue(parser.parse("-1 100", 100L) is Reading.InvalidData)
        assertTrue(parser.parse("1", 100L) is Reading.InvalidData)
    }

    @Test
    fun parserHandlesVeryLargeCountersWithoutLongOverflow() {
        val parser = CumulativeBusyTotalParser("test:busy_total")
        parser.parse("100000000000000000000 200000000000000000000", 100L)
        val reading = parser.parse(
            "100000000000000000030 200000000000000000100",
            200L
        )
        assertTrue(reading is Reading.Value)
        assertEquals(30.0, (reading as Reading.Value).value, 0.0001)
    }
}
