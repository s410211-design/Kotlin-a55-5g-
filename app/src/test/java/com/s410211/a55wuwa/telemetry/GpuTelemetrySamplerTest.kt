package com.s410211.a55wuwa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuTelemetrySamplerTest {
    @Test
    fun sampleOnceKeepsSourceTimestampAndIsolatesReaderFailures() {
        val readable = GpuNodeCandidate(
            "/sys/kernel/gpu/gpu_clock",
            GpuNodeRole.CURRENT_CLOCK_CANDIDATE,
            "test"
        )
        val failing = GpuNodeCandidate(
            "/sys/kernel/gpu/gpu_busy",
            GpuNodeRole.UTILIZATION_CANDIDATE,
            "test"
        )
        val sampler = GpuTelemetrySampler(
            intervalMillis = 1_000L,
            candidates = listOf(readable, failing),
            reader = { candidate, sampledAt ->
                if (candidate == readable) {
                    Reading.Value("742000", candidate.path, "raw", sampledAt)
                } else {
                    throw SecurityException("denied")
                }
            },
            clock = { 777L }
        )

        try {
            val batch = sampler.sampleOnce()
            assertEquals(777L, batch.sampledAtMillis)
            assertEquals(2, batch.readings.size)

            val value = batch.readings[0].second
            assertTrue(value is Reading.Value)
            assertEquals(777L, value.sampledAtMillis)

            val error = batch.readings[1].second
            assertTrue(error is Reading.Error)
            assertEquals(failing.path, error.source)
            assertEquals("SecurityException", error.reason)
        } finally {
            sampler.close()
        }
    }

    @Test
    fun rejectsUnboundedSamplingIntervalsAndEmptyCatalogs() {
        val candidate = GpuNodeCandidate(
            "/sys/kernel/gpu/gpu_clock",
            GpuNodeRole.CURRENT_CLOCK_CANDIDATE,
            "test"
        )
        assertTrue(
            runCatching { GpuTelemetrySampler(intervalMillis = 100L, candidates = listOf(candidate)) }
                .exceptionOrNull() is IllegalArgumentException
        )
        assertTrue(
            runCatching { GpuTelemetrySampler(candidates = emptyList()) }
                .exceptionOrNull() is IllegalArgumentException
        )
    }
}
