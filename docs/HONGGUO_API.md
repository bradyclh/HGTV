# 紅果短劇介面參考

本檔案整理紅果短劇（`com.phoenix.read`，內容側與番茄小說同源）的客戶端介面。
內容分兩層：

- **官網層**（`https://hongguoduanju.com`）：Next.js SSR 頁面，資料藏在 HTML 的 `_ROUTER_DATA` 裡，
  不需要簽名，負責分類、片庫目錄、榜單、詳情、官網搜尋（App 搜尋不可用時的兜底）。
- **App 層**（`https://api5-normal-*.fqnovel.com`）：位元組系 App 介面，需要一整套裝置參數與簽名頭，
  負責 App 搜尋、播放地址解析、彈幕。

整理來源與可信度：

| 來源 | 用途 |
| --- | --- |
| `core`（本工程 Kotlin 實現，逐行核對） | 欄位名、參數、簽名與回應結構的主依據 |
| Android 12 模擬器 + 系統 CA + Frida 掛鉤 `SSL_write`/`SSL_read` 實測 | 驗證請求標頭、回應壓縮（brotli）與匿名取樣結論 |
| `woshishiq1/drpys`（22261ad）、`zhenyong97/hongguo-downloader` | 簽名演算法與協議變換的移植來源（GPL-3.0） |

> 所有內容都是**客戶端介面的逆向整理**，不是官方開放 API，也不承諾長期穩定。
> 平臺改欄位、改簽名、關介面都可能發生；本檔案描述的是當前實現所依賴的行為。

---

## 1. 域名與線路

| 用途 | 地址 |
| --- | --- |
| 官網 / SSR 資料 | `https://hongguoduanju.com` |
| App 介面（主） | `https://api5-normal-sinfonlinea.fqnovel.com` |
| App 介面（備用） | `https://api5-normal-sinfonlinec.fqnovel.com`、`https://api5-normal-sinfonlineb.fqnovel.com` |
| App 介面（備用） | `https://api5-normal-lf.fqnovel.com`、`https://api5-normal-lq.fqnovel.com`、`https://api5-normal-hl.fqnovel.com` |
| 播放地址解析 | `https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/`（2026-10-14 起；舊路徑見 4.3） |
| 裝置註冊 | `https://log5-applog-lf.fqnovel.com/service/2/device_register/`（App 介面的准入前提，見 [DEVICE_REGISTER.md](DEVICE_REGISTER.md)） |
| 彈幕 | 上述六條線路，按 `sinfonlinea → c → b → lf → lq → hl` 順序失敗轉移 |
| 影片 CDN | 由播放介面返回的 `fallback_api` 給出，域名隨資源與排程變化，不要寫死 |

線路都是同一套介面的不同入口；單條失敗（超時 / 非 2xx / 空回應）時依次換下一條，
彈幕視窗是唯一實現了「六線輪詢」的呼叫，其餘介面按需換線。

---

## 2. 通用約定

### 2.1 裝置參數（query 裡的 `device`）

官網介面不需要這些；App 介面**每一個**都要帶上，缺欄位會被按舊版本或異常客戶端處理。

| 欄位 | 值 | 說明 |
| --- | --- | --- |
| `iid` | `1905892595382586` | 安裝 ID。**必須與 `device_id` 成對且已在服務端註冊**（見 [DEVICE_REGISTER.md](DEVICE_REGISTER.md)） |
| `device_id` | `1905892595378490` | 裝置 ID。上表這個內建值是第三方共用的舊值，**已被平臺拉黑**，實現裡用註冊下發的那對 |
| `ac` | `wifi` | 網路型別 |
| `channel` | `update_64` | 渠道 |
| `aid` | `8662` | 應用 ID（紅果短劇） |
| `app_name` | `novelread` | 應用名 |
| `version_code` | `71332` / `72232` | 播放器介面用 71332，搜尋與彈幕用 72232 |
| `version_name` | `7.1.3.32` / `7.2.2.32` | 與 `version_code` 成對 |
| `manifest_version_code` | `71332` / `72232` | 與 `version_code` 同步 |
| `update_version_code` | `71332` / `72232` | 與 `version_code` 同步 |
| `pv_player` | `71332` / `72232` | 與 `version_code` 同步 |
| `device_platform` / `os` | `android` | |
| `ssmix` | `a` | |
| `device_type` | `25053RT47C` | |
| `device_brand` | `Redmi` | |
| `language` | `zh` | |
| `os_api` | `36` | |
| `os_version` | `16` | |
| `resolution` | `1280*2772` | |
| `dpi` | `520` | |
| `host_abi` | `arm64-v8a` | |
| `dragon_device_type` | `phone` | |
| `compliance_status` | `0` | |
| `need_personal_recommend` | `1` | |
| `player_so_load` | `1` | |
| `is_android_pad_screen` | `0` | |

兩套版本號在實現裡叫 `DEVICE`（播放器版本 7.1.3.32）與 `APP_DEVICE`（App 版本 7.2.2.32，
即上表的 `version_code` / `version_name` / `manifest_version_code` / `update_version_code` / `pv_player`
五項改成 72232 / 7.2.2.32）。**搜尋與彈幕用 App 版本，播放地址解析用播放器版本**，
混用會被平臺按舊客戶端處理。

### 2.1.1 裝置註冊（App 介面的准入前提）

上表的 `iid` / `device_id` **不是隨便填的**：平臺按這一對放行，只有**服務端註冊過的**
那一對才拿得到資料。註冊是匿名的、一次請求即可，協議與踩過的坑見
[DEVICE_REGISTER.md](DEVICE_REGISTER.md)。

> 沒註冊的裝置：App 搜尋回 `HTTP 200 + 0 字节`，播放回 `101000 服务异常`。
> 這曾經被誤讀成「要求登入態」——實際門檻是裝置，與帳號無關。

### 2.2 簽名（App 介面必需）

App 介面的 query 裡除了裝置參數，還要加兩個時間欄位，並帶上一整套簽名頭：

