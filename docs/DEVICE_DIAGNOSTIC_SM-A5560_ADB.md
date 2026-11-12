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
| Android 版本 / API | 第一輪未取得；第二輪確認 Android 17 / API 37 | 第二輪已確認 |
| One UI 版本 | 未取得可獨立辨識輸出 | 待確認 |
| Build fingerprint / security patch | 未取得可獨立辨識輸出 | 待確認 |
| Kernel 版本 | 未取得可獨立辨識輸出 | 待確認 |
| SoC / GPU 型號 | 第二輪確認 SoC property `s5e8845`、GPU `Samsung Xclipse 530` | 第二輪已確認 |

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

使用者接著執行 `adb shell ls -laL /sys/class/thermal/thermal_zone0`，系統回傳：

~~~text
ls: /sys/class/thermal/thermal_zone0: Permission denied
~~~

此結果證明一般 ADB Shell 在此次測試中無法跟隨 `thermal_zone0` 符號連結並列出目標目錄。尚未取得目標目錄的 Unix mode / owner / SELinux label，故不能把拒絕原因直接歸因於 SELinux 或 DAC；也不能推論其他 thermal zone 全部不可讀。

使用者再執行 `adb shell ls -ldZ /sys/class/thermal/thermal_zone0`，成功取得符號連結本身的資訊：

~~~text
lrwxrwxrwx 1 root root u:object_r:sysfs:s0 0 2026-11-09 18:39 /sys/class/thermal/thermal_zone0 -> ../../devices/virtual/thermal/thermal_zone0
~~~

判定：連結 inode 本身可被一般 ADB Shell 查詢，其標籤為 `u:object_r:sysfs:s0`，目標相對路徑為 `../../devices/virtual/thermal/thermal_zone0`。**這是 symlink 本身的 mode/label，不是目標目錄的 mode/label**；不能用此標籤判定目標的 SELinux type。下一步應對解析後的實際路徑 `/sys/devices/virtual/thermal/thermal_zone0` 執行 `ls -ldZ`，再視結果測試 `type` / `temp`。拒絕原因仍未定（DAC / SELinux / target path traversal policy）。

使用者接著執行 `adb shell ls -ldZ /sys/devices/virtual/thermal/thermal_zone0`，成功取得實際目標目錄資訊：

~~~text
drwxr-xr-x 3 root root u:object_r:sysfs_thermal:s0 0 2026-11-08 23:21 /sys/devices/virtual/thermal/thermal_zone0
~~~

判讀：目標目錄 owner/group 為 `root:root`、mode `0755`，SELinux label 為 `u:object_r:sysfs_thermal:s0`。以傳統 DAC 權限看，other 具有 read + execute，因此 UID 2000 的 shell 在 DAC 層具備列目錄/搜尋權限；但此前跟隨 `/sys/class/thermal/thermal_zone0` 列出目錄回傳 Permission denied。SELinux enforcing 下的 `sysfs_thermal` policy 是待查的優先方向，但尚無 AVC denial 或目標 children 的實際讀取結果，**不可把根因定論為 SELinux**。下一步對實際路徑執行 `ls -laZ`，再確認 `type` / `temp` 的檔案節點與讀取結果。

使用者依此執行 `adb shell ls -laZ /sys/devices/virtual/thermal/thermal_zone0/`，系統仍回傳：

~~~text
ls: /sys/devices/virtual/thermal/thermal_zone0/: Permission denied
~~~

使用者接著直接執行 `adb shell cat /sys/devices/virtual/thermal/thermal_zone0/type`，系統回傳：

~~~text
cat: /sys/devices/virtual/thermal/thermal_zone0/type: Permission denied
~~~

因此，一般 ADB Shell 不只不能列出 `thermal_zone0` 目錄內容，也不能讀取已知路徑下的 `type` 屬性檔。這進一步支持 Thermal Zone 對 shell domain 有存取限制的判斷；但尚未取得 `type` 節點自身的 mode/owner/SELinux label，也沒有 AVC denial，仍不能明確區分 SELinux 與節點自身 DAC 權限。目前已測試 `thermal_zone0` 的目錄列舉、`type` 讀取均遭拒；下一步測試 `temp`，不得嘗試更改權限或 SELinux 設定。

