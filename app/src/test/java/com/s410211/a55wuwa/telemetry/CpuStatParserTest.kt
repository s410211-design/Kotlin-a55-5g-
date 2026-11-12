package com.s410211.a55wuwa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuStatParserTest {
    @Test
    fun calculatesAggregateCpuUsageFromCounterDeltas() {
        val first = CpuStatParser.parse("cpu 100 0 50 800 50 0 0 0 0 0", 1L)
        assertTrue(first is Reading.Value)
        val tracker = CpuLoadTracker()
        val baseline = tracker.update((first as Reading.Value).value, 1L)
        assertTrue(baseline is Reading.AwaitingSample)

        val second = CpuStatParser.parse("cpu 150 0 70 900 60 0 0 0 0 0", 1_001L)
        assertTrue(second is Reading.Value)
        val result = tracker.update((second as Reading.Value).value, 1_001L)
        assertTrue(result is Reading.Value)
        assertEquals(38.8889, (result as Reading.Value).value, 0.001)
    }

    @Test
    fun rejectsMalformedCountersAndResets() {
        assertTrue(CpuStatParser.parse("intr 10", 1L) is Reading.InvalidData)
        val tracker = CpuLoadTracker()
        tracker.update(CpuStatCounters(100, 50), 1L)
        assertTrue(tracker.update(CpuStatCounters(90, 45), 2L) is Reading.InvalidData)
    }
}
