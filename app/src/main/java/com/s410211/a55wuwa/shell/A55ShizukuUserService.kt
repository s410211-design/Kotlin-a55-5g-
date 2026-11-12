package com.s410211.a55wuwa.shell

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.Keep
import java.io.File

/**
 * Runs in the identity Shizuku actually provides (UID 2000 for ADB backend,
 * UID 0 for a root/Sui backend). Never accept arbitrary paths or commands.
 */
@Keep
class A55ShizukuUserService : IA55ShizukuService.Stub() {

    override fun readNode(nodeId: Int): Array<String> {
        val node = ShizukuNode.fromId(nodeId)
            ?: return response(STATUS_UNSUPPORTED, detail = "Node ID is not allowlisted")

        val fd = try {
            Os.open(node.path, OsConstants.O_RDONLY, 0)
        } catch (error: ErrnoException) {
            return errnoResponse(error.errno)
        } catch (error: SecurityException) {
            return response(STATUS_PERMISSION_DENIED, detail = error.javaClass.simpleName)
        } catch (error: Exception) {
            return response(STATUS_ERROR, detail = error.javaClass.simpleName)
        }

        return try {
            val buffer = ByteArray(MAX_NODE_BYTES + 1)
            var size = 0
            while (size < buffer.size) {
                val count = Os.read(fd, buffer, size, buffer.size - size)
                if (count <= 0) break
                size += count
            }

            if (size > MAX_NODE_BYTES) {
                response(STATUS_OUTPUT_TOO_LARGE, detail = "Node output exceeds $MAX_NODE_BYTES bytes")
            } else {
                val raw = String(buffer, 0, size, Charsets.UTF_8).trim()
                if (raw.isEmpty()) {
                    response(STATUS_INVALID_DATA, detail = "Node returned an empty value")
                } else {
                    response(STATUS_OK, raw)
                }
            }
        } catch (error: ErrnoException) {
            errnoResponse(error.errno)
        } catch (error: SecurityException) {
            response(STATUS_PERMISSION_DENIED, detail = error.javaClass.simpleName)
        } catch (error: Exception) {
            response(STATUS_ERROR, detail = error.javaClass.simpleName)
        } finally {
            runCatching { Os.close(fd) }
        }
    }

    /**
     * Only CPUFreq scaling_min_freq is writable. The value must be an OPP
     * exposed by this policy and remain within its live scaling_max_freq.
     * GPU controls and CPU maximum limits are deliberately not writable.
     */
    override fun writeNode(nodeId: Int, value: String): Array<String> {
        val control = when (nodeId) {
            ShizukuNode.CPU_POLICY0_MIN.id -> CpuMinControl(
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies"
            )
            ShizukuNode.CPU_POLICY4_MIN.id -> CpuMinControl(
                "/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq",
                "/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq",
                "/sys/devices/system/cpu/cpufreq/policy4/scaling_available_frequencies"
            )
            else -> return response(STATUS_UNSUPPORTED, detail = "Write target is not allowlisted")
        }

        val requested = value.trim().toLongOrNull()
            ?: return response(STATUS_INVALID_DATA, detail = "Frequency must be an integer in kHz")
        if (requested <= 0L) {
            return response(STATUS_INVALID_DATA, detail = "Frequency must be positive")
        }

        return try {
            val max = readNodeText(control.maxPath).toLongOrNull()
                ?: return response(STATUS_INVALID_DATA, detail = "Invalid live CPU policy maximum")
            val available = readNodeText(control.availablePath)
                .split(Regex("\\s+"))
                .mapNotNull(String::toLongOrNull)
                .toSet()

            if (available.isEmpty()) {
                return response(STATUS_UNSUPPORTED, detail = "Firmware exposes no valid CPU OPP table")
            }
            if (requested > max || requested !in available) {
                return response(
                    STATUS_INVALID_DATA,
                    detail = "Requested frequency is outside live policy maximum or not a supported OPP"
                )
            }

            val fd = Os.open(control.path, OsConstants.O_WRONLY, 0)
            try {
                val bytes = "$requested\n".toByteArray(Charsets.US_ASCII)
                val written = Os.write(fd, bytes, 0, bytes.size)
                if (written != bytes.size) {
                    return response(STATUS_ERROR, detail = "Incomplete sysfs write")
                }
            } finally {
                runCatching { Os.close(fd) }
            }

            val readBack = readNodeText(control.path).toLongOrNull()
                ?: return response(STATUS_INVALID_DATA, detail = "CPU policy read-back is invalid")
            if (readBack != requested) {
                response(
                    STATUS_ERROR,
                    rawValue = readBack.toString(),
                    detail = "CPU policy read-back did not match requested value"
                )
            } else {
                response(STATUS_OK, rawValue = readBack.toString())
            }
        } catch (error: ErrnoException) {
            errnoResponse(error.errno)
        } catch (error: SecurityException) {
            response(STATUS_PERMISSION_DENIED, detail = error.javaClass.simpleName)
        } catch (error: Exception) {
            response(STATUS_ERROR, detail = error.javaClass.simpleName)
        }
    }

