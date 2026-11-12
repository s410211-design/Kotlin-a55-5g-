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
        val node = ShizukuNodeCatalog.fromId(nodeId)
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