1. `ts` = 當前秒級時間戳；`_rticket` = 毫秒級時間戳。
2. 用 `ts` / `_rticket` / query（SM3）/ body（MD5）算出一個分支號 `branch`；
   **只有 `branch == 0` 的請求才會被接受**，因此要把 `_rticket` 從 `now` 起逐個 +1 試，
   最多試 128 次，直到算出的分支號為 0（`Signer.sign` 的迴圈）。
3. 分支號命中後，再生成下列頭：

   | 頭 | 內容 |
   | --- | --- |
   | `x-ss-req-ticket` | 試出來的 `_rticket` |
   | `x-khronos` | `ts` |
   | `x-ladon` / `x-argus` | `ts` 的大端 / 小端位元組再 base64 |
   | `x-gorgon` | RC4 變體（key 含隨機數）加密 `MD5(query)[0:4] + MD5(body)[0:4] + 0 + 67503104 + ts`，再逐位元組半位元組交換、按位反轉、異或 20 |
   | `x-helios` | 34 輪表驅動的偽隨機流，輸入 `ts-1588093228-8662` |
   | `x-medusa` | protobuf 環境資訊（裝置、版本、時間、`cmr/vpn/kd` 等開關）+ `AesV3` 加密後 base64；外層是「20 位元組版本字首 + 4 位元組隨機尾」+ 密文，明文包以 `0x35 + 随机数 + 校验` 開頭 |
   | `x-tt-request-tag` | `t=0;n=0` |
   | `sdk-version` | `2` |
   | `passport-sdk-version` | `50561` |
   | `x-xs-from-web` | `0` |
   | `x-tt-dt` | 空 |
   | `Accept` | `application/json; charset=utf-8,application/x-protobuf` |

4. 有 body 時（POST）額外加：

   | 頭 | 內容 |
   | --- | --- |
   | `Content-Type` | `application/json; charset=UTF-8` |
   | `x-vc-bdturing-sdk-version` | `3.7.2.cn` |
   | `x-ss-stub` | body 的 MD5，**大寫十六進位** |

5. `User-Agent`：有 body 時用播放器 UA，無 body 時用 App UA（見 2.4）。

`AesV3` 是紅果自己的分組變換（不是標準 AES）：4 張 256 位元組 sbox（按 `ts & 3` 選一張）、
固定初始常量、8→12 輪金鑰擴充套件；加密時把 248 位元組以上的明文按每 8 位元組取 1 位壓成 32 位元組，
用兩段 CBC 派生金鑰，再把金鑰的 8 位逐位寫回原位元組的固定位元位，密文前置 1 位元組金鑰尾。

### 2.3 回應約定

- 官網介面返回 HTML（`text/html`），資料在 `_ROUTER_DATA` 裡，需要自己按括號配平截出 JSON
  （不能正則，字串裡會有 `}`）。
- App 介面返回 JSON，**成功判定是 `code == 0`**；播放介面大寫優先：`Code` 取不到再取 `code`。
- 回應體上限 16 MiB，超過直接判失敗（防止異常回應把記憶體吃滿）。
- 超時：連線 15s / 讀 30s / 整次呼叫 45s。
- 彈幕、播放地址這類回應可能是 **brotli** 壓縮（不是 gzip），抓包時要注意解壓。

### 2.4 User-Agent

```
# 播放器（有 body 的请求、视频 CDN 拉流）
com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)

# App（无 body 的请求）
com.phoenix.read/72232 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)
```

官網介面統一用播放器 UA + `Accept-Language: zh-CN,zh;q=0.9`。

---

## 3. 官網層介面（無需簽名）

### 3.1 `_ROUTER_DATA` 提取

所有官網介面共用一個解析入口：在 HTML 裡找到 `_ROUTER_DATA`，從其後第一個 `{` 開始做括號配平
（要考慮字串與轉義），截出的 JSON 結構是：

```json
{ "loaderData": { "<route-key>": { "……页面数据……": null } } }
```

route-key 隨頁面不同，下面每節單獨列。

### 3.2 首頁 / 分類頁

```
GET https://hongguoduanju.com/category?tab=1&sort_type=1[&page=N]
```

| 參數 | 說明 |
| --- | --- |
| `tab` | `1` = 短劇（真人劇） |
| `sort_type` | `1` = 最熱，`2` = 最新 |
| `page` | 第 2 頁起才帶；不傳即第 1 頁 |

route-key：`category_page`（部分回應是 `category_$`）。

```json
{
  "category_page": {
    "recommendList": [ { "video_data": { "series_id": "……" } } ],
    "pagination": { "totalPages": 100 }
  }
}
```

- 列表優先取 `recommendList`，其次 `categoryData.recommendList`；
- `pagination.totalPages` 預設按 100 處理，客戶端再夾到 100 頁上限；
- 卡片欄位對應見 4.1。

### 3.3 片庫目錄（搜尋補頁用）

```
GET https://hongguoduanju.com/category/comic-drama?page=N     # 漫剧，24 部一页、约 34 页
GET https://hongguoduanju.com/category/real-drama?page=N     # 真人剧
```

route-key：`category_$`（**字面量**，不是模板）。

```json
{ "category_$": { "recommendList": [ { "video_data": {} } ], "pagination": { "totalPages": 34 } } }
```

官網搜尋沒有分頁參數，能往下翻的只有這本目錄，因此 App 搜尋介面不可用時，搜尋第 2 頁起走這裡：
每次往後掃 3 頁，只保留**片名包含關鍵詞**（忽略空白、忽略大小寫）且介面還沒發過的條目。

### 3.4 劇集詳情

```
GET https://hongguoduanju.com/detail?series_id={剧集ID}
```

route-key：`detail_page`。

```json
{
  "detail_page": {
    "seriesDetail": {
      "series_id": "7615470177953532990",
      "series_name": "聚宝仙盆之杂灵根才是真BOSS",
      "series_cover": "https://……jpg",
      "series_intro": "……",
      "episode_cnt": 99,
      "episode_right_text": "全 99 集",
      "tags": [ { "name": "玄幻" }, { "name": "爽文" } ],
      "vid_list": ["7615470177953532991", "7615470177953532992"]
    }
  }
}
```