這與該目錄 mode `0755`、owner/group `root:root` 的 DAC 權限預期不符：other-read/execute 已開放，但 UID 2000 仍不能讀取目錄內容。由於 SELinux 已確認為 `Enforcing`，目前高度懷疑 `sysfs_thermal` 目錄的 SELinux policy 拒絕 shell domain 的 directory read/open；但尚未取得 AVC denial，故仍不能視為已證實。先前 thermal zone 迴圈使用 `[ -r $d/type ] || continue` 而沒有輸出，與檔案可讀性檢查失敗後全部略過的現象相符，但尚未逐一驗證。

後續改採已知標準節點名稱直接測試，不需要先列目錄：
~~~bat
adb shell cat /sys/devices/virtual/thermal/thermal_zone0/type
adb shell cat /sys/devices/virtual/thermal/thermal_zone0/temp
~~~
兩條命令請分開執行並保存原始結果；即使其中一條被拒絕，也分別記錄。不要嘗試修改權限、SELinux mode 或寫入 thermal 節點。


使用者依此單獨執行 `adb shell cat /sys/devices/virtual/thermal/thermal_zone0/temp`，系統回傳：

~~~text
cat: /sys/devices/virtual/thermal/thermal_zone0/temp: Permission denied
~~~

判定：一般 ADB Shell UID 2000 對 `thermal_zone0` 的目錄列舉、`type` 與 `temp` 三種讀取操作均遭拒。另一方面，`/sys/kernel/gpu/gpu_tmu` 已實測可讀並回傳原始值 `37.0`。因此目前可行的 GPU 溫度候選來源是 Samsung 自訂 `gpu_tmu`；標準 Thermal Zone 路徑不能由目前 ADB Shell 通道讀取。拒絕來源仍未有 AVC 或目標檔案自身 mode/label 證據，不能定論為 SELinux；此限制僅針對目前 shell 讀取通道，不能推論 App、Shizuku 或其他受授權 domain 的結果相同。
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
- 第一輪只有閒置／影片播放基線，沒有遊戲負載樣本；第二輪可見的 SAMPLE 1–32 結果見第 9 節。GPU 頻率單位、GPU_TMU 意義及 FPS 仍未驗證。

## 9. 第二輪 60 秒診斷（2026-11-12 18:13）

> 來源：使用者提供的第二輪唯讀 ADB 報告。對話中可見 SAMPLE 1–32；未收到／未核對 SAMPLE 33–60 與取樣後 Thermal Service，因此以下統計只涵蓋可見的 32 筆，不宣稱完整 60 筆測試完成。

### 9.1 系統與硬體補充

| 項目 | 實測內容 |
|---|---|
| Android / API | Android 17 / API 37 |
| Build Display | `CP2A.260605.016.A5560ZHUIEZJ1` |
| SoC property | `s5e8845` |
| GPU | Samsung Xclipse 530 |
| CPU online | `0-7` |
| CPUFreq policy | `policy0`、`policy4` 目錄存在，ADB Shell 可讀取 `scaling_cur_freq` |

### 9.2 GPU 取樣（僅可見 32 筆）

| 項目 | 可見樣本結果 | 判讀 |
|---|---:|---|
| `gpu_busy` | 最低 11、最高 73、平均 49.125 | 單一數值在樣本間變動，格式符合 0–100 候選百分比；零售核心語義／來源仍待確認 |
| `gpu_clock` | 32 筆均為 450000 | 原始數值；單位與即時性未確認 |
| devfreq `cur_freq` | 多數 450000；SAMPLE 27 為 544000 | 與 `gpu_clock` 不一致，暫不可選定為權威即時頻率 |
| `gpu_tmu` | 約 48–55 | 只記錄原始值，單位及感測器對應未確認 |

GPU devfreq 靜態讀取回報 `min_freq=450000`、`max_freq=450000`，但取樣中的 `cur_freq` 曾回報 544000。三者與 `gpu_clock` 的一致性尚未解決。支援頻率表是獨立資訊，不得替代目前頻率。現階段 App 只能並列呈現各節點原始值及其來源，不可硬標成 MHz，也不可推斷已鎖定 450 MHz。

### 9.3 CPUFreq 取樣

可見樣本中：
- `policy0/scaling_cur_freq`：864000–2050000。
- `policy4/scaling_cur_freq`：1920000–2750000。

