# 資安審查與去追蹤修改（fork：bradyclh/HGTV）

審查日期：2026-10-24。對象：上游 `pan8664716/HGTV` 在 `c0f1156`（tag `v1.2.3`）的原始碼，以及 Release 附件 `HGTV_1.2.3.apk`
（SHA-256 `a400c97e3fdb9b8d02959d4a530f026ecf4c4bfeb1d9fcfcbd1465a4e0a9954d`，用 jadx 1.5.6 反編譯）。

## 一、最重要的發現：發布的 APK 不是用公開原始碼建置的

| 證據 | 公開原始碼（tag v1.2.3） | 發布的 APK 1.2.3 |
|---|---|---|
| 版本 | build.gradle 預設 `versionCode 53 / 1.2.0.1`，CI 沒有覆寫 | `versionCode 58 / 1.2.3` |
| 建置來源 | Actions 最後一次執行是 2026-10-18 | 1.1.8.2～1.2.3 都在 10-20 之後發布，**沒有對應的 CI run**（本機打包後手動上傳） |
| Application 類別 | 沒有，Manifest 也沒有 `android:name` | `com.hgtv.star.HgtvApplication`，啟動就初始化友盟 |
| `SessionBootstrap`、session 鏡像 | 沒有 | 有（`G0/N`、`G0/J`） |
| 權限 | 沒有 `WRITE_EXTERNAL_STORAGE` | 有 |
| 更新校驗 | 比對 SHA-256 + 大小 + 包名 + 版本 + 簽章，只允許 GitHub 網域 | 只確認檔案不是空的，另外加了第三方鏡像 `gh-proxy.org` |
| 可否建置 | 不能：少了友盟 Maven 倉庫、efs 插件 classpath、`multidex-keep.pro` | — |

友盟依賴是在 commit `59ff3a9` 加進來的，**但這個 commit 的標題是「docs: 1.1.8.2 变更记录」**。
專案以 GPL-3.0 發布，但目前散布的二進位檔沒有對應的完整原始碼。

## 二、問題清單

| # | 嚴重度 | 問題 | 位置 | 這個 fork 的處理 |
|---|---|---|---|---|
| 1 | **嚴重** | session 明文鏡像到共享儲存：`account-cookie`（sessionid／sid_tt…）、`session-token`、`x-tt-token`、裝置指紋寫進 `/sdcard/.hgtv/hgtv-session.json`。任何有儲存權限的 app 都能**讀取**（帳號被盜），也能**寫入**（植入 session）。卸載 app 後檔案還會留著。 | 只存在 APK（`G0/N.c()`）；build.gradle 的註解特別寫了 targetSdk 固定 29 就是為了這個功能 | 公開原始碼本來就沒有這段，fork 也**不加回來**。`SessionPrefs` 的說明裡寫明不可以寫入共享儲存 |
| 2 | 高 | 沒有同意畫面的友盟追蹤：`UMConfigure.init` 加上 UMCrash 全部開啟，包括 NET（網路請求監測）、PAGE、MEM、POWER、CODE_LOG；另外還有 U-APM 插樁 | `build.gradle.kts`（依賴與 efs 插件）、APK 的 `HgtvApplication` | **已移除**友盟 common／asms／apm 依賴，以及 efs 插件與它的設定 |
| 3 | 高 | 自動更新（APK 版本）不比對 sha256，還會走第三方鏡像 | 只存在 APK（`G0/C0188k.e()`、`E0/O1`） | fork 使用原始碼裡比較嚴格的更新流程，並把**更新來源改成本 fork**。上游帳號或發布流程被入侵時，新版不會推到這個 fork 的安裝上；本 fork 不發 Release 就等於關閉自動更新 |
| 4 | 中 | 登入憑證以明文存在 SharedPreferences `native-session-v1` | `Library.kt` | **已加密**：新增 `SessionPrefs`，用 Android Keystore 的 AES-256-GCM（API 23+）加密。舊的明文值讀得到，下次寫入就改成密文 |
| 5 | 低 | `PhoneSearchServer` 綁定在 `0.0.0.0`，另外兩個本機頁面只綁區網 IP | `PhoneSearchServer.kt` | **已改成**只綁指定的區網 IP |
| 6 | 低 | 三個區網頁面走 HTTP：手機貼上的 `x-tt-token`、cookieStore.xml 在同一個 Wi-Fi 上是明文傳送 | `AccountImportServer`、`PhoneSearchServer` | 沒有修改。有 128-bit 一次性 token、Host／Origin 檢查、CSP 和 5 分鐘時限，風險可以接受；建議只在信任的網路上使用 |
| 7 | 資訊 | 只允許 `douyinvod.com`、`byteicdn.com`、`byteimg.com` 走明文流量（影片與圖片 CDN） | `network_security_config.xml` | 沒有修改，因為影片 CDN 需要 |
| 8 | 資訊 | `minSdk` 的允許範圍 26..100 和實際的 19 不一致，原始碼版的更新流程會拒絕上游現在的 update.json | `AppUpdates.kt` | 改成 19..100 |

## 三、已修改的檔案

- `android/app/build.gradle.kts`：移除 `com.efs.sdk.plugin`、efs 設定區塊，以及 `com.umeng.umsdk:*`。
- `android/app/multidex-keep.pro`：補上這個缺少的檔案（build.gradle 有參照到它）。
- `android/core/.../AppUpdates.kt`：`REPOSITORY` 改成 `bradyclh/HGTV`；minSdk 允許範圍改成 19..100。
- `android/core/.../PhoneSearchServer.kt`：`ServerSocket` 綁定到指定的區網 IP。
- `android/app/.../data/SessionPrefs.kt`（新增），`Library.kt` 的 `sessionPrefs` 改用它。

## 四、使用建議

1. **不要安裝上游發布的 APK**（1.1.8.2 之後的版本都有上面第 1～3 項的問題）。如果已經裝了：
   - 解除安裝；
   - 刪除 `/sdcard/.hgtv/`；
   - 到紅果 app 裡登出所有裝置（讓已經外流的 session 失效）。
2. 用這個 fork 自己建置，並用**自己的簽章金鑰**簽署。簽章和上游不同，所以要先解除安裝上游版本才裝得上（這也剛好阻止上游的更新覆蓋掉 fork 版本）。
3. 在網路層擋掉 `*.umeng.com`、`*.umengcloud.com`、`one.umops.com`、`gh-proxy.org`，作為第二道防線。
4. 帳號用的是第三方非官方客戶端，請自行評估帳號風險，最好不要用主要帳號。

## 五、限制

- 本機沒有 Android SDK，`:app` 沒有編譯過，`SessionPrefs` 和 build 設定的修改也沒有在實機上驗證過。
- 公開原始碼缺少 APK 裡的登入保活／`SessionBootstrap` 和 QR 登入等 1.1.8.2 之後的功能，所以這個 fork 建出來的功能會比 1.2.3 少。
- jadx 有 7 個方法沒能反編譯，那幾處的行為無法確認。
