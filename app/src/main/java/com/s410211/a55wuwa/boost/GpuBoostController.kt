package com.s410211.a55wuwa.boost

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.s410211.a55wuwa.shell.ShizukuGateway
import com.s410211.a55wuwa.telemetry.Reading
import java.util.concurrent.atomic.AtomicBoolean

sealed interface GpuBoostOutcome {
    data class Applied(val plan: GpuBoostPlan, val effectivePercent: Int) : GpuBoostOutcome
    data class Restored(val changed: Boolean, val externalChange: Boolean) : GpuBoostOutcome
    data class Rejected(val reason: String) : GpuBoostOutcome
    data object NoChange : GpuBoostOutcome
}

/**
 * GPU boost is attempted only under an actual Shizuku Root UID 0 backend.
 * The SM-A5560 inventory shows gpu_min_clock owned by system:system, so
 * Shell UID 2000 is known not to have DAC write access.
 */
class GpuBoostController(
    context: Context,
    private val gateway: ShizukuGateway
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val operationLock = AtomicBoolean(false)

    fun hasPendingJournal(): Boolean = preferences.getBoolean(KEY_PRESENT, false)

    fun apply(percent: Int = DEFAULT_PERCENT): GpuBoostOutcome = exclusive {
        if (hasPendingJournal()) {
            return@exclusive GpuBoostOutcome.Rejected("GPU 已有待還原記錄，請先還原")
        }
        val identity = gateway.snapshot()
        if (!identity.userServiceConnected) {
            return@exclusive GpuBoostOutcome.Rejected("Shizuku UserService 尚未連線")
        }
        if (identity.uid == null) {
            return@exclusive GpuBoostOutcome.Rejected("尚未確認 Shizuku 遠端 UID，請稍後重試 GPU Boost")
        }
        if (identity.uid != 0) {
            return@exclusive GpuBoostOutcome.Rejected(
                "GPU 控制節點為 system:system；目前是 UID ${identity.uid}，不是 Root UID 0，拒絕寫入"
            )
        }

        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return@exclusive GpuBoostOutcome.Rejected("無法取得 Android Thermal 狀態")
        val effectivePercent = CpuBoostPolicy.effectivePercent(
            percent,
            powerManager.currentThermalStatus
        ) ?: return@exclusive GpuBoostOutcome.Rejected(
            "目前 Thermal 狀態或 Boost 百分比不允許 GPU Boost"
        )

        val snapshot = try {
            GpuPolicySnapshot(
                currentMinRaw = readLong(GPU_MIN_PATH),
                currentMaxRaw = readLong(GPU_MAX_PATH),
                availableOperatingPointsRaw = readText(GPU_TABLE_PATH)
                    .split(Regex("\\s+"))
                    .mapNotNull(String::toLongOrNull)
                    .filter { it > 0L }
                    .toSet()
            )
        } catch (error: BoostFailure) {
            return@exclusive GpuBoostOutcome.Rejected(error.message ?: "GPU 資料讀取失敗")
        }

        val plan = when (val result = GpuBoostPolicy.planAtPercent(snapshot, effectivePercent)) {
            is GpuBoostPlanResult.Ready -> result.plan
            is GpuBoostPlanResult.NoChange -> return@exclusive GpuBoostOutcome.NoChange
            is GpuBoostPlanResult.Rejected ->
                return@exclusive GpuBoostOutcome.Rejected(result.reason)
        }

        if (!saveJournal(plan)) {
            return@exclusive GpuBoostOutcome.Rejected("無法保存 GPU 還原記錄")
        }
        val failure = writeVerified(GPU_MIN_PATH, plan.targetMinRaw, plan.originalMinRaw)
        if (failure != null) {
            val current = runCatching { readLong(GPU_MIN_PATH) }.getOrNull()
            if (current == plan.targetMinRaw) {
                val rollbackFailure = writeVerified(GPU_MIN_PATH, plan.originalMinRaw, plan.targetMinRaw)
                if (rollbackFailure == null) clearJournal()
                else return@exclusive GpuBoostOutcome.Rejected(
                    "GPU 寫入後驗證失敗且回復失敗：$rollbackFailure；保留還原記錄"
                )
            } else if (current == plan.originalMinRaw) {
                clearJournal()
            }
            return@exclusive GpuBoostOutcome.Rejected("GPU 寫入／讀回驗證失敗：$failure")
        }
        GpuBoostOutcome.Applied(plan, effectivePercent)
    }

    fun restore(): GpuBoostOutcome = exclusive {
        if (!hasPendingJournal()) return@exclusive GpuBoostOutcome.NoChange
        val original = preferences.getLong(KEY_ORIGINAL, 0L)
        val target = preferences.getLong(KEY_TARGET, 0L)
        if (original <= 0L || target <= original) {
            return@exclusive GpuBoostOutcome.Rejected("GPU 還原記錄無效，拒絕猜測原值")
        }
        val current = try {
            readLong(GPU_MIN_PATH)
        } catch (error: BoostFailure) {
            return@exclusive GpuBoostOutcome.Rejected(
                "GPU 目前值無法讀取，保留還原記錄：${error.message}"
            )
        }
        when (current) {
            original -> {
                clearJournal()
                GpuBoostOutcome.Restored(changed = false, externalChange = false)
            }
            target -> {
                val failure = writeVerified(GPU_MIN_PATH, original, current)
                if (failure != null) {
                    GpuBoostOutcome.Rejected("GPU 還原失敗：$failure；保留還原記錄")
                } else {
                    clearJournal()
                    GpuBoostOutcome.Restored(changed = true, externalChange = false)
                }
            }
            else -> {
                clearJournal()
                GpuBoostOutcome.Restored(changed = false, externalChange = true)
            }
        }
    }

    private fun readLong(path: String): Long =
        readText(path).toLongOrNull() ?: throw BoostFailure("$path 回傳非整數")

    private fun readText(path: String): String {
        val reading = gateway.readNode(path, SystemClock.elapsedRealtime())
            ?: throw BoostFailure("Shizuku UserService 未連線")
        return when (reading) {
            is Reading.Value -> reading.value.trim()
            is Reading.PermissionDenied -> throw BoostFailure("權限拒絕：${reading.reason}")
            is Reading.Unsupported -> throw BoostFailure("不支援：${reading.reason}")
            is Reading.NotFound -> throw BoostFailure("節點不存在：${reading.reason}")
            is Reading.InvalidData -> throw BoostFailure("資料無效：${reading.reason}")
            is Reading.TemporarilyUnavailable -> throw BoostFailure("暫時不可用：${reading.reason}")
            is Reading.Error -> throw BoostFailure("讀取錯誤：${reading.reason}")
            is Reading.AwaitingSample -> throw BoostFailure("等待樣本：${reading.reason}")
        }
    }

    private fun writeVerified(path: String, value: Long, expectedCurrent: Long): String? {
        val reading = gateway.writeNode(path, value.toString(), expectedCurrent.toString(), SystemClock.elapsedRealtime())
            ?: return "Shizuku UserService 未連線"
        return when (reading) {
            is Reading.Value -> if (reading.value.trim().toLongOrNull() == value) null
                else "讀回值不符：${reading.value}"
            is Reading.PermissionDenied -> "權限拒絕：${reading.reason}"
            is Reading.Unsupported -> "不支援：${reading.reason}"
            is Reading.NotFound -> "節點不存在：${reading.reason}"
            is Reading.InvalidData -> "資料無效：${reading.reason}"
            is Reading.TemporarilyUnavailable -> "暫時不可用：${reading.reason}"
            is Reading.Error -> "寫入錯誤：${reading.reason}"
            is Reading.AwaitingSample -> "等待樣本：${reading.reason}"
        }
    }

    private fun saveJournal(plan: GpuBoostPlan): Boolean =
        preferences.edit()
            .putBoolean(KEY_PRESENT, true)
            .putLong(KEY_ORIGINAL, plan.originalMinRaw)
            .putLong(KEY_TARGET, plan.targetMinRaw)
            .commit()

    private fun clearJournal() {
        preferences.edit().clear().commit()
    }

    private fun exclusive(block: () -> GpuBoostOutcome): GpuBoostOutcome {
        if (!operationLock.compareAndSet(false, true)) {
            return GpuBoostOutcome.Rejected("另一個 GPU Boost／還原操作正在執行")
        }
        return try {
            block()
        } finally {
            operationLock.set(false)
        }
    }

    private class BoostFailure(message: String) : Exception(message)

    private companion object {
        const val PREFERENCES = "gpu_boost_restore_journal"
        const val KEY_PRESENT = "journal_present"
        const val KEY_ORIGINAL = "original_min"
        const val KEY_TARGET = "target_min"
        const val DEFAULT_PERCENT = 80
        const val GPU_MIN_PATH = "/sys/kernel/gpu/gpu_min_clock"
        const val GPU_MAX_PATH = "/sys/kernel/gpu/gpu_max_clock"
        const val GPU_TABLE_PATH = "/sys/kernel/gpu/gpu_freq_table"
    }
}
