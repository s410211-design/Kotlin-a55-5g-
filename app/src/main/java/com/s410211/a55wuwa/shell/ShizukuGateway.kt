package com.s410211.a55wuwa.shell

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import com.s410211.a55wuwa.telemetry.Reading
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

data class ShizukuGatewayState(
    val binderAlive: Boolean = false,
    val permissionGranted: Boolean = false,
    val userServiceConnected: Boolean = false,
    val uid: Int? = null,
    val pid: Int? = null,
    val selinuxContext: String? = null,
    val detail: String = "尚未檢查 Shizuku"
) {
    fun displayText(): String {
        val identity = when (uid) {
            0 -> "Root UID 0"
            2000 -> "Shell UID 2000"
            null -> "尚未取得"
            else -> "UID " + uid
        }
        return buildString {
            appendLine("Binder：" + if (binderAlive) "存活" else "未連線")
            appendLine("App 授權：" + if (permissionGranted) "已授權" else "未授權")
            appendLine("UserService：" + if (userServiceConnected) "已連線" else "未連線")
            append("執行身分：" + identity)
            if (pid != null) append("／PID " + pid)
            if (!selinuxContext.isNullOrBlank()) appendLine("\nSELinux：" + selinuxContext)
            appendLine("\n狀態：" + detail)
        }
    }
}

/**
 * Owns Shizuku permission and UserService lifecycle. The actual remote UID is
 * obtained from inside the UserService; the App never assumes Shell is Root.
 */
