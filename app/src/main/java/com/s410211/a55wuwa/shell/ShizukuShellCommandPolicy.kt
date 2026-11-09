package com.s410211.a55wuwa.shell

/**
 * Immutable paths that the Shizuku read-only gateway may inspect.
 * The UI and callers can select an identifier, never supply a shell path.
 */
enum class GpuShellNode(val path: String) {
    GPU_BUSY("/sys/kernel/gpu/gpu_busy"),
    GPU_CLOCK("/sys/kernel/gpu/gpu_clock"),
    GPU_FREQ_TABLE("/sys/kernel/gpu/gpu_freq_table"),
    GPU_TEMPERATURE("/sys/kernel/gpu/gpu_tmu"),
    GPU_MODEL("/sys/kernel/gpu/gpu_model"),
    GPU_MEMORY_INFO("/sys/kernel/gpu/mem_info"),
    GPU_UTILIZATION_SOURCE("/sys/kernel/gpu/utilization_source"),
    GPU_AVAILABLE_UTILIZATION_SOURCES("/sys/kernel/gpu/available_utilization_sources")
}

/** Public system properties approved for read-only device identification. */
enum class AllowedSystemProperty(val key: String) {
    ONE_UI_VERSION("ro.build.version.oneui"),
    BUILD_FINGERPRINT("ro.build.fingerprint"),
    BUILD_ID("ro.build.id"),
    KERNEL_VERSION("ro.kernel.version")
}

/**
 * Typed allowlist. There is intentionally no raw command or arbitrary-argument
 * variant, and the generated argv is passed directly to Process, never a shell.
 */
sealed interface ReadOnlyShellCommand {
    data class ReadGpuNode(val node: GpuShellNode) : ReadOnlyShellCommand
    data class ReadSystemProperty(val property: AllowedSystemProperty) : ReadOnlyShellCommand
}

data class ShellCommandSpec(
    val argv: List<String>,
    val timeoutMillis: Long,
    val stdoutLimitBytes: Int,
    val stderrLimitBytes: Int
)

object ShizukuShellCommandPolicy {
    private const val CAT = "/system/bin/cat"
    private const val GETPROP = "/system/bin/getprop"

    const val DEFAULT_TIMEOUT_MILLIS = 2_500L
    const val MAX_GPU_NODE_STDOUT_BYTES = 4_096
    const val MAX_PROPERTY_STDOUT_BYTES = 512
    const val MAX_STDERR_BYTES = 1_024

    fun toSpec(command: ReadOnlyShellCommand): ShellCommandSpec = when (command) {
        is ReadOnlyShellCommand.ReadGpuNode -> ShellCommandSpec(
            argv = listOf(CAT, command.node.path),
            timeoutMillis = DEFAULT_TIMEOUT_MILLIS,
            stdoutLimitBytes = MAX_GPU_NODE_STDOUT_BYTES,
            stderrLimitBytes = MAX_STDERR_BYTES
        )
        is ReadOnlyShellCommand.ReadSystemProperty -> ShellCommandSpec(
            argv = listOf(GETPROP, command.property.key),
            timeoutMillis = DEFAULT_TIMEOUT_MILLIS,
            stdoutLimitBytes = MAX_PROPERTY_STDOUT_BYTES,
            stderrLimitBytes = MAX_STDERR_BYTES
        )
    }
}
