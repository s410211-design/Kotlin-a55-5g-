package com.s410211.a55wuwa.telemetry

import org.junit.Assert.assertTrue
import org.junit.Test

class CpuSysfsReaderTest {
    @Test
    fun rejectsArbitraryPathsBeforeFileAccess() {
        assertTrue(CpuSysfsReader.read("/system/bin/sh", 1L) is Reading.Unsupported)
    }
}
