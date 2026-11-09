package com.s410211.a55wuwa.shell

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.min

fun interface ShellProcessLauncher {
    /** Implementations must pass argv directly to the privileged process API. */
    fun start(argv: Array<String>): Process
}

enum class ShellExecutionState {
    SUCCESS,
    NON_ZERO_EXIT,
    TIMED_OUT,
    CANCELLED,
    PERMISSION_DENIED,
    OUTPUT_TRUNCATED,
    ERROR
}

data class ShellExecutionResult(
    val state: ShellExecutionState,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val elapsedMillis: Long,
    val detail: String? = null
)

/**
 * Bounded Process executor for allowlisted commands. Invoke from an IO thread.
 *
 * Both output pipes are drained concurrently to avoid deadlock. Only the first
 * configured bytes are retained; the remainder is discarded while draining.
 * A non-zero exit remains a generic process failure because stderr wording is
 * not a reliable permission-vs-missing-node signal across firmware locales.
 */
class ShellCommandExecutor(
    private val launcher: ShellProcessLauncher,
    private val terminationGraceMillis: Long = 250L,
    private val streamJoinMillis: Long = 400L
) {
    init {
        require(terminationGraceMillis in 0L..2_000L)
        require(streamJoinMillis in 50L..2_000L)
    }

    fun execute(command: ReadOnlyShellCommand): ShellExecutionResult {
        val startedAt = System.nanoTime()
        val spec = ShizukuShellCommandPolicy.toSpec(command)

        val process = try {
            launcher.start(spec.argv.toTypedArray())
        } catch (error: SecurityException) {
            return result(
                ShellExecutionState.PERMISSION_DENIED, null, "", "", false, false,
                startedAt, "建立受限程序時遭安全政策拒絕"
            )
        } catch (error: Exception) {
            return result(
                ShellExecutionState.ERROR, null, "", "", false, false,
                startedAt, error.javaClass.simpleName
            )
        }

        // None of the allowlisted commands consume stdin; close it immediately.
        closeQuietly(process.outputStream)

        val stdoutTask = FutureTask { capture(process.inputStream, spec.stdoutLimitBytes) }
        val stderrTask = FutureTask { capture(process.errorStream, spec.stderrLimitBytes) }
        Thread(stdoutTask, "a55-shell-stdout").apply { isDaemon = true }.start()
        Thread(stderrTask, "a55-shell-stderr").apply { isDaemon = true }.start()

        var timedOut = false
        var cancelled = false
        var exitCode: Int? = null
        var detail: String? = null

        try {
            if (process.waitFor(spec.timeoutMillis, TimeUnit.MILLISECONDS)) {
                exitCode = runCatching { process.exitValue() }.getOrNull()
            } else {
                timedOut = true
                terminate(process)
            }
        } catch (_: InterruptedException) {
            cancelled = true
            terminate(process)
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            detail = error.javaClass.simpleName
            terminate(process)
        }

        if (timedOut || cancelled || detail != null) {
            closeQuietly(process.inputStream)
            closeQuietly(process.errorStream)
        }

        val stdout = awaitCapture(stdoutTask)
        val stderr = awaitCapture(stderrTask)

        val state = when {
            cancelled -> ShellExecutionState.CANCELLED
            timedOut -> ShellExecutionState.TIMED_OUT
            detail != null -> ShellExecutionState.ERROR
            stdout == null || stderr == null -> ShellExecutionState.ERROR
            stdout.failure != null || stderr.failure != null -> ShellExecutionState.ERROR
            exitCode == null -> ShellExecutionState.ERROR
            exitCode != 0 -> ShellExecutionState.NON_ZERO_EXIT
            stdout.truncated || stderr.truncated -> ShellExecutionState.OUTPUT_TRUNCATED
            else -> ShellExecutionState.SUCCESS
        }

        return result(
            state = state,
            exitCode = exitCode,
            stdout = stdout?.bytes?.toString(StandardCharsets.UTF_8).orEmpty(),
            stderr = stderr?.bytes?.toString(StandardCharsets.UTF_8).orEmpty(),
            stdoutTruncated = stdout?.truncated ?: false,
            stderrTruncated = stderr?.truncated ?: false,
            startedAt = startedAt,
            detail = detail ?: stdout?.failure ?: stderr?.failure
        )
    }

    private fun terminate(process: Process) {
        runCatching { process.destroy() }
        val exited = runCatching {
            process.waitFor(terminationGraceMillis, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!exited) {
            runCatching { process.destroyForcibly() }
            runCatching { process.waitFor(terminationGraceMillis, TimeUnit.MILLISECONDS) }
        }
    }

    private fun capture(stream: InputStream, byteLimit: Int): CapturedStream {
        val retained = ByteArrayOutputStream(min(byteLimit, 1_024))
        val buffer = ByteArray(512)
        var truncated = false
        return try {
            stream.use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue

                    val remaining = byteLimit - retained.size()
                    if (remaining > 0) {
                        val keep = min(remaining, count)
                        retained.write(buffer, 0, keep)
                        if (keep < count) truncated = true
                    } else {
                        truncated = true
                    }
                }
            }
            CapturedStream(retained.toByteArray(), truncated, null)
        } catch (error: IOException) {
            CapturedStream(retained.toByteArray(), truncated, error.javaClass.simpleName)
        }
    }

    private fun awaitCapture(task: FutureTask<CapturedStream>): CapturedStream? =
        try {
            task.get(streamJoinMillis, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            task.cancel(true)
            null
        }

    private fun closeQuietly(stream: java.io.Closeable) {
        runCatching { stream.close() }
    }

    private fun result(
        state: ShellExecutionState,
        exitCode: Int?,
        stdout: String,
        stderr: String,
        stdoutTruncated: Boolean,
        stderrTruncated: Boolean,
        startedAt: Long,
        detail: String?
    ) = ShellExecutionResult(
        state = state,
        exitCode = exitCode,
        stdout = stdout,
        stderr = stderr,
        stdoutTruncated = stdoutTruncated,
        stderrTruncated = stderrTruncated,
        elapsedMillis = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L),
        detail = detail
    )

    private data class CapturedStream(
        val bytes: ByteArray,
        val truncated: Boolean,
        val failure: String?
    )
}