Linux CPUFreq 文件定義 `scaling_cur_freq` 單位為 kHz；其多數情況反映 scaling driver 最近要求的 P-state，未必等於硬體當下實際頻率。因此 App 顯示時應標示「核心回報頻率」，不可宣稱是精確硬體時鐘。參考：[Linux CPUFreq documentation](https://www.kernel.org/doc/html/latest/admin-guide/pm/cpufreq.html)。

### 9.4 Thermal Service

取樣前 Thermal Service 回報：
- Thermal Status：`1`（Android 定義為 LIGHT）。
- AP：44.9°C。
- PA：42.1°C。
- BAT：32.4°C。
- SKIN：40.7°C。
- USB：32.8°C。

這些是 `dumpsys thermalservice` 的感測器資料；GPU_TMU 不可直接等同 GPU 晶片攝氏溫度。Android 公開 `PowerManager` API 可提供整機 thermal status 與 thermal headroom 估計，但不是 AP/GPU/BAT/PA/SKIN 的逐感測器攝氏讀值。App 目前只能透過公開 API 顯示狀態及 headroom；逐感測器資料仍需後續核准並整合受控的 shell/Shizuku 讀取通道。參考：[Android PowerManager API](https://developer.android.com/reference/android/os/PowerManager)。

### 9.5 取樣與權限限制

- 報告中取樣時間間隔約 1–2 秒，並非精確每秒一次；每筆包含多次 ADB 呼叫。
- policy0/policy4 的 ADB Shell 讀取成功，不等於 App 自身 UID 一定能讀取；App 端直接 sysfs 存取仍待實機安裝驗證。
- 本次對話只收到前 32 筆樣本，後 28 筆及取樣後溫度摘要尚未核對。
- 目前沒有可用證據證明遊戲 FPS 已成功取得。

## 10. 隱私與紀錄注意事項

- 公開文件不要保存完整 ADB serial、使用者帳號、私人檔案路徑或不必要的裝置識別資訊。
- 分享診斷報告前，先檢查並遮蔽 serial、Wi-Fi、帳號及其他個資。
- 本文件已省略完整 ADB serial。
## 10. 最終唯讀節點盤點（2026-11-12 18:32；使用者上傳報告）

> 來源：使用者上傳的 A55 FINAL READ-ONLY NODE INVENTORY。ADB serial 與電池記錄中的識別資訊不重複保存。報告完成節點盤點區段，但取樣只取得 8 筆；不得稱為完整 60 秒測試。

### 10.1 裝置、韌體與核心

| 欄位 | 實測 |
|---|---|
| Model | SM-A5560 |
| SoC property / hardware | s5e8845 |
| Android / API | 17 / 37 |
| Build display | CP2A.260605.016.A5560ZHUIEZJ1 |
| Build fingerprint | samsung/a55xzh/a55x:17/CP2A.260605.016/A5560ZHUIEZJ1:user/release-keys |
| Security patch | 2026-10-05 |
| Kernel | 6.1.162-android14-11 |
| SELinux | Enforcing |
| ADB shell | UID 2000，非 Root |

### 10.2 GPU 根目錄節點盤點

/ sys / kernel / gpu 根目錄（實際路徑 /sys/kernel/gpu/）列出以下節點；已讀取的唯讀資訊結果：

| 節點 | Shell 讀取結果 | 判讀 |
|---|---|---|
| gpu_model | Samsung Xclipse 530 | 型號字串已確認 |
| gpu_busy | 80（盤點快照）；取樣 64–91 | 單一 0–100 範圍原始值；零售核心精確語義/來源仍待確認 |
| gpu_clock | 960000；取樣中動態變化 | 核心回報頻率候選；單位及硬體即時性待確認 |
| gpu_freq_table | 9 檔：450000–1300000 | 支援檔位，不是目前頻率 |
| gpu_governor | conservative | 當前 governor 字串 |
| gpu_available_governor | static conservative interactive profiler | 可用 governor 字串 |
| gpu_min_clock / gpu_max_clock | 450000 / 1152000 | vendor 介面限制值；單位與 devfreq 限制的關係仍待確認 |
| gpu_mm_min_clock | 0 | 原始值；語義未確認 |
| gpu_siop_max_clock | 2147483647 | 原始值；不可當成實際頻率 |
| gpu_tmu | 63.0；取樣 64–66 | 原始感測器值；單位及感測器對應未確認，不可標示 °C |
| mem_info | per-PID 數值及 total | 可讀；格式、單位仍待解析 |
| ctx_mem_info | 只有 PID SIZE 標頭 | 該次沒有資料列；不能據此判斷沒有 GPU context |
| sgpu_pm_monitor | Monitor duration: 0us，各狀態 0us | 沒有有效監測時間；不可當成功耗統計 |

根目錄亦列出 gpu_cl_boost_disable、gpu_disable_llc_way、gpu_ifpo_control、gpu_ifpo_runtime_control、sgpu_swap_ctrl 等名稱；本次沒有讀取其控制內容，也沒有任何寫入。proc/ 子目錄存在並列出 9 個子目錄，但未遞迴探查，因此 GPU 整個 sysfs 子樹仍不是完全盤點。

根目錄清單沒有 utilization_source 或 available_utilization_sources。在此 Build 的該目錄快照中，這兩個候選節點未出現；不可在此機 App 中假設它們存在。

### 10.3 Devfreq domains

報告列出 13 個 devfreq domain：
- GPU：10e00000.sgpu
- 其他 12 個：17000010.devfreq_mif、17000020.devfreq_int、17000030.devfreq_npu0、17000040.devfreq_dnc、17000050.devfreq_aud、17000060.devfreq_disp、17000070.devfreq_intcam、17000080.devfreq_cam、17000090.devfreq_isp、170000a0.devfreq_mfc、170000b0.devfreq_csis、170000c0.devfreq_icpu。

GPU domain 10e00000.sgpu：

| 屬性 | 結果 |
|---|---|
| name | 10e00000.sgpu |
| governor | sgpu_governor |
| cur_freq | 960000 |
| target_freq | 960000 |
| available_frequencies | 1300000、1152000、1025000、960000、864000、750000、648000、544000、450000 |
| min_freq / max_freq | 450000 / 1152000 |
| trans_stat | Permission denied |
| polling_interval、status、load | 該路徑回報 No such file or directory |

在 8 筆動態樣本中，gpu_clock、devfreq cur_freq 與 target_freq 每筆完全相同，值在 750000、864000、960000 間變動。這比前一份報告的單一不一致快照更有力，但三者可能共用核心/driver 回報路徑，不能視為三個獨立硬體量測來源。Linux devfreq ABI 說明 cur_freq 是目前頻率；若 driver 未實作 get_cur_freq()，它可能與 target_freq 相同。因此仍須確認本機 driver 是否提供真正的 get_cur_freq()，以及 vendor 節點是否有快取語義。參考 [Linux devfreq sysfs ABI](https://github.com/torvalds/linux/blob/master/Documentation/ABI/testing/sysfs-class-devfreq)。

MIF domain 只有 available_frequencies 成功讀到；其餘列出的 name、governor、cur/min/max/target/trans_stat 欄位遭 Permission denied。其餘 11 個非 GPU domains 的主要內容欄位亦遭 Permission denied。這表示 Shell 可見 domain 清單，但一般讀取能力大多受限。

### 10.4 CPUFreq policy 完整對應

| 欄位 | policy0 | policy4 |
|---|---|---|
| affected_cpus / related_cpus | 0 1 2 3 | 4 5 6 7 |
| scaling_driver | exynos_cpufreq | exynos_cpufreq |
| scaling_governor | energy_aware | energy_aware |
| 可用 governor | energy_aware / conservative / powersave / performance / schedutil | 同左 |
| cpuinfo_min_freq | 533000 | 533000 |
| cpuinfo_max_freq | 2050000 | 2750000 |
| scaling_min_freq | 1344000 | 1248000 |
| scaling_max_freq | 2050000 | 2750000 |
| scaling_cur_freq（盤點時） | 2050000 | 2750000 |
| cpuinfo_cur_freq | Permission denied | Permission denied |
| stats/time_in_state | 可讀 | 可讀 |

依 Linux CPUFreq ABI，scaling_cur_freq 和 scaling_available_frequencies 單位為 kHz；scaling_cur_freq 多數情況是 scaling driver 最近要求的 P-state，不一定等於硬體實際時鐘。cpuinfo_cur_freq 才是透過硬體取得的頻率欄位，但本機 shell 讀取遭拒。因此 App 應顯示「CPU policy 核心回報頻率」，並列出核心群組，不得冒稱硬體實際時鐘。參考 [Linux CPUFreq documentation](https://www.kernel.org/doc/html/latest/admin-guide/pm/cpufreq.html)。

### 10.5 Thermal、Cooling 與電池

- /sys/class/thermal/ 有 8 個 thermal_zone 與 5 個 cooling_device 符號連結；本次對各 Zone 的 type/temp/mode/policy/sustainable_power 和各 Cooling Device 的 type/cur_state/max_state 讀取均遭 Permission denied。
- dumpsys thermalservice 成功，回報 Thermal Status 2（MODERATE）。HAL AIDL 3 connected。Current HAL readings：AP 47.4、BAT 36.3、PA 44.0、SKIN 41.2、SUBBAT 0.0、USB 36.3；沿用 Thermal HAL 輸出單位 °C。SKIN status 為 2。Cooling devices 清單沒有列出裝置。
- Thermal thresholds：BAT hot thresholds 中可見 55.0、85.0；SKIN 為 38.0、40.0、42.0、45.0、47.0。這些是 HAL 回報 threshold，不是本 App 可修改的目標。
- /sys/class/power_supply/ 列舉遭 Permission denied；Android dumpsys battery 仍成功回報 level 36%、voltage 3744 mV、temperature 363、technology Li-ion 等資料。BatteryManager 溫度慣例為十分之一攝氏度；此欄位可解讀為 36.3°C。正式 App 應優先使用 Android BatteryManager / ACTION_BATTERY_CHANGED 公開資料，不依賴受限 power_supply sysfs。

### 10.6 RAM 與 GPU memory

- MemTotal=7606432 kB、MemAvailable=2792936 kB。
- SwapTotal=4194300 kB、SwapFree=1441808 kB；但 /proc/swaps 本身 Permission denied。
- /proc/meminfo 與 /proc/vmstat 可讀。
- kernel GpuTotal=820316 kB；mem_info 的 total 原始值為 827379712（盤點快照）及 846295040（後續記憶體讀取）；節點格式/單位尚未由 driver ABI 證實，暫保留原始整數，不混成同一指標。

### 10.7 動態取樣結果與中斷

本次單一 shell 取樣區段只出現 SAMPLE 1–8（18:33:06 至 18:33:15），不是 60 筆：

| 指標 | 範圍 / 統計 |
|---|---:|
| gpu_busy | 64–91，平均 79.25 |
| gpu_clock | 750000–960000 |
| devfreq cur_freq | 750000–960000 |
| devfreq target_freq | 750000–960000 |
| gpu_tmu | 64–66 |
| CPU policy0 scaling_cur_freq | 1056000–1824000 |
| CPU policy4 scaling_cur_freq | 2750000（8 筆相同） |

每筆樣本中 gpu_clock = cur_freq = target_freq。這驗證本次取樣條件下三個介面的同步變動，但尚未驗證 gpu_busy 精確語義、GPU 頻率單位/硬體時鐘獨立量測、gpu_tmu 單位。取樣後 Thermal Service 呼叫失敗，回報 adb.exe: no devices/emulators found；結束時間 18:33:15.89。報告沒有證據能判斷是 USB 線、USB 埠、ADB transport 或手機端服務中斷，根因未知。

### 10.8 對 App / ADB-free 的更新判定

**可作為 App 自身公開 API/普通 UID 優先驗證的項目：**
- CPUFreq policy0/4 原始頻率來源（尚需 App UID 安裝實測；CPUFreq 文件可確定 kHz 單位）。
- Android Thermal Status / Headroom（不是逐感測器溫度）。
- BatteryManager 電量、狀態、電壓與溫度。
- /proc/meminfo 讀取（仍需 App UID 實測與權限確認）。

**仍不可宣稱 ADB-free 普通 App 已可用：**
- GPU sysfs：Shell 讀取成功不等於 App UID 允許；須直接安裝 APK 實測。
- GPU 個別溫度：gpu_tmu 單位未知；Thermal Zone 全部被 Shell 拒絕。
- AP/BAT/PA/SKIN/USB 逐感測器溫度：目前只在 dumpsys thermalservice 取得；Android public PowerManager API 不提供相同逐感測器 °C 清單。
- 其餘 12 個 devfreq domains：多數屬性對 Shell 已被拒絕。
- FPS：本報告沒有遊戲 frame presentation timing。

**本報告狀態：** 節點/權限盤點取得大量結果，但 60 筆取樣未完成；GPU proc 子樹未遞迴探查；App UID 實機可讀性尚未驗收。不得把「最終盤點」檔名視為所有準備工作已完成。
