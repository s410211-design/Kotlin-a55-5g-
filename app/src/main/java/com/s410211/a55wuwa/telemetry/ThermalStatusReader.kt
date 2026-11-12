package com.s410211.a55wuwa.telemetry

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock

data class ThermalStatusSnapshot(
    val status: Int,
    val statusLabel: String,
    val headroom: Float?,
    val headroomNote: String?
)

/**
 * Reads the public Android thermal status/headroom APIs.
 *
 * These APIs do not expose AP/GPU/BAT/PA/SKIN sensor temperatures. Per-sensor
 * temperatures from dumpsys thermalservice require a separate authorized
 * shell transport and are intentionally not faked here.
 */
object ThermalStatusReader {
    fun read(
        context: Context,
        sampledAtMillis: Long = SystemClock.elapsedRealtime()
    ): Reading<ThermalStatusSnapshot> {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return Reading.Unsupported(
                "PowerManager",
                sampledAtMillis,
                "Android PowerManager service is unavailable"
            )

        return try {
            val status = powerManager.currentThermalStatus
            val headroomResult = try {
                val value = powerManager.getThermalHeadroom(0)
                if (value.isFinite() && value >= 0f) {
                    value to null
                } else {
                    null to "系統未提供有效的 thermal headroom"
                }
            } catch (_: UnsupportedOperationException) {
                null to "裝置未提供 thermal headroom"
            } catch (_: IllegalStateException) {
                null to "Thermal service 尚未就緒"
            }

            Reading.Value(
                ThermalStatusSnapshot(
                    status = status,
                    statusLabel = statusLabel(status),
                    headroom = headroomResult.first,
                    headroomNote = headroomResult.second
                ),
                "PowerManager.getCurrentThermalStatus/getThermalHeadroom",
                "status/headroom",
                sampledAtMillis
            )
        } catch (_: SecurityException) {
            Reading.PermissionDenied(
                "PowerManager thermal APIs",
                sampledAtMillis,
                "系統拒絕讀取 Thermal API"
            )
        } catch (error: Exception) {
            Reading.Error(
                "PowerManager thermal APIs",
                sampledAtMillis,
                error.javaClass.simpleName
            )
        }
    }

    fun statusLabel(status: Int): String = when (status) {
        0 -> "NONE（正常）"
        1 -> "LIGHT（輕度）"
        2 -> "MODERATE（中度）"
        3 -> "SEVERE（嚴重）"
        4 -> "CRITICAL（危急）"
        5 -> "EMERGENCY（緊急）"
        6 -> "SHUTDOWN（系統關機等級）"
        else -> "UNKNOWN（$status）"
    }
}
