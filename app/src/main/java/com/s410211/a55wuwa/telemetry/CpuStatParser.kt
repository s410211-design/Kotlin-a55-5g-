package com.s410211.a55wuwa.telemetry

data class CpuStatCounters(
    val totalTicks: Long,
    val idleTicks: Long
)

/** Parses the aggregate first line of Linux /proc/stat. */
object CpuStatParser {
    fun parse(raw: String, sampledAtMillis: Long): Reading<CpuStatCounters> {
        val line = raw.lineSequence().firstOrNull { it.startsWith("cpu ") }
            ?: return Reading.InvalidData("/proc/stat", sampledAtMillis, "缺少 aggregate cpu 行")
        val values = line.trim().split(Regex("\\s+")).drop(1)
            .mapNotNull(String::toLongOrNull)
        if (values.size < 4 || values.any { it < 0L }) {
            return Reading.InvalidData("/proc/stat", sampledAtMillis, "CPU tick 欄位不足或含無效值")
        }
        val total = values.take(8).sum()
        val idle = values[3] + (values.getOrNull(4) ?: 0L)
        if (total <= 0L || idle > total) {
            return Reading.InvalidData("/proc/stat", sampledAtMillis, "CPU tick 總量不合理")
        }
        return Reading.Value(
            CpuStatCounters(total, idle),
            "/proc/stat",
            "ticks",
            sampledAtMillis
        )
    }
}

/** Stateful delta calculator; first sample establishes a baseline only. */
class CpuLoadTracker {
    private var previous: CpuStatCounters? = null

    fun update(
        current: CpuStatCounters,
        sampledAtMillis: Long
    ): Reading<Double> {
        val before = previous
        previous = current
        if (before == null) {
            return Reading.AwaitingSample("/proc/stat", sampledAtMillis, "等待第二次 CPU tick 樣本")
        }
        val totalDelta = current.totalTicks - before.totalTicks
        val idleDelta = current.idleTicks - before.idleTicks
        if (totalDelta <= 0L || idleDelta < 0L || idleDelta > totalDelta) {
            return Reading.InvalidData("/proc/stat", sampledAtMillis, "CPU tick 計數器重置或差值異常")
        }
        val usage = ((totalDelta - idleDelta).toDouble() / totalDelta.toDouble()) * 100.0
        if (!usage.isFinite() || usage !in 0.0..100.0) {
            return Reading.InvalidData("/proc/stat", sampledAtMillis, "CPU 使用率計算結果超出範圍")
        }
        return Reading.Value(usage, "/proc/stat", "%", sampledAtMillis)
    }

    fun reset() {
        previous = null
    }
}
