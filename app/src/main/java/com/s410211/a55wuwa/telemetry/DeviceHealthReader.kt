package com.s410211.a55wuwa.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

data class DeviceHealthSnapshot(
    val batteryLevelPercent: Int?,
    val batteryTemperatureCelsius: Double?,
    val batteryVoltageMillivolts: Int?,
    val charging: Boolean?,
    val networkTransport: String?,
    val networkValidated: Boolean?
)

/** Reads public Android battery and connectivity state without shell access. */
object DeviceHealthReader {
    fun read(context: Context): DeviceHealthSnapshot {
        val battery = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val levelPercent = if (level >= 0 && scale > 0) {
            (level * 100f / scale).toInt().coerceIn(0, 100)
        } else null

        val temperatureDeciC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val temperature = if (temperatureDeciC in 1..1_000) {
            temperatureDeciC / 10.0
        } else null

        val voltage = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            ?.takeIf { it > 0 }

        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING,
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }

        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager
        val activeNetwork = runCatching { connectivity?.activeNetwork }.getOrNull()
        val capabilities = runCatching {
            if (activeNetwork == null) null else connectivity?.getNetworkCapabilities(activeNetwork)
        }.getOrNull()
        val transport = when {
            capabilities == null -> null
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "行動網路"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "乙太網路"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "其他／未知"
        }
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        return DeviceHealthSnapshot(
            batteryLevelPercent = levelPercent,
            batteryTemperatureCelsius = temperature,
            batteryVoltageMillivolts = voltage,
            charging = charging,
            networkTransport = transport,
            networkValidated = validated
        )
    }
}
