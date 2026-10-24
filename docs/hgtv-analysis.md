# HGTV 1.2.3 資安分析報告（唯讀靜態分析，2026-10-24）

> 只做分析，沒有修改、重新打包或重新簽章 APK，也沒有連任何裝置。

## 結論
- 整體評級：**高風險**。
- VirusTotal：68 家引擎都沒有判定為惡意程式，但這個樣本今天才第一次上傳，參考價值有限。

## 基本資料
| 項目 | 值 |
|---|---|
| 來源 | https://github.com/pan8664716/HGTV/releases，v1.2.3（fork 自 N3urda/hongguoTV，GPL-3.0） |
| 套件 | com.hgtv.star，versionCode 58，minSdk 19，targetSdk 29 |
| APK SHA-256 | a400c97e3fdb9b8d02959d4a530f026ecf4c4bfeb1d9fcfcbd1465a4e0a9954d |
| 簽章 | v1 和 v2 驗證通過，自簽憑證 CN=HGTV, O=HGTV, L=Shanghai, C=CN，RSA 4096 |
| 憑證 SHA-256 | 27da90725c2bcec6c334096ee438877b480875dfaa1339b5dbedc5fd9fcee019 |
| VirusTotal | 0/68（59 家未偵測到，9 家無法處理這個檔案格式）；標籤：obfuscated、contains-elf、reflection |

## 1. 友盟追蹤（沒有同意畫面）
- App key：6ad3d4236545637cd615f056，channel 是 HGTV。
- 在 `com.hgtv.star.HgtvApplication.onCreate()` 一啟動就依序呼叫 `UMCrash.initConfig`、`UMConfigure.preInit`、`UMConfigure.init`。
- 整個 app 都找不到 `submitPolicyGrantResult` 或任何同意判斷。
- Native 函式庫：`libumeng-spy.so`（友盟裝置 ID）、`libcrashsdk.so`（UC 當機回報）；另外有 `com.efs.sdk`（U-APM 效能監測）。
- 從程式碼看得到的識別碼：Android ID、Google 廣告 ID（AD_ID）、OAID（透過 msa）、Wi-Fi MAC、序號、友盟裝置 ID。
- 沒看到讀取已安裝 app 清單的程式碼。IMEI 有程式路徑，但沒有 READ_PHONE_STATE 權限，Android 10 以上應該拿不到。
- 端點：
  - 統計：ulogs/cnlogs/alogus/alogsus/pslog/htls/preulogs.umeng.com、ulogs.umengcloud.com、htls.umengcloud.com
  - 識別與設定：utoken.umeng.com、resolve.umeng.com、ucc.umeng.com
  - 推播事件：aspect-upush.umeng.com
  - 當機回報：errlog/errlogos/errnewlog/errnewlogos.umeng.com
  - 安裝歸因與深層連結（deep link）：one.umops.com
- 實際回報了哪些欄位屬於執行時期的行為，靜態分析看不到。

## 2. 自動更新（供應鏈風險：中到高）
- 從 `https://github.com/pan8664716/HGTV/releases/latest/download/update.json` 取得更新資訊（`C0/C0066g`）。
- 下載來源可以選直接連線 GitHub，或第三方鏡像站 `https://gh-proxy.org/`（`E0/O1`）。全程 HTTPS。
- update.json 裡有 sha256，`E0/B0.t()` 也有解析，但**從來沒有拿去比對下載的檔案**。`G0/C0188k.e()` 只確認檔案不是空的，就交給 PackageInstaller 安裝。
- app 自己不驗簽章，只靠 Android 對同套件更新要求簽章相同。作者帳號或鏡像站被入侵時，同簽章的惡意更新擋不住。

## 3. 登入與憑證
- 用手機號碼加簡訊驗證碼登入：`api5-normal-lq.fqnovel.com/passport/mobile/sms_login/`；也支援 QR Code 登入。
- 也可以上傳紅果 app 的 `cookieStore.xml`，從裡面抽出 sessionid（`X0/f`）。
- SharedPreferences `native-session-v1` **明文存放** session-token、account-cookie（sessionid、sid_tt、sid_guard、uid_tt、odin_tt、d_ticket 等）、x-tt-token、裝置 ID。沒有用 EncryptedSharedPreferences 或 Keystore 加密。
- `allowBackup=false`。寫進 log 前會先遮罩 ticket 和 sessionid。

## 4. 其他
- 明文流量：networkSecurityConfig 只允許 douyinvod.com、byteicdn.com、byteimg.com（含子網域）走 HTTP。
- 匯出元件只有 MainActivity。FileProvider `com.hgtv.star.updates` 沒有匯出。
- 沒有廣告 SDK，沒有用 DexClassLoader 載入外部程式碼。WebView 的 JS bridge 只在 U-APM 裡出現。
- 權限：INTERNET、ACCESS_NETWORK_STATE、ACCESS_WIFI_STATE、REQUEST_INSTALL_PACKAGES、WRITE_EXTERNAL_STORAGE、AD_ID、freemme.permission.msa。
- jadx 有幾個方法沒能反編譯（`C0188k.d()`、`C0189l.b/c`），那幾處的內容無法確認。

## 測試環境建議
- 在網路層擋掉 `*.umeng.com`、`*.umengcloud.com`、`one.umops.com`、`gh-proxy.org`。
- 用一個閒置的門號登入。
