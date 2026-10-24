# 派生來源與第三方聲明

## 1. 派生來源

本 repo 是 [pan8664716/HGTV](https://github.com/pan8664716/HGTV) 的 fork，而 pan8664716/HGTV 又是
[N3urda/hongguoTV](https://github.com/N3urda/hongguoTV) 的 fork，兩層都依 GPL-3.0-only 派生。
上游程式碼的著作權歸原作者（N3urda、ermilylu、pan8664716 等）所有，本 fork 的修改歸 bradyclh 所有。
repo 根目錄的 `LICENSE` 和上游是同一份文字，沒有改動。

### 1.1 本 fork（bradyclh/HGTV）的修改

依 GPL-3.0 §5(a) 的要求聲明修改內容：本 fork **自 2026-10-24 起**在 pan8664716/HGTV `v1.2.3`（`c0f1156`）的基礎上修改。

- 移除友盟（Umeng）統計、當機回報、U-APM 相依套件與 efs Gradle plugin；
- 新增 `SessionPrefs`，用 Android Keystore 的 AES-256-GCM 加密登入憑證；
- `PhoneSearchServer` 改成只綁定指定的區網 IP；
- 應用程式內更新改指向 `bradyclh/HGTV`；
- 播放器相依套件從 `com.google.android.exoplayer` 2.19.1 改成 AndroidX Media3 1.4.1（原始碼本來就使用 Media3）；
- `minSdk` 從 19 調到 23，移除舊版 multidex，並補上 API 24／26 的版本判斷；
- 文件改成繁體中文（台灣用語），程式碼區塊與 API 原始資料保持原樣。

理由與細節見 [資安審查](docs/SECURITY_REVIEW.md)。

### 1.2 pan8664716/HGTV 相對於 N3urda/hongguoTV 的修改

pan8664716/HGTV **自 2026-10-06 起**修改：

- 移除 React Native 電視版、iOS 專案和 Node／Docker 內容服務，只保留 Android TV 原生用戶端；
- 可建置的 Gradle 專案從 `kotlin-tv/` 移到 `android/`，repo 根目錄只留文件與工具；
- 套件名稱 `com.hongguotv.nativeapp` → `com.hgtv.star`，應用程式名稱改成「红果TV」；
- 重做介面（新擬態與十四套材質主題）、發布流程與產出檔命名。

派生基準是 N3urda/hongguoTV `main` 的 `eea73e6`（`fix: release updater TLS connections off the main thread`，2026-09-23），
之後上游沒有新的 commit。逐項修改見 [變更紀錄](docs/CHANGELOG.md)。

## 2. 第三方相依套件聲明

`core` 裡的內容請求簽章、Spade URL／key 處理、MP4 CENC 處理，是以下 GPL-3.0 原始碼的 Kotlin 移植：

- drpys, `spider/js/红果果[短].js`
- https://github.com/woshishiq1/drpys/tree/22261adfa31435e3b3ef5a730b8a2a75bcaf1715
- 修改內容：完整的 Kotlin 實作；簽章只採用已驗證過的 hash branch 0；加密媒體透過 Media3 DataSource 直接讀取；加上樣本邊界檢查、子樣本處理和 AVC 原始編碼還原。

本 repo 的 Android TV 原生應用程式原始碼與派生實作以 GPL-3.0-only 發布，授權條款全文見 `LICENSE`。
本 fork 的原始碼 repo：https://github.com/bradyclh/HGTV 。發布 APK 時會一併提供對應的原始碼、Gradle wrapper 和建置說明。
APK 不包含 Node、React Native、JavaScript 引擎或上述 JavaScript 原始檔。開發時用來逐行比對的上游 JS 參考實作只在本機使用，
不隨本 repo 散布，也不參與應用程式的建置或執行。

其他相依套件：

- ZXing core（在本機產生手機輸入與更新用的 QR Code）：https://github.com/zxing/zxing — Apache-2.0
- Kotlin 標準函式庫：https://github.com/JetBrains/kotlin — Apache-2.0
- AndroidX Media3：https://github.com/androidx/media — Apache-2.0
- OkHttp／Okio：https://github.com/square/okhttp ／ https://github.com/square/okio — Apache-2.0
- Bouncy Castle SM3：https://github.com/bcgit/bc-java — MIT
- jsoup（只用來解析官網 HTML，不執行網頁腳本）：https://jsoup.org/ — MIT
- Android desugar_jdk_libs：https://github.com/google/desugar_jdk_libs — GPL-2.0 with Classpath Exception
- org.json（只用於 JVM 工具，APK 使用 Android 內建的實作）：https://github.com/stleary/JSON-java — 公眾領域聲明
- JUnit（只用於測試）：https://github.com/junit-team/junit4 — EPL-1.0
- Microsoft Fluent Emoji（彈幕表情圖，`app/src/main/assets/danmaku_emoji/`，已轉成 128px WebP）：
  https://github.com/microsoft/fluentui-emoji — MIT，授權條款全文和圖片放在一起：
  `app/src/main/assets/danmaku_emoji/LICENSE.txt`

應用程式圖示與電視橫幅（`app_icon.xml`、`tv_banner.png` 和由它們衍生的點陣圖）自 pan8664716/HGTV 建立起維護，
是在上游的基礎上重繪的（2026-10-12 改成扁平白底）。本應用程式不是內容平台的官方用戶端。

應用程式內「設定 → 開源授權」視窗顯示的是本文件的摘要：GPL-3.0-only 的授權範圍、原始碼 repo、
上面的第三方元件清單，以及「本機紀錄只保存在這台裝置上」這條界線。修改這裡時，要同步修改 `MainActivity.licenseText()`。
（注意：`licenseText()` 目前顯示的原始碼網址仍是 `pan8664716/HGTV`，還沒改成本 fork。）
