# SM-A5560 ADB 實機診斷紀錄

> 狀態：第一輪 ADB 探測紀錄（2026-11-12）。本文件只記錄使用者提供的 Windows CMD / ADB 輸出；不是完整硬體鑑定，也不代表 GPU 即時監控已通過驗證。未取得明確輸出的欄位一律標示為待確認。

## 1. 測試環境

| 項目 | 已知資訊 | 狀態 |
|---|---|---|
| 電腦作業系統 | Windows 10.0.26200（ADB 終端輸出） | 已確認 |
| ADB | Android Debug Bridge 1.0.41 | 已確認 |
| Platform Tools | 37.0.1-15733141 | 已確認 |
| ADB 執行位置 | D:\\platform-tools-latest-windows\\platform-tools\\adb.exe | 已確認 |
| USB 裝置狀態 | device | 已確認；公開文件省略完整 serial |
| 手機型號 | SM-A5560 | 已確認 |
| Android 版本 / API | 未取得可獨立辨識輸出 | 待確認 |
| One UI 版本 | 未取得可獨立辨識輸出 | 待確認 |
| Build fingerprint / security patch | 未取得可獨立辨識輸出 | 待確認 |
| Kernel 版本 | 未取得可獨立辨識輸出 | 待確認 |
| SoC / GPU 型號 | 本輪未取得可獨立辨識的屬性值 | 待確認；不可預填成實測結果 |

## 2. ADB 連線與 Shell 身分

### 2.1 ADB 連線

使用者輸出顯示 ADB daemon 啟動成功，裝置狀態為 device。這證明 ADB transport 可用，不代表每個 Android 系統節點都允許讀取。

### 2.2 Shell 身分

使用者提供的 adb shell id 輸出包含：

~~~text
uid=2000(shell) gid=2000(shell)
context=u:r:shell:s0
~~~

判定：目前命令以 Android shell UID 2000 執行，不是 Root（UID 0）。id 輸出中列出的群組包含 input、log、adb、sdcard_rw、sdcard_r、readproc、readtracefs 等；`groups` 指令尚無可獨立辨識的輸出。使用者後續單獨執行 `adb shell getenforce`，明確回傳 `Enforcing`，因此 SELinux 強制執行模式已確認。Unix DAC 權限允許讀取，不代表 SELinux 一定放行，仍須對各節點實際 `cat` 驗證。

## 3. GPU sysfs 探測

### 3.1 GPU 目錄

adb shell ls -l /sys/kernel/gpu/ 成功列出目錄內容，證明該目錄存在且 shell 能列出其項目。

| 節點 | 清單中可見 | 實際 cat 結果 |
|---|---:|---|
| gpu_busy | 是 | **成功；兩次均回傳原始值 `0`** |
| gpu_clock | 是 | **成功；兩次均回傳原始值 `0`** |
| SGPU `cur_freq` | SGPU devfreq 項目存在 | **成功；一次回傳原始值 `0`** |
| SGPU `available_frequencies` | SGPU devfreq 項目存在 | **成功；9 個檔位，與 `gpu_freq_table` 數值集合相同** |
| `gpu_freq_table` | 是 | **成功；9 個檔位，排序與 devfreq 相反** |
| gpu_tmu | 是 | **成功；回傳原始值 `37.0`，單位未確認** |
| gpu_model | 是 | **成功；回傳 `Samsung Xclipse 530`** |
| mem_info | 是 | 尚未測試 |
| sgpu_pm_monitor | 是 | 尚未測試 |
| utilization_source | 否 | 本次清單未見 |
| available_utilization_sources | 否 | 本次清單未見 |

使用者於 2026-11-12 執行 `adb shell cat /sys/kernel/gpu/gpu_tmu`，成功回傳：

~~~text
37.0
~~~

記錄為 GPU TMU 節點原始值 `37.0`。目前沒有取得能直接對應 SM-A5560 / Xclipse 530 此節點的驅動 ABI 或原始碼證據來確認單位與換算方式；因此**不可先標記成 37.0 °C**，也不可直接當作熱降頻判斷門檻。此系列測試延續先前使用者描述的影片播放、未執行遊戲情境；除非另有標註，視為非遊戲基線資料。

使用者於 2026-11-12 執行 `adb shell cat /sys/kernel/gpu/gpu_model`，成功回傳：

~~~text
Samsung Xclipse 530
~~~

因此本機 GPU 型號已由實機驅動節點確認為 Samsung Xclipse 530。這是 GPU 型號識別，不代表 GPU 即時負載、時脈或溫度已通過驗證。

