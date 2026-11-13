package com.s410211.a55wuwa.perf

import android.content.Context
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Samsung Galaxy Performance SDK controller.
 *
 * Tries the documented per-resource CPU_MIN/GPU_MIN requests first.
 * If this firmware rejects CustomParams.add(), falls back to Samsung's
 * documented CPU/GPU preset request API. No protected sysfs node is written.
 */
class SamsungSPerfController(context: Context) {
    private val appContext = context.applicationContext

    @Volatile
    private var api: Api? = null

    @Volatile
    private var initializationFailure: String? = null

    private var lastRequestKey: String? = null

    @Synchronized
    fun request(level: Int, timeoutMs: Int): String {
        require(level in 0..4) { "SPerf level must be in 0..4" }
        require(timeoutMs in 500..30_000) { "SPerf timeout must be 500..30000 ms" }

        return try {
            val sdk = getApi()
            val params = sdk.paramsConstructor.newInstance()

            val cpuAdd = invokeInt(
                sdk.addMethod, params, sdk.cpuMinType, level, timeoutMs
            )
            val gpuAdd = invokeInt(
                sdk.addMethod, params, sdk.gpuMinType, level, timeoutMs
            )

            if (cpuAdd == SUCCESS || gpuAdd == SUCCESS) {
                val startCode = invokeInt(sdk.startCustomMethod, sdk.manager, params)
                if (startCode == SUCCESS) {
                    lastRequestKey = "custom:$level"
                    return "CustomParams：CPU_MIN add()=$cpuAdd、GPU_MIN add()=$gpuAdd；start(CustomParams)=0"
                }
                // Custom request could not start; try the documented preset API.
            }

            val presetResult = startPresetFallback(sdk, timeoutMs)
            if (presetResult.first) {
                lastRequestKey = "preset"
                "CustomParams：CPU_MIN add()=$cpuAdd、GPU_MIN add()=$gpuAdd；改用 Samsung CPU/GPU preset：${presetResult.second}"
            } else {
                lastRequestKey = null
                "CustomParams 拒絕（CPU_MIN=$cpuAdd、GPU_MIN=$gpuAdd）；Samsung preset 也失敗：${presetResult.second}"
            }
        } catch (error: Throwable) {
            val cause = unwrap(error)
            "SPerf 失敗：${cause.javaClass.simpleName}: ${cause.message.orEmpty()}"
        }
    }

    @Synchronized
    fun stop(): String {
        val sdk = api ?: return "尚未初始化 SPerf，無請求需要解除"
        return try {
            val code = invokeInt(sdk.stopMethod, sdk.manager)
            lastRequestKey = null
            "PerformanceManager.stop()=$code" +
                if (code == SUCCESS) "，已要求解除效能請求" else "，解除請求失敗"
        } catch (error: Throwable) {
            val cause = unwrap(error)
            lastRequestKey = null
            "PerformanceManager.stop() 錯誤：${cause.javaClass.simpleName}: ${cause.message.orEmpty()}"
        }
    }

    private fun startPresetFallback(sdk: Api, timeoutMs: Int): Pair<Boolean, String> {
        val combined = sdk.presetCpu or sdk.presetGpu
        val combinedCode = invokeInt(
            sdk.startPresetMethod, sdk.manager, combined, timeoutMs
        )
        if (combinedCode == SUCCESS) {
            return true to "CPU|GPU combined start($combined, $timeoutMs)=0"
        }

        // Some firmware builds may expose only one preset resource.
        val cpuCode = invokeInt(
            sdk.startPresetMethod, sdk.manager, sdk.presetCpu, timeoutMs
        )
        val gpuCode = invokeInt(
            sdk.startPresetMethod, sdk.manager, sdk.presetGpu, timeoutMs
        )
        return if (cpuCode == SUCCESS || gpuCode == SUCCESS) {
            true to "combined=$combinedCode；CPU=$cpuCode；GPU=$gpuCode"
        } else {
            false to "combined=$combinedCode；CPU=$cpuCode；GPU=$gpuCode"
        }
    }

    private fun getApi(): Api {
        api?.let { return it }
        initializationFailure?.let { throw IllegalStateException(it) }

        val jar = File(SDK_JAR)
        check(jar.isFile && jar.canRead()) {
            "Samsung SPerf SDK 不存在或無法讀取：$SDK_JAR"
        }

        try {
            val loader = PathClassLoader(SDK_JAR, appContext.classLoader)
            val sPerfClass = Class.forName(SPERF_CLASS, true, loader)
            val managerClass = Class.forName(PERFORMANCE_MANAGER_CLASS, true, loader)
            val paramsClass = Class.forName(CUSTOM_PARAMS_CLASS, true, loader)

            val initialized = sPerfClass
                .getMethod("initialize", Context::class.java)
                .invoke(null, appContext) as? Boolean ?: false
            check(initialized) { "SPerf.initialize() 回傳 false" }

            val manager = managerClass.getMethod("getInstance").invoke(null)
                ?: error("PerformanceManager.getInstance() 回傳 null")

            val intType = Int::class.javaPrimitiveType!!
            val loaded = Api(
                manager = manager,
                paramsConstructor = paramsClass.getDeclaredConstructor(),
                addMethod = paramsClass.getMethod("add", intType, intType, intType),
                startCustomMethod = managerClass.getMethod("start", paramsClass),
                startPresetMethod = managerClass.getMethod("start", intType, intType),
                stopMethod = managerClass.getMethod("stop"),
                cpuMinType = paramsClass.getField("TYPE_CPU_MIN").getInt(null),
                gpuMinType = paramsClass.getField("TYPE_GPU_MIN").getInt(null),
                presetCpu = managerClass.getField("PRESET_TYPE_CPU").getInt(null),
                presetGpu = managerClass.getField("PRESET_TYPE_GPU").getInt(null)
            )
            api = loaded
            return loaded
        } catch (error: Throwable) {
            val cause = unwrap(error)
            val message = "${cause.javaClass.simpleName}: ${cause.message.orEmpty()}"
            initializationFailure = message
            throw IllegalStateException(message, cause)
        }
    }

    private fun invokeInt(method: Method, receiver: Any?, vararg args: Any): Int =
        method.invoke(receiver, *args) as? Int ?: UNKNOWN_RESULT

    private fun unwrap(error: Throwable): Throwable =
        if (error is InvocationTargetException && error.targetException != null) {
            error.targetException
        } else {
            error
        }

    private data class Api(
        val manager: Any,
        val paramsConstructor: Constructor<*>,
        val addMethod: Method,
        val startCustomMethod: Method,
        val startPresetMethod: Method,
        val stopMethod: Method,
        val cpuMinType: Int,
        val gpuMinType: Int,
        val presetCpu: Int,
        val presetGpu: Int
    )

    private companion object {
        const val SDK_JAR = "/system/framework/perfsdk.jar"
        const val SPERF_CLASS = "com.samsung.sdk.sperf.SPerf"
        const val PERFORMANCE_MANAGER_CLASS = "com.samsung.sdk.sperf.PerformanceManager"
        const val CUSTOM_PARAMS_CLASS = "com.samsung.sdk.sperf.CustomParams"
        const val SUCCESS = 0
        const val UNKNOWN_RESULT = Int.MIN_VALUE
    }
}
