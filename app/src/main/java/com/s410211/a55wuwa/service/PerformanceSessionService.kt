package com.s410211.a55wuwa.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.s410211.a55wuwa.MainActivity
import com.s410211.a55wuwa.boost.CpuBoostController
import com.s410211.a55wuwa.boost.CpuBoostOutcome
import com.s410211.a55wuwa.boost.GpuBoostController
import com.s410211.a55wuwa.boost.GpuBoostOutcome
import com.s410211.a55wuwa.shell.ShizukuGateway
import com.s410211.a55wuwa.telemetry.GpuNodeCatalog
import com.s410211.a55wuwa.telemetry.CpuLoadTracker
import com.s410211.a55wuwa.telemetry.CpuStatParser
import com.s410211.a55wuwa.telemetry.DeviceHealthReader
import com.s410211.a55wuwa.telemetry.GpuSysfsReader
import com.s410211.a55wuwa.telemetry.Reading
import com.s410211.a55wuwa.telemetry.SurfaceFlingerTimeStatsParser
import com.s410211.a55wuwa.telemetry.ThermalStatusReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-visible owner of monitoring and Boost lifecycle.
 *
 * The service never disables thermal policy. SEVERE or higher thermal status
 * triggers conditional CPU Boost restoration; MODERATE limits Boost to 70%. A persistent journal is also
 * retained so a later service start can recover interrupted work.
 */