- `vid_list` 就是**分集 ID 列表，順序即集號**，播放與彈幕都用它；
- 客戶端會校驗：列表非空、每項都是 1–30 位數字、無重複，否則判為「分集資料異常」。

#### 3.4.1 相關短劇（同一份 HTML 的第二段資料）

頁面上「相關短劇」那一欄**不在** `_ROUTER_DATA` 裡，而是同一份 HTML 後面那段：

```html
<script async="" data-script-src="modern-run-window-fn" data-fn-name="mergeLoaderData"
        data-fn-args="[&quot;detail_page&quot;,[{&quot;key&quot;:&quot;recommendations&quot;,
          &quot;routerDataFnName&quot;:&quot;p&quot;,&quot;routerDataFnArgs&quot;:[&quot;{\&quot;videoList\&quot;:…}&quot;]}]]">
```

取值步驟（`ContentRepository.extractRecommendations`）：

1. 找 `data-fn-name="mergeLoaderData"`，取它後面的 `data-fn-args="…"`（到下一個 `"` 為止）；
2. HTML 實體反轉義（`&quot;` / `&#39;` / `&lt;` / `&gt;` / `&amp;`）後 `JSON.parse` 成陣列：
   `["detail_page", [ { key:"recommendations", routerDataFnArgs:[ "<JSON 字符串>" ] } ]]`；
3. `routerDataFnArgs[0]` **再** `JSON.parse` 一次，得到：

```json
{
  "videoList": [ { "series_id": "…", "series_name": "…", "series_cover": "…", "tags": [ … ] } ],
  "recommendationGroups": {
    "strategy_version": 2,
    "hot_list":     [ … 8 部 … ],
    "related_list": [ … 16 部 … ]
  }
}
```

- 卡片欄位與 3.4 / 4.1 的 `Card` 同構，直接複用同一份欄位對應；
- `related_list` 與 `videoList` 實測內容一致（16 部），取 `related_list`；缺失時退回 `videoList`；
- `hot_list` 是同一頁的「熱門」欄（8 部），本工程未使用；
- 解析失敗一律返回空列表——相關短劇只是詳情頁的附加內容，不能影響詳情本身。
- 聯調：`./android/gradlew -p android --offline :core:run --args="--related 7691228619774905368"`。

#### 3.4.2 同一部劇的其它季（走 App 詳情頁的「相關作品」介面）

官網詳情頁裡**沒有**同系列各季，也沒有分季介面；能一次拿全的是 **App 詳情頁那一欄
「相關作品」**（見 4.6）。客戶端現在就用它，不再做「官網搜尋 + 沿季號逐季搜 + 掃片庫目錄」
那套近似召回（實測 2~4s、二十多次請求，且召回不穩定）。

官網這一側只保留一個兜底：`mergeLoaderData` 裡的 `recommendationGroups.related_list`
（16 條「看了這部還看什麼」的推薦，見 3.4.1），介面整輪失敗時用它填那一欄，
**不發任何搜尋請求**。

### 3.5 官網搜尋（第 1 頁兜底）

```
GET https://hongguoduanju.com/search/{URL 编码后的关键词}?page=1
```

route-key：`search_(keyword)/page`（部分回應是 `search_page`）。

```json
{ "search_(keyword)/page": { "searchList": [ { "video_data": {} } ] } }
```

**`page` 參數被服務端忽略**：`page` / `page_num` / `offset` / `page_size` / `count` 試過都一樣，
一次只給相關度最高的約 10 條，`totalCount` 卻顯示 200 左右。所以它只負責第 1 頁：
App 搜尋拿不到資料時（裝置未註冊），客戶端退回到這裡取第 1 頁，第 2 頁起改用片庫目錄（3.3）。

### 3.5.1 搜尋提示詞（聯想詞）

官網 PC 端搜尋框的下拉建議用的就是它（前端 chunk `gp.js` 裡的 `/incent_resource/suggestion`）：

```
GET https://hongguoduanju.com/incent_resource/suggestion?app_id=8662&web_id={随机 UUID}&query={关键词}&count=10
```

| 參數 | 說明 |
| --- | --- |
| `app_id` | **必填**，固定 `8662`（紅果短劇在番茄系的 aid）。缺了它服務端返回 **500** |
| `query` | 關鍵詞；**空值返回 500**，因此呼叫前先判空 |
| `count` | 條數，有效上限 **20**（傳 100 也只回 20），不傳按 10 |
| `web_id` | 官網每次頁面載入生成的隨機標識。**可省**，服務端不校驗它與會話的對應關係 |

**不需要簽名、不需要裝置註冊**（與 App 搜尋介面不同），實測延遲約 70ms。

```json
{ "suggest_list": [
  { "display_words": [ "聚宝仙盆之杂灵根才是真BOSS" ],
    "keyword": "7615465407347952664",
    "name": "聚宝仙盆之杂灵根才是真BOSS",
    "word_type": "short_play_name",
    "video_data": { "series_id": 7615465407347952664, "series_title": "……",
                    "series_cover": "……", "series_intro": "……", "episode_cnt": 99,
                    "episode_right_text": "全99集",
                    "hot_score_data": { "score": 46855999, "text": "4685万热度" },
                    "category_list": [ { "category_id": 7, "name": "玄幻" } ],
                    "vid_list": [ "……" ] } }
] }
```

- `name`（兜底 `display_words[0]`）就是提示詞本身；
- `video_data` **與卡片是同一套欄位**，可直接複用卡片解析（見 4.1）；
- `word_type` 實測見過六種：`short_play_name`（劇名，**帶完整 `video_data`**）、`title`、
  `common_query`、`album`、`short_play_category`、`short_play_kol_user`——
  後五種 `video_data` 是空物件 `{}`，只能當**查詢詞**用；
- 客戶端據此分兩類：`video_data.series_id` 有值 → 點這條直接進詳情；
  否則 → 點這條拿 `name` 去搜。

