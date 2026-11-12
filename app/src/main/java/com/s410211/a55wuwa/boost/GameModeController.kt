package com.s410211.a55wuwa.boost

import android.content.Context
import android.os.SystemClock
import com.s410211.a55wuwa.shell.ShizukuGateway
import com.s410211.a55wuwa.telemetry.Reading
import java.util.concurrent.atomic.AtomicBoolean

sealed interface GameModeOutcome {
    data class Applied(val packageName: String, val originalMode: String) : GameModeOutcome
    data class Restored(val originalMode: String, val externalChange: Boolean) : GameModeOutcome
    data class Rejected(val reason: String) : GameModeOutcome
    data object NoChange : GameModeOutcome
}

/** Requests OEM-mediated GameManager PERFORMANCE mode; never disables thermal protections. */
class GameModeController(context: Context, private val gateway: ShizukuGateway) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val operationLock = AtomicBoolean(false)
    @Volatile private var lastReadError: String = "尚未讀取"

    fun hasPendingJournal(): Boolean = preferences.getBoolean(KEY_PRESENT, false)

    fun apply(packageName: String): GameModeOutcome = exclusive {
        if (hasPendingJournal()) return@exclusive GameModeOutcome.Rejected("已有待還原的 GameManager 模式記錄")
        if (!isValidPackageName(packageName)) return@exclusive GameModeOutcome.Rejected("鳴潮套件名稱無效")
        val before = readMode(packageName) ?: return@exclusive GameModeOutcome.Rejected(lastReadError)
        if ("performance" !in before.second) {
            return@exclusive GameModeOutcome.Rejected(
                "此裝置未向鳴潮提供 Performance Mode（目前：\${before.first}；可用：\${before.second.joinToString()}）"
            )
        }
        if (before.first == "performance") return@exclusive GameModeOutcome.NoChange
        if (!preferences.edit().putBoolean(KEY_PRESENT, true)
                .putString(KEY_PACKAGE, packageName).putString(KEY_ORIGINAL, before.first).commit()) {
            return@exclusive GameModeOutcome.Rejected("無法保存原始 GameManager 模式")
        }
        val applied = gateway.setGameMode(packageName, "performance", SystemClock.elapsedRealtime())
        if (applied !is Reading.Value || applied.value.trim() != "performance") {
            val afterFailure = readMode(packageName)
            if (afterFailure?.first == before.first) clearJournal()
            return@exclusive GameModeOutcome.Rejected("GameManager Performance Mode 套用失敗：" + readingReason(applied))
        }
        if (readMode(packageName)?.first != "performance") {
            return@exclusive GameModeOutcome.Rejected("GameManager 模式讀回驗證失敗；保留還原記錄")
        }
        GameModeOutcome.Applied(packageName, before.first)
    }

    fun restore(): GameModeOutcome = exclusive {
        if (!hasPendingJournal()) return@exclusive GameModeOutcome.NoChange
        val packageName = preferences.getString(KEY_PACKAGE, null)
            ?: return@exclusive GameModeOutcome.Rejected("GameManager 還原記錄缺少套件名稱")
        val original = preferences.getString(KEY_ORIGINAL, null)
            ?: return@exclusive GameModeOutcome.Rejected("GameManager 還原記錄缺少原始模式")
        if (original !in SUPPORTED_MODES) return@exclusive GameModeOutcome.Rejected("GameManager 原始模式記錄無效")
        val current = readMode(packageName)
            ?: return@exclusive GameModeOutcome.Rejected("無法讀取目前 GameManager 模式：$lastReadError")
        when (current.first) {
            original -> {
                clearJournal()
                GameModeOutcome.Restored(original, false)
            }
            "performance" -> {
                if (original !in current.second) return@exclusive GameModeOutcome.Rejected("原始模式已不再受支援，保留還原記錄")
                val restored = gateway.setGameMode(packageName, original, SystemClock.elapsedRealtime())
                if (restored !is Reading.Value || restored.value.trim() != original) {
                    return@exclusive GameModeOutcome.Rejected("還原 GameManager 模式失敗：" + readingReason(restored))
                }
                if (readMode(packageName)?.first != original) {
                    return@exclusive GameModeOutcome.Rejected("GameManager 還原讀回不符，保留記錄")
                }
                clearJournal()
                GameModeOutcome.Restored(original, false)
            }
            else -> {
                clearJournal()
                GameModeOutcome.Restored(original, true)
            }
        }
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
        if (current !in SUPPORTED_MODES || available.isEmpty()) {
            lastReadError = "GameManager 回傳的模式資料格式無法辨識"
            return null
        }
        return current to available
    }

    private fun readingReason(reading: Reading<String>?): String = when (reading) {
        is Reading.PermissionDenied -> "權限拒絕：\${reading.reason}"
        is Reading.Unsupported -> "不支援：\${reading.reason}"
        is Reading.InvalidData -> "資料無效：\${reading.reason}"
        is Reading.TemporarilyUnavailable -> "暫時不可用：\${reading.reason}"
        is Reading.Error -> reading.reason
        is Reading.NotFound -> reading.reason
        is Reading.AwaitingSample -> reading.reason
        is Reading.Value -> reading.value
        null -> "Shizuku UserService 未連線"
    }

    private fun isValidPackageName(value: String): Boolean =
        value.length in 3..255 && Regex("^[A-Za-z0-9_]+(?:\\\\.[A-Za-z0-9_]+)+$").matches(value)

    private fun clearJournal() { preferences.edit().clear().commit() }

    private fun exclusive(block: () -> GameModeOutcome): GameModeOutcome {
        if (!operationLock.compareAndSet(false, true)) return GameModeOutcome.Rejected("另一個 GameManager 操作正在執行")
        return try { block() } finally { operationLock.set(false) }
    }

    private companion object {
        const val PREFERENCES = "game_mode_restore_journal"
        const val KEY_PRESENT = "journal_present"
        const val KEY_PACKAGE = "package_name"
        const val KEY_ORIGINAL = "original_mode"
        val SUPPORTED_MODES = setOf("standard", "performance", "battery", "custom")
    }
}
