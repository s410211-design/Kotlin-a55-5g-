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
import com.s410211.a55wuwa.shell.ShizukuGateway
import com.s410211.a55wuwa.telemetry.GpuNodeCatalog
import com.s410211.a55wuwa.telemetry.GpuSysfsReader
import com.s410211.a55wuwa.telemetry.Reading
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
    private lateinit var scheduler: ScheduledExecutorService
    private lateinit var operations: ExecutorService
    private var sampleTask: ScheduledFuture<*>? = null
    private val thermalRestoreQueued = AtomicBoolean(false)
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
        boostController = CpuBoostController(applicationContext, gatewayPlaceholder())
        gateway.start()
        if (boostController.hasPendingJournal()) {
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
            if (state.userServiceConnected && boostController.hasPendingJournal()) {
                operations.execute {
                    val result = boostController.restore()
                    sessionText = outcomeText(result)
                    updateNotification()
                }
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
            ACTION_BOOST -> {
                val percent = intent?.getIntExtra(EXTRA_PERCENT, DEFAULT_PERCENT)
                    ?: DEFAULT_PERCENT
                operations.execute {
                    if (!awaitUserService()) {
                        sessionText = "Boost 未執行：Shizuku UserService 未連線"
                    } else {
                        sessionText = outcomeText(boostController.apply(percent))
                    }
                    updateNotification()
                }
            }
            ACTION_RESTORE -> operations.execute {
                if (!awaitUserService() && boostController.hasPendingJournal()) {
                    sessionText = "還原失敗：Shizuku UserService 未連線，請先重新連線"
                } else {
                    sessionText = outcomeText(boostController.restore())
                }
                updateNotification()
            }
            ACTION_STOP -> operations.execute {
                if (boostController.hasPendingJournal()) {
                    if (!awaitUserService()) {
                        sessionText = "停止暫緩：無法連線 Shizuku，尚有 Boost 還原記錄"
                        updateNotification()
                        return@execute
                    }
                    val result = boostController.restore()
                    if (result is CpuBoostOutcome.Rejected) {
                        sessionText = "停止暫緩：" + result.reason
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
            boostController.hasPendingJournal() &&
            thermalRestoreQueued.compareAndSet(false, true)
        ) {
            lastThermalRestoreAttemptStatus = lastThermalStatus
            operations.execute {
                try {
                    sessionText = "熱狀態升高，正在安全還原 CPU Boost"
                    updateNotification()
                    sessionText = outcomeText(boostController.restore())
                } finally {
                    thermalRestoreQueued.set(false)
                    updateNotification()
                }
            }
        }
        updateHud()
        if (lastThermalStatus != previousThermalStatus) updateNotification()
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
        const val EXTRA_PERCENT = "boost_percent"
        const val DEFAULT_PERCENT = 80
        const val SNAPSHOT_PREFS = "performance_session_snapshot"
        private const val CHANNEL_ID = "a55_performance_session"
        private const val NOTIFICATION_ID = 5502
        private const val SAMPLE_INTERVAL_MS = 1_000L
        private const val SHIZUKU_WAIT_ATTEMPTS = 40
        private const val SHIZUKU_WAIT_INTERVAL_MS = 250L
        private val CPU_CURRENT_PATHS = listOf(
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq",
            "/sys/devices/system/cpu/cpufreq/policy4/scaling_cur_freq"
        )
    }
}
