package com.s410211.a55wuwa.shell

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.Keep
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

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
    override fun writeNode(nodeId: Int, value: String, expectedCurrentValue: String): Array<String> {
        val control = when (nodeId) {
            ShizukuNode.CPU_POLICY0_MIN.id -> FrequencyControl(
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies",
                "CPU policy0"
            )
            ShizukuNode.CPU_POLICY4_MIN.id -> FrequencyControl(
                "/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq",
                "/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq",
                "/sys/devices/system/cpu/cpufreq/policy4/scaling_available_frequencies",
                "CPU policy4"
            )
            ShizukuNode.GPU_MIN_CLOCK.id -> FrequencyControl(
                "/sys/kernel/gpu/gpu_min_clock",
                "/sys/kernel/gpu/gpu_max_clock",
                "/sys/kernel/gpu/gpu_freq_table",
                "GPU"
            )
            else -> return response(STATUS_UNSUPPORTED, detail = "Write target is not allowlisted")
        }

        val requested = value.trim().toLongOrNull()
            ?: return response(STATUS_INVALID_DATA, detail = "Frequency must be an integer")
        val expectedCurrent = expectedCurrentValue.trim().toLongOrNull()
            ?: return response(STATUS_INVALID_DATA, detail = "Expected current frequency must be an integer")
        if (requested <= 0L || expectedCurrent <= 0L) {
            return response(STATUS_INVALID_DATA, detail = "Frequency values must be positive")
        }

        return try {
            val current = readNodeText(control.path).toLongOrNull()
                ?: return response(STATUS_INVALID_DATA, detail = "Invalid live ${control.label} current value")
            if (current != expectedCurrent) {
                return response(
                    STATUS_EXTERNAL_CHANGE,
                    rawValue = current.toString(),
                    detail = "${control.label} changed externally; refusing to overwrite"
                )
            }
            val max = readNodeText(control.maxPath).toLongOrNull()
                ?: return response(STATUS_INVALID_DATA, detail = "Invalid live ${control.label} maximum")
            val available = readNodeText(control.availablePath)
                .split(Regex("\\s+"))
                .mapNotNull(String::toLongOrNull)
                .toSet()

            if (available.isEmpty()) {
                return response(STATUS_UNSUPPORTED, detail = "Firmware exposes no valid ${control.label} OPP table")
            }
            if (requested > max || requested !in available) {
                return response(
                    STATUS_INVALID_DATA,
                    detail = "Requested ${control.label} frequency exceeds live maximum or is not a supported OPP"
                )
            }

            val currentBeforeWrite = readNodeText(control.path).toLongOrNull()
                ?: return response(STATUS_INVALID_DATA, detail = "Invalid ${control.label} pre-write value")
            if (currentBeforeWrite != expectedCurrent) {
                return response(
                    STATUS_EXTERNAL_CHANGE,
                    rawValue = currentBeforeWrite.toString(),
                    detail = "${control.label} changed before write; refusing to overwrite"
                )
            }

            val fd = Os.open(control.path, OsConstants.O_WRONLY, 0)
            try {
                val bytes = "$requested\n".toByteArray(Charsets.US_ASCII)
                val written = Os.write(fd, bytes, 0, bytes.size)
                if (written != bytes.size) {
                    return response(STATUS_ERROR, detail = "Incomplete ${control.label} sysfs write")
                }
            } finally {
                runCatching { Os.close(fd) }
            }

            val readBack = readNodeText(control.path).toLongOrNull()
                ?: return response(STATUS_INVALID_DATA, detail = "${control.label} read-back is invalid")
            if (readBack != requested) {
                response(
                    STATUS_ERROR,
                    rawValue = readBack.toString(),
                    detail = "${control.label} read-back did not match requested value"
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

    private data class FrequencyControl(
        val path: String,
        val maxPath: String,
        val availablePath: String,
        val label: String
    )

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


    override fun getGameMode(packageName: String): Array<String> {
        if (!isValidPackageName(packageName)) {
            return response(STATUS_INVALID_DATA, detail = "Invalid game package name")
        }
        return queryGameMode(packageName)
    }

    override fun setGameMode(packageName: String, mode: String): Array<String> {
        if (!isValidPackageName(packageName)) {
            return response(STATUS_INVALID_DATA, detail = "Invalid game package name")
        }
        if (mode !in SUPPORTED_GAME_MODES) {
            return response(STATUS_INVALID_DATA, detail = "Unsupported requested game mode")
        }
        val before = queryGameMode(packageName)
        if (before[0] != STATUS_OK) return before
        val available = before.getOrNull(2).orEmpty().split(",").filter { it.isNotBlank() }
        if (mode !in available) {
            return response(
                STATUS_UNSUPPORTED,
                rawValue = before.getOrNull(1).orEmpty(),
                detail = "GameManager does not advertise $mode mode for this game"
            )
        }
        val changed = runFixedCommand(
            listOf("/system/bin/cmd", "game", "mode", mode, packageName)
        )
        if (changed[0] != STATUS_OK) return changed
        val after = queryGameMode(packageName)
        if (after[0] != STATUS_OK) return after
        if (after.getOrNull(1) != mode) {
            return response(
                STATUS_ERROR,
                rawValue = after.getOrNull(1).orEmpty(),
                detail = "GameManager mode read-back did not match requested mode"
            )
        }
        return response(
            STATUS_OK,
            rawValue = mode,
            detail = "GameManager mode changed and read back successfully"
        )
    }


    override fun getGameModeConfig(packageName: String): Array<String> {
        if (!isValidPackageName(packageName)) {
            return response(STATUS_INVALID_DATA, detail = "Invalid game package name")
        }
        return queryGameModeConfig(packageName)
    }

    override fun setGameModeDownscale(
        packageName: String,
        mode: String,
        scale: String
    ): Array<String> {
        if (!isValidPackageName(packageName)) {
            return response(STATUS_INVALID_DATA, detail = "Invalid game package name")
        }
        if (mode != "performance") {
            return response(STATUS_INVALID_DATA, detail = "Only Performance mode downscaling is allowed")
        }
        if (scale !in SUPPORTED_DOWNSCALE_RATIOS) {
            return response(STATUS_INVALID_DATA, detail = "Downscale ratio is not in the safe allowlist")
        }

        val modes = queryGameMode(packageName)
        if (modes[0] != STATUS_OK) return modes
        val available = modes.getOrNull(2).orEmpty().split(",").filter { it.isNotBlank() }
        if ("performance" !in available) {
            return response(STATUS_UNSUPPORTED, detail = "GameManager Performance mode is not advertised for this game")
        }

        val changed = runFixedCommand(
            listOf(
                "/system/bin/cmd", "game", "set",
                "--mode", "2",
                "--downscale", scale,
                packageName
            )
        )
        if (changed[0] != STATUS_OK) return changed

        val config = queryGameModeConfig(packageName)
        if (config[0] != STATUS_OK) return config
        val actual = Regex(
            """downscal(?:e|ing)[^0-9]*([0-9]+(?:\.[0-9]+)?)""",
            RegexOption.IGNORE_CASE
        ).find(config.getOrNull(1).orEmpty())?.groupValues?.getOrNull(1)
        val matches = actual?.toFloatOrNull()?.let {
            kotlin.math.abs(it - scale.toFloat()) < 0.001f
        } == true
        return if (matches) {
            response(STATUS_OK, rawValue = scale, detail = config.getOrNull(1).orEmpty())
        } else {
            response(
                STATUS_ERROR,
                rawValue = config.getOrNull(1).orEmpty(),
                detail = "GameManager downscale read-back did not match $scale"
            )
        }
    }

    override fun resetGameModeConfig(packageName: String, mode: String): Array<String> {
        if (!isValidPackageName(packageName)) {
            return response(STATUS_INVALID_DATA, detail = "Invalid game package name")
        }
        if (mode != "performance") {
            return response(STATUS_INVALID_DATA, detail = "Only Performance mode config can be reset")
        }
        val reset = runFixedCommand(
            listOf("/system/bin/cmd", "game", "reset", "--mode", "2", packageName)
        )
        if (reset[0] != STATUS_OK) return reset
        val after = queryGameModeConfig(packageName)
        if (after[0] != STATUS_OK) return after
        return response(STATUS_OK, rawValue = after.getOrNull(1).orEmpty(), detail = after.getOrNull(2).orEmpty())
    }

    private fun queryGameModeConfig(packageName: String): Array<String> =
        runFixedCommand(listOf("/system/bin/cmd", "game", "list-configs", packageName))

    private fun queryGameMode(packageName: String): Array<String> {
        val result = runFixedCommand(
            listOf("/system/bin/cmd", "game", "list-modes", packageName)
        )
        if (result[0] != STATUS_OK) return result
        val output = result.getOrNull(1).orEmpty()
        val current = Regex("current mode:\\s*([A-Za-z_-]+)", RegexOption.IGNORE_CASE)
            .find(output)?.groupValues?.getOrNull(1)?.lowercase()
            ?: return response(STATUS_UNSUPPORTED, detail = output.take(MAX_DETAIL_CHARS))
        val availableText = Regex("available game modes:\\s*\\[([^]]*)]", RegexOption.IGNORE_CASE)
            .find(output)?.groupValues?.getOrNull(1).orEmpty()
        val available = availableText.split(",")
            .map { it.trim().lowercase() }
            .filter { it in SUPPORTED_GAME_MODES }
            .distinct()
        if (available.isEmpty()) {
            return response(STATUS_UNSUPPORTED, rawValue = current, detail = "No supported GameManager modes advertised")
        }
        return response(STATUS_OK, rawValue = current, detail = available.joinToString(","))
    }

    /** Runs only fixed /system/bin/cmd game argument shapes; no shell parsing. */
    private fun runFixedCommand(arguments: List<String>): Array<String> {
        val process = try {
            ProcessBuilder(arguments).redirectErrorStream(true).start()
        } catch (error: SecurityException) {
            return response(STATUS_PERMISSION_DENIED, detail = "GameManager command denied")
        } catch (error: Exception) {
            return response(STATUS_UNSUPPORTED, detail = "Cannot start GameManager command: \${error.javaClass.simpleName}")
        }
        val output = ByteArrayOutputStream()
        val reader = Thread({
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0 || output.size() + count > MAX_DETAIL_CHARS * 4) break
                        output.write(buffer, 0, count)
                    }
                }
            }
        }, "a55-game-mode-output").apply { isDaemon = true }
        reader.start()
        val completed = try {
            process.waitFor(5L, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) {
            runCatching { process.destroyForcibly() }
            return response(STATUS_TEMPORARY, detail = "GameManager command timed out")
        }
        runCatching { reader.join(500L) }
        val raw = String(output.toByteArray(), Charsets.UTF_8).trim()
        return if (process.exitValue() == 0) {
            response(STATUS_OK, rawValue = raw, detail = raw.take(MAX_DETAIL_CHARS))
        } else {
            response(STATUS_UNSUPPORTED, detail = raw.ifBlank { "GameManager command returned non-zero" }.take(MAX_DETAIL_CHARS))
        }
    }

    override fun startSurfaceFlingerTimeStats(packageName: String): Array<String> {
        if (!isValidPackageName(packageName)) {
            return response(STATUS_INVALID_DATA, detail = "Invalid game package name")
        }
        return runSurfaceFlingerTimestats(listOf("-clear", "-enable"), null)
    }

    override fun dumpSurfaceFlingerTimeStats(packageName: String): Array<String> {
        if (!isValidPackageName(packageName)) {
            return response(STATUS_INVALID_DATA, detail = "Invalid game package name")
        }
        return runSurfaceFlingerTimestats(listOf("-dump"), packageName)
    }

    override fun stopSurfaceFlingerTimeStats(): Array<String> =
        runSurfaceFlingerTimestats(listOf("-disable"), null)

    /**
     * Runs only fixed SurfaceFlinger timestats arguments; no shell and no
     * caller-controlled executable/flags. Output is bounded and filtered to
     * the requested game's layer before crossing Binder.
     */
    private fun runSurfaceFlingerTimestats(
        arguments: List<String>,
        packageFilter: String?
    ): Array<String> {
        val process = try {
            ProcessBuilder(
                listOf("/system/bin/dumpsys", "SurfaceFlinger", "--timestats") + arguments
            ).redirectErrorStream(true).start()
        } catch (error: SecurityException) {
            return response(STATUS_PERMISSION_DENIED, detail = "dumpsys execution denied")
        } catch (error: Exception) {
            return response(STATUS_ERROR, detail = "Cannot start dumpsys: ${error.javaClass.simpleName}")
        }

        val output = ByteArrayOutputStream()
        val oversized = java.util.concurrent.atomic.AtomicBoolean(false)
        val reader = Thread({
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(4_096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        if (output.size() + count > MAX_COMMAND_OUTPUT_BYTES) {
                            oversized.set(true)
                            break
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } catch (_: Exception) {
                // Process termination and stream closure are reported below.
            }
        }, "a55-surfaceflinger-output").apply { isDaemon = true }
        reader.start()

        val completed = try {
            process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed || oversized.get()) {
            runCatching { process.destroyForcibly() }
            runCatching { process.inputStream.close() }
            runCatching { reader.join(500L) }
            return if (oversized.get()) {
                response(STATUS_OUTPUT_TOO_LARGE, detail = "SurfaceFlinger output exceeded size limit")
            } else {
                response(STATUS_TEMPORARY, detail = "SurfaceFlinger command timed out or was interrupted")
            }
        }
        runCatching { reader.join(1_000L) }
        if (reader.isAlive) {
            runCatching { process.destroyForcibly() }
            return response(STATUS_TEMPORARY, detail = "SurfaceFlinger output reader did not finish")
        }

        val raw = String(output.toByteArray(), Charsets.UTF_8)
        if (runCatching { process.exitValue() }.getOrDefault(-1) != 0) {
            return response(
                STATUS_ERROR,
                detail = raw.take(MAX_DETAIL_CHARS).ifBlank { "dumpsys returned non-zero exit status" }
            )
        }

        val filtered = if (packageFilter == null) raw else filterTimestatsForPackage(raw, packageFilter)
        if (packageFilter != null && filtered.isBlank()) {
            return response(STATUS_TEMPORARY, detail = "No active SurfaceFlinger layer matched the game package")
        }
        return response(STATUS_OK, rawValue = filtered)
    }

    private fun filterTimestatsForPackage(output: String, packageName: String): String {
        val result = StringBuilder()
        var includeSection = false
        output.lineSequence().forEach { line ->
            if (line.trimStart().startsWith("layerName")) {
                includeSection = layerLineMatchesPackage(line, packageName)
            }
            if (includeSection) result.appendLine(line)
        }
        return result.toString().take(MAX_COMMAND_OUTPUT_BYTES)
    }

    private fun layerLineMatchesPackage(line: String, packageName: String): Boolean =
        line.contains("$packageName/") ||
            line.contains("$packageName]") ||
            line.trimEnd().endsWith(packageName)

    private fun isValidPackageName(value: String): Boolean =
        value.length in 3..255 &&
            Regex("^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$").matches(value)

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
    ): Array<String> = arrayOf(status, rawValue.take(MAX_COMMAND_OUTPUT_BYTES), detail.take(MAX_DETAIL_CHARS))

    private companion object {
        const val MAX_NODE_BYTES = 4_096
        const val MAX_DETAIL_CHARS = 256
        const val MAX_COMMAND_OUTPUT_BYTES = 262_144
        const val COMMAND_TIMEOUT_SECONDS = 5L
        const val STATUS_OK = "OK"
        const val STATUS_UNSUPPORTED = "UNSUPPORTED"
        const val STATUS_PERMISSION_DENIED = "PERMISSION_DENIED"
        const val STATUS_NOT_FOUND = "NOT_FOUND"
        const val STATUS_TEMPORARY = "TEMPORARY"
        const val STATUS_OUTPUT_TOO_LARGE = "OUTPUT_TOO_LARGE"
        const val STATUS_EXTERNAL_CHANGE = "EXTERNAL_CHANGE"
        const val STATUS_INVALID_DATA = "INVALID_DATA"
        const val STATUS_ERROR = "ERROR"
        val SUPPORTED_GAME_MODES = setOf("standard", "performance", "battery", "custom")
        val SUPPORTED_DOWNSCALE_RATIOS = setOf("0.7", "0.75", "0.8", "0.85", "0.9")
    }
}