#### 3.5.1.1 熱搜詞（沒有專門介面，用聯想詞合成）

平臺**沒有**熱搜榜介面（`/incent_resource/hot_word`、`/incent_resource/hot_search`、
`/incent_resource/keyword`、`/incent_resource/search_word` 實測全是 **404**），
`/query` 頁面也是 **410 Gone**。但 3.5.1 的聯想詞介面本身就是「按熱度排的候選」：
拿幾個題材詞當種子各問一次，把返回裡**不是某一部劇**的那些（`video_data` 是空物件）收集起來
去重，就是一份現成的熱詞表。

客戶端的做法（`ContentRepository.hotSearches`）：

1. 種子詞 `短剧 / 都市 / 逆袭 / 甜宠 / 战神` **併發**各問一次（序列五個要 1.8s，併發約 0.3~0.8s）；
2. 丟掉「是某部劇」的那些（`series_id` 有值）與過長過短的詞，按 `word_type` 分層：
   `short_play_category` / `category`（題材）> `common_query`（平臺自己的搜尋建議）> `title` / `album`；
3. 同層內**按種子輪詢**交替取：一個種子單獨就能填滿整張表，順著一個種子取的話
   整排熱搜詞都是同一個題材的變體（實測 `短剧` 一個種子回的全是「…短劇」）；
4. 全部種子都失敗時退回一張寫死的兜底表（搜尋頁不能因為一個提示介面而空著）。

實測 `hotSearches(12)` 約 0.8s 返回：`都市日常 / 逆襲翻身 / 甜寵 / 戰神歸來 / 都市愛情 / 逆襲 /
都市 / 都市修仙 / 青春甜寵 / 戰神贅婿 / 30集短劇 / 都市旺業`。

> 相關但**不可用**的路徑：`/incent_resource/selector`（分類頁的篩選器）、
> `/incent_resource/kep/query`（需要 `keyword_hash`）、`/query` 頁面（返回 **410 Gone**）。
> 這三條本工程未採用。

### 3.6 榜單

```
GET https://hongguoduanju.com/rank/{route}[?page=N]
```

| route | 榜單 |
| --- | --- |
| `hot-drama` | 紅果熱播榜 |
| `hot-real-drama` | 真人劇熱播榜 |
| `hot-comic-drama` | 漫劇熱播榜 |
| `hot-ai-drama` | AI 劇熱播榜 |

回應有兩種形態，客戶端先試第一種、失敗再退回第二種：

1. **序列化資料**（優先）：HTML 裡的 `<script data-fn-name data-fn-args>`，
   `data-fn-name` 為 `r`（且 `data-fn-args[1] == "content"`）或 `mergeLoaderData`
   （`routerDataFnName == "p"`），`data-fn-args[0]` 為 `rank_hot-comic-drama/page`。

   ```json
   {
     "isSuccess": true,
     "rankList": [
       { "seriesId": "……", "title": "……", "cover": "……", "description": "……",
         "episodeCount": 99, "heatText": "1.2 亿", "rank": 1, "tags": [ "玄幻" ] }
     ],
     "pagination": { "pageNum": 1, "totalPages": 20 }
   }
   ```

   校驗：`isSuccess` 為真、`pagination.pageNum == 请求页`、`totalPages >= 1`。

2. **已渲染的 DOM 兜底**：`article[aria-labelledby^=rank-title-]`（`rank-title-{seriesId}` 即 ID），
   名次取 `[class*=badge-number-]`，熱度取 `p[class*=metrics-]`，
   分頁取 `nav[aria-label=榜单分页]`（`[aria-current=page]` 必須等於請求頁）。

另外 `loaderData["rank_hot-comic-drama/page"].updatedText` 是「更新於 ……」文案。

---

## 4. App 層介面（需要簽名）

### 4.1 卡片欄位對應（官網與 App 搜尋共用）

```json
{
  "video_data": {
    "series_id": "7615470177953532990",
    "series_name": "聚宝仙盆之杂灵根才是真BOSS",
    "series_cover": "https://……jpg",
    "series_intro": "……",
    "episode_cnt": 99,
    "episode_right_text": "全 99 集",
    "tags": [ { "name": "玄幻" } ]
  }
}
```

| 輸出欄位 | 取值 | 兜底 |
| --- | --- | --- |
| `id` | `series_id` | — |
| `title` | `series_name` | `series_title` → 外層 `name` |
| `cover` | `series_cover` | 反轉義 `\/`、`\u002F`、`\u0026`、`&amp;` |
| `description` | `series_intro` | — |
| `badge` | `episode_right_text` | `episode_cnt > 0` 時「全 N 集」 |
| `tags` | `tags[].name`（最多 5 個，` · ` 連線） | `category_list` |

`episode_cnt` 還可以藏在 `series_episode_info.episode_cnt`。
入庫前統一校驗：ID 為 1–30 位數字、標題非空、按 ID 去重。

### 4.2 App 搜尋

```
POST https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v?<device + ts + _rticket>
```

query 除裝置參數（**App 版本 7.2.2.32**）外：

| 參數 | 值 | 說明 |
| --- | --- | --- |
| `query` | 關鍵詞 | |
| `count` | `20` | 每頁條數 |
| `offset` | `(page-1)*20` | 翻頁時被上一頁遊標裡的 `next_offset` 覆蓋 |
| `tab_type` | `11` = 短劇/真人劇，`19` = 漫劇 | |
| `bookshelf_search_plan` | `4` | |
| `use_correct` | `true` | |
| `user_is_login` | `0` | 匿名 |
| `search_id` | 上一頁 `search_tabs[].search_id` | 第 2 頁起必帶（漫劇） |
| `passback` | 上一頁 `search_tabs[].passback` | 同上 |

回應（擷取相關部分）：

