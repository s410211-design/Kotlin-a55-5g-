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
 * CPU_MIN and GPU_MIN are submitted together through Samsung's system SDK.
 * No protected sysfs node is written by this app.
 */
class SamsungSPerfController(context: Context) {
    private val appContext = context.applicationContext

    @Volatile
    private var api: Api? = null

    @Volatile
    private var initializationFailure: String? = null

    private var lastLevel: Int? = null

    @Synchronized
    fun request(level: Int, timeoutMs: Int): String {
        require(level in 1..4) { "SPerf level must be in 1..4" }
        require(timeoutMs in 500..30_000) { "SPerf timeout must be 500..30000 ms" }

        return try {
            val sdk = getApi()
            val previousLevel = lastLevel
            if (previousLevel != null && previousLevel != level) {
                val stopCode = invokeInt(sdk.stopMethod, sdk.manager)
                if (stopCode != SUCCESS) {
                    return "level 切換前 stop()=$stopCode；本次請求未送出"
                }
                lastLevel = null
            }

            val params = sdk.paramsConstructor.newInstance()
            val cpuAdd = invokeInt(
                sdk.addMethod,
                params,
                sdk.cpuMinType,
                level,
                timeoutMs
            )
            if (cpuAdd != SUCCESS) return "CPU_MIN add()=$cpuAdd，請求拒絕"

            val gpuAdd = invokeInt(
                sdk.addMethod,
                params,
                sdk.gpuMinType,
                level,
                timeoutMs
            )
            if (gpuAdd != SUCCESS) return "GPU_MIN add()=$gpuAdd，請求拒絕"

            val startCode = invokeInt(sdk.startMethod, sdk.manager, params)
            if (startCode == SUCCESS) {
                lastLevel = level
                "CPU_MIN + GPU_MIN start()=0，系統接受請求"
            } else {
                "CPU_MIN + GPU_MIN start()=$startCode，系統未接受請求"
            }
        } catch (error: Throwable) {
            val cause = unwrap(error)
            "SPerf 失敗：${cause.javaClass.simpleName}: ${cause.message.orEmpty()}"
        }
    }

    @Synchronized
    fun stop(): String {
        val sdk = api ?: return "未建立 SPerf 請求，無需 stop()"
        return try {
            val code = invokeInt(sdk.stopMethod, sdk.manager)
            lastLevel = null
            "PerformanceManager.stop()=$code" +
                if (code == SUCCESS) "，已要求解除效能請求" else "，解除請求失敗"
        } catch (error: Throwable) {
            val cause = unwrap(error)
            lastLevel = null
            "PerformanceManager.stop() 錯誤：${cause.javaClass.simpleName}: ${cause.message.orEmpty()}"
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
            val addMethod = paramsClass.getMethod(
                "add",
                intType,
                intType,
                intType
            )
            val startMethod = managerClass.getMethod("start", paramsClass)
            val stopMethod = managerClass.getMethod("stop")

            val loaded = Api(
                manager = manager,
                paramsConstructor = paramsClass.getDeclaredConstructor(),
                addMethod = addMethod,
                startMethod = startMethod,
                stopMethod = stopMethod,
                cpuMinType = paramsClass.getField("TYPE_CPU_MIN").getInt(null),
                gpuMinType = paramsClass.getField("TYPE_GPU_MIN").getInt(null)
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
        val startMethod: Method,
        val stopMethod: Method,
        val cpuMinType: Int,
        val gpuMinType: Int
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