    private fun readNodeText(path: String): String {
        val fd = Os.open(path, OsConstants.O_RDONLY, 0)
        return try {
            val buffer = ByteArray(MAX_NODE_BYTES + 1)
            var size = 0
            while (size < buffer.size) {
                val count = Os.read(fd, buffer, size, buffer.size - size)
                if (count <= 0) break
                size += count
            }
            if (size > MAX_NODE_BYTES) throw IllegalArgumentException("Node output too large")
            String(buffer, 0, size, Charsets.UTF_8).trim()
        } finally {
            Os.close(fd)
        }
    }

    private data class CpuMinControl(
        val path: String,
        val maxPath: String,
        val availablePath: String
    )

    override fun getIdentity(): Array<String> {
        val selinuxContext = runCatching {
            File("/proc/self/attr/current").readText(Charsets.UTF_8).trim().take(256)
        }.getOrDefault("")
        return arrayOf(
            Os.getuid().toString(),
            Os.getpid().toString(),
            selinuxContext
        )
    }

    /** Called by Shizuku when the UserService is explicitly removed. */
    override fun destroy() {
        System.exit(0)
    }

    override fun exit() {
        destroy()
    }

    private fun errnoResponse(errno: Int): Array<String> = when (errno) {
        OsConstants.EACCES, OsConstants.EPERM ->
            response(STATUS_PERMISSION_DENIED, detail = "open/read denied (errno=$errno)")
        OsConstants.ENOENT ->
            response(STATUS_NOT_FOUND, detail = "Node not found (errno=$errno)")
        OsConstants.EINTR, OsConstants.EIO, OsConstants.EAGAIN ->
            response(STATUS_TEMPORARY, detail = "Temporary I/O failure (errno=$errno)")
        else -> response(STATUS_ERROR, detail = "Linux I/O failure (errno=$errno)")
    }

    private fun response(
        status: String,
        rawValue: String = "",
        detail: String = ""
    ): Array<String> = arrayOf(status, rawValue.take(MAX_NODE_BYTES), detail.take(MAX_DETAIL_CHARS))

    private companion object {
        const val MAX_NODE_BYTES = 4_096
        const val MAX_DETAIL_CHARS = 256
        const val STATUS_OK = "OK"
        const val STATUS_UNSUPPORTED = "UNSUPPORTED"
        const val STATUS_PERMISSION_DENIED = "PERMISSION_DENIED"
        const val STATUS_NOT_FOUND = "NOT_FOUND"
        const val STATUS_TEMPORARY = "TEMPORARY"
        const val STATUS_OUTPUT_TOO_LARGE = "OUTPUT_TOO_LARGE"
        const val STATUS_INVALID_DATA = "INVALID_DATA"
        const val STATUS_ERROR = "ERROR"
    }
}