```json
{
  "code": 0,
  "search_tabs": [
    {
      "tab_type": 19,
      "search_id": "……",
      "passback": "……",
      "next_offset": "20",
      "has_more": true,
      "data": [
        { "book_id": "……", "cell_name": "……", "lynx_data": "{……}",
          "search_high_light": { "title": { "text": "聚宝<em>仙盆</em>" } },
          "video_data": [ { "series_id": "……", "title": "……", "cover_url": "……",
                            "episode_cnt": 99, "video_desc": "……", "rec_text": "……", "sub_title": "……" } ] }
      ]
    }
  ]
}
```

- 取 `tab_type` 與請求一致的那個 tab；
- 每個 cell 先看自身，再展開 `JSON.parse(cell.lynx_data).cell_data`（`lynx_data` 是字串化的 JSON）；
- 每條取 `video_data[0]`：`series_id`（兜底 `book_id` → `search_result_id`）、
  `search_high_light.title.text`（兜底 `video_data.title` → `cell_name`）、
  `cover`/`cover_url`、`video_desc`、`episode_cnt`、`rec_text`、`sub_title`；
- 遊標（`search_id` / `passback` / `next_offset`）按「型別 + 關鍵詞 + 頁碼」快取 300 秒，
  最多 100 條；漫劇翻頁缺遊標時拋「搜尋結果已過期，請從第 1 頁重新搜尋」。

> **搜尋的路徑**：客戶端先請求這個介面，第 2 頁起帶上一頁的遊標；
> 拿不到資料時退回官網搜尋（第 1 頁）+ 片庫目錄（第 2 頁起）。

> **門檻是「裝置已註冊」，不是登入態。** 平臺按 `device_id` + `install_id` 這一對放行：
> 註冊過的裝置回 `code 0` 或間歇性 `100103`，**從不回空回應**；沒註冊的一律
> **HTTP 200 + 空回應體**。因此必須**先判空**再解析——直接丟給 JSON 解析器會拋
> `A JSONObject text must begin with '{'`，把回退路徑打斷。
> 註冊是匿名的，協議見 [DEVICE_REGISTER.md](DEVICE_REGISTER.md)；
> 註冊後本介面第 1 頁起就能用，一次 6–20 條、帶遊標可翻頁。
>
> 空回應**不要原地重試**：它表示「這臺裝置沒註冊」，重發多少次都一樣
> （舊實現退避 5 次 × 2 個 tab ≈ 12.8 秒，搜尋因此要十幾秒才出結果）。

### 4.3 播放地址解析

**平臺在 2026-10-14 換了一次介面，而且是路徑與請求體一起換。**當前可用的是：

> **門檻是裝置，不是登入態。** 未註冊裝置回 `101000 服务异常`；註冊過的裝置回 `code 0`。
> 另外**只要 `device_id` 不在平臺黑名單裡**（內建那個第三方共用值被拉黑）隨機值也能透過：
> 服務端對播放的門檻比搜尋鬆。但客戶端**統一只用註冊下發的那一對**簽名，
> 不再自己編隨機身份（取播放地址之前會同步兜一次註冊）——
> 詳見 [DEVICE_REGISTER.md](DEVICE_REGISTER.md) 第 5 節。

```
POST https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/?<device + ts + _rticket>
Content-Type: application/json; charset=UTF-8
x-ss-stub: <body 的 MD5，大写>
```

body（`video_id` = 分集 ID，也就是 `vid_list` 裡的一項，**單值字串**）：

```json
{
  "video_id": "7615470177953532991",
  "biz_param": {
    "detail_page_version": 0, "device_level": 3, "disable_digg_stat": false,
    "need_all_video_definition": true, "need_mp4_align": false,
    "use_os_player": false, "use_server_dns": false, "video_platform": 1024
  }
}
```

回應：

```json
{
  "code": 0,
  "data": {
    "disable_vsr": true,
    "expire_time": 1791978756,
    "video_width": 1254,
    "video_height": 720,
    "video_model": "{\"status\":10,\"video_list\":[……],\"fallback_api\":{……}}"
  }
}
```

- **請求體形態不能混用**：這條路由只認 `video_id` 單值。給 `mixed_video_id_map`、`vid`、
  `video_ids`、`item_id` 一律返回 `100001 invalid param`（`debug_info` 是 `video_id invalid`），
  給陣列則返回 `400 InvalidRequest`；
- `biz_param` 可以省略（返回 1 檔清晰度），帶上才拿到完整 5 檔；欄位與舊路徑完全一致；
- **必須簽名**：裸 POST 或只帶裝置 query（沒有 `x-gorgon` / `x-medusa` / `x-helios` 等簽名頭）
  返回 `code -1`；簽名後才是 `code 0`；
- 六條線路（a/b/c/lf/lq/hl）表現一致；
- `video_model` 是**字串化的 JSON**，要再解析一層；`video_list` 是**陣列**（舊路徑是物件），
  元素裡是 `video_meta`（`vwidth`/`vheight`/`bitrate`/`size`）、`gear_des_key`（`…|4:720p|…`）、
  `encrypt_info.spade_a`（`cenc-aes-ctr`）、`main_url` / `backup_url`；
- 拿到 `video_model.fallback_api` 後**再發一次普通 GET**（播放器 UA，不需要簽名）：

```json
{
  "video_info": {
    "data": {
      "key_seed": "……base64……",
      "video_list": {
        "1080p": {
          "main_url": "……（可能是加密串）",
          "codec_type": "h264",
          "spade_a": "……base64……",
          "quality_desc": "1080P",
          "height": 1080
        }
      }
    }
  }
}
```

- `fallback_api` 可能是物件（取 `.fallback_api`）、陣列（取 `[0]`）或字串（再解析一層）；
- 這一跳的 `video_list` 是**物件**，鍵名是 `video_1`…`video_5`，清晰度看
  `quality_desc` / `height` / `vheight` / `quality`，或由 key 兜底；
- `key_seed` 非空時用 `main_url` 走 `MediaCrypto.decryptUrl`（見 5.1）；
- `spade_a` 非空時用 `MediaCrypto.deriveKey` 得到 16 位元組內容金鑰（CENC 解密用）；
- **跳過 `codec_type` 為 `bytevc2` / `bvc2` 的資源**：這類資源在 Android 的 MP4 解析器裡
  可能只有音軌、沒有畫面；
