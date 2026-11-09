package com.s410211.a55wuwa.telemetry

import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class GpuSampleBatch(
    val sampledAtMillis: Long,
    val readings: List<Pair<GpuNodeCandidate, Reading<String>>>
)

/**
 * A bounded, single-worker sampler for the visible dashboard.
 *
 * It has no history buffer and samples only the fixed catalog. Call stop()
 * when the Activity is not visible; the foreground monitoring service will
 * own its own lifecycle when that feature is implemented.
 */
class GpuTelemetrySampler(
    private val intervalMillis: Long = 1_000L,
    private val candidates: List<GpuNodeCandidate> = GpuNodeCatalog.candidates,
    private val reader: (GpuNodeCandidate, Long) -> Reading<String> =
        { candidate, at -> GpuSysfsReader.read(candidate, at) },
    private val clock: () -> Long = SystemClock::elapsedRealtime
) : AutoCloseable {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "a55-gpu-sampler").apply { isDaemon = true }
        }

    private var scheduledTask: ScheduledFuture<*>? = null
    private var callback: ((GpuSampleBatch) -> Unit)? = null
    private val samplingLock = Any()

    init {
        require(intervalMillis in MIN_INTERVAL_MILLIS..MAX_INTERVAL_MILLIS) {
            "Sampling interval must be between $MIN_INTERVAL_MILLIS and $MAX_INTERVAL_MILLIS ms"
        }
        require(candidates.isNotEmpty()) { "At least one telemetry candidate is required" }
    }

    @Synchronized
    fun start(onSample: (GpuSampleBatch) -> Unit) {
        if (scheduledTask?.isCancelled == false && scheduledTask?.isDone == false) {
            return
        }
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

    fun sampleOnce(sampledAtMillis: Long = clock()): GpuSampleBatch =
        synchronized(samplingLock) {
            val readings = candidates.map { candidate ->
                candidate to runCatching { reader(candidate, sampledAtMillis) }
                    .getOrElse { error ->
                        if (error is SecurityException) {
                            Reading.PermissionDenied(
                                candidate.path,
                                sampledAtMillis,
                                "讀取遭系統安全政策拒絕"
                            )
                        } else {
                            Reading.Error(
                                candidate.path,
                                sampledAtMillis,
                                error.javaClass.simpleName
                            )
                        }
                    }
            }
            GpuSampleBatch(sampledAtMillis, readings)
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
