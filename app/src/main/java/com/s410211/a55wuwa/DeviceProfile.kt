package com.s410211.a55wuwa

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.util.Locale

data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val device: String,
    val hardware: String,
    val androidRelease: String,
    val sdkInt: Int,
    val securityPatch: String?,
    val totalRamBytes: Long?,
    val availableRamBytes: Long?,
    val totalStorageBytes: Long?,
    val availableStorageBytes: Long?
)

object DeviceProfileReader {
    fun read(context: Context): DeviceProfile {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        val totalRam = memoryInfo.totalMem.takeIf { it > 0L }
        val availableRam = memoryInfo.availMem.takeIf { it > 0L }

        val storage = runCatching {
            StatFs(Environment.getDataDirectory().absolutePath).let {
                it.totalBytes.takeIf { value -> value > 0L } to
                    it.availableBytes.takeIf { value -> value >= 0L }
            }
        }.getOrNull()

        return DeviceProfile(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            hardware = Build.HARDWARE.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() },
            totalRamBytes = totalRam,
            availableRamBytes = availableRam,
            totalStorageBytes = storage?.first,
            availableStorageBytes = storage?.second
        )
    }
}

object DeviceProfileFormatter {
    private const val BYTES_PER_GIB = 1_073_741_824.0

    fun formatGiB(bytes: Long): String {
        if (bytes < 0L) return "無法取得"
        return String.format(Locale.ROOT, "%.1f GiB", bytes / BYTES_PER_GIB)
    }
}