- 清晰度數值由 `quality_desc` / `height` / `vheight` / `quality` / key 依次兜底解析，
  支援 `2160/1440/1080/720/576/540/480/360`，以及 `1920→1080`、`1280→720`、
  `1024→576`、`854→480`、`640→360`；按數值降序取第一個 ≤ 請求上限的，
  都不滿足時取最低的一檔並在畫面上標註「相容資源」。

#### 4.3.1 舊路徑的現狀（保留兜底）

| 路徑 | 2026-10-14 實測 | 說明 |
| --- | --- | --- |
| `POST /novel/player/video_model/v1/` | `code 0` + 完整 `video_model` | **當前可用**，`video_id` 單值 body |
| `POST /novel/player/multi_video_model/preload/v1/` | HTTP 200 + **0 位元組** | 0.15.0 起的第一候選，已失效 |
| `POST /novel/player/multi_video_model/default/v1/` | HTTP 200 + **0 位元組** | 同上 |
| `POST /novel/player/multi_video_model/detail/v1/` | HTTP 200 + **0 位元組** | 同上 |
| `POST /novel/player/multi_video_model/v1/` | `Code 110001 未知异常` | 更早的舊路徑 |
| `POST /novel/player/multi_video_detail/preload/v1` | `code 0` + 分集列表 | 分集列表介面，仍然正常 |

> **空回應體必須當失敗處理。** 閘道器在業務處理前擋掉請求時就是 `HTTP 200 + 0 字节`，
> 這時業務碼取不到、預設成 0，不判空就會被當成成功往下走——0.15.0 與 2026-10-14 兩次
> 「所有影片都放不了」都是這個坑的不同變體。實現裡按「回應體為空 → 換下一條」處理。

### 4.4 彈幕（評論介面）

```
POST https://{六条线路之一}/novel/commentapi/comment/list/{vid}/v1/?<device + ts + _rticket>
comment-source: 601
server-channel: 1000
x-ss-stub: ""            ← 注意：这个接口的 x-ss-stub 被显式置空
```

body：

```json
{
  "comment_source": 601,
  "server_channel": 1000,
  "group_id": "7615470177953532991",
  "group_type": 30,
  "comment_type": 20,
  "sort": 1,
  "count": 90,
  "cursor": "",
  "aid": 8662,
  "compliance_status": 0,
  "business_param": {
    "book_id": "7615470177953532990",
    "start_offset_time": 0,
    "playlet_item_duration": 126000,
    "need_danmaku_guide_type": [1, 3, 4, 2]
  }
}
```

| 欄位 | 說明 |
| --- | --- |
| `group_id` | 分集 ID（vid） |
| `business_param.book_id` | 劇集 ID（series_id） |
| `start_offset_time` | 本視窗起點，毫秒 |
| `playlet_item_duration` | 本集總時長，毫秒 |
| `cursor` | 上一視窗 `common_list_info.cursor` 原樣回傳 |

回應：

```json
{
  "code": 0,
  "data": {
    "data_list": [
      {
        "comment": {
          "common": { "content": { "text": "这剧真好看" } },
          "expand": { "offset_time": 12340 },
          "stat": { "digg_count": 12 }
        }
      }
    ],
    "extra": { "next_query_danmaku_list_time": 30000 },
    "common_list_info": { "cursor": "……", "has_more": true }
  }
}
```

| 輸出 | 來源 |
| --- | --- |
| 文字 | `comment.common.content.text`（`isNull` 視為空） |
| 時間點 | `comment.expand.offset_time`（毫秒） |
| 點贊 | `comment.stat.digg_count` |

客戶端規則：

- **一次只回 30 秒視窗**，下一個視窗起點取 `extra.next_query_danmaku_list_time`
  （取不到或沒前進時按 `start + 30000` 兜底）；
- `common_list_info.has_more` 表示還沒到片尾；知道本集時長時以「視窗起點 ≥ 時長」為停止條件；
- 最多 40 個視窗（= 20 分鐘）、最多保留 3000 條，按 `offsetMs:text` 去重；
- **視窗之間至少間隔 180ms**：這個介面對匿名裝置有頻控，連發會返回風控錯誤；
- 一條都拿不到、或所有線路都失敗時返回空列表，介面上就是「這一集沒有彈幕」，不報錯。

> **匿名裝置只拿到取樣後的「熱彈幕」**：實測同一集原版 App 也只收到 2 條，
> 用抓到的請求體逐欄位回放結果一致；`cursor` 裡的 `danmaku_count`（自稱 542）
> **不是彈幕條數**。表現就是「有的劇一屏彈幕（實測某集 210 條）、有的劇一條都沒有」。

### 4.5 影片拉流（CDN）

播放地址是帶簽名的 CDN 直鏈，客戶端自己按段拉：

```
GET {fallback_api 返回的直链}
User-Agent: <播放器 UA>
Referer: https://novel.snssdk.com/
Range: bytes={start}-{end}          # 单段最大 8 MiB
Accept-Encoding: identity
```

- **必須返回 `206`** 並給出 `Content-Range: bytes a-b/total`，客戶端會校驗
  `a == 请求起点`、`b == min(请求终点, total-1)`、`total > 0`；返回 200（不支援分段）直接判失敗；
- 首次讀取 256 KiB 定位 `moov`，`moov` 必須緊跟 `ftyp` 且在檔案開頭
  （索引大小上限 8 MiB），否則「影片索引不在檔案開頭」；
- `total` 在一次會話內必須一致，變化即「影片資源已變化，請重試」。

---

### 4.6 相關作品（詳情頁「相關作品」那一欄）

App 詳情頁上「相關作品」那一欄不是官網頁面給的，而是詳情頁自己拉的一個 cell：

```
GET https://{线路}/reading/bookapi/plan/v:version/
    ?scene=10&from=detail_page_more_related&book_id={剧集 ID}
    &<设备参数 + ts + _rticket>
```

