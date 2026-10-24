<p align="center"><img src="docs/preview/app-logo.png" width="192" alt="紅果TV 應用程式圖示"></p>

# 紅果TV（Android TV 6.0+）· 去追蹤版

給 Android TV 用的短劇用戶端：單一 APK，Kotlin + Media3（ExoPlayer），裝到電視上不用登入就能看。

> **這是 [pan8664716/HGTV](https://github.com/pan8664716/HGTV) 的 fork，重點是隱私與安全。**
> 上游發布的 APK 跟公開原始碼不一致，裡面多了沒有同意畫面的友盟追蹤，還會把登入憑證以明文寫到 `/sdcard`。
> 這個 fork 從原始碼重新建置：
>
> - 移除友盟（Umeng）統計、當機回報與 U-APM 監測；
> - 登入憑證用 Android Keystore（AES-256-GCM）加密保存，不寫到共享儲存空間；
> - 手機輔助用的區網服務只綁定區網 IP；
> - 應用程式內更新改指向本 fork，並驗證 SHA-256、套件名稱、版本與簽章；
> - 修好上游原始碼原本建置不起來的問題（Media3 相依套件），`minSdk` 調到 23。
>
> 完整的審查過程與發現見 [資安審查](docs/SECURITY_REVIEW.md) 與 [1.2.3 APK 靜態分析](docs/hgtv-analysis.md)。

## 目錄

- [功能](#功能)
- [介面](#介面)
- [安裝](#安裝)
- [在 Mac 瀏覽器觀看](#在-mac-瀏覽器觀看)
- [目前的限制](#目前的限制)
- [專案結構](#專案結構)
- [開發](#開發)
- [維護者](#維護者)
- [參與貢獻](#參與貢獻)
- [授權條款](#授權條款)

## 功能

- **瀏覽**：首頁分區劇架、熱播榜（多個榜單）、分類頁（大類／小類），長列表右下角有「回到頂部」。
- **搜尋**：用關鍵字搜尋，往下捲動會自動載入更多；在輸入框按確認就直接顯示結果。
- **收藏與紀錄**：本機收藏、最近觀看、稍後再看，支援從上次的位置繼續播放，以及「繼續觀看」直接跳轉。
- **帳號同步（選用）**：匯入紅果 app 的登入資料（電視選檔，或用手機掃碼上傳 `cookieStore.xml`）後，收藏與觀看進度可以和帳號雙向同步。登入資料只加密存在這台電視上。
- **選集**：詳情頁和播放器共用同一套選集表格，可以分頁瀏覽。
- **播放**：畫質上限可選 480P／720P／1080P，0.75–2× 倍速，三種畫面比例，自動連播。
- **影片解碼**：可以切換硬體解碼（預設，省電、4K 比較穩）和軟體解碼（相容性好，遇到花屏、綠屏或音畫不同步時使用）。
- **自訂按鍵**：播放畫面上的上下左右、選單、確定鍵各自的功能都可以改，也能一鍵恢復預設。
- **彈幕**：全螢幕／上方／隱藏三種模式，表情標籤會還原成表情圖，字級和透明度可以調整。
- **定時停止**：可以設定幾分鐘後或播完幾集後暫停，並保存進度。
- **播完推薦**：一集播完自動接下一集，整部劇播完後推薦下一部。
- **手機輔助**：用手機掃 QR Code，就能在手機上輸入搜尋字詞，或匯出、匯入本機紀錄（只在區網內，用完即關）。
- **應用程式內更新**：從本 fork 的 GitHub Releases 檢查新版本，下載後會先驗證才安裝。本 fork 沒發布 Release 時，等於關閉自動更新。
- **主題**：十四套材質主題，深色淺色都有。

## 介面

深色新擬態（Neumorphism）風格：整個 app 只用一種材質，層次靠「凸起／凹陷」和柔光來表現。
播放畫面不套用新擬態，疊在影片上的文字和線條固定用暖白色，避免搶走畫面的注意力。十四套材質主題如下：

<table>
<tr>
<td colspan="3" align="center"><img src="docs/preview/theme.png" width="100%" alt="十四套主題對照"></td>
</tr>
<tr>
<td align="center"><img src="docs/preview/1.jpg" width="100%" alt="介面截圖 1"></td>
<td align="center"><img src="docs/preview/2.jpg" width="100%" alt="介面截圖 2"></td>
<td align="center"><img src="docs/preview/3.jpg" width="100%" alt="介面截圖 3"></td>
</tr>
<tr>
<td align="center"><img src="docs/preview/4.webp" width="100%" alt="介面截圖 4"></td>
<td align="center"><img src="docs/preview/5.webp" width="100%" alt="介面截圖 5"></td>
<td align="center"><img src="docs/preview/6.webp" width="100%" alt="介面截圖 6"></td>
</tr>
<tr>
<td align="center"><img src="docs/preview/7.jpg" width="100%" alt="介面截圖 7"></td>
<td align="center"><img src="docs/preview/8.jpg" width="100%" alt="介面截圖 8"></td>
<td align="center"><img src="docs/preview/9.jpg" width="100%" alt="介面截圖 9"></td>
</tr>
</table>

> 截圖沿用上游，app 內的介面文字目前仍是簡體中文。

## 安裝

本 fork 目前**沒有發布 APK**，請依照下方的[開發](#開發)步驟自行建置，再用 ADB 安裝到電視。

**不要安裝上游發布的 APK**（1.1.8.2 之後的版本都包含友盟追蹤和明文 session 鏡像）。如果已經裝了：

1. 先解除安裝；
2. 刪除 `/sdcard/.hgtv/`；
3. 到紅果 app 裡登出所有裝置，讓可能已經外流的 session 失效。

本 fork 的簽章和上游不同，所以必須先解除安裝上游版本，才能裝上本 fork 的版本。

### 裝到 Fire TV（以 Fire OS 8 實測）

1. 電視上開啟 **設定 → 我的 Fire TV → 開發人員選項 → ADB 偵錯**。
   沒看到「開發人員選項」的話，到「關於」裡連按裝置名稱 7 次。
2. 在 **設定 → 我的 Fire TV → 關於 → 網路** 查到電視的 IP。
3. 在電腦上執行（第一次連線時，電視會跳出授權視窗，要按允許）：

   ```sh
   adb connect <電視IP>:5555
   adb install -r android/app/build/outputs/apk/debug/app-debug.apk
   ```

4. 裝好之後，建議把「ADB 偵錯」關掉。

### 系統需求

- Android TV 或 Android 6.0（API 23）以上的裝置，包括 Fire TV（Fire OS 6 以上）。
  已在 Fire OS 8.1（Android 11）上實測過。
- APK 全部是 JVM 程式碼，沒有原生函式庫（上游 APK 裡的 `.so` 是友盟的當機回報函式庫，已經移除）。影片能不能解碼要看裝置本身，
  遇到花屏、綠屏或音畫不同步時，可以在設定裡改用軟體解碼。
- 用不同簽章重新建置的 APK，不能直接覆蓋安裝原本的版本（解除安裝會清除本機紀錄）。

## 在 Mac 瀏覽器觀看

除了裝到電視，也可以在 MacBook 上用瀏覽器看。`android/web/` 是一個本機小型伺服器，
內容、簽章、播放網址與影片解密都沿用 `core`（和電視版同一套實作），瀏覽器只負責顯示與播放。

```sh
brew install openjdk@17     # 只需要 JDK，不需要 Android SDK
tools/web.sh                # 建置並啟動，會自動用預設瀏覽器開啟 http://127.0.0.1:8686/
```

- 功能：首頁熱播劇架、熱播榜、分類、搜尋（含提示詞與熱搜）、詳情與選集、相關作品、
  播放（畫質 1080P／720P／480P、0.75–2× 倍速、自動連播、斷點續播、彈幕全螢幕／上方／關閉）。
- 鍵盤：`空白` 播放／暫停、`←` `→` 快退／快進 5 秒、`↑` `↓` 音量、`F` 全螢幕、`D` 切換彈幕、
  `N`／`P` 下一集／上一集、`/` 跳到搜尋框。
- 收藏與觀看紀錄存在瀏覽器的 localStorage；裝置身分存在 `~/.hgtv-web/device.json`
  （第一次啟動時匿名註冊一次，之後沿用）。
- 影片多為 HEVC（H.265）：Safari、Chrome 在 macOS 上都能硬體解碼。
- 參數：`--port <埠號>`（預設 8686）、`--host 0.0.0.0`（讓同一區網的其他裝置也能看，預設只聽本機）、
  `--token <x-tt-token>`（可選，讓搜尋直接走 App 介面）、`--data-dir <目錄>`。
- 不支援帳號登入與雲端收藏同步。

## 目前的限制

內容來源是非官方介面，平台改版時隨時可能失效。目前已知的限制如下：

### 播放

- 拉流網址是 CDN 直連，**有時效性**；畫質上限 1080P，個別編碼的串流會被略過
  （有些裝置只有聲音沒有畫面）。
- 偶爾拿不到播放網址時，畫面上會顯示可以重試的說明，稍後再試即可。

### 與上游 1.2.3 APK 的差異

公開原始碼本來就缺少上游 APK 在 1.1.8.2 之後加入的部分功能，所以本 fork 沒有：

- 手機號碼＋簡訊驗證碼登入、QR Code 登入；
- 重裝後自動恢復登入（上游靠的是把 session 明文寫到 `/sdcard`，本 fork 刻意不做）。

### 其他

| 現象 | 說明 |
| --- | --- |
| 搜尋沒有結果 | 裝置還沒在伺服器端註冊時，搜尋介面會回傳空結果；下次啟動時會自動重新註冊 |
| 彈幕數量比平台顯示的少 | 平台只下發抽樣過的熱門彈幕 |

平台介面會隨版本變動，個別功能可能暫時無法使用；畫面上會顯示可以重試的說明。

## 專案結構

```
android/                Gradle 專案根目錄（用 Android Studio 直接開這一層）
├─ app/                 電視介面、遙控器焦點、本機收藏／進度、彈幕繪製、Media3 播放器
├─ web/                 Mac 瀏覽器版：本機 HTTP 伺服器（API、解密串流）與單頁網頁
├─ core/                內容列表、搜尋、詳情、播放網址解析、媒體分段與彈幕解析（純 Kotlin，可在 JVM 上測試）
│  └─ src/test/         單元測試與固定測試向量
├─ gradle/ gradlew      Gradle 8.14.3 wrapper
└─ build.gradle.kts     建置腳本與發布簽章設定
docs/                   專案文件；preview/ 放應用程式圖示、主題圖與介面截圖
tools/                  圖示／主題／介面校對腳本、靜態檢查腳本、建置與版本腳本
README.md NOTICE.md LICENSE
```

## 開發

需要 JDK 17、Android SDK Platform 36 和 Build Tools 36.0.0；Gradle 8.14.3 wrapper 已經包含在 repo 裡。
在 macOS 上可以用 Homebrew 安裝：

```sh
brew install openjdk@17
brew install --cask android-commandlinetools
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
yes | sdkmanager --sdk_root=$ANDROID_HOME --licenses
sdkmanager --sdk_root=$ANDROID_HOME "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

也可以不設 `ANDROID_HOME`，改在 `android/local.properties` 寫入 `sdk.dir=<SDK 絕對路徑>`（這個檔案不會進版本控制）。

```sh
# 開發版：不需要發布用的私鑰就能直接安裝
./android/gradlew -p android :core:test :app:lintDebug :app:assembleDebug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

`:core:test`、`:app:lintDebug`、`:app:assembleDebug` 目前都能通過。

靜態檢查與離線校對圖（校對圖輸出到 `docs/preview/.local/`，不進版本控制）：

```sh
python3 tools/verify-button-system.py    # 按鈕系統規則（全站唯一實作、三種尺寸、內距使用 token）
python3 tools/app-icon.py --check        # 向量圖／點陣圖／背景色是否和腳本一致
python3 tools/app-icon.py                # 重新產生應用程式圖示、電視橫幅與 docs/preview/app-logo.png
python3 tools/theme-preview.py           # 十四套主題的對比度自我檢查 → docs/preview/theme.png
python3 tools/neumorphism-preview.py     # 首頁／按鈕六種狀態／強調色校對（只在本機看）
python3 tools/maintainer-avatars.py --check  # 維護者頭像是否為 256px 圓形透明背景
tools/version.sh                            # 應用程式名稱／識別碼／版本號／versionCode／產出檔名
```

## 維護者

本 fork 由 [bradyclh](https://github.com/bradyclh) 維護。

### 上游維護者與貢獻者

<table>
<tr>
<td align="center"><a href="https://github.com/pan8664716"><img src="docs/maintainers/pan8664716.png" width="88" alt="pan8664716"><br><sub><b>pan8664716</b></sub></a></td>
<td align="center"><a href="https://github.com/N3urda"><img src="docs/maintainers/N3urda.png" width="88" alt="N3urda"><br><sub><b>N3urda</b></sub></a></td>
<td align="center"><img src="docs/maintainers/ermilylu.png" width="88" alt="ermilylu"><br><sub><b>ermilylu</b></sub></td>
</tr>
</table>

派生關係：[N3urda/hongguoTV](https://github.com/N3urda/hongguoTV) → [pan8664716/HGTV](https://github.com/pan8664716/HGTV) → 本 fork。
pan8664716/HGTV **自 2026-10-06 起**在 N3urda/hongguoTV 的基礎上修改：移除 React Native 電視版、iOS 專案和
Node／Docker 內容服務，只保留 Android TV 原生用戶端，並把 Gradle 專案移到 `android/`；套件名稱從
`com.hongguotv.nativeapp` 改成 `com.hgtv.star`，應用程式名稱改成「红果TV」；重做介面和發布流程。
本 fork **自 2026-10-24 起**再做資安相關修改（見本頁開頭）。逐項差異見 [變更紀錄](docs/CHANGELOG.md)，
派生與第三方聲明見 [NOTICE.md](NOTICE.md)。

## 參與貢獻

本 fork 的問題請到 [bradyclh/HGTV 的 Issues](https://github.com/bradyclh/HGTV/issues) 回報。
和隱私、資安無關的功能問題，也可以向上游 [pan8664716/HGTV](https://github.com/pan8664716/HGTV/issues) 回報。

## 授權條款

[GPL-3.0-only](LICENSE)。© 2026 pan8664716；本 fork 的修改 © 2026 bradyclh。
派生自 [N3urda/hongguoTV](https://github.com/N3urda/hongguoTV)（GPL-3.0，上游程式碼著作權歸原作者所有）。
派生說明與第三方相依套件清單見 [NOTICE.md](NOTICE.md)。

本應用程式與紅果官方沒有關係，內容介面是非官方實作。使用帳號同步功能前請自行評估帳號風險，建議不要使用主要帳號。