使用者於 2026-11-12 在相同影片播放、未執行遊戲的基線測試中，執行 `adb shell cat /sys/class/devfreq/10e00000.sgpu/cur_freq`，成功回傳原始值 `0`。目前觀察到 `gpu_busy`、`gpu_clock`、SGPU `cur_freq` 均可由 ADB Shell 讀取，但值皆為 0。此現象與 GPU 閒置/時鐘閘控相容，但尚未證實；不可將其當成有效 0 MHz 或已驗證的即時 GPU 頻率。

使用者補充：上述 GPU 讀值測試時手機只是在播放影片，沒有執行遊戲。使用者於 2026-11-12 單獨執行兩次 `adb shell cat /sys/kernel/gpu/gpu_clock`，兩次皆成功回傳：

~~~text
0
~~~

這證明本次測試時一般 ADB Shell UID 2000 可以讀取 `gpu_clock`，但回傳原始值為 0。**不得將 0 解讀為 GPU 實際頻率 0 MHz**；該節點的單位、更新機制及是否為有效即時值尚未確認，必須與 SGPU devfreq `cur_freq` 交叉驗證。測試情境是影片播放、未執行遊戲，因此目前只有低/一般使用情境基線，沒有遊戲負載樣本。

使用者於 2026-11-12 單獨執行兩次 `adb shell cat /sys/kernel/gpu/gpu_busy`，兩次皆成功回傳：

~~~text
0
~~~

這證明在本次測試時，一般 ADB Shell UID 2000 可以讀取 `gpu_busy`，且 SELinux Enforcing 未阻止這兩次讀取。兩次原始值皆為 0；使用者補充測試時只在播放影片、沒有執行遊戲，因此這是影片播放情境的基線，不是遊戲負載樣本。資料格式、單位與語義仍待負載狀態下連續取樣及交叉驗證。

使用者後續執行 `adb shell ls -lZ /sys/kernel/gpu/` 成功，取得以下監控候選節點權限：

| 節點 | Mode | Owner:Group | SELinux label | Unix DAC（UID 2000） |
|---|---:|---|---|---|
| gpu_busy | 0444 | root:root | sysfs_gpu | other-read 有開放 |
| gpu_clock | 0444 | root:root | sysfs_gpu | other-read 有開放 |
| gpu_freq_table | 0444 | system:system | sysfs_gpu | other-read 有開放 |
| gpu_tmu | 0444 | root:root | sysfs_gpu | other-read 有開放 |
| gpu_model | 0444 | root:root | sysfs_gpu | other-read 有開放 |
| mem_info | 0444 | root:root | sysfs_gpu | other-read 有開放 |
| sgpu_pm_monitor | 0444 | root:root | sysfs_gpu | other-read 有開放 |

上述節點完整 SELinux label 均為 `u:object_r:sysfs_gpu:s0`。這代表傳統 Unix DAC 權限沒有阻擋 shell UID 2000 讀取這些節點；但 SELinux 已確認為 Enforcing，仍可能依 domain/type policy 拒絕讀取。**目前已實際讀取成功的節點為 `gpu_busy` 與 `gpu_clock`（各兩次，原始值均為 `0`）；其餘節點仍須以逐項 `cat` 的實際結果確認。讀取成功不代表資料語義/單位已驗證。**

另外，清單中的部分控制節點（例如 `gpu_ifpo_control` mode 0640、`gpu_ifpo_runtime_control` mode 0640）沒有 other-read 權限；一般 shell UID 2000 依 DAC 即不可讀。不得對任何控制節點執行寫入。

### 3.2 可寫節點安全注意

目錄清單亦出現 gpu_min_clock、gpu_max_clock、gpu_governor、gpu_cl_boost_disable 等帶有寫入權限的控制節點。本輪只有列目錄，沒有寫入。後續診斷必須維持唯讀；不得因看到檔案可寫就嘗試修改。任何控制功能都須另行提案、取得使用者同意，並完成原值保存、讀回驗證及安全還原設計。

## 4. SGPU devfreq 探測

adb shell ls -l /sys/class/devfreq/ 的清單可見以下項目：

~~~text
10e00000.sgpu
17000010.devfreq_mif
17000020.devfreq_int
17000030.devfreq_npu0
17000040.devfreq_dnc
17000050.devfreq_aud
17000060.devfreq_disp
17000070.devfreq_intcam
17000080.devfreq_cam
17000090.devfreq_isp
170000a0.devfreq_mfc
170000b0.devfreq_csis
170000c0.devfreq_icpu
~~~

