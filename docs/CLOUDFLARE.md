# 用 Cloudflare Tunnel + Access 從外面觀看網頁版

網頁版伺服器照常在 Mac 上跑（只聽 `127.0.0.1`），由 Cloudflare 負責對外連線與登入：

```
瀏覽器 ──HTTPS──▶ Cloudflare（Access：Google 登入 + email 白名單）──Tunnel──▶ Mac 上的 cloudflared ──▶ 127.0.0.1:8686
```

- **不用開路由器埠**：`cloudflared` 從 Mac 主動連到 Cloudflare。
- **登入交給 Access**：只有白名單裡的 Gmail 能進。伺服器會再驗一次 Access 簽發的 JWT（簽章、AUD、到期時間），
  驗證不過一律 403；沒設定 Access 時，任何經過 Cloudflare 的請求都會被拒絕。
- **每個人各自的紀錄**：收藏、觀看進度、播放設定依登入的 email 存在 Mac 的 `~/.hgtv-web/users/`。
  換裝置登入同一個帳號就接著看；第一次登入時，該瀏覽器原本的本機紀錄會自動併上去。
- 在 Mac 上直接開 `http://127.0.0.1:8686/` 不需要登入，紀錄照舊存在瀏覽器裡。

> 內容來自非官方介面且有版權，請只開放給自己和家人，不要公開分享網址。

## 需要準備

- 一個已經接到 Cloudflare 的網域（DNS 由 Cloudflare 管理），以下以 `hgtv.example.com` 為例。
- Cloudflare Zero Trust（免費方案即可，50 人以內）。
- 一個 Google 帳號，用來在 Google Cloud 建 OAuth 用戶端。

## 1. 安裝並登入 cloudflared

```sh
brew install cloudflared
cloudflared tunnel login          # 會開瀏覽器，選擇你的網域授權
```

## 2. 建立 Tunnel 並指到網頁版

```sh
cloudflared tunnel create hgtv                       # 記下輸出的 Tunnel UUID
cloudflared tunnel route dns hgtv hgtv.example.com   # 建立 DNS 記錄
```

建立 `~/.cloudflared/config.yml`（`<UUID>` 換成上一步的值）：

```yaml
tunnel: <UUID>
credentials-file: /Users/<你的帳號>/.cloudflared/<UUID>.json
ingress:
  - hostname: hgtv.example.com
    service: http://127.0.0.1:8686
  - service: http_status:404
```

## 3. 在 Google Cloud 建 OAuth 用戶端

1. 到 [Google Cloud Console](https://console.cloud.google.com/) → **API 和服務 → 憑證 → 建立憑證 → OAuth 用戶端 ID**
   （第一次會要求先設定「OAuth 同意畫面」，類型選「外部」，測試使用者加上要登入的 Gmail）。
2. 應用程式類型選 **網頁應用程式**。
3. **已授權的 JavaScript 來源**：`https://<團隊名>.cloudflareaccess.com`
4. **已授權的重新導向 URI**：`https://<團隊名>.cloudflareaccess.com/cdn-cgi/access/callback`
5. 記下 **用戶端 ID** 與 **用戶端密鑰**。

`<團隊名>` 在 Zero Trust 後台 **Settings → Custom Pages → Team domain** 可以看到（第一次進 Zero Trust 時會請你取一個）。

## 4. 在 Cloudflare Access 設定 Google 登入與應用程式

1. Zero Trust 後台 → **Settings → Authentication → Login methods → Add new → Google**，
   貼上上一步的用戶端 ID 與密鑰，按 **Test** 確認能登入。
2. **Access → Applications → Add an application → Self-hosted**：
   - Application domain：`hgtv.example.com`
   - Identity providers：只勾 **Google**（可以順便開 *Instant Auth* 直接跳到 Google 登入）
   - Session duration：依需要，例如 1 個月
3. 新增 Policy：Action **Allow**，Include → **Emails**，填入允許登入的 Gmail（自己與家人）。
4. 儲存後，在該應用程式的 **Overview** 複製 **Application Audience (AUD) Tag**。

## 5. 啟動網頁版（帶上 Access 設定）

```sh
tools/web.sh --no-open --access-team <團隊名> --access-aud <AUD Tag>
```

啟動訊息應該出現「Cloudflare Access 已啟用」。另開一個終端機跑 Tunnel：

```sh
cloudflared tunnel run hgtv
```

用手機（關掉 Wi-Fi 走行動網路更準）打開 `https://hgtv.example.com`，應該先看到 Google 登入，
登入後右上角會顯示帳號與「登出」。

## 6. 讓它常駐（可選）

- Tunnel 開機自動啟動：`sudo cloudflared service install`（會讀 `~/.cloudflared/config.yml`）。
- Mac 不要睡眠：系統設定 → 電池／能源 → 接上電源時「防止自動進入睡眠」；或在跑網頁版時加 `caffeinate -s`：

  ```sh
  caffeinate -s tools/web.sh --no-open --access-team <團隊名> --access-aud <AUD Tag>
  ```

- 也可以把 `--access-team` / `--access-aud` 改用環境變數 `HGTV_ACCESS_TEAM` / `HGTV_ACCESS_AUD`。

## 常見問題

| 現象 | 原因 |
| --- | --- |
| 從外面打開顯示「這台伺服器沒有啟用 Cloudflare Access」 | 啟動網頁版時沒帶 `--access-team` / `--access-aud` |
| 登入後顯示「請先登入」 | AUD Tag 或團隊名填錯；或 Access 工作階段過期，重新整理再登入一次 |
| 外面打不開（502 / 1033） | Mac 上的網頁版或 `cloudflared` 沒在跑，或 Mac 睡著了 |
| 播放比在家裡慢 | 影片從 Mac 經家裡的上傳頻寬送出去，受限於家用網路上傳速度 |
