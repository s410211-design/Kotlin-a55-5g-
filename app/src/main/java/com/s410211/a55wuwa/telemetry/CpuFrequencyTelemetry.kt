package com.s410211.a55wuwa.telemetry

import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class CpuFrequencyPolicy(
    val id: String,
    val label: String,
    val path: String
)

/**
 * These are the two policies observed on the target SM-A5560 firmware.
 * Policy IDs are not CPU core numbers; the kernel groups CPUs that share
 * one frequency-control interface into a policy.
 */
object CpuFrequencyCatalog {
    val policies: List<CpuFrequencyPolicy> = listOf(
        CpuFrequencyPolicy(
            id = "policy0",
            label = "CPU Policy 0",
            path = "/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq"
        ),
        CpuFrequencyPolicy(
            id = "policy4",
            label = "CPU Policy 4",
            path = "/sys/devices/system/cpu/cpufreq/policy4/scaling_cur_freq"
        )
    )
}

/**
 * Linux CPUFreq scaling_cur_freq is reported in kHz. Convert only this
 * source-specific format to Hz; do not reuse this assumption for GPU nodes.
 */
object CpuFrequencyParser {
    private const val MAX_TOKEN_LENGTH = 32
    private const val KHZ_TO_HZ = 1_000L
    private val INTEGER_TOKEN = Regex("""[0-9]+""")

    fun parse(raw: String, source: String, sampledAtMillis: Long): Reading<Long> {
        val token = raw.trim()
        if (token.isEmpty() || token.length > MAX_TOKEN_LENGTH ||
            !INTEGER_TOKEN.matches(token)
        ) {
            return Reading.InvalidData(
                source,
                sampledAtMillis,
                "Expected one positive integer CPU frequency in kHz"
            )
        }

        val khz = token.toLongOrNull()
            ?: return Reading.InvalidData(source, sampledAtMillis, "CPU frequency exceeds Long range")

        if (khz <= 0L) {
            return Reading.InvalidData(source, sampledAtMillis, "CPU frequency must be greater than zero")
        }
        if (khz > Long.MAX_VALUE / KHZ_TO_HZ) {
            return Reading.InvalidData(source, sampledAtMillis, "CPU frequency conversion overflows Hz")
        }

        return Reading.Value(khz * KHZ_TO_HZ, source, "Hz", sampledAtMillis)
    }
}

/** Exact-path, read-only access to the two validated CPUFreq policy nodes. */
object CpuFrequencyReader {
    private const val MAX_BYTES = 64

    fun read(
        policy: CpuFrequencyPolicy,
        sampledAtMillis: Long = SystemClock.elapsedRealtime()
    ): Reading<Long> {
        if (policy !in CpuFrequencyCatalog.policies) {
            return Reading.Unsupported(
                policy.path,
                sampledAtMillis,
                "CPU policy path is not in the built-in read-only allowlist"
            )
        }

        val descriptor = try {
            Os.open(policy.path, OsConstants.O_RDONLY, 0)
        } catch (error: ErrnoException) {
            return LinuxErrnoMapper.toReading(error.errno, policy.path, sampledAtMillis)
        } catch (_: SecurityException) {
            return Reading.PermissionDenied(
                policy.path,
                sampledAtMillis,
                "系統安全政策拒絕讀取 CPU 頻率節點"
            )
        }

        return try {
            val buffer = ByteArray(MAX_BYTES + 1)
            var size = 0
            while (size < buffer.size) {
                val count = Os.read(descriptor, buffer, size, buffer.size - size)
                if (count <= 0) break
                size += count
            }

            if (size > MAX_BYTES) {
                Reading.InvalidData(
                    policy.path,
                    sampledAtMillis,
                    "CPU frequency node output exceeded $MAX_BYTES bytes"
                )
            } else {
                val raw = String(buffer, 0, size, Charsets.UTF_8)
                CpuFrequencyParser.parse(raw, policy.path, sampledAtMillis)
            }
        } catch (error: ErrnoException) {
            LinuxErrnoMapper.toReading(error.errno, policy.path, sampledAtMillis)
        } catch (_: SecurityException) {
            Reading.PermissionDenied(
                policy.path,
                sampledAtMillis,
                "系統安全政策拒絕讀取 CPU 頻率節點"
            )
        } finally {
            runCatching { Os.close(descriptor) }
        }
    }
}

data class CpuSampleBatch(
    val sampledAtMillis: Long,
    val readings: List<Pair<CpuFrequencyPolicy, Reading<Long>>>
)

/** Single-worker, bounded sampler for the target device's CPUFreq policies. */
class CpuTelemetrySampler(
    private val intervalMillis: Long = 1_000L,
    private val policies: List<CpuFrequencyPolicy> = CpuFrequencyCatalog.policies,
    private val reader: (CpuFrequencyPolicy, Long) -> Reading<Long> =
        { policy, at -> CpuFrequencyReader.read(policy, at) },
    private val clock: () -> Long = SystemClock::elapsedRealtime
) : AutoCloseable {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "a55-cpu-sampler").apply { isDaemon = true }
        }

    private var scheduledTask: ScheduledFuture<*>? = null
    private var callback: ((CpuSampleBatch) -> Unit)? = null
    private val samplingLock = Any()

    init {
        require(intervalMillis in MIN_INTERVAL_MILLIS..MAX_INTERVAL_MILLIS) {
            "Sampling interval must be between $MIN_INTERVAL_MILLIS and $MAX_INTERVAL_MILLIS ms"
        }
        require(policies.isNotEmpty()) { "At least one CPUFreq policy is required" }
        require(policies.all { it in CpuFrequencyCatalog.policies }) {
            "Only catalogued CPUFreq policies can be sampled"
        }
    }

    @Synchronized
    fun start(onSample: (CpuSampleBatch) -> Unit) {
        if (scheduledTask?.isCancelled == false && scheduledTask?.isDone == false) return
        callback = onSample
        scheduledTask = executor.scheduleWithFixedDelay(
            {
                val batch = sampleOnce()
                val target = synchronized(this) { callback }
                runCatching { target?.invoke(batch) }
            },
            0L,
            intervalMillis,
            TimeUnit.MILLISECONDS
        )
    }

    fun sampleOnce(sampledAtMillis: Long = clock()): CpuSampleBatch =
        synchronized(samplingLock) {
            val readings = policies.map { policy ->
                policy to runCatching { reader(policy, sampledAtMillis) }
                    .getOrElse { error ->
                        if (error is SecurityException) {
                            Reading.PermissionDenied(
                                policy.path,
                                sampledAtMillis,
                                "讀取遭系統安全政策拒絕"
                            )
                        } else {
                            Reading.Error(
                                policy.path,
                                sampledAtMillis,
                                error.javaClass.simpleName
                            )
                        }
                    }
            }
            CpuSampleBatch(sampledAtMillis, readings)
        }

    @Synchronized
    fun stop() {
        scheduledTask?.cancel(true)
        scheduledTask = null
        callback = null
    }

    override fun close() {
        stop()
        executor.shutdownNow()
    }

    private companion object {
        const val MIN_INTERVAL_MILLIS = 250L
        const val MAX_INTERVAL_MILLIS = 10_000L
    }
}
