package com.s410211.a55wuwa.boost

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.s410211.a55wuwa.shell.ShizukuGateway
import com.s410211.a55wuwa.telemetry.Reading
import java.util.concurrent.atomic.AtomicBoolean

sealed interface CpuBoostOutcome {
    data class Applied(val plans: List<CpuBoostPlan>, val effectivePercent: Int) : CpuBoostOutcome
    data class Restored(val restoredPolicies: Int, val externalChanges: List<String>) : CpuBoostOutcome
    data class Rejected(val reason: String) : CpuBoostOutcome
    data object NoChange : CpuBoostOutcome
}

/**
 * Executes CPUFreq minimum-floor Boost through the restricted Shizuku service.
 *
 * It never changes scaling_max_freq, GPU controls, voltage, or thermal policy.
 * A durable journal is committed before the first write so a later app launch
 * can conditionally restore an interrupted operation.
 *
 * All public methods are synchronous and MUST be called from a worker thread.
 */
class CpuBoostController(
    context: Context,
    private val gateway: ShizukuGateway
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val operationLock = AtomicBoolean(false)

    fun hasPendingJournal(): Boolean = preferences.getBoolean(KEY_JOURNAL_PRESENT, false)

    fun apply(percent: Int = DEFAULT_PERCENT): CpuBoostOutcome = withExclusiveOperation {
        if (hasPendingJournal()) {
            return@withExclusiveOperation CpuBoostOutcome.Rejected(
                "偵測到上次 Boost 記錄；請先執行停止／還原"
            )
        }

        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return@withExclusiveOperation CpuBoostOutcome.Rejected("無法取得 Android PowerManager")
        val thermalStatus = powerManager.currentThermalStatus
        val effectivePercent = CpuBoostPolicy.effectivePercent(percent, thermalStatus)
            ?: return@withExclusiveOperation CpuBoostOutcome.Rejected(
                "目前熱狀態為 ${thermalStatusLabel(thermalStatus)} 或 Boost 百分比無效；拒絕啟動"
            )

        val plans = mutableListOf<CpuBoostPlan>()
        for (policy in POLICIES) {
            val snapshot = try {
                readSnapshot(policy)
            } catch (error: BoostFailure) {
                return@withExclusiveOperation CpuBoostOutcome.Rejected(error.message ?: "CPU policy 讀取失敗")
            }
            when (val result = CpuBoostPolicy.planAtPercent(snapshot, effectivePercent)) {
                is CpuBoostPlanResult.Ready -> plans += result.plan
                is CpuBoostPlanResult.NoChange -> Unit
                is CpuBoostPlanResult.Rejected ->
                    return@withExclusiveOperation CpuBoostOutcome.Rejected(
                        "${policy.id}：${result.reason}"
                    )
            }
        }

        if (plans.isEmpty()) return@withExclusiveOperation CpuBoostOutcome.NoChange
        if (!saveJournal(plans)) {
            return@withExclusiveOperation CpuBoostOutcome.Rejected("無法安全保存 Boost 還原記錄")
        }

        val applied = mutableListOf<CpuBoostPlan>()
        for (plan in plans) {
            val result = writeVerified(pathFor(plan.policyId), plan.targetMinKHz, plan.originalMinKHz)
            if (result != null) {
                val rollbackErrors = rollback(applied)
                if (rollbackErrors.isEmpty()) clearJournal()
                return@withExclusiveOperation CpuBoostOutcome.Rejected(
                    "${plan.policyId} 寫入／讀回驗證失敗：$result" +
                        if (rollbackErrors.isEmpty()) "；已回復先前套用項目" else
                            "；部分回復失敗，還原記錄已保留：${rollbackErrors.joinToString()}"
                )
            }
            applied += plan
        }

        CpuBoostOutcome.Applied(plans, effectivePercent)
    }

    fun restore(): CpuBoostOutcome = withExclusiveOperation {
        if (!hasPendingJournal()) return@withExclusiveOperation CpuBoostOutcome.NoChange
        val plans = loadJournal()
            ?: return@withExclusiveOperation CpuBoostOutcome.Rejected("Boost 還原記錄損毀，拒絕猜測原始值")

        var restored = 0
        val conflicts = mutableListOf<String>()
        for (plan in plans) {
            val current = try {
                readLong(pathFor(plan.policyId))
            } catch (error: BoostFailure) {
                return@withExclusiveOperation CpuBoostOutcome.Rejected(
                    "無法讀取 ${plan.policyId} 目前值：${error.message}"
                )
            }

            when (CpuBoostPolicy.decideRestore(
                plan.originalMinKHz,
                plan.targetMinKHz,
                current
            )) {
                RestoreDecision.ALREADY_RESTORED -> Unit
                RestoreDecision.EXTERNAL_CHANGE -> conflicts += plan.policyId
                RestoreDecision.INVALID_JOURNAL ->
                    return@withExclusiveOperation CpuBoostOutcome.Rejected(
                        "${plan.policyId} 還原記錄無效，拒絕寫入"
                    )
                RestoreDecision.RESTORE_ORIGINAL -> {
                    val failure = writeVerified(pathFor(plan.policyId), plan.originalMinKHz, plan.targetMinKHz)
                    if (failure != null) {
                        return@withExclusiveOperation CpuBoostOutcome.Rejected(
                            "${plan.policyId} 還原失敗：$failure；保留還原記錄"
                        )
                    }
                    restored++
                }
            }
        }

        clearJournal()
        CpuBoostOutcome.Restored(restored, conflicts)
    }

    private fun readSnapshot(policy: CpuPolicyConfig): CpuPolicySnapshot {
        val currentMin = readLong(policy.minPath)
        val currentMax = readLong(policy.maxPath)
        val availableText = readText(policy.availablePath)
        val available = availableText.split(Regex("\\s+"))
            .mapNotNull(String::toLongOrNull)
            .filter { it > 0L }
            .toSet()
        return CpuPolicySnapshot(
            policyId = policy.id,
            currentMinKHz = currentMin,
            currentMaxKHz = currentMax,
            availableFrequenciesKHz = available
        )
    }

    private fun readLong(path: String): Long =
        readText(path).toLongOrNull()
            ?: throw BoostFailure("$path 回傳非整數資料")

    private fun readText(path: String): String {
        val result = gateway.readNode(path, SystemClock.elapsedRealtime())
            ?: throw BoostFailure("Shizuku UserService 尚未連線")
        return when (result) {
            is Reading.Value -> result.value.trim()
            is Reading.PermissionDenied -> throw BoostFailure("權限拒絕：${result.reason}")
            is Reading.Unsupported -> throw BoostFailure("不支援：${result.reason}")
            is Reading.NotFound -> throw BoostFailure("節點不存在：${result.reason}")
            is Reading.InvalidData -> throw BoostFailure("資料無效：${result.reason}")
            is Reading.TemporarilyUnavailable -> throw BoostFailure("暫時不可用：${result.reason}")
            is Reading.Error -> throw BoostFailure("讀取錯誤：${result.reason}")
            is Reading.AwaitingSample -> throw BoostFailure("等待樣本：${result.reason}")
        }
    }

    /** Returns null only when the remote write and read-back both match. */
    private fun writeVerified(path: String, value: Long, expectedCurrent: Long): String? {
        val result = gateway.writeNode(path, value.toString(), expectedCurrent.toString(), SystemClock.elapsedRealtime())
            ?: return "Shizuku UserService 尚未連線"
        return when (result) {
            is Reading.Value -> if (result.value.trim().toLongOrNull() == value) null
                else "讀回值不符：${result.value}"
            is Reading.PermissionDenied -> "權限拒絕：${result.reason}"
            is Reading.Unsupported -> "不支援：${result.reason}"
            is Reading.NotFound -> "節點不存在：${result.reason}"
            is Reading.InvalidData -> "資料無效：${result.reason}"
            is Reading.TemporarilyUnavailable -> "暫時不可用：${result.reason}"
            is Reading.Error -> "寫入錯誤：${result.reason}"
            is Reading.AwaitingSample -> "等待樣本：${result.reason}"
        }
    }

    private fun rollback(plans: List<CpuBoostPlan>): List<String> {
        val failures = mutableListOf<String>()
        for (plan in plans.asReversed()) {
            try {
                val current = readLong(pathFor(plan.policyId))
                if (current == plan.targetMinKHz) {
                    val failure = writeVerified(pathFor(plan.policyId), plan.originalMinKHz, plan.targetMinKHz)
                    if (failure != null) failures += "${plan.policyId}: $failure"
                } else if (current != plan.originalMinKHz) {
                    failures += "${plan.policyId}: 偵測到外部變更，未覆寫"
                }
            } catch (error: BoostFailure) {
                failures += "${plan.policyId}: ${error.message}"
            }
        }
        return failures
    }

    private fun saveJournal(plans: List<CpuBoostPlan>): Boolean {
        val editor = preferences.edit()
            .putBoolean(KEY_JOURNAL_PRESENT, true)
            .putInt(KEY_JOURNAL_VERSION, JOURNAL_VERSION)
            .putInt(KEY_PLAN_COUNT, plans.size)
        plans.forEachIndexed { index, plan ->
            editor.putString("policy_$index", plan.policyId)
                .putLong("original_$index", plan.originalMinKHz)
                .putLong("target_$index", plan.targetMinKHz)
        }
        return editor.commit()
    }

    private fun loadJournal(): List<CpuBoostPlan>? {
        if (preferences.getInt(KEY_JOURNAL_VERSION, -1) != JOURNAL_VERSION) return null
        val count = preferences.getInt(KEY_PLAN_COUNT, 0)
        if (count !in 1..POLICIES.size) return null
        val result = mutableListOf<CpuBoostPlan>()
        repeat(count) { index ->
            val id = preferences.getString("policy_$index", null) ?: return null
            val original = preferences.getLong("original_$index", 0L)
            val target = preferences.getLong("target_$index", 0L)
            POLICIES.firstOrNull { it.id == id } ?: return null
            if (original <= 0L || target <= original) return null
            result += CpuBoostPlan(id, original, target, maxLimitKHz = 0L)
        }
        return result
    }

    private fun clearJournal() {
        preferences.edit().clear().commit()
    }

    private fun withExclusiveOperation(block: () -> CpuBoostOutcome): CpuBoostOutcome {
        if (!operationLock.compareAndSet(false, true)) {
            return CpuBoostOutcome.Rejected("另一個 Boost／還原操作正在執行")
        }
        return try {
            block()
        } finally {
            operationLock.set(false)
        }
    }

    private fun thermalStatusLabel(status: Int): String = when (status) {
        0 -> "NONE"
        1 -> "LIGHT"
        2 -> "MODERATE"
        3 -> "SEVERE"
        4 -> "CRITICAL"
        5 -> "EMERGENCY"
        6 -> "SHUTDOWN"
        else -> "UNKNOWN($status)"
    }

    private data class CpuPolicyConfig(
        val id: String,
        val minPath: String,
        val maxPath: String,
        val availablePath: String
    )

    private class BoostFailure(message: String) : Exception(message)

    private companion object {
        const val PREFERENCES = "cpu_boost_restore_journal"
        const val KEY_JOURNAL_PRESENT = "journal_present"
        const val KEY_JOURNAL_VERSION = "journal_version"
        const val KEY_PLAN_COUNT = "plan_count"
        const val JOURNAL_VERSION = 1
        const val DEFAULT_PERCENT = 80

        val POLICIES = listOf(
            CpuPolicyConfig(
                "policy0",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies"
            ),
            CpuPolicyConfig(
                "policy4",
                "/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq",
                "/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq",
                "/sys/devices/system/cpu/cpufreq/policy4/scaling_available_frequencies"
            )
        )

        fun pathFor(policyId: String): String =
            POLICIES.firstOrNull { it.id == policyId }?.minPath
                ?: throw IllegalArgumentException("Unknown CPU policy")
    }
}