class PerformanceSessionService : Service() {
    private lateinit var gateway: ShizukuGateway
    private lateinit var boostController: CpuBoostController
    private lateinit var gpuBoostController: GpuBoostController
    private lateinit var scheduler: ScheduledExecutorService
    private lateinit var operations: ExecutorService
    private var sampleTask: ScheduledFuture<*>? = null
    private var fpsTask: ScheduledFuture<*>? = null
    @Volatile private var fpsCaptureActive = false
    @Volatile private var fpsCaptureRequested = false
    @Volatile private var fpsStopRequested = false
    @Volatile private var lastFpsTotalFrames: Long? = null
    @Volatile private var lastFpsSampleAt: Long? = null
    private val thermalRestoreQueued = AtomicBoolean(false)
    private val cpuLoadTracker = CpuLoadTracker()
    private var hudView: TextView? = null
    private var windowManager: WindowManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastThermalRestoreAttemptStatus: Int = -1
    @Volatile private var sessionText: String = "準備中"
    @Volatile private var lastThermalStatus: Int = -1

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        scheduler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "a55-session-monitor").apply { isDaemon = true }
        }
        operations = Executors.newSingleThreadExecutor { task ->
            Thread(task, "a55-session-operations").apply { isDaemon = true }
        }
        val sharedGateway = gatewayPlaceholder()
        boostController = CpuBoostController(applicationContext, sharedGateway)
        gpuBoostController = GpuBoostController(applicationContext, sharedGateway)
        val savedFpsPreferences = getSharedPreferences(SNAPSHOT_PREFS, MODE_PRIVATE)
        fpsCaptureRequested = savedFpsPreferences.getBoolean("fps_capture_requested", false)
        fpsStopRequested = savedFpsPreferences.getBoolean("fps_stop_requested", false)
        gateway.start()
        if (hasPendingBoostJournal()) {
            sessionText = "偵測到待還原的 CPU Boost 記錄"
        }
    }

    private fun gatewayPlaceholder(): ShizukuGateway {
        lateinit var created: ShizukuGateway
        created = ShizukuGateway { state ->
            sessionText = if (state.userServiceConnected) {
                "Shizuku 已連線（UID ${state.uid ?: "未知"}）"
            } else {
                "Shizuku 未連線"
            }
            updateNotification()
            if (state.userServiceConnected && hasPendingBoostJournal()) {
                operations.execute {
                    sessionText = restoreBothBoostsText()
                    updateNotification()
                }
            }
            if (state.userServiceConnected && fpsStopRequested) {
                operations.execute { stopFpsCapture() }
            } else if (state.userServiceConnected && fpsCaptureRequested && !fpsCaptureActive) {
                operations.execute { startFpsCapture() }
            }
        }
        gateway = created
        return created
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForeground()
        startSampling()
        getSharedPreferences(SNAPSHOT_PREFS, MODE_PRIVATE)
            .edit().putBoolean("service_active", true).apply()
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                sessionText = "監控服務執行中"
                updateNotification()
            }
            ACTION_SHOW_HUD -> {
                if (showHud()) sessionText = "監控與 HUD 執行中"
                else sessionText = "HUD 未顯示：尚未授予懸浮視窗權限"
                updateNotification()
            }
            ACTION_HIDE_HUD -> {
                hideHud()
                sessionText = "HUD 已隱藏"
                updateNotification()
            }
            ACTION_START_FPS -> {
                fpsStopRequested = false
                fpsCaptureRequested = true
                getSharedPreferences(SNAPSHOT_PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean("fps_capture_requested", true)
                    .putBoolean("fps_stop_requested", false)
                    .apply()
                operations.execute {
                    if (!awaitUserService()) {
                        setFpsStatus("FPS 未啟動：Shizuku UserService 未連線")
                    } else {
                        startFpsCapture()
                    }
                }
            }
            ACTION_STOP_FPS -> operations.execute {
                fpsStopRequested = true
                getSharedPreferences(SNAPSHOT_PREFS, MODE_PRIVATE)
                    .edit().putBoolean("fps_stop_requested", true).apply()
                if (!stopFpsCapture()) {
                    sessionText = "FPS 停止尚未完成：" +
                        (getSharedPreferences(SNAPSHOT_PREFS, MODE_PRIVATE)
                            .getString("fps_status", "Shizuku 未連線") ?: "Shizuku 未連線")
                    updateNotification()
                }
            }
            ACTION_BOOST -> {
                val percent = intent?.getIntExtra(EXTRA_PERCENT, DEFAULT_PERCENT)
                    ?: DEFAULT_PERCENT
                operations.execute {
                    if (!awaitUserService()) {
                        sessionText = "Boost 未執行：Shizuku UserService 未連線"
                    } else {
                        val cpuResult = boostController.apply(percent)
                        val gpuResult = gpuBoostController.apply(percent)
                        sessionText = applyBothBoostsText(cpuResult, gpuResult)
                    }
                    updateNotification()
                }
            }
            ACTION_RESTORE -> operations.execute {
                if (!awaitUserService() && hasPendingBoostJournal()) {
                    sessionText = "還原失敗：Shizuku UserService 未連線，請先重新連線"
                } else {
                    sessionText = restoreBothBoostsText()
                }
                updateNotification()
            }
            ACTION_STOP -> operations.execute {
                if (fpsCaptureRequested || fpsCaptureActive) {
                    fpsStopRequested = true
                    getSharedPreferences(SNAPSHOT_PREFS, MODE_PRIVATE)
                        .edit().putBoolean("fps_stop_requested", true).apply()
                }
                if ((fpsCaptureRequested || fpsCaptureActive || fpsStopRequested) && !awaitUserService()) {
                    sessionText = "停止暫緩：FPS 擷取仍需要 Shizuku 送出 disable"
                    updateNotification()
                    return@execute
                }
                if (!stopFpsCapture()) {
                    sessionText = "停止暫緩：SurfaceFlinger timestats 尚未確認停用"
                    updateNotification()
                    return@execute
                }
                if (hasPendingBoostJournal()) {
                    if (!awaitUserService()) {
                        sessionText = "停止暫緩：無法連線 Shizuku，尚有 Boost 還原記錄"
                        updateNotification()
                        return@execute
                    }
                    val cpuRestore = boostController.restore()
                    val gpuRestore = gpuBoostController.restore()
                    if (cpuRestore is CpuBoostOutcome.Rejected ||
                        gpuRestore is GpuBoostOutcome.Rejected
                    ) {
                        sessionText = "停止暫緩：CPU " + cpuRestoreText(cpuRestore) +
                            "；GPU " + gpuRestoreText(gpuRestore)
                        updateNotification()
                        return@execute
                    }
                }
                mainHandler.post {
                    stopSampling()
                    hideHud()
                    getSharedPreferences(SNAPSHOT_PREFS, MODE_PRIVATE)
                        .edit().putBoolean("service_active", false).apply()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
        return START_STICKY
    }

    private fun enterForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startSampling() {
        if (sampleTask != null) return
        sampleTask = scheduler.scheduleWithFixedDelay(
            { runCatching { sampleOnce() } },
            0L,
            SAMPLE_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun stopSampling() {
        sampleTask?.cancel(true)
        sampleTask = null
    }

    private fun sampleOnce() {
        val previousThermalStatus = lastThermalStatus
        val at = SystemClock.elapsedRealtime()
        val snapshot = getSharedPreferences(SNAPSHOT_PREFS, MODE_PRIVATE).edit()
            .putLong("sampled_at", at)

        GpuNodeCatalog.candidates.forEach { candidate ->
            val remote = gateway.readNode(candidate.path, at)
            val reading = remote ?: GpuSysfsReader.read(candidate, at)
            if (reading is Reading.Value) {
                snapshot.putString("gpu_" + candidate.path.hashCode(), reading.value.take(160))
            }
        }

        CPU_CURRENT_PATHS.forEachIndexed { index, path ->
            val reading = gateway.readNode(path, at)
            if (reading is Reading.Value) {
                snapshot.putString("cpu_policy_$index", reading.value.take(64))
            }
        }

        val procStat = gateway.readNode("/proc/stat", at)
        if (procStat is Reading.Value) {
            when (val parsed = CpuStatParser.parse(procStat.value, at)) {
                is Reading.Value -> when (val load = cpuLoadTracker.update(parsed.value, at)) {
                    is Reading.Value -> snapshot.putFloat("cpu_load_percent", load.value.toFloat())
                    else -> snapshot.putString("cpu_load_status", readingReason(load))
                }
                else -> snapshot.putString("cpu_load_status", readingReason(parsed))
            }
        } else if (procStat != null) {
            snapshot.putString("cpu_load_status", readingReason(procStat))
        }

        val health = DeviceHealthReader.read(this)
        snapshot.putInt("battery_level", health.batteryLevelPercent ?: -1)
        snapshot.putFloat("battery_temp_c", health.batteryTemperatureCelsius?.toFloat() ?: -1f)
        snapshot.putInt("battery_voltage_mv", health.batteryVoltageMillivolts ?: -1)
        snapshot.putInt("battery_charging", when (health.charging) { true -> 1; false -> 0; null -> -1 })
        snapshot.putString("network_transport", health.networkTransport ?: "未連線／未知")
        snapshot.putInt("network_validated", when (health.networkValidated) { true -> 1; false -> 0; null -> -1 })

        when (val thermal = ThermalStatusReader.read(this, at)) {
            is Reading.Value -> {
                lastThermalStatus = thermal.value.status
                snapshot.putInt("thermal_status", thermal.value.status)
                thermal.value.headroom?.let { snapshot.putFloat("thermal_headroom", it) }
            }
            else -> snapshot.putInt("thermal_status", -1)
        }
        snapshot.putString("session_status", sessionText).apply()

        if (lastThermalStatus < 3) lastThermalRestoreAttemptStatus = -1
        if (lastThermalStatus >= 3 &&
            lastThermalRestoreAttemptStatus != lastThermalStatus &&
            hasPendingBoostJournal() &&
            thermalRestoreQueued.compareAndSet(false, true)
        ) {
            lastThermalRestoreAttemptStatus = lastThermalStatus
            operations.execute {
                try {
                    sessionText = "熱狀態升高，正在安全還原 CPU Boost"
                    updateNotification()
                    sessionText = restoreBothBoostsText()
                } finally {
                    thermalRestoreQueued.set(false)
                    updateNotification()
                }
            }
        }
        updateHud()
        if (lastThermalStatus != previousThermalStatus) updateNotification()
    }

    private fun startFpsCapture() {
        if (fpsCaptureActive) return
        val prefs = getSharedPreferences(SNAPSHOT_PREFS, MODE_PRIVATE)
        val packageName = prefs.getString("game_package", null)
        if (packageName.isNullOrBlank()) {
            setFpsStatus("尚未選取鳴潮套件；請先透過啟動器按鈕啟動遊戲")
            return
        }
        val result = gateway.startSurfaceFlingerTimeStats(
            packageName,
            SystemClock.elapsedRealtime()
        )
        if (result !is Reading.Value) {
            setFpsStatus("FPS 啟動失敗：" + readingReason(result))
            return
        }
        fpsCaptureActive = true
        fpsCaptureRequested = true
        fpsStopRequested = false
        lastFpsTotalFrames = null
        lastFpsSampleAt = null
        setFpsStatus("已開始擷取 SurfaceFlinger 遊戲 layer：$packageName")
        fpsTask?.cancel(true)
        fpsTask = scheduler.scheduleWithFixedDelay(
            { runCatching { sampleGameFps(packageName) } },
            0L,
            FPS_SAMPLE_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun sampleGameFps(packageName: String) {
        if (!fpsCaptureActive) return
        val at = SystemClock.elapsedRealtime()
        val raw = gateway.dumpSurfaceFlingerTimeStats(packageName, at)
        if (raw !is Reading.Value) {
            setFpsStatus("FPS 暫時不可用：" + readingReason(raw))
            return
        }
        when (val parsed = SurfaceFlingerTimeStatsParser.parse(raw.value, packageName, at)) {
            is Reading.Value -> {
                val current = parsed.value
                val previousFrames = lastFpsTotalFrames
                val previousAt = lastFpsSampleAt
                val windowFps = if (
                    previousFrames != null && previousAt != null &&
                    current.totalFrames >= previousFrames && at > previousAt &&
                    current.totalFrames > previousFrames
                ) {
                    (current.totalFrames - previousFrames) * 1_000.0 / (at - previousAt)
                } else null

                lastFpsTotalFrames = current.totalFrames
                lastFpsSampleAt = at
                val prefs = getSharedPreferences(SNAPSHOT_PREFS, MODE_PRIVATE)
                prefs.edit()
                    .putString("fps_status", if (windowFps == null) "等待下一個有效影格區間" else "有效")
                    .putFloat("fps_cumulative", current.cumulativeAverageFps.toFloat())
                    .putString("fps_window", windowFps?.toString())
                    .putLong("fps_total_frames", current.totalFrames)
                    .putString("fps_layer", current.layerName.take(240))
                    .putLong("fps_sampled_at", at)
                    .apply()
                updateHud()
            }
            else -> setFpsStatus("FPS layer 尚未有效：" + readingReason(parsed))
        }
    }

    private fun stopFpsCapture(): Boolean {
        if (fpsCaptureRequested || fpsCaptureActive || fpsStopRequested) {
            val stopResult = gateway.stopSurfaceFlingerTimeStats(SystemClock.elapsedRealtime())
            if (stopResult !is Reading.Value) {
                setFpsStatus("無法確認 timestats 已停用：" + readingReason(stopResult))
                return false
            }
        }
        fpsTask?.cancel(true)
        fpsTask = null
        fpsCaptureActive = false
        fpsCaptureRequested = false
        fpsStopRequested = false
        lastFpsTotalFrames = null
        lastFpsSampleAt = null
        getSharedPreferences(SNAPSHOT_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean("fps_capture_requested", false)
            .putBoolean("fps_stop_requested", false)
            .putString("fps_status", "FPS 擷取已停止")
            .remove("fps_window")
            .apply()
        return true
    }

    private fun setFpsStatus(status: String) {
        getSharedPreferences(SNAPSHOT_PREFS, MODE_PRIVATE)
            .edit().putString("fps_status", status.take(240)).apply()
        updateHud()
    }

    private fun readingReason(reading: Reading<*>): String = when (reading) {
        is Reading.Value -> "有效"
        is Reading.AwaitingSample -> reading.reason
        is Reading.Unsupported -> reading.reason
        is Reading.PermissionDenied -> reading.reason
        is Reading.NotFound -> reading.reason
        is Reading.InvalidData -> reading.reason
        is Reading.TemporarilyUnavailable -> reading.reason
        is Reading.Error -> reading.reason
    }

    private fun showHud(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        if (hudView != null) return true
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(14, 10, 14, 10)
            background = GradientDrawable().apply {
                setColor(0xDD101820.toInt())
                cornerRadius = 18f
                setStroke(1, 0xFF78E0BD.toInt())
            }
            text = "A55 HUD\n等待第一筆樣本"
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 12
            y = 140
        }
        return try {
            manager.addView(view, params)
            windowManager = manager
            hudView = view
            updateHud()
            true
        } catch (_: Exception) {
            hudView = null
            windowManager = null
            false
        }
    }

    private fun hideHud() {
        val view = hudView ?: return
        runCatching { windowManager?.removeView(view) }
        hudView = null
        windowManager = null
    }

    private fun updateHud() {
        val view = hudView ?: return
        val prefs = getSharedPreferences(SNAPSHOT_PREFS, MODE_PRIVATE)
        fun rawGpu(path: String): String =
            prefs.getString("gpu_" + path.hashCode(), null)?.take(24) ?: "—"
        fun cpuMHz(index: Int): String {
            val khz = prefs.getString("cpu_policy_$index", null)?.toLongOrNull()
                ?: return "—"
            return String.format(java.util.Locale.US, "%.0f MHz", khz / 1_000.0)
        }
        val fpsWindow = prefs.getString("fps_window", null)?.toDoubleOrNull()
        val fpsCumulative = prefs.getFloat("fps_cumulative", -1f)
        val fpsText = when {
            fpsWindow != null && fpsWindow.isFinite() && fpsWindow > 0.0 ->
                String.format(java.util.Locale.US, "%.1f FPS (5s)", fpsWindow)
            fpsCumulative > 0f ->
                String.format(java.util.Locale.US, "%.1f FPS (session avg)", fpsCumulative)
            else -> "FPS：${prefs.getString("fps_status", "未啟動") ?: "未啟動"}"
        }
        val thermal = when (prefs.getInt("thermal_status", -1)) {
            0 -> "NONE"
            1 -> "LIGHT"
            2 -> "MODERATE"
            3 -> "SEVERE"
            4 -> "CRITICAL"
            5 -> "EMERGENCY"
            6 -> "SHUTDOWN"
            else -> "未知"
        }
        view.text = buildString {
            appendLine("A55 · 鳴潮 HUD")
            appendLine("GPU busy raw：${rawGpu("/sys/kernel/gpu/gpu_busy")}")
            appendLine("GPU clock raw：${rawGpu("/sys/kernel/gpu/gpu_clock")}")
            appendLine("CPU policy0：${cpuMHz(0)}")
            appendLine("CPU policy4：${cpuMHz(1)}")
            val batteryTemp = prefs.getFloat("battery_temp_c", -1f)
            appendLine(if (batteryTemp > 0f) String.format(java.util.Locale.US, "電池：%.1f°C", batteryTemp) else "電池溫度：—")
            val cpuLoad = prefs.getFloat("cpu_load_percent", -1f)
            appendLine(if (cpuLoad >= 0f) String.format(java.util.Locale.US, "CPU 使用率：%.1f%%", cpuLoad) else "CPU 使用率：未取得")
            appendLine(fpsText)
            append("Thermal：$thermal")
        }
    }

    private fun awaitUserService(): Boolean {
        repeat(SHIZUKU_WAIT_ATTEMPTS) {
            if (gateway.snapshot().userServiceConnected) return true
            if (Thread.currentThread().isInterrupted) return false
            try {
                Thread.sleep(SHIZUKU_WAIT_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return gateway.snapshot().userServiceConnected
    }

    private fun hasPendingBoostJournal(): Boolean =
        boostController.hasPendingJournal() || gpuBoostController.hasPendingJournal()

    private fun applyBothBoostsText(
        cpu: CpuBoostOutcome,
        gpu: GpuBoostOutcome
    ): String = "CPU：${cpuOutcomeText(cpu)}；GPU：${gpuOutcomeText(gpu)}"

    private fun restoreBothBoostsText(): String {
        val cpu = boostController.restore()
        val gpu = gpuBoostController.restore()
        return "CPU：${cpuRestoreText(cpu)}；GPU：${gpuRestoreText(gpu)}"
    }

    private fun cpuOutcomeText(outcome: CpuBoostOutcome): String = when (outcome) {
        is CpuBoostOutcome.Applied -> "已套用 ${outcome.effectivePercent}% 並讀回驗證"
        is CpuBoostOutcome.Restored -> "已還原 ${outcome.restoredPolicies} 個 policy；外部變更未覆寫 ${outcome.externalChanges.joinToString()}"
        is CpuBoostOutcome.Rejected -> "未套用：" + outcome.reason
        CpuBoostOutcome.NoChange -> "無需變更"
    }

    private fun gpuOutcomeText(outcome: GpuBoostOutcome): String = when (outcome) {
        is GpuBoostOutcome.Applied -> "已套用 ${outcome.effectivePercent}%（raw OPP ${outcome.plan.targetMinRaw}）並讀回驗證"
        is GpuBoostOutcome.Restored -> when {
            outcome.externalChange -> "偵測到外部變更，未覆寫"
            outcome.changed -> "已還原原始 GPU 最低頻率"
            else -> "無需變更"
        }
        is GpuBoostOutcome.Rejected -> "未套用：" + outcome.reason
        GpuBoostOutcome.NoChange -> "無需變更"
    }

    private fun cpuRestoreText(outcome: CpuBoostOutcome): String = when (outcome) {
        is CpuBoostOutcome.Restored -> "還原完成 ${outcome.restoredPolicies} 個 policy；外部變更未覆寫 ${outcome.externalChanges.joinToString()}"
        is CpuBoostOutcome.Rejected -> "還原失敗：" + outcome.reason
        is CpuBoostOutcome.Applied -> "異常狀態：回報為已套用"
        CpuBoostOutcome.NoChange -> "無待還原項目"
    }

    private fun gpuRestoreText(outcome: GpuBoostOutcome): String = when (outcome) {
        is GpuBoostOutcome.Restored -> when {
            outcome.externalChange -> "外部變更未覆寫"
            outcome.changed -> "已還原"
            else -> "無待還原項目"
        }
        is GpuBoostOutcome.Rejected -> "還原失敗：" + outcome.reason
        is GpuBoostOutcome.Applied -> "異常狀態：回報為已套用"
        GpuBoostOutcome.NoChange -> "無待還原項目"
    }

    private fun outcomeText(outcome: CpuBoostOutcome): String = when (outcome) {
        is CpuBoostOutcome.Applied -> "CPU Boost 已寫入並讀回驗證：${outcome.plans.size} 個 policy（目標 ${outcome.effectivePercent}%）"
        is CpuBoostOutcome.Restored -> "還原完成：${outcome.restoredPolicies} 個 policy；外部變更未覆寫：${outcome.externalChanges.joinToString().ifBlank { "無" }}"
        is CpuBoostOutcome.Rejected -> "未套用／未還原：" + outcome.reason
        CpuBoostOutcome.NoChange -> "目前沒有需要變更或還原的 CPU Boost"
    }

    private fun updateNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
        getSharedPreferences(SNAPSHOT_PREFS, MODE_PRIVATE)
            .edit().putString("session_status", sessionText).apply()
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, PerformanceSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val restoreIntent = PendingIntent.getService(
            this, 2, Intent(this, PerformanceSessionService::class.java).setAction(ACTION_RESTORE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val launchIntent = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val thermal = when (lastThermalStatus) {
            0 -> "NONE"
            1 -> "LIGHT"
            2 -> "MODERATE"
            3 -> "SEVERE"
            4 -> "CRITICAL"
            5 -> "EMERGENCY"
            6 -> "SHUTDOWN"
            else -> "未知"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("鳴潮效能監控")
            .setContentText("$sessionText · Thermal $thermal")
            .setStyle(Notification.BigTextStyle().bigText("$sessionText\n熱狀態：$thermal\nBoost 只調整 CPU 最低頻率限制，不修改最高上限或溫控。"))
            .setContentIntent(launchIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_revert, "還原 Boost", restoreIntent).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "停止服務", stopIntent).build())
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 &&
            manager.getNotificationChannel(CHANNEL_ID) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "效能監控服務",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "鳴潮監控與 CPU Boost 狀態"
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopSampling()
        fpsTask?.cancel(true)
        hideHud()
        getSharedPreferences(SNAPSHOT_PREFS, MODE_PRIVATE)
            .edit().putBoolean("service_active", false).apply()
        scheduler.shutdownNow()
        operations.shutdownNow()
        gateway.close()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.s410211.a55wuwa.action.START"
        const val ACTION_BOOST = "com.s410211.a55wuwa.action.BOOST"
        const val ACTION_RESTORE = "com.s410211.a55wuwa.action.RESTORE"
        const val ACTION_STOP = "com.s410211.a55wuwa.action.STOP"
        const val ACTION_SHOW_HUD = "com.s410211.a55wuwa.action.SHOW_HUD"
        const val ACTION_HIDE_HUD = "com.s410211.a55wuwa.action.HIDE_HUD"
        const val ACTION_START_FPS = "com.s410211.a55wuwa.action.START_FPS"
        const val ACTION_STOP_FPS = "com.s410211.a55wuwa.action.STOP_FPS"
        const val EXTRA_PERCENT = "boost_percent"
        const val DEFAULT_PERCENT = 80
        const val SNAPSHOT_PREFS = "performance_session_snapshot"
        private const val CHANNEL_ID = "a55_performance_session"
        private const val NOTIFICATION_ID = 5502
        private const val SAMPLE_INTERVAL_MS = 1_000L
        private const val FPS_SAMPLE_INTERVAL_MS = 5_000L
        private const val SHIZUKU_WAIT_ATTEMPTS = 40
        private const val SHIZUKU_WAIT_INTERVAL_MS = 250L
        private val CPU_CURRENT_PATHS = listOf(
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq",
            "/sys/devices/system/cpu/cpufreq/policy4/scaling_cur_freq"
        )
    }
}