- 線路：六條 `api5-normal-*`（`sinfonlinea/c/b`、`lf`、`lq`、`hl`），與搜尋 / 彈幕同一套。
  注意 `ads5-normal-*.zijieapi.com` **不認**這個介面（實測 0/6，一律空回應體）；
- 鑑權：與其它 App 介面一樣，**裝置註冊 + 簽名**即可，不要求登入態；
- `from` 必須正是 `detail_page_more_related`：換成 `detail_page` / `video_detail_page_module`
  一律回空回應體；`scene=10` 之外的多餘參數（`limit` / `offset` / `pad_column_detail` …）
  實測不影響結果，給不給都一樣。

回應（`code == 0`）：

```json
{
  "code": 0, "message": "SUCCESS",
  "data": [
    { "cell_id": 7511643039589220414, "show_type": 547, "cell_name": "相关作品",
      "next_offset": 26, "has_more": false,
      "video_data": [ { "series_id": "7628202881140657177", "title": "万妖图录传第二季",
                        "cover": "…", "video_desc": "…", "episode_cnt": 53,
                        "season_index": 2, "tag_info": { "text": "第2季" } } ] },
    { "cell_id": 6975711477277655048, "show_type": 506, "cell_name": "猜你喜欢", "cell_data": [ … ] }
  ]
}
```

- 取 `show_type == 547`（或 `cell_name == "相关作品"`）那個 cell 的 `video_data[]`；
- 每條的卡片欄位與官網絡卡片同構（`series_id` / `title` / `cover` / `video_desc` / `episode_cnt`），
  另外多兩個平臺自己算好的欄位：
  - `season_index`：這一部是第幾季（沒有季號的條目為 `null`）；
  - `tag_info.text`：`第N季`（同一部劇的其它季）或 `同IP`（同 IP 的其它劇）。
- **一次給全，不需要翻頁**：`offset` / `limit` 被忽略，`next_offset` 就是條目總數、
  `has_more` 恆為 `false`。實測：萬妖圖錄傳 26 條（13 季 + 13 部同 IP）、
  聚寶仙盆 45 條、單季短劇 25 條。
- 每個 `book_id` 的結果**與該劇是第幾季無關**：查第一季、第二季、第十三季、未上線的
  第十四季，回來的都是同一份 26 條（只是「自己」那一條是否出現在 `第N季` 組裡會變）。

> **空回應體是常態，而且與註冊無關。** 這個介面實測約一半機率回
> **HTTP 200 + 0 位元組**（六條線路的成功率 2/6 ~ 4/6，同一線路連發亦然），
> 與「裝置沒註冊」那種空回應表現一致。因此客戶端**換線重發**（最多 8 次、輪一圈線路），
> 拿到 `code 0` 就返回；整輪失敗才退回官網詳情頁的 `related_list`（3.4.1）。
> 實測：一次請求約 0.4~1.1s（含重試），成功即拿到全部條目。

> **別只看 `tag_info` 挑同系列。** `第N季` 那一組裡混著同 IP 的**另一個系列**：
> 萬妖圖錄傳第一季那頁的 `第N季` 組裡有「萬妖圖錄傳：月落鎮魔第二季」，
> 聚寶仙盆那頁裡也有「凡人修仙開局撿到聚寶仙鼎第二季」。
> 因此客戶端仍然用 `core/SeriesSeasons.normalize` 按**劇名**篩同系列
>（剝季號 + 全形轉半形 + 去標點後完全相同），`tag_info` 只當參考。

## 5. 媒體解密

### 5.1 播放地址解密 `MediaCrypto.decryptUrl`

```text
raw     = base64(main_url)
前置校验 = raw[0] == 0xA8 && raw[2] == 1 && raw[3] == 0
h       = SHA512( SHA512(key_seed) + 常量串 )
plain   = AES-CBC( key = h[0:16], iv = h[16:32], data = raw[4:] 对齐到 16 的整数倍 )
```

去掉 PKCS#7 填充後再去掉尾部 `0x00`，得到真正的 URL。

### 5.2 內容金鑰 `MediaCrypto.deriveKey`

```text
raw    = base64(spade_a)
len    = min(raw.size - (raw[0]^raw[1]^raw[2]) + 47, raw.size - 1)   # 需 >= 33
work   = raw[1 : 1+len]
逐字节变换：a=85, b=246 交替参与，( -21 - popcount(i) + (previous ^ old) ) & 0xFF
key    = hex( ascii(work[1:33]) )        # 32 个十六进制字符 -> 16 字节
```

### 5.3 樣本解密（CENC）

拿到 `spade_a` 派生的金鑰後，客戶端**自己解析 MP4**（`MediaCrypto.prepare`）：

1. 遍歷 `moov → trak → stbl`，讀 `stsz`（樣本大小）、`stsc`（塊→樣本對應）、
   `stco`/`co64`（塊偏移）、`saiz`/`saio`（輔助資訊大小與偏移）；
2. 每個樣本的 8 位元組 IV 從輔助資訊裡取；`auxSize == 8` 表示整樣本加密，
   更大時按 `subsample_count + 6*count` 拆出「明文段 + 密文段」；
3. `sinf → frma` 給出**原始編碼格式**（AVC / HEVC），把它寫回 `encv`/`enca` 位置、
   把 `sinf` 換成 `free` 清零，這樣播放器看到的是普通 MP4；
4. 讀取時按樣本絕對偏移做 **AES-CTR** 解密，計數器是 `IV + 偏移/16`，
   段內不對齊的部分按 `relative % 16` 跳過。

---

## 6. 端到端呼叫順序

