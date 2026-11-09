# Galaxy A55 5G — Wuthering Waves Monitor & Booster

以 Kotlin 開發，面向 Samsung Galaxy A55 5G（優先驗證台灣版 SM-A5560）的鳴潮效能監控與安全調校工具。

## 專案目標
- 即時顯示可驗證的 GPU 負載、GPU 頻率及其他裝置狀態。
- 整合 Shizuku／ADB shell 權限，明確呈現可用能力與限制。
- 提供遊戲啟動、HUD、Boost、停止及還原等簡化操作。
- 所有調校必須經過結果讀回驗證；不偽造成功狀態或效能提升。
- 優先確保系統安全、穩定性、低耗電及 UI 流暢。

## 專案規格與驗收標準
完整需求、GPU 資料來源候選清單、權限與服務生命週期、安全 Boost／還原規則、架構要求及 16 項驗收測試，請見：

- [docs/PROJECT_SPEC.md](docs/PROJECT_SPEC.md)

## 目標硬體
- 裝置：Samsung Galaxy A55 5G（SM-A5560 優先）
- SoC：Exynos 1480
- GPU：Xclipse 530

注意：GPU sysfs 節點、讀取權限、頻率及溫度來源都必須在實際零售韌體上驗證；規格文件列出的路徑只是探測候選，不代表每台裝置都支援。

## 開發狀態
目前已提交專案需求與驗收規格文件。Android/Kotlin 應用程式原始碼、實機資料來源驗證及功能測試仍待開發。
