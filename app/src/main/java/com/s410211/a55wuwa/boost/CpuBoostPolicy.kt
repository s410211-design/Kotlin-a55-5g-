package com.s410211.a55wuwa.boost

/**
 * Pure policy layer for CPUFreq minimum-floor Boost.
 *
 * This class never performs I/O. A platform adapter must independently verify
 * Shizuku identity, permissions, write access, and read-back before reporting
 * that a Boost has been applied.
 */
data class CpuPolicySnapshot(
    val policyId: String,
    val currentMinKHz: Long,
    val currentMaxKHz: Long,
    val availableFrequenciesKHz: Set<Long>
)

data class CpuBoostPlan(
    val policyId: String,
    val originalMinKHz: Long,
    val targetMinKHz: Long,
    val maxLimitKHz: Long
)

sealed interface CpuBoostPlanResult {
    data class Ready(val plan: CpuBoostPlan) : CpuBoostPlanResult
    data class NoChange(val currentMinKHz: Long) : CpuBoostPlanResult
    data class Rejected(val reason: String) : CpuBoostPlanResult
}

enum class RestoreDecision {
    RESTORE_ORIGINAL,
    ALREADY_RESTORED,
    EXTERNAL_CHANGE,
    INVALID_JOURNAL
}

object CpuBoostPolicy {
    /**
     * Pick the lowest supported OPP at or above the requested percentage of
     * the current OEM scaling_max_freq. Never raises scaling_max_freq.
     */
    fun planAtPercent(
        snapshot: CpuPolicySnapshot,
        requestedPercent: Int
    ): CpuBoostPlanResult {
        if (requestedPercent !in MIN_PERCENT..MAX_PERCENT) {
            return CpuBoostPlanResult.Rejected("Boost 百分比必須介於 $MIN_PERCENT–$MAX_PERCENT")
        }
        if (snapshot.policyId.isBlank()) {
            return CpuBoostPlanResult.Rejected("CPU policy ID 無效")
        }
        if (snapshot.currentMinKHz <= 0L ||
            snapshot.currentMaxKHz <= 0L ||
            snapshot.currentMinKHz > snapshot.currentMaxKHz
        ) {
            return CpuBoostPlanResult.Rejected("CPU policy 最低／最高限制不合理")
        }
        if (snapshot.availableFrequenciesKHz.isEmpty()) {
            return CpuBoostPlanResult.Rejected("韌體未提供可驗證的 CPU OPP 頻率表")
        }
        if (snapshot.availableFrequenciesKHz.any { it <= 0L }) {
            return CpuBoostPlanResult.Rejected("CPU OPP 頻率表含有無效值")
        }

        val validOpps = snapshot.availableFrequenciesKHz
            .filter { it >= snapshot.currentMinKHz && it <= snapshot.currentMaxKHz }
            .distinct()
            .sorted()
        if (validOpps.isEmpty()) {
            return CpuBoostPlanResult.Rejected("目前 policy 限制內沒有可用 OPP")
        }

        val requestedFloor = snapshot.currentMaxKHz * requestedPercent / 100L
        val target = validOpps.firstOrNull { it >= requestedFloor }
            ?: validOpps.last()

        if (target <= snapshot.currentMinKHz) {
            return CpuBoostPlanResult.NoChange(snapshot.currentMinKHz)
        }

        return CpuBoostPlanResult.Ready(
            CpuBoostPlan(
                policyId = snapshot.policyId,
                originalMinKHz = snapshot.currentMinKHz,
                targetMinKHz = target,
                maxLimitKHz = snapshot.currentMaxKHz
            )
        )
    }

    /**
     * Restore only if the live value still equals the value this app applied.
     * Never overwrite a value changed by another system component or app.
     */
    fun decideRestore(
        originalMinKHz: Long,
        lastAppliedMinKHz: Long,
        currentMinKHz: Long
    ): RestoreDecision {
        if (originalMinKHz <= 0L || lastAppliedMinKHz <= 0L || currentMinKHz <= 0L ||
            originalMinKHz > lastAppliedMinKHz
        ) return RestoreDecision.INVALID_JOURNAL

        return when (currentMinKHz) {
            originalMinKHz -> RestoreDecision.ALREADY_RESTORED
            lastAppliedMinKHz -> RestoreDecision.RESTORE_ORIGINAL
            else -> RestoreDecision.EXTERNAL_CHANGE
        }
    }

    /**
     * Conservative thermal gate. Unknown values fail closed.
     * NONE and LIGHT may proceed; MODERATE or above must not start/continue Boost.
     */
    fun thermalAllowsBoost(status: Int): Boolean =
        status == THERMAL_STATUS_NONE ||
            status == THERMAL_STATUS_LIGHT ||
            status == THERMAL_STATUS_MODERATE

    /** Moderate heat permits only a reduced floor target; unknown/severe fails closed. */
    fun effectivePercent(requestedPercent: Int, thermalStatus: Int): Int? {
        if (requestedPercent !in MIN_PERCENT..MAX_PERCENT || !thermalAllowsBoost(thermalStatus)) {
            return null
        }
        return if (thermalStatus == THERMAL_STATUS_MODERATE) {
            minOf(requestedPercent, MODERATE_MAX_PERCENT)
        } else {
            requestedPercent
        }
    }

    const val MIN_PERCENT = 50
    const val MAX_PERCENT = 90
    const val THERMAL_STATUS_NONE = 0
    const val THERMAL_STATUS_LIGHT = 1
    const val THERMAL_STATUS_MODERATE = 2
    const val MODERATE_MAX_PERCENT = 70
}
