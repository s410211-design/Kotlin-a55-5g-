# Galaxy A55 5G — Wuthering Waves Monitor & Booster

> **本 README 是本專案的最高層級準則、開發方法與決策依據。**
>
> 所有設計、程式碼、功能、測試及文件都必須遵守本文件。詳細技術規格與驗收項目另見 [docs/PROJECT_SPEC.md](docs/PROJECT_SPEC.md)。若兩份文件有衝突，以本 README 的治理規則為準；技術細節則應提出修訂，不得自行默默改變準則。

---

## 1. 專案定位與目標

以 Kotlin 開發 Samsung Galaxy A55 5G 專用的鳴潮（Wuthering Waves）效能監控與安全調校工具。

核心目標：
- 讓使用者清楚掌握遊戲期間裝置與 GPU 的真實狀態。
- 在裝置、韌體及權限確實支援的範圍內，提供可驗證的效能調校。
- 將複雜的 ADB、Shizuku、系統節點與診斷流程藏在易懂的操作背後。
- 以一鍵操作完成遊戲啟動、監控/HUD、Boost、停止及還原。
- 優先確保安全、穩定、低耗電與操作流暢；效能提升不能凌駕裝置安全。

本專案不是只做外觀展示的假加速器。任何數值、狀態、功能或效能改善都必須有實際資料或可重現的驗證依據。

**免電腦 ADB 目標與驗收：** 正式使用以 App 自身 UID 與 Android 公開 API 為優先，不依賴電腦、USB ADB 或使用者輸入 shell 指令。最後一次完整唯讀硬體盤點腳本與 ADB-free 驗收門檻見 [docs/ADB_FREE_RUNTIME_PLAN.md](docs/ADB_FREE_RUNTIME_PLAN.md) 及 [tools/A55_Final_ReadOnly_Node_Inventory.bat](tools/A55_Final_ReadOnly_Node_Inventory.bat)。正式設計以 Shizuku 作為主要受限系統通道，App 需自動檢查 Binder、授權、UserService 與遠端 UID；仍保留 App 自身 UID 的公開 API 作為基本監控路徑。Shizuku Shell UID 2000 不等於 Root UID 0，不能將授權狀態視為所有 sysfs 節點皆可讀寫。

## 2. 目標裝置與相容性原則

主要驗證目標：
- 裝置：Samsung Galaxy A55 5G
- 優先型號：台灣版 SM-A5560
- SoC：Samsung Exynos 1480
- GPU：Xclipse 530
- 開發語言：Kotlin
- 平台：Android

必須在執行時辨識並記錄實際型號、Android 版本、One UI 版本、核心版本及可取得的 Build 資訊。不得假設不同地區、版本、核心或韌體的系統節點與權限完全相同。

模擬器測試不能取代 SM-A5560 實機驗證。任何尚未在目標實機確認的功能，都必須標示為「待實機驗證」。

## 3. 不可妥協的開發準則

### 3.1 真實性與可驗證性
- 不得偽造 GPU 負載、GPU 頻率、溫度、FPS、記憶體或其他監控數據。
- 不得把讀取失敗、沒有權限或不支援顯示成 0。
- 不得把螢幕更新率當成遊戲 FPS，也不得把一般 App 的 Choreographer FPS 冒稱為遊戲 FPS。
- 不得將「命令已送出」等同於「設定已套用」。
- 不得將「設定已套用」等同於「效能確實提升」。
- 每個讀值應盡可能附上來源、單位、時間戳記及可信狀態。
- 若資料來源不可靠、過期或無法讀取，必須明確顯示狀態與原因。

### 3.2 安全優先
- 不修改電壓，不停用溫控或安全保護。
- 不長時間強制 GPU 維持最高頻率。
- 不以大量強制關閉背景程序作為加速手段。
- 不執行可能造成裝置不穩、資料遺失或不可逆狀態的操作。
- 所有可修改設定都要先記錄原值，並設計可驗證的還原流程。
- 還原時不得覆蓋其他 App、系統或使用者在此期間所做的外部變更。
- 遇到不確定或無法驗證的狀況，採取保守策略，停止該項調校並說明原因。

