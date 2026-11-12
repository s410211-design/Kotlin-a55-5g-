package com.s410211.a55wuwa.boost

import android.content.Context
import android.os.SystemClock
import com.s410211.a55wuwa.shell.ShizukuGateway
import com.s410211.a55wuwa.telemetry.Reading
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

sealed interface GameModeOutcome {
    data class Applied(
        val packageName: String,
        val originalMode: String,
        val targetMode: String,
        val downscalePercent: Int
    ) : GameModeOutcome
    data class Restored(val originalMode: String, val externalChange: Boolean) : GameModeOutcome
    data class Rejected(val reason: String) : GameModeOutcome
    data object NoChange : GameModeOutcome
}

/**
 * Applies an OEM-mediated Performance or Custom mode and Android WindowManager backbuffer
 * downscale. Existing GameManager intervention configurations are never overwritten.
 */
class GameModeController(context: Context, private val gateway: ShizukuGateway) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val operationLock = AtomicBoolean(false)
    @Volatile private var lastReadError: String = "尚未讀取"

    fun hasPendingJournal(): Boolean = preferences.getBoolean(KEY_PRESENT, false)

    fun apply(packageName: String, downscalePercent: Int = DEFAULT_DOWNSCALE_PERCENT): GameModeOutcome = exclusive {
        if (hasPendingJournal()) return@exclusive GameModeOutcome.Rejected("已有待還原的 GameManager 記錄")
        if (!isValidPackageName(packageName)) return@exclusive GameModeOutcome.Rejected("鳴潮套件名稱無效")
        if (downscalePercent !in SUPPORTED_DOWNSCALE_PERCENT) {
            return@exclusive GameModeOutcome.Rejected("渲染比例不在允許範圍")
        }

        val before = readMode(packageName) ?: return@exclusive GameModeOutcome.Rejected(lastReadError)
        val targetMode = if ("performance" in before.second) "performance" else "custom"

        val originalConfig = readConfig(packageName)
            ?: return@exclusive GameModeOutcome.Rejected("無法讀取既有 GameManager 介入設定：$lastReadError")
        if (!isEmptyInterventionConfig(originalConfig)) {
            return@exclusive GameModeOutcome.Rejected(
                "偵測到既有 GameManager 介入設定，為避免覆蓋原廠或其他程式的設定，本次不修改。現況：${originalConfig.take(160)}"
            )
        }

        val scale = formatScale(downscalePercent)
        if (!preferences.edit()
                .putBoolean(KEY_PRESENT, true)
                .putString(KEY_PACKAGE, packageName)
                .putString(KEY_ORIGINAL_MODE, before.first)
                .putString(KEY_TARGET_MODE, targetMode)
                .putString(KEY_ORIGINAL_CONFIG, originalConfig)
                .putString(KEY_DOWNSCALE, scale)
                .putString(KEY_APPLIED_CONFIG, "")
                .commit()
        ) {
            return@exclusive GameModeOutcome.Rejected("無法保存 GameManager 原始狀態")
        }

        val downscale = gateway.setGameModeDownscale(
            packageName, targetMode, scale, SystemClock.elapsedRealtime()
        )
        if (downscale !is Reading.Value) {
            val afterFailure = readConfig(packageName)
            if (afterFailure == originalConfig) clearJournal()
            return@exclusive GameModeOutcome.Rejected(
                "GPU 渲染降採樣指令失敗：" + readingReason(downscale)
            )
        }

        val appliedConfig = downscale.value.trim()
        if (appliedConfig.isBlank()) {
            return@exclusive GameModeOutcome.Rejected(
                "GameManager 沒有回傳介入設定快照；保留還原記錄"
            )
        }
        // Save exact read-back before validating interpretation. This preserves
        // a safe rollback path even if a vendor formats the output unexpectedly.
        if (!preferences.edit().putString(KEY_APPLIED_CONFIG, appliedConfig).commit()) {
            return@exclusive GameModeOutcome.Rejected(
                "渲染設定已送出，但無法保存讀回快照；保留還原記錄"
            )
        }
        if (!hasDownscale(appliedConfig, scale)) {
            return@exclusive GameModeOutcome.Rejected(
                "GameManager 讀回設定未包含目標比例 $scale；保留還原記錄"
            )
        }
        val verifiedConfig = readConfig(packageName)
            ?: return@exclusive GameModeOutcome.Rejected(
                "渲染比例已讀回，但第二次讀取失敗；保留還原記錄"
            )
        if (verifiedConfig != appliedConfig) {
            return@exclusive GameModeOutcome.Rejected(
                "GameManager 設定在驗證期間發生變化；不覆寫外部變更"
            )
        }

        if (before.first != targetMode) {
            val changed = gateway.setGameMode(
                packageName, targetMode, SystemClock.elapsedRealtime()
            )
            if (changed !is Reading.Value || changed.value.trim() != targetMode) {
                val rolledBack = restoreOwnedConfig(packageName)
                if (rolledBack) clearJournal()
                return@exclusive GameModeOutcome.Rejected(
                    "渲染比例已驗證，但切換 Performance 失敗：" +
                        readingReason(changed) +
                        if (rolledBack) "；已自動撤銷渲染修改" else "；保留還原記錄"
                )
            }
        }

        if (readMode(packageName)?.first != targetMode) {
            return@exclusive GameModeOutcome.Rejected("GameManager 模式讀回驗證失敗；保留還原記錄")
        }

        GameModeOutcome.Applied(packageName, before.first, targetMode, downscalePercent)
    }

    fun restore(): GameModeOutcome = exclusive {
        if (!hasPendingJournal()) return@exclusive GameModeOutcome.NoChange
        val packageName = preferences.getString(KEY_PACKAGE, null)
            ?: return@exclusive GameModeOutcome.Rejected("GameManager 還原記錄缺少套件名稱")
        val originalMode = preferences.getString(KEY_ORIGINAL_MODE, null)
            ?: return@exclusive GameModeOutcome.Rejected("GameManager 還原記錄缺少原始模式")
        if (originalMode !in SUPPORTED_MODES) {
            return@exclusive GameModeOutcome.Rejected("GameManager 原始模式記錄無效")
        }

        val currentConfig = readConfig(packageName)
            ?: return@exclusive GameModeOutcome.Rejected("無法讀取目前渲染設定：$lastReadError")
        val originalConfig = preferences.getString(KEY_ORIGINAL_CONFIG, null)
            ?: return@exclusive GameModeOutcome.Rejected("缺少原始 GameManager 介入設定")
        var externalChange = false

        when {
            currentConfig == originalConfig -> Unit
            isOwnedAppliedConfig(currentConfig) -> {
                if (!restoreOwnedConfig(packageName)) {
                    return@exclusive GameModeOutcome.Rejected(
                        "GameManager 渲染設定還原失敗或讀回不符；保留還原記錄"
                    )
                }
            }
            else -> externalChange = true
        }

        val targetMode = preferences.getString(KEY_TARGET_MODE, null)
            ?: return@exclusive GameModeOutcome.Rejected("GameManager 還原記錄缺少目標模式")
        val currentMode = readMode(packageName)
            ?: return@exclusive GameModeOutcome.Rejected("無法讀取目前 GameManager 模式：$lastReadError")
        when {
            currentMode.first == originalMode -> Unit
            currentMode.first == targetMode -> {
                if (originalMode !in setOf("standard", "custom") && originalMode !in currentMode.second) {
                    return@exclusive GameModeOutcome.Rejected("原始模式已不再受支援，保留還原記錄")
                }
                val restored = gateway.setGameMode(
                    packageName, originalMode, SystemClock.elapsedRealtime()
                )
                if (restored !is Reading.Value || restored.value.trim() != originalMode) {
                    return@exclusive GameModeOutcome.Rejected(
                        "還原 GameManager 模式失敗：" + readingReason(restored)
                    )
                }
                if (readMode(packageName)?.first != originalMode) {
                    return@exclusive GameModeOutcome.Rejected("GameManager 模式還原讀回不符，保留記錄")
                }
            }
            else -> externalChange = true
        }

        clearJournal()
        GameModeOutcome.Restored(originalMode, externalChange)
    }

    private fun restoreOwnedConfig(packageName: String): Boolean {
        val current = readConfig(packageName) ?: return false
        val original = preferences.getString(KEY_ORIGINAL_CONFIG, null) ?: return false
        if (current == original) return true
        if (!isOwnedAppliedConfig(current)) return false

        val reset = gateway.resetGameModeConfig(
            packageName, preferences.getString(KEY_TARGET_MODE, "performance") ?: "performance",
            SystemClock.elapsedRealtime()
        )
        if (reset !is Reading.Value) {
            lastReadError = readingReason(reset)
            return false
        }
        val after = readConfig(packageName) ?: return false
        return after == original
    }

    private fun isOwnedAppliedConfig(config: String): Boolean {
        // Exact string equality prevents a reset from deleting later external
        // intervention changes that happen to retain the same downscale ratio.
        val expected = preferences.getString(KEY_APPLIED_CONFIG, "").orEmpty()
        return expected.isNotBlank() && config == expected
    }

    private fun readMode(packageName: String): Pair<String, Set<String>>? {
        val result = gateway.getGameMode(packageName, SystemClock.elapsedRealtime())
        if (result !is Reading.Value) {
            lastReadError = readingReason(result)
            return null
        }
        val parts = result.value.split("|", limit = 2)
        val current = parts.getOrNull(0)?.trim().orEmpty()
        val available = parts.getOrNull(1).orEmpty().split(",")
            .map { it.trim() }.filter { it in SUPPORTED_MODES }.toSet()
        if (current !in SUPPORTED_MODES) {
            lastReadError = "GameManager 回傳的模式資料格式無法辨識"
            return null
        }
        return current to available
    }

    private fun readConfig(packageName: String): String? {
        val result = gateway.getGameModeConfig(packageName, SystemClock.elapsedRealtime())
        if (result !is Reading.Value) {
            lastReadError = readingReason(result)
            return null
        }
        return result.value.trim()
    }

    private fun isEmptyInterventionConfig(value: String): Boolean =
        value.isBlank() || value.contains("No interventions found", ignoreCase = true)

    private fun hasDownscale(config: String, expected: String): Boolean {
        val actual = Regex(
            """downscal(?:e|ing)[^0-9]*([0-9]+(?:\.[0-9]+)?)""",
            RegexOption.IGNORE_CASE
        ).find(config)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: return false
        return kotlin.math.abs(actual - expected.toFloat()) < 0.001f
    }

    private fun formatScale(percent: Int): String =
        String.format(Locale.US, "%.2f", percent / 100.0)
            .trimEnd('0').trimEnd('.')

    private fun readingReason(reading: Reading<String>?): String = when (reading) {
        is Reading.PermissionDenied -> "權限拒絕：${reading.reason}"
        is Reading.Unsupported -> "不支援：${reading.reason}"
        is Reading.InvalidData -> "資料無效：${reading.reason}"
        is Reading.TemporarilyUnavailable -> "暫時不可用：${reading.reason}"
        is Reading.Error -> reading.reason
        is Reading.NotFound -> reading.reason
        is Reading.AwaitingSample -> reading.reason
        is Reading.Value -> reading.value
        null -> "Shizuku UserService 未連線"
    }

    private fun isValidPackageName(value: String): Boolean =
        value.length in 3..255 && Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$").matches(value)

    private fun clearJournal() { preferences.edit().clear().commit() }

    private fun exclusive(block: () -> GameModeOutcome): GameModeOutcome {
        if (!operationLock.compareAndSet(false, true)) {
            return GameModeOutcome.Rejected("另一個 GameManager 操作正在執行")
        }
        return try {
            block()
        } finally {
            operationLock.set(false)
        }
    }

    private companion object {
        const val PREFERENCES = "game_mode_restore_journal"
        const val KEY_PRESENT = "journal_present"
        const val KEY_PACKAGE = "package_name"
        const val KEY_ORIGINAL_MODE = "original_mode"
        const val KEY_TARGET_MODE = "target_mode"
        const val KEY_ORIGINAL_CONFIG = "original_config"
        const val KEY_APPLIED_CONFIG = "applied_config"
        const val KEY_DOWNSCALE = "downscale_ratio"
        const val DEFAULT_DOWNSCALE_PERCENT = 85
        val SUPPORTED_DOWNSCALE_PERCENT = setOf(70, 75, 80, 85, 90)
        val SUPPORTED_MODES = setOf("standard", "performance", "battery", "custom")
    }
}