判定：devfreq 目錄可列出，且清單包含 SGPU 項目 10e00000.sgpu。使用者於 2026-11-12 執行 `adb shell cat /sys/class/devfreq/10e00000.sgpu/cur_freq`，成功回傳原始值 `0`，沒有 Permission denied。這證明該路徑本次可讀，但 `0` 尚不能被判定為真實 GPU 頻率 0 Hz；需要與 GPU 負載、其他頻率來源及裝置活動狀態交叉驗證。

使用者接著執行 `adb shell cat /sys/class/devfreq/10e00000.sgpu/available_frequencies`，成功回傳原始頻率表：

~~~text
1300000 1152000 1025000 960000 864000 750000 648000 544000 450000
~~~

使用者再執行 `adb shell cat /sys/kernel/gpu/gpu_freq_table`，成功回傳：

~~~text
450000 544000 648000 750000 864000 960000 1025000 1152000 1300000
~~~

兩個不同節點回傳的九個數值集合完全相同，僅排序相反：devfreq 的 `available_frequencies` 由高到低，GPU `gpu_freq_table` 由低到高。這交叉確認了兩個介面列出的可用檔位一致，但**不代表當前 GPU 頻率**。本次裝置輸出本身未附單位，文件暫保留 raw value，不自行標成 Hz 或 kHz。

本輪曾使用字面佔位路徑 /sys/class/devfreq/目錄名稱/...，因此得到 No such file or directory。這是因為路徑沒有替換成真實目錄名稱，不能據此判斷 SGPU 節點不存在或權限遭拒。後續應使用實際路徑 /sys/class/devfreq/10e00000.sgpu。

### Thermal Zone 初步探測（2026-11-12）

使用者執行唯讀指令，嘗試列出 `/sys/class/thermal/thermal_zone*/type` 與 `temp`：

~~~bat
adb shell "for d in /sys/class/thermal/thermal_zone*; do [ -r $d/type ] || continue; echo ==== $d; cat $d/type; cat $d/temp; done"
~~~

使用者回報終端機沒有任何標準輸出，也沒有顯示錯誤。**此結果不具判定性**：迴圈在 `[ -r $d/type ]` 不成立時會略過該項目；也可能沒有匹配到任何 thermal_zone 目錄。不能因此判定 Thermal Zone 不存在、GPU sensor 不存在或節點權限遭拒。後續先列出 `/sys/class/thermal/` 目錄，再按實際項目逐一探測。

使用者接著執行 `adb shell ls -la /sys/class/thermal/`，成功列出目錄，結果包含：
- `thermal_zone0` 至 `thermal_zone7`：8 個符號連結，目標位於 `../../devices/virtual/thermal/thermal_zoneN`。
- `cooling_device0` 至 `cooling_device4`：5 個符號連結，目標位於 `../../devices/virtual/thermal/cooling_deviceN`。

因此已確認 `/sys/class/thermal/` 目錄存在且有 8 個 Thermal Zone 及 5 個 Cooling Device 入口。先前迴圈沒有輸出，原因尚未確定；不能當作 Zone 不存在或資料不可讀的證據。下一步需直接檢查個別 Zone 的符號連結目標、`type` 與 `temp`。

## 5. 本輪結論

### 已確認
1. Windows ADB 工具可啟動，ADB 1.0.41 / Platform Tools 37.0.1。
2. USB ADB 裝置狀態為 device。
3. `adb shell getenforce` 明確回傳 `Enforcing`，SELinux 為強制執行模式。
4. GPU 監控候選節點的 Unix mode 與 SELinux label 已取得；多個候選節點有 other-read 權限，label 為 `u:object_r:sysfs_gpu:s0`。這尚不等於 SELinux 放行或 `cat` 成功。
3. 手機型號輸出為 SM-A5560。
4. ADB shell 身分為 UID 2000，不是 Root。
5. /sys/kernel/gpu/ 目錄可列出，清單包含 GPU 負載、時鐘、頻率表、溫度/模型/記憶體等候選節點。
6. /sys/class/devfreq/ 目錄可列出，清單包含 10e00000.sgpu。

7. SGPU `available_frequencies` 可讀，回傳 9 個原始頻率檔位；單位尚待此機核心介面或其他可核對來源確認。