### 3.3 穩定性優先
- 不允許因監控或 Boost 造成 ANR、UI 凍結、死鎖、無限重試、執行緒失控或記憶體持續成長。
- 所有耗時 I/O、ADB、Shizuku、sysfs 讀取及命令執行都不得放在主執行緒。
- 所有工作必須可取消、有逾時、有資源上限，並在停止時確實清理。
- 一項感測器或資料來源失敗，不得連帶終止其他正常監控項目。
- 不得無限制持有 WakeLock、建立執行緒或提高輪詢頻率。
- 任何重試都必須有退避策略、上限及停止條件。

### 3.4 使用者操作簡單
- 一般使用者不應需要輸入終端機指令、sysfs 路徑或自行判斷 Linux 權限錯誤。
- 優先自動偵測裝置、權限、資料來源及可用功能。
- 權限設定、錯誤診斷與復原提示必須使用清楚的繁體中文。
- 主要操作應簡單直接：啟動鳴潮、開啟/關閉 HUD、Boost、停止、還原。
- 不支援的功能應清楚標示，不可用隱藏錯誤或假動畫掩飾。

## 4. GPU 與系統監控方法

### 4.1 監控項目
依實機可取得且可信的資料，逐步支援：
- GPU 即時負載。
- GPU 目前運作頻率。
- GPU 支援頻率表（必須與目前頻率分開呈現）。
- GPU 溫度（僅限可信感測來源）。
- CPU 負載與頻率（CPUFreq policy 節點須標示 policy 與核心回報語義；不得逕稱為精確硬體時鐘）。
- RAM 使用情況。
- 電池溫度。
- Android 公開 Thermal API 可提供整機 thermal status/headroom；逐感測器溫度必須有額外且已核准的資料來源，不可用 headroom 代替攝氏溫度。
- 網路狀態（資料來源可靠時）。
- FPS（只有取得真正有效的遊戲影格資料來源時才顯示）。

### 4.2 GPU 節點探測
以下僅為候選路徑，不代表零售韌體必定存在或允許讀取：
- `/sys/kernel/gpu/gpu_busy`
- `/sys/kernel/gpu/gpu_clock`
- `/sys/kernel/gpu/gpu_freq_table`
- `/sys/kernel/gpu/gpu_tmu`
- `/sys/kernel/gpu/gpu_model`
- `/sys/kernel/gpu/mem_info`
- `/sys/class/devfreq/*gpu*/`
- `/sys/class/devfreq/*sgpu*/`

探測時必須區分「路徑不存在」、「權限拒絕」、「資料格式錯誤」、「暫時不可用」及「裝置不支援」。不得假設 DRM GPU 一定是 `card0`，應依 driver、name、symlink 等資訊辨識。

### 4.3 數據計算與解讀
- 若 GPU busy/total 是累積計數器，必須以兩次取樣的差值計算：`100 × Δbusy / Δtotal`。
- 必須處理 total 差值為 0、計數器重置、負值、溢位及取樣間隔異常。
- `gpu_freq_table` 是支援頻率清單，不是當下 GPU 頻率。
- `gpu_clock` 可能回傳快取或前次頻率，必須評估資料新鮮度。
- 必須檢查 `utilization_source` 與 `available_utilization_sources`；未確認前不可宣稱是硬體計數器。
- 不可只靠單一 `gpu_model` 字串就斷定 GPU 型號。
- 不可用粗糙正規表示式破壞小數、負號或單位資訊；數值解析必須明確且可測試。



### 4.4 Exynos SGPU 社群核心研究紀錄（非零售韌體保證）

