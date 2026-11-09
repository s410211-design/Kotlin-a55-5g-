package com.s410211.a55wuwa.shell

import com.s410211.a55wuwa.telemetry.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellReadingMapperTest {
    @Test
    fun mapsSuccessfulOutputToSharedRawReading() {
        val reading = ShellReadingMapper.toReading(
            result(ShellExecutionState.SUCCESS, stdout = "  77.5 %\n"),
            "/sys/kernel/gpu/gpu_busy",
            100L
        )
        assertTrue(reading is Reading.Value)
        assertEquals("77.5 %", (reading as Reading.Value).value)
        assertEquals("raw", reading.unit)
        assertEquals(100L, reading.sampledAtMillis)
    }

    @Test
    fun neverInfersMissingNodeOrPermissionFromLocalizedStderr() {
        val reading = ShellReadingMapper.toReading(
            result(
                ShellExecutionState.NON_ZERO_EXIT,
                exitCode = 1,
                stderr = "Permission denied"
            ),
            "/sys/kernel/gpu/gpu_clock",
            101L
        )
        assertTrue(reading is Reading.Error)
        assertTrue((reading as Reading.Error).reason.contains("無法僅憑 stderr"))
    }

    @Test
    fun mapsTimeoutCancellationAndExplicitLaunchDenial() {
        assertTrue(
            ShellReadingMapper.toReading(
                result(ShellExecutionState.TIMED_OUT),
                "test",
                102L
            ) is Reading.TemporarilyUnavailable
        )
        assertTrue(
            ShellReadingMapper.toReading(
                result(ShellExecutionState.CANCELLED),
                "test",
                103L
            ) is Reading.TemporarilyUnavailable
        )
        assertTrue(
            ShellReadingMapper.toReading(
                result(ShellExecutionState.PERMISSION_DENIED),
                "test",
                104L
            ) is Reading.PermissionDenied
        )
    }

    @Test
    fun mapsTruncationAndEmptySuccessToInvalidData() {
        assertTrue(
            ShellReadingMapper.toReading(
                result(ShellExecutionState.OUTPUT_TRUNCATED),
                "test",
                105L
            ) is Reading.InvalidData
        )
        assertTrue(
            ShellReadingMapper.toReading(
                result(ShellExecutionState.SUCCESS, stdout = " \n"),
                "test",
                106L
            ) is Reading.InvalidData
        )
    }

    private fun result(
        state: ShellExecutionState,
        exitCode: Int? = if (state == ShellExecutionState.SUCCESS) 0 else null,
        stdout: String = "",
        stderr: String = ""
    ) = ShellExecutionResult(
        state = state,
        exitCode = exitCode,
        stdout = stdout,
        stderr = stderr,
        stdoutTruncated = false,
        stderrTruncated = false,
        elapsedMillis = 1L
    )
}
