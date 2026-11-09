package com.s410211.a55wuwa

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val ioExecutor = Executors.newSingleThreadExecutor()
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
        scroll.addView(page, ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT,
            ScrollView.LayoutParams.WRAP_CONTENT
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
            text = "重新整理裝置資訊"
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
            val result = runCatching { DeviceProfileReader.read(applicationContext) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                refreshButton.isEnabled = true
                refreshButton.text = "重新整理裝置資訊"
                render(result.getOrNull(), result.exceptionOrNull())
            }
        }
    }

    private fun render(profile: DeviceProfile?, failure: Throwable?) {
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
        page.addView(card(
            "GPU 即時負載與頻率",
            "尚未讀取。候選 sysfs 節點必須先在 SM-A5560 零售韌體上確認存在、格式、單位與權限。頻率表不會當成當前頻率。"
        ))
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
        refreshButton.text = "重新整理裝置資訊"
        page.addView(refreshButton, marginParams(top = 16))
        page.addView(label(
            "目前版本：0.1.0 · 裝置資訊與 GPU 解析核心",
            11,
            Color.rgb(132, 148, 156),
            false
        ), marginParams(top = 18))
    }

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

    private fun card(heading: String, value: String): LinearLayout {
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
        container.addView(TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(Color.rgb(218, 229, 228))
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(9), 0, 0)
        })
        return container.apply { layoutParams = marginParams(top = 10) }
    }

    private fun marginParams(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }
}
