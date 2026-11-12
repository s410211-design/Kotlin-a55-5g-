package com.s410211.a55wuwa.telemetry

import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants

/**
 * Maps Linux errno values to user-visible read states. These values are the
 * generic Linux errno numbers used by Android's Linux-based kernels.
 */
object LinuxErrnoMapper {
    private const val EPERM = 1
    private const val ENOENT = 2
    private const val EINTR = 4
    private const val EIO = 5
    private const val EAGAIN = 11
    private const val EACCES = 13

    fun <T> toReading(
        errno: Int,
        source: String,
        sampledAtMillis: Long
    ): Reading<T> = when (errno) {
        EPERM, EACCES -> Reading.PermissionDenied(
            source,
            sampledAtMillis,
            "核心拒絕讀取（errno=$errno）"
        )
        ENOENT -> Reading.NotFound(
            source,
            sampledAtMillis,
            "核心回報目標節點不存在（errno=$errno）"
        )
        EINTR, EIO, EAGAIN -> Reading.TemporarilyUnavailable(
            source,
            sampledAtMillis,
            "讀取暫時失敗（errno=$errno）"
        )
        else -> Reading.Error(
            source,
            sampledAtMillis,
            "系統呼叫失敗（errno=$errno）"
        )
    }
}

/**
 * Direct, read-only sysfs access. It only accepts catalogued candidate paths,
 * reads at most MAX_BYTES + 1 bytes, and never writes to a kernel node.
 */
object GpuSysfsReader {
    private const val MAX_BYTES = 4096

    fun read(
        candidate: GpuNodeCandidate,
        sampledAtMillis: Long = SystemClock.elapsedRealtime()
    ): Reading<String> {
        if (candidate !in GpuNodeCatalog.candidates) {
            return Reading.Unsupported(
                candidate.path,
                sampledAtMillis,
                "路徑不在內建唯讀白名單"
            )
        }

        val descriptor = try {
            Os.open(candidate.path, OsConstants.O_RDONLY, 0)
        } catch (error: ErrnoException) {
            return LinuxErrnoMapper.toReading(
                error.errno,
                candidate.path,
                sampledAtMillis
            )
        }

        return try {
            val buffer = ByteArray(MAX_BYTES + 1)
            var size = 0
            while (size < buffer.size) {
                val count = Os.read(descriptor, buffer, size, buffer.size - size)
                if (count <= 0) break
                size += count
            }

            if (size > MAX_BYTES) {
                Reading.InvalidData(
                    candidate.path,
                    sampledAtMillis,
                    "節點輸出超過 ${MAX_BYTES} bytes 上限"
                )
            } else {
                val raw = String(buffer, 0, size, Charsets.UTF_8).trim()
                if (raw.isEmpty()) {
                    Reading.InvalidData(
                        candidate.path,
                        sampledAtMillis,
                        "節點回傳空內容"
                    )
                } else {
                    Reading.Value(
                        raw,
                        candidate.path,
                        "raw",
                        sampledAtMillis
                    )
                }
            }
        } catch (error: ErrnoException) {
            LinuxErrnoMapper.toReading(
                error.errno,
                candidate.path,
                sampledAtMillis
            )
        } finally {
            runCatching { Os.close(descriptor) }
        }
    }
}
