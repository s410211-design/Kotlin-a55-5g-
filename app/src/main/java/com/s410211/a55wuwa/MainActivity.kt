package com.s410211.a55wuwa

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.s410211.a55wuwa.telemetry.GpuNodeCandidate
import com.s410211.a55wuwa.telemetry.GpuSampleBatch
import com.s410211.a55wuwa.telemetry.GpuTelemetrySampler
import com.s410211.a55wuwa.telemetry.Reading
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val gpuSampler = GpuTelemetrySampler(intervalMillis = 1_000L)
    private var activityVisible = false
    private var latestGpuResults: List<Pair<GpuNodeCandidate, Reading<String>>>? = null
    private var gpuValueText: TextView? = null
    private lateinit var page: LinearLayout
    private lateinit var refreshButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11, 16, 20)
        window.navigationBarColor = Color.rgb(11, 16, 20)

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            setBackgroundColor(Color.rgb(11, 16, 20))
        }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        scroll.addView(page, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            scroll.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                page.setPadding(
                    dp(20) + bars.left,
                    dp(20) + bars.top,
                    dp(20) + bars.right,
                    dp(28) + bars.bottom
                )
                insets
            }
        }

        refreshButton = Button(this).apply {
            text = "重新整理裝置與 GPU 節點"
            isAllCaps = false
            setTextColor(Color.rgb(11, 16, 20))
            setBackgroundColor(Color.rgb(120, 224, 189))
            setOnClickListener { refreshProfile() }
        }
        setContentView(scroll)
        renderLoading()
        refreshProfile()
    }

    private fun renderLoading() {
        page.removeAllViews()
        page.addView(label("A55 PERFORMANCE LAB", 12, Color.rgb(120, 224, 189), true))
        page.addView(title("鳴潮效能監控"))
        page.addView(body("先確認裝置基礎資料，再逐步啟用真實 GPU、FPS 與權限監控。"))
        page.addView(card("裝置資訊", "正在讀取 Android 回報的硬體、記憶體與儲存資訊…"))
        page.addView(refreshButton, marginParams(top = 16))
    }

    private fun refreshProfile() {
        refreshButton.isEnabled = false
        refreshButton.text = "讀取中…"
        ioExecutor.execute {
            val profileResult = runCatching { DeviceProfileReader.read(applicationContext) }
            val gpuResults = runCatching { gpuSampler.sampleOnce().readings }
                .getOrElse { emptyList() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                refreshButton.isEnabled = true
                refreshButton.text = "重新整理裝置與 GPU 節點"
                render(
                    profileResult.getOrNull(),
                    profileResult.exceptionOrNull(),
                    gpuResults
                )
            }
        }
    }

    private fun render(
        profile: DeviceProfile?,
        failure: Throwable?,
        gpuResults: List<Pair<GpuNodeCandidate, Reading<String>>>
    ) {
        gpuValueText = null
        page.removeAllViews()
        page.addView(label("A55 PERFORMANCE LAB", 12, Color.rgb(120, 224, 189), true))
        page.addView(title("鳴潮效能監控"))
        page.addView(body(
            "監控數值只顯示可驗證的來源；權限不足或韌體不支援時會明確標示，不會用 0 代替。"
        ))

        if (profile != null) {
            val deviceText = buildString {
                appendLine("廠牌：${profile.manufacturer}")
                appendLine("型號：${profile.model}")
                appendLine("裝置代號：${profile.device}")
                appendLine("硬體平台字串：${profile.hardware}")
                appendLine("Android：${profile.androidRelease}（API ${profile.sdkInt}）")
                appendLine("One UI（Build 字串提示）：${profile.oneUiVersionHint ?: "尚未從公開 Build 字串辨識"}")
                appendLine("Build ID：${profile.buildId.ifBlank { "系統未提供" }}")
                appendLine("核心版本：${profile.kernelRelease ?: "系統未提供"}")
                appendLine("Build Display：${profile.buildDisplay.ifBlank { "系統未提供" }}")
                appendLine("Build Fingerprint：${profile.buildFingerprint.ifBlank { "系統未提供" }}")
                append("安全更新：${profile.securityPatch ?: "系統未提供"}")
            }
            page.addView(card("裝置資訊", deviceText))
            val memoryText = buildString {
                appendLine("RAM 總量：${profile.totalRamBytes?.let(DeviceProfileFormatter::formatGiB) ?: "無法取得"}")
                append("RAM 可用：${profile.availableRamBytes?.let(DeviceProfileFormatter::formatGiB) ?: "無法取得"}")
            }
            page.addView(card("記憶體", memoryText))
            val storageText = buildString {
                appendLine("資料分區總量：${profile.totalStorageBytes?.let(DeviceProfileFormatter::formatGiB) ?: "無法取得"}")
                append("資料分區可用：${profile.availableStorageBytes?.let(DeviceProfileFormatter::formatGiB) ?: "無法取得"}")
            }
            page.addView(card("內部儲存", storageText))
        } else {
            page.addView(card(
                "裝置資訊讀取失敗",
                failure?.javaClass?.simpleName ?: "未知錯誤"
            ))
        }

        page.addView(card(
            "目標實機設定",
            "Galaxy A55 5G／8GB RAM／256GB 儲存（依使用者提供）\n" +
                "Android 17／One UI 9（依使用者提供；App 端版本辨識仍待驗證）"
        ))

        val visibleGpuResults = latestGpuResults ?: gpuResults
        page.addView(
            card(
                "GPU 節點唯讀探測",
                formatGpuReadings(visibleGpuResults, android.os.SystemClock.elapsedRealtime())
            ) { gpuValueText = it }
        )


        page.addView(card(
            "遊戲 FPS",
            "尚未取得有效的遊戲呈現時間來源。不會用螢幕更新率或本 App 自身畫面更新率冒充鳴潮 FPS。"
        ))
        page.addView(card(
            "Shizuku／ADB",
            "尚未接入。後續會分別檢查 Binder、授權、UserService 與命令結果；ADB Shell 不等於 Root。"
        ))
        page.addView(card(
            "安全最佳化",
            "目前不會寫入 GPU、CPU 或溫控設定。必須先確認裝置支援、原值、讀回驗證與安全還原方式。"
        ))
        refreshButton.text = "重新整理裝置與 GPU 節點"
        page.addView(refreshButton, marginParams(top = 16))
        page.addView(label(
            "目前版本：0.3.0 · 裝置識別與唯讀 GPU 探測",
            11,
            Color.rgb(132, 148, 156),
            false
        ), marginParams(top = 18))
    }

    private fun formatGpuReadings(
        gpuResults: List<Pair<GpuNodeCandidate, Reading<String>>>,
        sampledAtMillis: Long
    ): String = buildString {
        appendLine("最近取樣：系統啟動後 ${sampledAtMillis} ms")
        appendLine("以下是唯讀節點探測，不代表原始資料已具備負載／頻率語義。")
        appendLine("只有確認零售韌體格式、單位與來源後，才會轉換成 GPU 百分比或頻率。")
        gpuResults.forEach { (candidate, reading) ->
            appendLine()
            appendLine("${candidate.roleLabel()}  ${reading.statusLabel()}")
            append(candidate.path)
            if (reading is Reading.Value) {
                appendLine()
                append("原始資料：${reading.value.toSafeDisplay()}")
            } else {
                val reason = when (reading) {
                    is Reading.AwaitingSample -> reading.reason
                    is Reading.Unsupported -> reading.reason
                    is Reading.PermissionDenied -> reading.reason
                    is Reading.NotFound -> reading.reason
                    is Reading.InvalidData -> reading.reason
                    is Reading.TemporarilyUnavailable -> reading.reason
                    is Reading.Error -> reading.reason
                    is Reading.Value -> ""
                }
                if (reason.isNotBlank()) {
                    appendLine()
                    append("原因：$reason")
                }
            }
        }
    }

    private fun GpuNodeCandidate.roleLabel(): String = when (role) {
        com.s410211.a55wuwa.telemetry.GpuNodeRole.UTILIZATION_CANDIDATE -> "GPU 負載候選"
        com.s410211.a55wuwa.telemetry.GpuNodeRole.CURRENT_CLOCK_CANDIDATE -> "GPU 當前頻率候選"
        com.s410211.a55wuwa.telemetry.GpuNodeRole.SUPPORTED_CLOCK_TABLE -> "GPU 支援頻率表"
        com.s410211.a55wuwa.telemetry.GpuNodeRole.TEMPERATURE_CANDIDATE -> "GPU 溫度候選"
        com.s410211.a55wuwa.telemetry.GpuNodeRole.GPU_IDENTIFICATION -> "GPU 型號資訊"
        com.s410211.a55wuwa.telemetry.GpuNodeRole.MEMORY_INFORMATION -> "GPU 記憶體資訊"
        com.s410211.a55wuwa.telemetry.GpuNodeRole.UTILIZATION_SOURCE -> "GPU 負載來源"
        com.s410211.a55wuwa.telemetry.GpuNodeRole.AVAILABLE_UTILIZATION_SOURCES -> "GPU 可用負載來源"
    }

    private fun Reading<*>.statusLabel(): String = when (this) {
        is Reading.Value -> "可讀取"
        is Reading.AwaitingSample -> "等待下一筆樣本"
        is Reading.Unsupported -> "不支援"
        is Reading.PermissionDenied -> "權限拒絕"
        is Reading.NotFound -> "節點不存在"
        is Reading.InvalidData -> "資料格式無效"
        is Reading.TemporarilyUnavailable -> "暫時不可用"
        is Reading.Error -> "讀取錯誤"
    }

    private fun String.toSafeDisplay(): String = take(160)
        .filter { it == '\n' || it == '\t' || !it.isISOControl() }
        .replace('\n', ' ')
        .replace('\t', ' ')
        .ifBlank { "（空內容）" }

    private fun title(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 28f
        setTextColor(Color.rgb(241, 247, 245))
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun body(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(Color.rgb(171, 187, 190))
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(0, 0, 0, dp(16))
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }

    private fun card(
        heading: String,
        value: String,
        onValueViewCreated: ((TextView) -> Unit)? = null
    ): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.rgb(21, 29, 34))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.rgb(40, 56, 62))
            }
        }
        container.addView(label(heading, 15, Color.rgb(120, 224, 189), true))
        val valueView = TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(Color.rgb(218, 229, 228))
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(9), 0, 0)
        }
        container.addView(valueView)
        onValueViewCreated?.invoke(valueView)
        return container.apply { layoutParams = marginParams(top = 10) }
    }

    private fun marginParams(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onStart() {
        super.onStart()
        activityVisible = true
        gpuSampler.start { batch: GpuSampleBatch ->
            runOnUiThread {
                if (!activityVisible || isFinishing || isDestroyed) return@runOnUiThread
                latestGpuResults = batch.readings
                gpuValueText?.text = formatGpuReadings(batch.readings, batch.sampledAtMillis)
            }
        }
    }

    override fun onStop() {
        activityVisible = false
        gpuSampler.stop()
        super.onStop()
    }

    override fun onDestroy() {
        gpuSampler.close()
        ioExecutor.shutdownNow()
        super.onDestroy()
    }
}
