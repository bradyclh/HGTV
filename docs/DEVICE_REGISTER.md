# 裝置註冊（`device_register`）

平臺的內容介面**按裝置放行**。所謂「不匯入帳號就用不了」，真實原因是這臺裝置沒在服務端註冊過；
註冊本身是**匿名**的——不需要帳號、密碼或簡訊，一次請求即可。

本檔案記錄這條介面的協議、踩過的坑，以及本工程 `core/.../DeviceRegister.kt` 的實現依據。

## 1. 它解決什麼問題

同一套簽名、同一個請求，註冊前後結果完全不同：

| 介面 | 未註冊裝置 | 註冊後 |
| --- | --- | --- |
| App 搜尋 `POST /reading/bookapi/search/tab/v` | `HTTP 200 + 0 字节`（從不返回資料） | `code 0`，一次 6–20 條、帶 `has_more` 遊標可翻頁 |
| 播放 `POST /novel/player/video_model/v1/` | `101000 服务异常` | `code 0` + 完整播放模型 |

平臺認的是 **`device_id` + `install_id` 這一對**，兩者繫結：

- 用抓包裡那對真機標識 → 搜尋有資料；
- 只換其中任一個（哪怕形狀完全正確）→ 立刻回空回應；
- 換任意一對全新標識 → 回空回應，**必須先註冊**。

因此「隨手編一對 16 位數字」不行：它只夠讓播放透過（見第 5 節），搜尋一定拿不到資料。

## 2. 請求

```
POST https://log5-applog-lf.fqnovel.com/service/2/device_register/
     ?tt_data=a&cronet_version=8d40f833_2026-03-03&ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1
```

`is.snssdk.com` 與 `log5-applog-lf.fqnovel.com` 等價，抓包裡兩個都在用。

### 請求標頭

| 頭 | 值 |
| --- | --- |
| `Content-Type` | `application/octet-stream;tt-data=a` |
| `log-encode-type` | `gzip` |
| `x-ss-dp` | `8662` |
| `x-ss-req-ticket` | 毫秒時間戳 |
| `x-vc-bdturing-sdk-version` | `4.0.3.cn` |
| `lc` / `sdk-version` / `passport-sdk-version` | `101` / `2` / `5051452` |
| `x-neptune` | `-8|50:51:59:30:40:47:49:39` |
| `x-ss-stub` | **body 的 MD5，大寫** |
| `User-Agent` | `com.phoenix.read/73932 (…)` |

**沒有簽名頭**：這條請求不帶 `x-gorgon` / `x-khronos` / `x-ladon` / `x-argus` / `x-helios` /
`x-medusa`，query 裡也沒有 `ts` / `_rticket`。加上反而會被判異常客戶端。

### 請求體

`ttEncrypt(gzip(JSON))`，原始二進位（不是 base64）。

`ttEncrypt` 與 `MediaCrypto.decryptUrl` 用的是同一族常量：

```
74 63 05 10 00 00 || rnd[32] || AES-128-CBC(key, iv, PKCS7(SHA512(data) || data))
key || iv = SHA512( SHA512(rnd) || KDF_SALT )
KDF_SALT = 4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524
           f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58
```

JSON 結構（冷啟動首註冊的形態，50 個欄位；`install_id` / `device_id` **不帶**，由服務端下發）：

```json
{
  "magic_tag": "ss_app_log",
  "header": {
    "display_name": "红果免费短剧",
    "aid": 8662, "channel": "69310258a", "package": "com.phoenix.read",
    "version_code": 73932, "app_version": "7.3.9.32",
    "sdk_version": "3.7.0-rc.34-fanqie-xiaoshuo", "sdk_target_version": 29,
    "git_hash": "3a88f4e", "release_build": "b5e187f",
    "os": "Android", "os_version": "12", "os_api": 32,
    "device_model": "AOSP on Intel Platform", "device_brand": "SAMSUNG",
    "device_manufacturer": "Intel", "cpu_abi": "arm64-v8a",
    "density_dpi": 360, "display_density": "mdpi", "resolution": "1440x2560",
    "language": "zh", "timezone": 8, "access": "wifi",
    "carrier": "CHINA MOBILE", "mcc_mnc": "46000",
    "rom": "eng.root.20231116.160340", "rom_version": "W528JS release-keys",
    "cdid": "6f224cd8-…", "sig_hash": "56a962410c494bbaf0b58dba20cae56f",
    "openudid": "72390a64583f5577", "clientudid": "ddf2f77b-…",
    "region": "CN", "tz_name": "Asia/Shanghai", "tz_offset": 28800,
    "sim_region": "cn", "sim_serial_number": [],
    "oaid_may_support": false, "req_id": "ecd30b14-…",
    "device_platform": "android",
    "custom": { "account_region_src": "fallback", "host_bit": 64,
                "account_region": "cn", "dragon_device_type": "pad" },
    "pre_installed_channel": "", "apk_first_install_time": 1791533654675,
    "is_system_app": 0, "sdk_flavor": "china", "guest_mode": 0
  },
  "_gen_time": 1792085984275
}
```

## 3. 回應

```json
{
  "server_time": 1792086005,
  "device_id": 4175314086266699,
  "install_id": 4175314086270795,
  "device_id_str": "4175314086266699",
  "install_id_str": "4175314086270795",
  "device_token": "AAA3NH3APQNV2E4S7QLXYLLML36OQY4NYPALK5IHYFI7XKHBNQ7WJKTFAAG4FO4O5CPD6RXR6LTAE64WHPJ73E236BXFB276AIBLF7WUM6AC7WVEPYCJCXV37NQXSFT6VY27SKSPFB53SNBDVXVGRDI",
  "new_user": 0
}
```

