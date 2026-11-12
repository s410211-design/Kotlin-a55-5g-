package com.s410211.a55wuwa.telemetry

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Direct App-UID fallback for the exact CPU telemetry paths used by the monitor. */
object CpuSysfsReader {
    private val allowedPaths = setOf(
        "/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq",
        "/sys/devices/system/cpu/cpufreq/policy4/scaling_cur_freq",
        "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq",
        "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
        "/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq",
        "/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq",
        "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies",
        "/sys/devices/system/cpu/cpufreq/policy4/scaling_available_frequencies",
        "/proc/stat"
    )

    fun read(path: String, sampledAtMillis: Long): Reading<String> {
        if (path !in allowedPaths) {
            return Reading.Unsupported(path, sampledAtMillis, "CPU 直接讀取路徑不在白名單")
        }
        return try {
            val raw = File(path).readText(Charsets.UTF_8).trim()
            if (raw.isBlank()) Reading.InvalidData(path, sampledAtMillis, "CPU 節點回傳空資料")
            else Reading.Value(raw, path, "raw", sampledAtMillis)
        } catch (_: SecurityException) {
            Reading.PermissionDenied(path, sampledAtMillis, "App UID 沒有讀取權限")
        } catch (_: FileNotFoundException) {
            Reading.NotFound(path, sampledAtMillis, "CPU 節點不存在")
        } catch (error: IOException) {
            Reading.TemporarilyUnavailable(path, sampledAtMillis, error.javaClass.simpleName)
        } catch (error: Exception) {
            Reading.Error(path, sampledAtMillis, error.javaClass.simpleName)
        }
    }
}
