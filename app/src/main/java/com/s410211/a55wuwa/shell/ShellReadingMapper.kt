package com.s410211.a55wuwa.shell

import com.s410211.a55wuwa.telemetry.Reading

/**
 * Converts process outcomes to the shared telemetry state model.
 *
 * A non-zero exit is intentionally not classified as missing-node or
 * permission-denied from localized stderr text. Only a trusted launch-time
 * SecurityException and explicit Shizuku permission status can assert denial.
 */
object ShellReadingMapper {
    fun toReading(
        result: ShellExecutionResult,
        source: String,
        sampledAtMillis: Long
    ): Reading<String> = when (result.state) {
        ShellExecutionState.SUCCESS -> {
            val output = result.stdout.trim()
            if (output.isEmpty()) {
                Reading.InvalidData(source, sampledAtMillis, "命令成功但沒有回傳資料")
            } else {
                Reading.Value(output, source, "raw", sampledAtMillis)
            }
        }
        ShellExecutionState.NON_ZERO_EXIT -> Reading.Error(
            source,
            sampledAtMillis,
            "唯讀命令結束碼為 ${result.exitCode ?: "未知"}；無法僅憑 stderr 判定節點不存在或權限拒絕"
        )
        ShellExecutionState.TIMED_OUT -> Reading.TemporarilyUnavailable(
            source,
            sampledAtMillis,
            "唯讀命令逾時"
        )
        ShellExecutionState.CANCELLED -> Reading.TemporarilyUnavailable(
            source,
            sampledAtMillis,
            "唯讀命令已取消"
        )
        ShellExecutionState.PERMISSION_DENIED -> Reading.PermissionDenied(
            source,
            sampledAtMillis,
            "建立受限程序時遭系統安全政策拒絕"
        )
        ShellExecutionState.OUTPUT_TRUNCATED -> Reading.InvalidData(
            source,
            sampledAtMillis,
            "命令輸出超過安全長度上限"
        )
        ShellExecutionState.ERROR -> Reading.Error(
            source,
            sampledAtMillis,
            result.detail?.take(160) ?: "唯讀命令執行失敗"
        )
    }
}