同時下發 Cookie（**後續請求要帶上**）：

```
store-region=cn-gd; store-region-src=did; install_id=4175314086270795; ttreq=1$1c2213245b92…
```

- `new_user` = 1 表示平臺這次新建了一臺裝置；`0` 表示這臺已存在。
- `device_id` / `install_id` 有時給數字、有時給字串，兩種都認；優先取 `*_str`。
- 判成功：兩個值都存在且不為 `0`。**回 `device_id=0` 就是被拒**（body 形態或頭不對）。

## 4. 三個踩過的坑

這三條都是照著抓包 HAR 復現時踩出來的，且都能穩定復現。

### 4.1 body 必須是原始二進位，不是 base64

抓包 HAR 裡 `postData.text` 是 **base64 文字**，但同一條請求的 `content-length` 等於
**解碼後**的位元組數（1030 / 1222）。照著 `postData.text` 把 base64 文字當 body 發出去，
服務端一律回 `device_id=0`。

用原始二進位重放同一條抓包請求，立刻回真實 `device_id`：

```
base64 文本当 body  → {"device_id":0,"install_id":0}
原始二进制当 body    → {"device_id":4175314086266699,…,"device_token":"AAA3NH3A…"}
```

### 4.2 `os_version` 必須是字串

寫成數字就失敗，且**只錯這一個欄位**：

| `os_version` | 結果 |
| --- | --- |
| `"12"`（字串） | 成功 6/6 |
| `12`（數字） | 失敗 6/6 |
| `"13"`（字串） | 成功 6/6 |
| `13`（數字） | 失敗 6/6 |

`os_api` / `density_dpi` / `sdk_target_version` 這些數字或字串都收。

### 4.3 回應可能是壓縮的

成功回應可能是 **brotli**（`Content-Encoding: br`）或 **gzip**；失敗回應（`device_id=0`）反而是
明文 JSON。用 OkHttp 時**不要手動設 `accept-encoding`**：OkHttp 只在自己加這個頭時才透明解壓，
手動寫死會讓它跳過解壓，body 到手上還是壓縮位元組、JSON 解析必失敗。

## 5. 播放與搜尋都只用註冊下發的那一對

實測兩者對身份的要求不同，但**本工程統一按註冊身份來**：

| | 服務端的實際門檻 | 依據 |
| --- | --- | --- |
| 播放 | 只要 `device_id` **不在平臺黑名單**裡 | 隨機 16 位數字 20/20 可播；倉庫內建那個固定值（大量第三方共用）被拉黑，一律 `200 + 0 字节` |
| 搜尋 | 必須是**服務端註冊過的 `device_id` + `install_id`** | 隨機值 0/6 命中；註冊後 6/6 有資料 |

也就是說：播放對身份的**要求**比搜尋松，但本機不再因此自己編一份隨機身份——
`device_id` / `install_id` 只有一個來源，就是註冊下發的這一對（`Library.deviceIdentity()`），
註冊之前它是空表：

- 啟動時非同步註冊一次（`MainActivity.ensureDeviceRegistered`）；
- **播放取播放地址之前再同步兜一次**（`registerDeviceOnce`；取地址那條路與下一集預取都走它），
  保證簽名用的身份是註冊過的這一對，而不是內建那份被拉黑的檔案；
- 註冊失敗時播放照舊嘗試（會走到「播放失敗，可重試」那一步），下次啟動或下一次播放再試。

併發呼叫由一把鎖收斂成一次請求（啟動那次與播放前那次可能同時進來）。

## 6. 第三方註冊不出來嗎

能註冊出來。上面第 2 節那套頭 + 原始二進位 body，就能穩定拿到全新裝置：

```
全新 cdid / openudid / clientudid / req_id，连发 10 次 → 10 次都拿到新 device_id、new_user=1
```

早期「註冊不出來」的記錄（一律 `device_id=0`）全部是第 4.1 節那個 base64 body 的錯。

## 7. 程式碼位置

| 位置 | 職責 |
| --- | --- |
| `core/.../DeviceRegister.kt` | `ttEncrypt` / 組 body / 組頭 / 解析回應（無 Android 依賴） |
| `core/.../ContentRepository.kt` | `registerDevice()`：發請求、寫回 `deviceIdentity` 與下發的 Cookie；搜尋與播放共用這份身份。`inheritDevice()` 讓播放預取那個倉庫例項共用同一份身份與 Cookie |
| `app/.../Library.kt` | 落盤註冊結果：`registered-device-id` / `registered-install-id`；`deviceIdentity()` 只認這一對，沒註冊過就是空表 |
| `app/.../MainActivity.kt` | `registerDeviceOnce()`：啟動時非同步補註冊、播放前同步兜一次；成功後重新拉首頁 |

### 升級相容

身份只有一組鍵，判據是 `Library.deviceRegistered()`：

- `registered-device-id` / `registered-install-id`：只有註冊成功才寫。判據是
  `Library.deviceRegistered()`。

早期版本（未釋出的那幾版試跑）曾把「本機隨機 `device-id` / `install-id`」當身份用，
播放與搜尋都拿它簽名。這兩組鍵**不再被讀取**（`Library` 裡已經沒有生成隨機身份的程式碼），
升級上來的機器上遺留的那兩個值就是一段死資料，不影響任何請求。

老版本升級上來時 `registered-device-id` 是空的，因此 `deviceRegistered()` 為 false：
啟動後會補一次註冊，播放前還會再兜一次；註冊結果寫進這一組鍵。
