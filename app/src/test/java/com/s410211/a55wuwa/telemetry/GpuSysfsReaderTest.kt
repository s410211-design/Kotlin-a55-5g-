package com.s410211.a55wuwa.telemetry

import org.junit.Assert.assertTrue
import org.junit.Test

class GpuSysfsReaderTest {
    @Test
    fun mapsPermissionErrorsSeparatelyFromMissingNode() {
        assertTrue(LinuxErrnoMapper.toReading(1, "test", 1L) is Reading.PermissionDenied)
        assertTrue(LinuxErrnoMapper.toReading(13, "test", 1L) is Reading.PermissionDenied)
        assertTrue(LinuxErrnoMapper.toReading(2, "test", 1L) is Reading.NotFound)
    }

    @Test
    fun mapsIoAndInterruptedReadsAsTemporary() {
        assertTrue(LinuxErrnoMapper.toReading(4, "test", 1L) is Reading.TemporarilyUnavailable)
        assertTrue(LinuxErrnoMapper.toReading(5, "test", 1L) is Reading.TemporarilyUnavailable)
        assertTrue(LinuxErrnoMapper.toReading(11, "test", 1L) is Reading.TemporarilyUnavailable)
    }

    @Test
    fun rejectsPathsOutsideTheCatalogWithoutOpeningThem() {
        val unknown = GpuNodeCandidate(
            "/data/local/tmp/not-a-gpu-node",
            GpuNodeRole.UTILIZATION_CANDIDATE,
            "test"
        )
        val reading = GpuSysfsReader.read(unknown, 1L)
        assertTrue(reading is Reading.Unsupported)
    }
}