### 尚未確認
1. Android release、API level、One UI、Build fingerprint、security patch、kernel release。
2. SoC 製造商/型號等系統屬性（例如 `ro.soc.model`）仍未取得可獨立辨識輸出；GPU 型號已由實機節點確認為 Samsung Xclipse 530。
3. `gpu_tmu`、`mem_info`、`sgpu_pm_monitor` 等其餘 GPU 節點尚未實際讀取。
4. SGPU `name`、`min_freq`、`max_freq`、`governor` 是否存在且可讀仍待確認。
5. `gpu_busy` 的資料語義（即時百分比、累積計數器或其他格式）仍待驗證。
6. `gpu_clock` 持續回傳 0 的原因，以及該節點是否為有效即時頻率來源仍待驗證。
7. 兩份頻率表的數值單位尚未由裝置文件或驅動程式來源確認。
8. `gpu_tmu` 已確認可讀且回傳原始值 `37.0`；其確切單位、溫度感測來源及換算方式尚未確認。
9. Shizuku 是否能取得額外且實際可用的讀取能力。
## 6. 下一輪唯讀診斷命令

請在 Windows CMD 逐行執行，避免多個命令輸出混在一起。

### 6.1 系統與 SELinux
~~~bat
adb shell getprop ro.product.device
adb shell getprop ro.board.platform
adb shell getprop ro.hardware
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.display.id
adb shell getprop ro.build.fingerprint
adb shell uname -a
adb shell getenforce
~~~

### 6.2 GPU 節點內容
~~~bat
adb shell cat /sys/kernel/gpu/gpu_busy
adb shell cat /sys/kernel/gpu/gpu_clock
adb shell cat /sys/kernel/gpu/gpu_freq_table
adb shell cat /sys/kernel/gpu/gpu_tmu
adb shell cat /sys/kernel/gpu/gpu_model
adb shell cat /sys/kernel/gpu/mem_info
adb shell cat /sys/kernel/gpu/sgpu_pm_monitor
~~~

### 6.3 SGPU devfreq 內容
~~~bat
adb shell ls -l /sys/class/devfreq/10e00000.sgpu
adb shell cat /sys/class/devfreq/10e00000.sgpu/name
adb shell cat /sys/class/devfreq/10e00000.sgpu/cur_freq
adb shell cat /sys/class/devfreq/10e00000.sgpu/available_frequencies
adb shell cat /sys/class/devfreq/10e00000.sgpu/min_freq
adb shell cat /sys/class/devfreq/10e00000.sgpu/max_freq
adb shell cat /sys/class/devfreq/10e00000.sgpu/governor
~~~

以上均為唯讀命令。不要執行 echo >、setprop、su、頻率寫入或其他系統控制命令。

## 7. 資料判讀規則

| 結果 | 判讀 |
|---|---|
| 回傳有效內容 | 該次讀取成功；仍需驗證格式、單位與語義 |
| gpu_busy 兩次回傳 0 | 已證實節點可讀；不能在未記錄 GPU 負載場景及資料格式前當成百分比或遊戲負載結論 |
| Permission denied | 存取被拒；再區分 Unix 權限與 SELinux 等政策限制 |
| No such file or directory | 精確路徑不存在，或 symlink 目標不可用；先檢查路徑 |
| Is a directory | 指定路徑是目錄，不是資料檔 |
| 空白或無輸出 | 資訊不足，需用 ls -l、退出碼及重複讀取確認 |
| 頻率表有值 | 僅能確認支援頻率候選清單，不代表當前頻率 |

## 8. 對 App 實作的約束

- 以本文件「已確認」欄位建立實機能力證據；「尚未確認」欄位不可標記為支援。
- GPU 節點出現在目錄清單，不代表 App 已能讀取。
- gpu_busy 必須先觀察原始格式，再選擇 instant percentage 或 cumulative counter parser。
- gpu_clock 與 SGPU cur_freq 必須交叉比較，保留讀取時間及來源；不能直接假設兩者都是即時值。
- gpu_freq_table / available_frequencies 必須與 current frequency 分開。
- 直接 sysfs 與 Shizuku shell 通道必須共用同一來源語義、解析器及累積計數器 baseline。
- 權限拒絕、路徑不存在、格式錯誤、暫時不可用及未知/受限必須分開表達。
- 本輪沒有實際 GPU 數值，不得將任何 GPU 負載、頻率、溫度或 FPS 寫成已實測數據。

## 9. 隱私與紀錄注意事項

- 公開文件不要保存完整 ADB serial、使用者帳號、私人檔案路徑或不必要的裝置識別資訊。
- 分享診斷報告前，先檢查並遮蔽 serial、Wi-Fi、帳號及其他個資。
- 本文件已省略完整 ADB serial。