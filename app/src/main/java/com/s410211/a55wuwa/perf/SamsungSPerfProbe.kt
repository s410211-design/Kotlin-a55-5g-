package com.s410211.a55wuwa.perf

import android.content.Context
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.reflect.Method
import java.util.Locale

/**
 * Short, user-triggered verification of Samsung's preinstalled Galaxy Performance SDK.
 *
 * This is intentionally a probe, not a persistent boost controller:
 * - Loads the system-owned SDK jar with PathClassLoader because it is not in BOOTCLASSPATH.
 * - Requests CPU_MIN and GPU_MIN separately at level 2 for 1000 ms.
 * - Records SDK return codes and observable frequency snapshots.
 * - Calls PerformanceManager.stop() immediately after each short observation.
 * - Never writes CPU/GPU sysfs nodes.
 */
object SamsungSPerfProbe {
    private const val SDK_JAR = "/system/framework/perfsdk.jar"
    private const val SPERF_CLASS = "com.samsung.sdk.sperf.SPerf"
    private const val PERFORMANCE_MANAGER_CLASS = "com.samsung.sdk.sperf.PerformanceManager"
    private const val CUSTOM_PARAMS_CLASS = "com.samsung.sdk.sperf.CustomParams"

    private const val TYPE_CPU_MIN = 0
    private const val TYPE_GPU_MIN = 2
    private const val REQUEST_LEVEL = 2
    private const val REQUEST_TIMEOUT_MS = 1_000
    private const val OBSERVE_MS = 250L

    private val cpuNodes = listOf(
        "policy0" to "/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq",
        "policy4" to "/sys/devices/system/cpu/cpufreq/policy4/scaling_cur_freq"
    )
    private val gpuNodes = listOf(
        "devfreq.cur_freq" to "/sys/class/devfreq/10e00000.sgpu/cur_freq",
        "gpu_clock" to "/sys/kernel/gpu/gpu_clock"
    )

    fun run(context: Context): String {
        val out = StringBuilder()
        val jar = File(SDK_JAR)
        out.appendLine("SDK JAR: ${if (jar.isFile && jar.canRead()) "FOUND" else "MISSING / NOT READABLE"}")
        out.appendLine("Path: $SDK_JAR")
        if (!jar.isFile || !jar.canRead()) return out.toString()

        try {
            val loader = PathClassLoader(SDK_JAR, context.applicationContext.classLoader)
            val sperfClass = Class.forName(SPERF_CLASS, true, loader)
            val managerClass = Class.forName(PERFORMANCE_MANAGER_CLASS, true, loader)
            val paramsClass = Class.forName(CUSTOM_PARAMS_CLASS, true, loader)

            runCatching {
                sperfClass.getMethod("setDebugModeEnabled", java.lang.Boolean.TYPE)
                    .invoke(null, true)
            }

            val initialized = sperfClass
                .getMethod("initialize", Context::class.java)
                .invoke(null, context.applicationContext) as? Boolean ?: false

            out.appendLine("SPerf.initialize(): $initialized")
            val version = runCatching {
                sperfClass.getMethod("getVersionName").invoke(null)?.toString()
            }.getOrNull()
            out.appendLine("SDK version: ${version ?: "unavailable"}")

            if (!initialized) {
                out.appendLine("STOP: SDK initialization returned false; no CPU/GPU request sent.")
                return out.toString()
            }

            val manager = managerClass.getMethod("getInstance").invoke(null)
            if (manager == null) {
                out.appendLine("STOP: PerformanceManager.getInstance() returned null.")
                return out.toString()
            }
            out.appendLine("PerformanceManager: acquired")

            val constructor = paramsClass.getDeclaredConstructor()
            val addMethod = paramsClass.getMethod(
                "add",
                Integer.TYPE,
                Integer.TYPE,
                Integer.TYPE
            )
            val startMethod = managerClass.getMethod("start", paramsClass)
            val stopMethod = managerClass.getMethod("stop")

            testResource(
                out = out,
                label = "CPU_MIN",
                type = TYPE_CPU_MIN,
                paths = cpuNodes,
                paramsClass = paramsClass,
                constructor = constructor,
                addMethod = addMethod,
                startMethod = startMethod,
                stopMethod = stopMethod,
                manager = manager
            )
            testResource(
                out = out,
                label = "GPU_MIN",
                type = TYPE_GPU_MIN,
                paths = gpuNodes,
                paramsClass = paramsClass,
                constructor = constructor,
                addMethod = addMethod,
                startMethod = startMethod,
                stopMethod = stopMethod,
                manager = manager
            )
        } catch (error: Throwable) {
            val cause = error.cause ?: error
            out.appendLine("SPerf exception: ${cause.javaClass.name}: ${cause.message.orEmpty()}")
        }

        out.appendLine()
        out.appendLine("Interpretation:")
        out.appendLine("- add/start return 0 means the SDK accepted that request.")
        out.appendLine("- Frequency changes are observations, not proof by themselves.")
        out.appendLine("- A successful request does not guarantee a fixed clock or sustained boost.")
        return out.toString()
    }

    private fun testResource(
        out: StringBuilder,
        label: String,
        type: Int,
        paths: List<Pair<String, String>>,
        paramsClass: Class<*>,
        constructor: java.lang.reflect.Constructor<*>,
        addMethod: Method,
        startMethod: Method,
        stopMethod: Method,
        manager: Any
    ) {
        out.appendLine()
        out.appendLine("[$label] level=$REQUEST_LEVEL timeout=${REQUEST_TIMEOUT_MS}ms")
        val before = snapshot(paths)
        out.appendLine("Before: $before")

        var requestStarted = false
        try {
            val params = constructor.newInstance()
            val addResult = addMethod.invoke(
                params,
                type,
                REQUEST_LEVEL,
                REQUEST_TIMEOUT_MS
            ) as? Int ?: Int.MIN_VALUE
            out.appendLine("CustomParams.add(): $addResult")
            if (addResult != 0) {
                out.appendLine("Result: parameter rejected; start() not called.")
                return
            }

            val startResult = startMethod.invoke(manager, params) as? Int ?: Int.MIN_VALUE
            out.appendLine("PerformanceManager.start(): $startResult")
            if (startResult != 0) {
                out.appendLine("Result: daemon did not accept the request.")
                return
            }
            requestStarted = true

            Thread.sleep(OBSERVE_MS)
            out.appendLine("During: ${snapshot(paths)}")
        } finally {
            if (requestStarted) {
                val stopResult = runCatching {
                    stopMethod.invoke(manager) as? Int ?: Int.MIN_VALUE
                }.getOrElse { error ->
                    out.appendLine("PerformanceManager.stop() exception: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                    Int.MIN_VALUE
                }
                out.appendLine("PerformanceManager.stop(): $stopResult")
            } else {
                out.appendLine("PerformanceManager.stop(): not needed (request not started)")
            }
        }
        out.appendLine("After stop: ${snapshot(paths)}")
    }

    private fun snapshot(paths: List<Pair<String, String>>): String =
        paths.joinToString(" | ") { (label, path) ->
            val value = runCatching { File(path).readText().trim() }
                .getOrElse { "READ_ERROR:${it.javaClass.simpleName}" }
            "$label=$value"
        }

    private fun StringBuilder.appendLine(value: String) {
        append(value).append('\n')
    }
}