```text
浏览：GET /category?tab=1&sort_type=1            -> 卡片列表 + totalPages
榜单：GET /rank/hot-comic-drama?page=1           -> rankList + 名次/热度
搜索：GET /reading/bookapi/search/tab/v          -> tab_type 11=短剧 / 19=漫剧，一次 20 条
        └─ 第 2 页起带上一页的 search_id / passback / next_offset（游标缺省即回到第 1 页重搜）
        └─ 空响应体（设备未注册）/ 失败 -> GET /search/{kw}?page=1           （第 1 页）
                                      -> GET /category/{comic|real}-drama?page=N 往后扫（第 2 页起）
提示词：GET /incent_resource/suggestion?app_id=8662&query={kw}&count=10   （免签名、免登录）
        └─ 热搜词没有专门接口：并发问几个题材种子词，取「不是某部剧」的那些词（见 3.5.1.1）
详情：GET /detail?series_id={id}                 -> seriesDetail + vid_list[]
相关：GET /reading/bookapi/plan/v:version/         -> 相关作品 cell（show_type=547）的 video_data[]
      └─ scene=10&from=detail_page_more_related&book_id={id}；一次给全，空响应换线重发
      └─ 整轮失败时退回同一份 HTML 的 mergeLoaderData -> recommendationGroups.related_list[]（相关短剧）
播放：POST /novel/player/video_model/v1/          -> video_model.fallback_api（body 用 video_id 单值）
      └─ 兜底顺序：video_model → multi_video_model/{preload,default,detail}/ → multi_video_model/
      GET  {fallback_api}                        -> video_list + key_seed/spade_a
      GET  {main_url} Range: bytes=0-262143      -> 206 + moov -> 样本解密 -> 播放器
弹幕：POST /novel/commentapi/comment/list/{vid}/v1/  -> 30 秒窗口循环
```

本地聯調入口（本工程）：

```bash
# 榜单 / 搜索 / 提示词 / 热搜词 / 弹幕 五条探针
./android/gradlew -p android --offline :core:run --args="--ranking"
./android/gradlew -p android --offline :core:run --args="--suggest 仙"
./android/gradlew -p android --offline :core:run --args="--hot"
./android/gradlew -p android --offline :core:run --args="--search 聚宝 --short"
# 搜索探针：不带设备注册时退回官网搜索 + 片库目录
./android/gradlew -p android --offline :core:run --args="--search 聚宝 --short"
./android/gradlew -p android --offline :core:run --args="--danmaku"

# 默认探针：搜索 -> 首页 -> 详情 -> 播放地址 -> 分段读取（--save 落一份首段 MP4）
./android/gradlew -p android --offline :core:run
```

---

## 7. 已知限制與坑（實測）

| 現象 | 原因 / 處理 |
| --- | --- |
| App 搜尋返回 HTTP 200 空 body | **這臺裝置沒在服務端註冊**（不是登入問題）。空回應一次即判死、回退官網搜尋，**不要原地重試**；註冊協議見 [DEVICE_REGISTER.md](DEVICE_REGISTER.md) |
| 官網搜尋只有約 10 條、翻頁無效 | 服務端忽略所有分頁參數；App 搜尋不可用時第 2 頁起改用片庫目錄掃描 |
| 提示詞介面 500 | `app_id` 缺失或不是 `8662`；`query` 為空也會 500（兩條都是實測） |
| 漫劇搜尋報 `A JSONObject text must begin with '{'` | 空 body 直接進 JSON 解析器；先判空即可 |
| 彈幕條數遠少於平臺自報 | 匿名裝置只下發取樣熱彈幕，與參數無關 |
| 彈幕請求偶發風控 | 視窗之間留 180ms；六條線路輪詢 |
| 某些集只有聲音沒畫面 | 選到了 `bytevc2`/`bvc2` 編碼；選清晰度時跳過 |
| 播放返回 200 而非 206 | CDN 不支援分段；必須按分段協議處理，否則解密與 seek 都不成立 |
| 抓包拿不到有效資料 | App 有證書校驗 + QUIC；需系統 CA + Frida 解 pinning，或直接掛鉤 `SSL_write`/`SSL_read`，並注意回應是 brotli |
| 簽名請求被拒 | `_rticket` 必須落在 `branch == 0` 的分支上（本實現只發這一支） |
| 播放介面 `HTTP 200 + 0 字节` | 兩種成因：閘道器在業務處理前擋掉（路徑 / 請求體形態已變），或 `device_id` 被拉黑。**必須先判空再解析**，否則業務碼會預設成 0、被當成成功 |
| 裝置註冊回 `device_id=0` | body 形態不對（**必須是原始二進位，不能發 base64 文字**）或 `os_version` 寫成了數字；見 [DEVICE_REGISTER.md](DEVICE_REGISTER.md) 第 4 節 |
| 播放介面 `100001 video_id invalid` | 新路由 `/novel/player/video_model/v1/` 要 `video_id` 單值，給 `mixed_video_id_map` / `vid` / `video_ids` / 陣列都不行 |

---

## 8. 其他實現裡出現、本工程未使用的介面

以下路徑在開源實現中出現過，本工程沒有采用，列出來便於對照排查：

| 用途 | 路徑 | 備註 |
| --- | --- | --- |
| 分集詳情 | `POST /novel/player/multi_video_detail/v1/` | 與 `vid_list` 同源資訊 |
| 分集詳情（預載入） | `POST /novel/player/multi_video_detail/preload/v1` | `zhenyong97/hongguo-downloader` |
| 影片模型（新） | `POST /novel/player/video_model/v1/` | **已採用**：播放地址解析的第一候選，body 用 `video_id` 單值（見 `ContentRepository.videoModel`） |
| 影片模型（預載入） | `POST /novel/player/multi_video_model/preload/v1` | 已失效（200 + 0 位元組），保留為兜底 |
| 影片模型（場景段） | `POST /novel/player/multi_video_model/{default,detail}/v1/` | 已失效（200 + 0 位元組），保留為兜底 |
| 分享連結轉劇集 ID | — | 由分享頁 HTML 提取，本工程未實現 |

## 9. 來源與許可

- 簽名與 `AesV3` 協議變換移植自 `woshishiq1/drpys`（commit `22261adfa31435e3b3ef5a730b8a2a75bcaf1715`，GPL-3.0）。
- 內容協議與加密處理移植自 `drpys`，`MediaCrypto` 在此基礎上做了 Kotlin 適配。
- 本工程以 GPL-3.0 釋出，見 `NOTICE.md`。
