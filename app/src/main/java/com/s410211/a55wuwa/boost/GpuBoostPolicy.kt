package com.s410211.a55wuwa.boost

data class GpuPolicySnapshot(
    val currentMinRaw: Long,
    val currentMaxRaw: Long,
    val availableOperatingPointsRaw: Set<Long>
)

data class GpuBoostPlan(
    val originalMinRaw: Long,
    val targetMinRaw: Long,
    val maxLimitRaw: Long
)

sealed interface GpuBoostPlanResult {
    data class Ready(val plan: GpuBoostPlan) : GpuBoostPlanResult
    data class NoChange(val currentMinRaw: Long) : GpuBoostPlanResult
    data class Rejected(val reason: String) : GpuBoostPlanResult
}

/**
 * Plans a GPU minimum-floor change using the device's own raw OPP values.
 * It never converts guessed units or raises gpu_max_clock.
 */
object GpuBoostPolicy {
    fun planAtPercent(
        snapshot: GpuPolicySnapshot,
        requestedPercent: Int
    ): GpuBoostPlanResult {
        if (requestedPercent !in CpuBoostPolicy.MIN_PERCENT..CpuBoostPolicy.MAX_PERCENT) {
            return GpuBoostPlanResult.Rejected("GPU Boost 百分比超出允許範圍")
        }
        if (snapshot.currentMinRaw <= 0L ||
            snapshot.currentMaxRaw <= 0L ||
            snapshot.currentMinRaw > snapshot.currentMaxRaw
        ) {
            return GpuBoostPlanResult.Rejected("GPU 最低／最高限制無效")
        }
        if (snapshot.availableOperatingPointsRaw.isEmpty() ||
            snapshot.availableOperatingPointsRaw.any { it <= 0L }
        ) {
            return GpuBoostPlanResult.Rejected("GPU OPP 頻率表缺失或無效")
        }
        val validOpps = snapshot.availableOperatingPointsRaw
            .filter { it >= snapshot.currentMinRaw && it <= snapshot.currentMaxRaw }
            .distinct()
            .sorted()
        if (validOpps.isEmpty()) {
            return GpuBoostPlanResult.Rejected("目前 GPU 上限內沒有有效 OPP")
        }
        val requestedFloor = percentageFloor(snapshot.currentMaxRaw, requestedPercent)
        val target = validOpps.firstOrNull { it >= requestedFloor } ?: validOpps.last()
        if (target <= snapshot.currentMinRaw) {
            return GpuBoostPlanResult.NoChange(snapshot.currentMinRaw)
        }
        return GpuBoostPlanResult.Ready(
            GpuBoostPlan(snapshot.currentMinRaw, target, snapshot.currentMaxRaw)
        )
    }

    /** Avoid Long overflow when handling untrusted sysfs frequency values. */
    private fun percentageFloor(value: Long, percent: Int): Long =
        (value / 100L) * percent + ((value % 100L) * percent) / 100L
}