已檢查 [Lyinceer/android_kernel_samsung_exynos_1480](https://github.com/Lyinceer/android_kernel_samsung_exynos_1480) 的 SGPU 相關原始碼，包含 `exynos_gpu_interface.c`、`sgpu_utilization.c` 及 `sgpu_user_interface.c`。此 Repository 僅作為理解 Exynos SGPU 實作的研究材料；它不是 SM-A5560 零售韌體的直接證據。

研究觀察與實作限制：

| 介面/欄位 | 社群核心原始碼觀察 | App 必須採取的處理 |
|---|---|---|
| `gpu_busy` | `gpu_busy_show()` 以核心目前的 `busy_time / total_time × 100` 產生百分比文字，不是直接輸出的累積 busy/total 雙計數器。 | 若實機節點回傳單一百分比，依百分比格式解析；不得再套用累積計數器差值算法。檢查資料範圍、分母與新鮮度。 |
| `gpu_clock` | `gpu_clock_show()` 優先呼叫 `get_cur_freq`；失敗時可能回傳 `previous_freq`。 | 讀值可能是前次頻率，不能不加說明就標示為即時硬體頻率。資料來源或新鮮度不明時，標示為核心回報/可能為最後已知值。 |
| `gpu_freq_table` | 對應可用 GPU operating points/frequency table。 | 只代表支援頻率集合，不代表目前頻率。 |
| `utilization_source` | 該社群核心版本的預設來源為 `jiffies`；其 `hw_counter` 描述項的 `hw_source_valid` 為 `false`。 | 不可只因來源名稱包含 `hw_counter` 就宣稱是有效硬體計數器。需要同時讀取目前來源、可用來源，並確認該核心實作與實機結果。 |
| `available_utilization_sources` | 列出可選來源名稱。 | 可用來源清單不等於目前啟用來源，也不等於來源已被驗證有效。 |

核心介面中的百分比除法亦應視為需要防禦性檢查的邊界：App 對空值、零分母、非有限值、超出 0–100%、資料重置及解析錯誤都必須回報無效/暫時不可用，不能修飾成正常讀值。

**不得將上述觀察直接推論為 SM-A5560 的零售核心行為。** 必須記錄實機型號、韌體/Build fingerprint、核心版本、實際節點路徑、讀取權限、原始格式及取樣時間，才可將該節點標記為已驗證。這些核心程式碼也不構成修改 GPU 頻率、`min_freq`、`max_freq` 或利用率來源的授權；任何新增寫入功能仍須另行提案、取得使用者同意，並通過安全與還原驗證。

### 4.5 GPU 資料來源型別與一致性

每個 GPU 來源都必須明確定義資料形狀與解析器，不可依檔名猜測後套用通用算法：

- **Instant percentage：** 單次讀取即為百分比。檢查格式、範圍與時間戳；若來源本身已是核心計算後的百分比，不得再做差值。
- **Cumulative counters：** 至少包含 busy 與 total 累積計數器；第一次取樣只建立基準，第二次起使用差值計算。計數器回退/重置、溢位、`Δtotal ≤ 0`、`Δbusy < 0` 或 `Δbusy > Δtotal` 都不能產生正常百分比。
- **Cumulative time / vendor statistics：** 必須先確認核心定義、單位及時間基準，再用兩次取樣的差值計算；不可把毫秒、奈秒、jiffies 或其他時間單位混為一談。
- **Current frequency：** 必須與頻率表分開解析，記錄單位、來源與讀取時間；只有能確認為當次即時讀值時才標示為即時。
- **所有存取路徑共用同一解析邏輯：** 直接 sysfs 與 Shizuku shell 只改變讀取通道，不得改變數據的意義、單位或算法。尤其不得讓直接讀取使用差值，而 Shizuku fallback 改用累積值的絕對比例。
- **探測精確節點：** 父目錄存在不能證明目標檔案存在。必須針對實際檔案與讀取結果分類；若權限限制使存在性無法確認，使用未知/受限狀態，不可武斷標示為 NotFound 或 PermissionDenied。

## 5. ADB、Shizuku 與權限準則

- 本專案以 Shizuku 作為主要受限系統通道；必須辨識遠端實際 UID 與 SELinux context。
- Shizuku Shell UID 2000 不等於 Root UID 0；每個節點必須分別驗證讀取/寫入權限。
- 即使取得 Shizuku 權限，仍可能受 SELinux、檔案權限及核心設定限制。
- 必須自動檢查 Shizuku 服務狀態、授權狀態、Binder 存活狀態及連線中斷。
- Binder 死亡、授權撤銷或服務重啟時，必須安全停止受影響工作並提供復原方式。
- 重連使用有上限的指數退避；使用者停止服務後必須取消重試。
- 不得把權限拒絕誤判成節點不存在，也不得把節點不存在誤判成權限拒絕。
- 一般使用流程不得要求使用者自行輸入 shell 指令或節點路徑。


### 5.1 Shell 命令安全閘門

- Shell 存取必須經過集中管理的命令白名單，優先使用 sealed class/enum 等型別化命令；呼叫端不得直接傳入任意 shell 字串。
- 每個命令都要有明確用途、允許的參數、路徑/套件名稱驗證、逾時、取消、退出碼、輸出長度上限及錯誤分類。
- 禁止將 UI、Intent、外部檔案或未驗證資料直接串接到 shell 命令；需要傳入路徑時，先做正規化及允許根目錄檢查，並使用正確引用/參數傳遞方式。
- 優先提供唯讀命令；任何寫入命令都必須在白名單中逐項審核，且遵守 Boost/還原契約。不得提供任意命令執行入口。
- 分別追蹤 Shizuku Binder 是否存活、App 是否獲得授權、UserService 是否已綁定、遠端程序是否可用，以及單次命令是否成功；這些狀態不能合併成單一「Shizuku 已連線」。
- Binder/Service 失效時，清除失效的遠端參照並停止受影響工作；重綁必須冪等、有逾時及有上限。不可在主執行緒等待 Binder 或 UserService。
- stdout/stderr 必須限制長度；避免把 dumpsys、視窗標題、套件清單或使用者資料原文寫入 logcat。記錄診斷資訊時只保留必要且已去識別化的摘要。
- AUTO/強制權限模式必須有明確政策；使用者選擇強制 Shizuku 時，不得在權限失效後默默降級成其他權限層級執行。

### 5.2 Shizuku UserService 實作與效能調校目標

- Shizuku API 與 Provider 由 App 整合；Shizuku 管理器/服務需由使用者另外安裝與啟動，不得宣稱服務已被包進 APK。
- App 啟動時自動偵測既有 Binder 與已核准授權；尚未授權時，只能在使用者按下授權/連線操作後送出授權請求。
- UserService 必須回報遠端 UID、PID 與 SELinux context。UID 2000 標示為 Shell，UID 0 標示為 Root；其他 UID 不可推定。
- 目前已核准的產品目標：在硬體、韌體、權限及安全條件允許的範圍內，提高 CPU/GPU 可用頻率或調整頻率限制，以較高功耗換取遊戲性能。這是調校目標，不是已驗證的性能承諾。
- CPU 與 GPU 寫入節點必須分開驗證，包含節點存在、資料單位、可接受頻率表、寫入身分、SELinux/DAC 結果及寫入後讀回值。不得因 UID 0 就假設所有節點都可寫。
- 僅允許在原廠已列出的有效 operating points 或經確認的控制範圍內調整；不得修改電壓、停用 thermal daemon/thermal policy、停用 OPP 安全限制或繞過過溫保護。
- Boost 必須可一鍵停止與還原；還原前重新讀值，若目前值已被外部程序更改，不得覆寫外部狀態。
- 檔位需以功耗/性能權衡清楚命名，例如「平衡」「性能」「高性能（高耗電）」；未取得實測功耗與遊戲性能數據前，不得標示具體提升百分比。

## 6. Boost、停止與還原的操作契約

每次 Boost 都必須清楚經過以下階段：
1. 使用者操作已接收。
2. 背景工作確實開始。
3. 命令執行完成，記錄退出狀態與受限制的輸出。
4. 重新讀取設定，驗證是否真的套用。
5. 在資料可取得時，記錄調校前後的監控數據。

介面必須區分：
- 尚未開始。
- 執行中。
- 已套用且讀回驗證成功。
- 已執行但未能驗證。
- 權限不足。
- 裝置不支援。
- 執行失敗。
- 已停止或已還原。

同一時間只允許一個互斥的 Boost/還原操作。快速連點不得建立多個並行任務。

### 還原規則
- 修改前保存原始值、目標值、修改時間及操作結果。
- 還原前重新讀取目前值。
- 只有目前值仍等於本 App 最後套用值時，才可自動還原原始值。
- 若偵測到外部變更，必須保留外部值並提示使用者，不得直接覆蓋。
- 停止監控與還原調校是不同操作，介面必須說明兩者差異。
- 任何無法安全還原的狀況都必須保留紀錄並告知使用者。

## 7. 背景服務、通知與 HUD

- 持續監控應使用符合 Android 規範的前景服務，並明確呈現實際服務狀態。
- 通知必須反映真實狀態，提供可用的停止/還原操作，不得顯示虛假的執行中狀態。
- Android 13 以上需正確處理通知權限；未授權時不可假裝通知已正常顯示。
- Android 版本要求的 Foreground Service 類型必須依實際工作選擇，不得濫用 `dataSync`、`systemExempted` 或 `specialUse`。
- 必須考慮 Samsung One UI 的背景管理限制，不得承諾服務永不被系統停止。
- 區分一次性 Boost 工作與持續監控工作。
- HUD 繪製與資料取樣必須分離，HUD 不得自行啟動重複的感測器輪詢。
- 停止服務時必須取消取樣、重試及背景工作，釋放資源並更新通知。

## 8. 架構與程式品質方法

- 使用 Kotlin 與結構化並行（structured concurrency）。
- 依工作性質使用適當 Dispatcher；主執行緒只處理 UI。
- 以明確的資料模型表達讀值與錯誤狀態，不用魔術數字代表失敗。
- 使用共享監控器與有界歷史緩衝區，避免多個畫面重複輪詢。
- 每個命令設定逾時、取消機制、輸出長度上限及錯誤處理。
- Boost/還原使用 Mutex 或等效單工機制。
- 獨立監控項目應隔離失敗，避免單一工作取消整個監控服務。
- 所有 Service、Binder、Coroutine、檔案描述符及暫時資源都必須有明確生命週期。
- UI 狀態必須由實際工作狀態驅動，不可只靠按鈕動畫或延遲計時器製造成功感。

## 9. 介面與互動準則

介面以清楚、易懂、低操作負擔為原則：
- 首頁優先呈現裝置識別、GPU 即時狀態、權限/能力狀態及主要操作。
- 數據必須標示單位；必要時標示資料來源及更新時間。
- 不可用 0、空白或正常綠色圖示掩蓋讀取失敗。
- 錯誤訊息要告訴使用者發生什麼事、影響什麼功能，以及可採取的下一步。
- 權限引導應在真正需要時出現，不要一次要求不必要權限。
- Boost 過程需顯示真實階段與結果；不得用沒有實際工作支撐的百分比進度。
- 停止、還原與退出的行為必須明確，避免誤觸造成設定殘留。

## 10. 測試與驗收方法

每項功能都必須有可重現的測試條件、預期結果及實際結果。至少包含：
- 正確辨識 SM-A5560 與系統版本。
- GPU 負載來源與累積計數器差值計算正確。
- 目前 GPU 頻率與支援頻率表不混淆。
- 權限拒絕、節點不存在、格式錯誤及不支援狀態能正確區分。
- 快速連點 Boost 不會產生重複工作。
- Boost 命令結果與讀回驗證一致。
- 前後效能數據有清楚標示，且不誇大改善。
- 還原不覆蓋外部變更。
- 通知狀態正確且停止操作有效。
- 慢速或卡住的命令不會凍結 UI。
- 命令逾時後工作能取消且不洩漏資源。
- Shizuku 中斷/撤權能被偵測並安全處理。
- 單一監控器失敗不影響其他監控器。
- 長時間監控的記憶體、執行緒與耗電維持合理。
- 停止後所有背景工作與暫時資源都能清理。
- 最終必須在 Galaxy A55 5G 實機驗證，不能只以模擬器通過作為完成依據。

完整 AT-01 至 AT-25 驗收清單見 [docs/PROJECT_SPEC.md](docs/PROJECT_SPEC.md)。未通過的項目不得標示為完成；若受裝置或權限限制而無法測試，應標示「未驗證」及原因。

## 11. 開發流程與工作方法

依序推進，先建立可驗證基礎，再加入高風險功能：
1. 檢查 Repository、Gradle、Android 專案結構與建置環境。
2. 建立裝置資訊辨識及唯讀診斷。
3. 驗證 SM-A5560 真實可讀取的 GPU/系統資料來源。
4. 建立具型別狀態的監控資料層與共享取樣器。
5. 整合 Shizuku 權限、Binder 生命週期及錯誤復原。
6. 建立儀表板、HUD 與前景監控服務。
7. 只針對已確認支援且可安全還原的控制項實作 Boost。
8. 完成自動化測試、壓力測試及實機驗收。
9. 更新文件，記錄已完成、未完成、已知限制及驗證證據。

每次提交應聚焦單一目的，提交訊息要能說明改動。不得只因程式碼能編譯就宣稱功能已完成；必須有對應測試或實機證據。

## 12. 參考專案與授權規則

曾研究的參考方向：
- [GameCore](https://github.com/Dreamucxe/GameCore)：可研究 Kotlin、Shizuku、型別化感測器狀態與條件式還原設計。
- [FusionHUD](https://github.com/The412Banner/FusionHUD)：可研究 HUD 與取樣分離、動態 GPU 路徑探測；使用其程式碼前必須遵守 GPL-3.0。
- [Shizuku-API](https://github.com/RikkaApps/Shizuku-API)：作為 Shizuku API 與 Binder 生命週期的主要參考。
- [DeviceInsight](https://github.com/zenithblue-oss/DeviceInsight)：可研究 FPS 資料來源分層、SurfaceFlinger layer 辨識、Shizuku shell gateway、來源快取失效及對應解析器測試。其舊版 SurfaceFlinger latency 解析方式不可直接當成 present-to-present FPS；gfxinfo 也不能一概代表 Vulkan/Native 遊戲 FPS。該專案採 GPL-3.0-or-later，引用/衍生程式碼前必須確認授權義務。
- [MonitoredCheck](https://github.com/GenzPx/MonitoredCheck)：可研究 `Reading<T>` 狀態模型、硬體 Repository 分層、監控服務及單元測試結構。其 GPU `gpubusy` 絕對比例與刪除小數/負號的簡化解析方式不得直接複製。該專案採 MIT License。
- [ShizukuPlus](https://github.com/thejaustin/ShizukuPlus)：可研究重連退避等設計，但不可假設背景監控永遠不會被 Samsung 系統終止。
- [Exynos 1480 community kernel](https://github.com/Lyinceer/android_kernel_samsung_exynos_1480)：用於理解可能的 SGPU/sysfs 實作；社群核心資訊不代表零售韌體一定提供相同介面。

授權與程式碼來源：
- [FusionHUD](https://github.com/The412Banner/FusionHUD) 採 GPL-3.0，且其 Repository 的 `ATTRIBUTION.md` 宣告額外作者標示要求；若使用其程式碼，必須逐項確認 GPL-3.0 與該額外條款的適用性，並保留必要作者標示。HUD 程式碼中由 Winlator 渲染端 present callback 餵入的 FPS，不代表一般 Android App 可直接讀取第三方遊戲 FPS。
- [DeviceInsight](https://github.com/zenithblue-oss/DeviceInsight) 採 GPL-3.0-or-later；若衍生或複製程式碼，必須遵守所選授權版本及相應散布義務。
- [GameCore](https://github.com/Dreamucxe/GameCore)、[MonitoredCheck](https://github.com/GenzPx/MonitoredCheck) 與 [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) 的已檢查 LICENSE 為 MIT；如使用其程式碼仍須保留原始版權與授權聲明。
- 參考專案應優先學習架構與概念，不直接複製程式碼。
- 複製、改寫或衍生任何程式碼前，必須先確認原始授權、著作權聲明及散布義務。
- GPL-3.0 專案的衍生程式碼必須遵守 GPL-3.0；不得把受 GPL 約束的程式碼當成可任意改成其他授權。
- 新增第三方程式庫時，記錄來源、版本、授權與用途。

## 13. 專案準則變更與建議審核制度（重要）

**任何新增準則、架構變更、功能方向變更、安全策略變更、依賴套件變更或驗收標準變更，都必須先向使用者提出建議並取得同意。**

執行方式：
1. 發現更好的方法或新技術時，先整理成「變更提案」，不可直接寫入正式 README 或規格文件。
2. 提案必須說明：目前做法、建議做法、改善理由、優缺點、相容性/安全/耗電/維護影響、所需修改範圍及驗證方式。
3. 明確區分「必要修正」、「可選優化」與「實驗性想法」，不可把推測當成已驗證事實。
4. 將提案交由使用者審閱；**只有使用者明確同意後，才可合併進 README、PROJECT_SPEC 或正式程式碼。**
5. 使用者未同意前，提案只能留在對話或獨立草稿，不得悄悄改動正式準則。
6. 使用者同意後，才更新文件與程式碼，並在提交紀錄中說明變更內容及原因。
7. 若發現可能造成資料遺失、裝置損害或安全風險的問題，應立即停止相關高風險操作並先告知使用者；這不代表可以擅自改寫正式準則。

日常拼字、格式、連結修正及不改變原意的文件整理，可直接修正；任何改變行為、權限、安全邊界、架構方向或驗收意義的內容，都必須先提案、後核准。

## 14. 文件分工與專案狀態

- **README.md：** 本專案的正式準則、設計原則、開發方法、變更審核制度及最高層級決策依據。
- **docs/PROJECT_SPEC.md：** 詳細功能規格、資料來源候選、技術注意事項及 AT-01 至 AT-16 驗收清單。
- **docs/DEVICE_DIAGNOSTIC_SM-A5560_ADB.md：** SM-A5560 ADB 實機診斷紀錄，包含第一輪權限與 GPU 節點探測，以及第二輪 60 秒診斷中目前可見的前 32 筆 GPU/CPU/thermal 證據；未收到的取樣資料不得視為完成。
- 後續可新增架構決策紀錄（ADR）、測試紀錄及實機診斷文件，但不得與 README 的準則衝突；新增文件或重大規則仍須先經使用者同意。

目前已建立需求與準則文件；Android/Kotlin 應用程式原始碼、實機資料來源驗證及功能測試仍需逐步實作。不得將文件完成描述為 App 已完成。

---

## 官方與技術參考
- [Android Foreground Services](https://developer.android.com/develop/background-work/services/foreground-services)
- [Android Notification Permission](https://developer.android.com/develop/ui/views/notifications/notification-permission)
- [Shizuku API](https://github.com/RikkaApps/Shizuku-API)
- [Exynos 1480 community kernel reference](https://github.com/Lyinceer/android_kernel_samsung_exynos_1480)