class ShizukuGateway(
    private val removeUserServiceOnClose: Boolean = true,
    private val onStateChanged: (ShizukuGatewayState) -> Unit
) : AutoCloseable {

    @Volatile private var currentState = ShizukuGatewayState()
    @Volatile private var remote: IA55ShizukuService? = null
    @Volatile private var started = false
    @Volatile private var bindRequested = false
    @Volatile private var closed = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "a55-shizuku-gateway").apply { isDaemon = true }
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.s410211.a55wuwa", A55ShizukuUserService::class.java.name)
    )
        .tag("com.s410211.a55wuwa.telemetry.v1")
        .daemon(false)
        .processNameSuffix("shizuku")
        .debuggable(false)
        .version(USER_SERVICE_VERSION)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        handleBinderReceived()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        remote = null
        bindRequested = false
        publish(
            binderAlive = false,
            permissionGranted = false,
            userServiceConnected = false,
            uid = null,
            pid = null,
            selinuxContext = null,
            detail = "Shizuku Binder 已中斷；等待服務重新啟動"
        )
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PERMISSION_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    publish(
                        binderAlive = true,
                        permissionGranted = true,
                        detail = "Shizuku 授權完成，正在啟動 UserService"
                    )
                    bindUserService()
                } else {
                    publish(
                        binderAlive = Shizuku.pingBinder(),
                        permissionGranted = false,
                        userServiceConnected = false,
                        detail = "使用者尚未授權 Shizuku"
                    )
                }
            }
        }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = IA55ShizukuService.Stub.asInterface(binder)
            if (!binder.isBinderAlive || service == null) {
                remote = null
                bindRequested = false
                publish(
                    binderAlive = Shizuku.pingBinder(),
                    permissionGranted = true,
                    userServiceConnected = false,
                    detail = "UserService 回傳無效 Binder"
                )
                return
            }

            remote = service
            publish(
                binderAlive = Shizuku.pingBinder(),
                permissionGranted = true,
                userServiceConnected = true,
                detail = "UserService 已連線，正在確認遠端 UID"
            )
            readIdentity(service)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remote = null
            bindRequested = false
            publish(
                binderAlive = Shizuku.pingBinder(),
                permissionGranted = currentState.permissionGranted,
                userServiceConnected = false,
                uid = null,
                pid = null,
                selinuxContext = null,
                detail = "UserService 程序已中斷"
            )
        }
    }

    fun snapshot(): ShizukuGatewayState = currentState

    /** Register lifecycle listeners and reconnect if an existing grant is present. */
    fun start() {
        if (started || closed) return
        started = true
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        if (Shizuku.pingBinder()) {
            handleBinderReceived()
        } else {
            publish(
                binderAlive = false,
                permissionGranted = false,
                userServiceConnected = false,
                detail = "等待 Shizuku 服務；請先在手機上啟動 Shizuku"
            )
        }
    }

    /** Invoke only from an explicit user action; permission is not requested silently. */
    fun connectOrRequestPermission() {
        if (closed) return
        if (!Shizuku.pingBinder()) {
            publish(
                binderAlive = false,
                permissionGranted = false,
                userServiceConnected = false,
                detail = "Shizuku 尚未啟動，請先開啟 Shizuku App"
            )
            return
        }
        if (Shizuku.isPreV11()) {
            publish(
                binderAlive = true,
                permissionGranted = false,
                detail = "Shizuku API 版本過舊，需要 v11 或更新版本"
            )
            return
        }

        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                publish(
                    binderAlive = true,
                    permissionGranted = true,
                    detail = "授權已存在，正在連線 UserService"
                )
                bindUserService()
            } else {
                publish(
                    binderAlive = true,
                    permissionGranted = false,
                    detail = "等待 Shizuku 權限核准"
                )
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            }
        } catch (error: Throwable) {
            publish(
                binderAlive = Shizuku.pingBinder(),
                permissionGranted = false,
                detail = "Shizuku 授權檢查失敗：" + error.javaClass.simpleName
            )
        }
    }

    /**
     * null means no remote UserService is available yet, so the caller may
     * use the app-UID reader. A remote denial is returned and must not fallback.
     */
    fun readNode(path: String, sampledAtMillis: Long): Reading<String>? {
        val node = ShizukuNode.fromPath(path) ?: return null
        val service = remote ?: return null
        if (!service.asBinder().pingBinder()) {
            return Reading.TemporarilyUnavailable(
                path,
                sampledAtMillis,
                "Shizuku UserService Binder 已失效"
            )
        }

        return try {
            val response = service.readNode(node.id)
            val status = response.getOrNull(0).orEmpty()
            val raw = response.getOrNull(1).orEmpty()
            val detail = response.getOrNull(2).orEmpty().take(256)
            when (status) {
                "OK" -> if (raw.isBlank()) {
                    Reading.InvalidData(path, sampledAtMillis, "Shizuku 回傳空資料")
                } else {
                    Reading.Value(raw, path, "raw", sampledAtMillis)
                }
                "PERMISSION_DENIED" -> Reading.PermissionDenied(
                    path, sampledAtMillis, detail.ifBlank { "遠端身分沒有讀取權限" }
                )
                "NOT_FOUND" -> Reading.NotFound(
                    path, sampledAtMillis, detail.ifBlank { "節點不存在" }
                )
                "TEMPORARY" -> Reading.TemporarilyUnavailable(
                    path, sampledAtMillis, detail.ifBlank { "節點暫時無法讀取" }
                )
                "OUTPUT_TOO_LARGE", "INVALID_DATA" -> Reading.InvalidData(
                    path, sampledAtMillis, detail.ifBlank { "節點資料無效" }
                )
                else -> Reading.Error(
                    path, sampledAtMillis, detail.ifBlank { "Shizuku 節點讀取失敗：" + status }
                )
            }
        } catch (error: RemoteException) {
            Reading.TemporarilyUnavailable(
                path, sampledAtMillis, "Shizuku IPC 中斷：" + error.javaClass.simpleName
            )
        } catch (error: RuntimeException) {
            Reading.Error(
                path, sampledAtMillis, "Shizuku 呼叫失敗：" + error.javaClass.simpleName
            )
        }
    }

    /**
     * Writes only through the UserService's explicit node allowlist. The
     * remote service validates live OPPs and policy maximum, then reads back.
     */
    fun writeNode(path: String, value: String, sampledAtMillis: Long): Reading<String>? {
        val node = ShizukuNode.fromPath(path) ?: return Reading.Unsupported(
            path, sampledAtMillis, "寫入目標不在白名單"
        )
        val service = remote ?: return Reading.TemporarilyUnavailable(
            path, sampledAtMillis, "Shizuku UserService 尚未連線"
        )
        if (!service.asBinder().pingBinder()) {
            return Reading.TemporarilyUnavailable(
                path, sampledAtMillis, "Shizuku UserService Binder 已失效"
            )
        }
        return try {
            val response = service.writeNode(node.id, value)
            val status = response.getOrNull(0).orEmpty()
            val raw = response.getOrNull(1).orEmpty()
            val detail = response.getOrNull(2).orEmpty().take(256)
            when (status) {
                "OK" -> if (raw.isBlank()) Reading.InvalidData(
                    path, sampledAtMillis, "寫入成功回報缺少讀回值"
                ) else Reading.Value(raw, path, "raw", sampledAtMillis)
                "PERMISSION_DENIED" -> Reading.PermissionDenied(
                    path, sampledAtMillis, detail.ifBlank { "遠端身分沒有寫入權限" }
                )
                "NOT_FOUND" -> Reading.NotFound(
                    path, sampledAtMillis, detail.ifBlank { "控制節點不存在" }
                )
                "UNSUPPORTED" -> Reading.Unsupported(
                    path, sampledAtMillis, detail.ifBlank { "控制節點不支援" }
                )
                "INVALID_DATA", "OUTPUT_TOO_LARGE" -> Reading.InvalidData(
                    path, sampledAtMillis, detail.ifBlank { "寫入值不符合控制規則" }
                )
                "TEMPORARY" -> Reading.TemporarilyUnavailable(
                    path, sampledAtMillis, detail.ifBlank { "控制節點暫時不可用" }
                )
                else -> Reading.Error(
                    path, sampledAtMillis, detail.ifBlank { "Shizuku 寫入失敗：$status" }
                )
            }
        } catch (error: RemoteException) {
            Reading.TemporarilyUnavailable(
                path, sampledAtMillis, "Shizuku IPC 中斷：" + error.javaClass.simpleName
            )
        } catch (error: RuntimeException) {
            Reading.Error(
                path, sampledAtMillis, "Shizuku 寫入呼叫失敗：" + error.javaClass.simpleName
            )
        }
    }

    fun startSurfaceFlingerTimeStats(
        packageName: String,
        sampledAtMillis: Long
    ): Reading<String>? = invokeTimestats(sampledAtMillis) {
        it.startSurfaceFlingerTimeStats(packageName)
    }

    fun dumpSurfaceFlingerTimeStats(
        packageName: String,
        sampledAtMillis: Long
    ): Reading<String>? = invokeTimestats(sampledAtMillis) {
        it.dumpSurfaceFlingerTimeStats(packageName)
    }

    fun stopSurfaceFlingerTimeStats(sampledAtMillis: Long): Reading<String>? =
        invokeTimestats(sampledAtMillis) { it.stopSurfaceFlingerTimeStats() }

    private fun invokeTimestats(
        sampledAtMillis: Long,
        call: (IA55ShizukuService) -> Array<String>
    ): Reading<String>? {
        val source = "SurfaceFlinger --timestats"
        val service = remote ?: return Reading.TemporarilyUnavailable(
            source, sampledAtMillis, "Shizuku UserService 尚未連線"
        )
        if (!service.asBinder().pingBinder()) {
            return Reading.TemporarilyUnavailable(
                source, sampledAtMillis, "Shizuku UserService Binder 已失效"
            )
        }
        return try {
            val response = call(service)
            val status = response.getOrNull(0).orEmpty()
            val raw = response.getOrNull(1).orEmpty()
            val detail = response.getOrNull(2).orEmpty().take(256)
            when (status) {
                "OK" -> Reading.Value(raw, source, "raw", sampledAtMillis)
                "PERMISSION_DENIED" -> Reading.PermissionDenied(source, sampledAtMillis, detail)
                "TEMPORARY" -> Reading.TemporarilyUnavailable(source, sampledAtMillis, detail)
                "INVALID_DATA", "OUTPUT_TOO_LARGE" ->
                    Reading.InvalidData(source, sampledAtMillis, detail)
                "UNSUPPORTED" -> Reading.Unsupported(source, sampledAtMillis, detail)
                else -> Reading.Error(source, sampledAtMillis, detail.ifBlank { "timestats command failed: $status" })
            }
        } catch (error: RemoteException) {
            Reading.TemporarilyUnavailable(
                source, sampledAtMillis, "Shizuku IPC 中斷：" + error.javaClass.simpleName
            )
        } catch (error: RuntimeException) {
            Reading.Error(
                source, sampledAtMillis, "timestats 呼叫失敗：" + error.javaClass.simpleName
            )
        }
    }

    private fun handleBinderReceived() {
        if (closed) return
        if (Shizuku.isPreV11()) {
            publish(
                binderAlive = true,
                permissionGranted = false,
                detail = "Shizuku API 版本過舊，需要 v11 或更新版本"
            )
            return
        }

        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        publish(
            binderAlive = true,
            permissionGranted = granted,
            userServiceConnected = currentState.userServiceConnected,
            detail = if (granted) "授權已存在，正在連線 UserService" else "Shizuku 已啟動，等待 App 授權"
        )
        if (granted) bindUserService()
    }

    private fun bindUserService() {
        if (closed || bindRequested || currentState.userServiceConnected) return
        if (!Shizuku.pingBinder() || !currentState.permissionGranted) return

        bindRequested = true
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (error: Throwable) {
            bindRequested = false
            publish(
                binderAlive = Shizuku.pingBinder(),
                permissionGranted = currentState.permissionGranted,
                userServiceConnected = false,
                detail = "啟動 UserService 失敗：" + error.javaClass.simpleName
            )
        }
    }

    private fun readIdentity(service: IA55ShizukuService) {
        worker.execute {
            val identity = runCatching { service.getIdentity() }.getOrNull()
            val uid = identity?.getOrNull(0)?.toIntOrNull()
            val pid = identity?.getOrNull(1)?.toIntOrNull()
            val context = identity?.getOrNull(2)?.take(256)
            if (remote !== service) return@execute

            val description = when (uid) {
                0 -> "遠端程序確認為 Root UID 0"
                2000 -> "遠端程序確認為 Shell UID 2000"
                null -> "UserService 已連線，但 UID 身分資訊讀取失敗"
                else -> "遠端程序 UID=" + uid
            }
            publish(
                binderAlive = Shizuku.pingBinder(),
                permissionGranted = currentState.permissionGranted,
                userServiceConnected = true,
                uid = uid,
                pid = pid,
                selinuxContext = context,
                detail = description
            )
        }
    }

    private fun publish(
        binderAlive: Boolean = currentState.binderAlive,
        permissionGranted: Boolean = currentState.permissionGranted,
        userServiceConnected: Boolean = currentState.userServiceConnected,
        uid: Int? = currentState.uid,
        pid: Int? = currentState.pid,
        selinuxContext: String? = currentState.selinuxContext,
        detail: String = currentState.detail
    ) {
        if (closed) return
        val next = ShizukuGatewayState(
            binderAlive, permissionGranted, userServiceConnected,
            uid, pid, selinuxContext, detail
        )
        currentState = next
        mainHandler.post {
            if (!closed) onStateChanged(next)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (started) {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        }
        remote = null
        if (bindRequested && Shizuku.pingBinder()) {
            runCatching {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, removeUserServiceOnClose)
            }
        }
        bindRequested = false
        worker.shutdownNow()
    }

    private companion object {
        const val PERMISSION_REQUEST_CODE = 5501
        const val USER_SERVICE_VERSION = 2
    }
}
