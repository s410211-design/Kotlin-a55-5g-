package com.s410211.a55wuwa.shell

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCommandExecutorTest {
    @Test
    fun passesAllowlistedArgvAndCapturesBothStreams() {
        lateinit var launchedArgv: List<String>
        val process = FakeProcess(
            exitCode = 0,
            stdout = "742000\n".toByteArray(),
            stderr = "diagnostic".toByteArray()
        )
        val executor = ShellCommandExecutor(
            ShellProcessLauncher { argv ->
                launchedArgv = argv.toList()
                process
            }
        )

        val result = executor.execute(
            ReadOnlyShellCommand.ReadGpuNode(GpuShellNode.GPU_CLOCK)
        )

        assertEquals(listOf("/system/bin/cat", "/sys/kernel/gpu/gpu_clock"), launchedArgv)
        assertEquals(ShellExecutionState.SUCCESS, result.state)
        assertEquals(0, result.exitCode)
        assertEquals("742000\n", result.stdout)
        assertEquals("diagnostic", result.stderr)
        assertFalse(result.stdoutTruncated)
        assertFalse(result.stderrTruncated)
    }

    @Test
    fun reportsNonZeroExitWithoutGuessingPermissionOrMissingNode() {
        val executor = ShellCommandExecutor(
            ShellProcessLauncher {
                FakeProcess(1, ByteArray(0), "Permission denied".toByteArray())
            }
        )

        val result = executor.execute(
            ReadOnlyShellCommand.ReadGpuNode(GpuShellNode.GPU_BUSY)
        )

        assertEquals(ShellExecutionState.NON_ZERO_EXIT, result.state)
        assertEquals(1, result.exitCode)
        assertEquals("Permission denied", result.stderr)
        assertTrue(result.detail == null)
    }

    @Test
    fun truncatesRetainedOutputAtPolicyLimitAndReportsIt() {
        val output = ByteArray(ShizukuShellCommandPolicy.MAX_GPU_NODE_STDOUT_BYTES + 100) { 'x'.code.toByte() }
        val executor = ShellCommandExecutor(
            ShellProcessLauncher { FakeProcess(0, output, ByteArray(0)) }
        )

        val result = executor.execute(
            ReadOnlyShellCommand.ReadGpuNode(GpuShellNode.GPU_FREQ_TABLE)
        )

        assertEquals(ShellExecutionState.OUTPUT_TRUNCATED, result.state)
        assertEquals(ShizukuShellCommandPolicy.MAX_GPU_NODE_STDOUT_BYTES, result.stdout.toByteArray().size)
        assertTrue(result.stdoutTruncated)
    }

    @Test
    fun reportsTimeoutAndTerminatesProcess() {
        val process = FakeProcess(
            exitCode = 0,
            stdout = ByteArray(0),
            stderr = ByteArray(0),
            completeWithinTimeout = false
        )
        val executor = ShellCommandExecutor(
            launcher = ShellProcessLauncher { process },
            terminationGraceMillis = 50L,
            streamJoinMillis = 100L
        )

        val result = executor.execute(
            ReadOnlyShellCommand.ReadSystemProperty(AllowedSystemProperty.ONE_UI_VERSION)
        )

        assertEquals(ShellExecutionState.TIMED_OUT, result.state)
        assertFalse(process.isAlive)
    }

    @Test
    fun classifiesSecurityExceptionWhenLaunchingAsPermissionDenied() {
        val executor = ShellCommandExecutor(
            ShellProcessLauncher { throw SecurityException("denied") }
        )

        val result = executor.execute(
            ReadOnlyShellCommand.ReadSystemProperty(AllowedSystemProperty.BUILD_ID)
        )

        assertEquals(ShellExecutionState.PERMISSION_DENIED, result.state)
        assertEquals(null, result.exitCode)
    }

    private class FakeProcess(
        private val exitCode: Int,
        stdout: ByteArray,
        stderr: ByteArray,
        private val completeWithinTimeout: Boolean = true
    ) : Process() {
        private var alive = true
        private val stdoutStream = ByteArrayInputStream(stdout)
        private val stderrStream = ByteArrayInputStream(stderr)
        private val stdin = ByteArrayOutputStream()

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream() = stdoutStream
        override fun getErrorStream() = stderrStream

        override fun waitFor(): Int {
            alive = false
            return exitCode
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (!alive || completeWithinTimeout) {
                alive = false
                return true
            }
            return false
        }

        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException("process is still alive")
            return exitCode
        }

        override fun destroy() {
            alive = false
        }

        override fun destroyForcibly(): Process {
            alive = false
            return this
        }

        override fun isAlive(): Boolean = alive
    }
}
