# 紅果 App 端請求籤名演算法

適用物件：`https://api5-normal-sinfonlinea.fqnovel.com`、`https://api5-normal-sinfonlineb.fqnovel.com`
這類位元組系 App 介面。官網 SSR 層（`hongguoduanju.com`）不需要簽名，不在此文範圍。

演算法來源與落點：

| 位置 | 說明 |
| --- | --- |
| `android/core/src/main/kotlin/com/hgtv/star/core/Signer.kt` | Android/TV 端 Kotlin 移植，**只發 branch 0** |
| `android/core/src/main/kotlin/com/hgtv/star/core/AesV3.kt` | medusa 用的分組變換 |
| `android/core/src/main/kotlin/com/hgtv/star/core/Bytes.kt` | SM3 / MD5 / 端序 / 位運算原語 |
| `android/core/src/main/kotlin/com/hgtv/star/core/VendorConstants.kt` | 裝置參數、UA、sbox、hashTable |
| `android/core/src/test/resources/signing.json` | 固定向量（開發期從上游 JS 參考實現逐組核對後固化） |

移植上游：`woshishiq1/drpys` commit `22261adfa31435e3b3ef5a730b8a2a75bcaf1715`（GPL-3.0）。

---

## 1. 總覽

簽名只需要三樣輸入：

1. `query`：URL `?` 之後的完整字串（**不含** base、不含 `?`），已 URL 編碼，參數順序即拼接順序；
2. `body`：POST 請求體位元組；GET 時為 `null`（等價於 16 位元組 0 的 MD5）；
3. `ts`：秒級時間戳（頭裡叫 `x-khronos`）。

```
设备参数 values ─┐
ts / _rticket   ─┴─→ query ──┬─→ branch(query, md5(body), ts) == 0 ? 放行 : _rticket+1 重试(≤128)
                             ├─→ x-gorgon = f(query, body, ts, rand16)
                             ├─→ x-helios = f(ts)
                             └─→ x-medusa = f(url, body, ts)   ← 内部再算一次 hashF13
```

五個簽名頭彼此獨立：`x-gorgon` 與 `x-medusa` 各自帶自己的隨機數，`x-medusa` 內部的 `hashF13`
與准入用的 `branch` 是同一個函式的兩種用法（前者用 20 位元組摘要，後者只看低 4 位）。

---

## 2. 公共原語

```text
sm3(x)                 SM3-256，返回 32 字节
md5(x)                 MD5，返回 16 字节（x-ss-stub 时转大写 hex）
le32/be32/le64         小端 / 大端定长整数
varint(n)              protobuf 变长整数（负数按补码 64 位无符号推进）
zigzag(n)              sint 编码：n<0 → -2n-1，否则 2n
proto([tag, value, type])  type ∈ string/bytes/message/sint/float
rol32/ror32/ror64      循环移位
getIv(seed, data)      逐字节混合（奇偶位置两套公式）
sumMd5(data)           12 轮自定校验和
```

### 2.1 getIv（核心混合函式）

```text
value = u32(seed)
for i in 0..len(data)-1:
    i 偶数: value = u32((value >>> 4) ^ value ^ (value << 6) ^ data[i])
    i 奇数: value = u32(~((value >>> 7) ^ value ^ (data[i] | (value << 12))))
return value
```

### 2.2 sumMd5（20 位元組摘要的後 4 位元組）

```text
check = 0x20220420
for i in 0..11:
    temp  = (i 偶) ? (check >>> 3) ^ check : (check >>> 5) ^ check
    check = (i 偶) ? data[i] ^ (check << 7) : data[i] | (check << 11)
    if i 奇: check = ~check
    check = u32(check ^ temp)
return u32((check | 4) ^ 0x1000000)
```

---

## 3. 准入閘門：branch 與 `_rticket` 抖動

```text
branch(query, bodyMd5, ts):
    iv  = getIv(getIv(getIv(0x20230928, sm3(query)), bodyMd5), le32(ts))
    low = iv & 15
    return low - ((low * 171) >> 9) * 3      // 等价于 low % 3
```

`branch ∈ {0,1,2}`：

- **只有 0 會被平臺接受**；
- 1 是服務端不認的變體（上游 JS 裡對播放介面是「跳過 1、其他也發」，Kotlin 端直接只找 0）；
- 2 有獨立的 `branch2F13` 實現（另一套 MD5 變體 + 64×64 順序表），本工程不傳送。

因為 `_rticket` 參與 `sm3(query)`，改它就能改 `branch`。做法：

```text
now = 毫秒时间戳; ts = now / 1000
for offset in 0..127:
    ticket = now + offset
    query  = encode(values + ts + _rticket)   // 顺序：设备参数 → ts → _rticket
    if branch(query, bodyMd5, ts) == 0: 采用该 ticket 并停止
```

試不出來就報錯（128 次內必中，期望 3 次）。

---

## 4. x-gorgon

```text
input = md5(query)[0:4] + (body ? md5(body)[0:4] : 00 00 00 00)
        + 00 00 00 00 + le32(67503104) + be32(ts)          // 共 20 字节
key   = [0x4a, 0x40, 0x16, (rand>>8)&255, 0x47, 0x6c, 0x01, rand&255]
out   = rc4_variant(input, key)                            // 见下
for i in 0..len(out)-1:
    v    = ((out[i] >> 4) | (out[i] << 4)) & 255           // 半字节交换
    next = out[i+1] 或 out[0]（最后一个字节）
    out[i] = (~(reverseBits8((next ^ v) & 255) ^ 20)) & 255
result = hex("8404" + [rand&255, (rand>>8)&255, 0x40, 0x01] + out)   // 40 位 hex
```

`rc4_variant` 與標準 RC4 的差別：PRGA 不做 `s[i] ↔ s[j]` 交換，只做 `s[i] = s[j]`，且索引從 1 開始。

```text
s = [0..255]; j = 0
for i in 0..255: j = (j + s[i] + key[i % 8]) & 255; s[i] = s[j]   // KSA 同标准
j = 0
for n in 0..len(input)-1:
    i = n + 1; j = (j + s[i]) & 255; y = s[j]; s[i] = y
    out[n] = input[n] ^ s[(y + y) & 255]
```

---

## 5. x-helios

```text
rand   = 32 位随机数
digest = md5(le32(rand) + "8662")                 // 16 字节
ascii  = hex(digest) 的 ASCII 字节（32 字节）
words  = [ascii.i64LE(0), .i64LE(8), .i64LE(16), .i64LE(24)]

table = [words[0]]; b0 = words[0]; b8 = words[1]
for i in 0..33:
    x = u64(ror64(b8, 8) + b0); x ^= i
    table.push(x ^ ror64(b0, 61))
    b0 = x ^ ror64(b0, 61); b8 = 下一个 words 元素

text    = "{ts}-1588093228-8662"
pad     = 16 - len(text) % 16，填充值 = 该长度本身
output  = le32(rand)
for 每个 16 字节块 (a, b) = (u64LE@0, u64LE@8):
    for i in 0..33:
        b = table[i] ^ u64(a + ror64(b, 8))
        a = b ^ ror64(a, 61)
    output += le64(a) + le64(b)
result  = base64(output)
```

---

## 6. x-medusa

最複雜的一支，順序是：`hashF13` → protobuf message → `xmxor` 混淆 → 外層包裹 → `AesV3` 加密 → base64。

### 6.1 hashF13（branch 0 分支，20 位元組）

```text
iv      = getIv(getIv(getIv(0x20230928, sm3(query)), bodyMd5), le32(ts))
ivV0    = (iv & 15) / 3                       // 0..4
ivV1    = [0xc4a78580,0xb3c0fd39,0xc58c5686,0xc9aa3ba7,0xf5a7adf2,0x963c2ed1][ivV0]
countV1 = (ivV1 + ts + 1) & 255
countV2 = u32(ivV1 + ts)
tt      = hashTable[0..63] 每个 ror32(值, countV1)
shift   = (countV2 + 2) & 7
pad     = [0xfa,0x45,0x61,0xd7] 每个 ((v | v<<8) >> shift) & 255   // 4 字节
data    = sm3(query) + bodyMd5 + le32(ts) + pad + 00 00 00 00 00 00 01 a0

di = data 按大端 u32 读入（长度必为 4 的倍数）
for i in 0..111:
    x = di[i+1]; y = di[i+14]
    di.push(u32(di[i] + di[i+9] + (rol32(x,14)^rol32(x,25)^(x>>>3))
                                 + (rol32(y,13)^rol32(y,15)^(y>>>10))))

init   = [0x7aba4fc8,0x67166507,0x6403fa00,0x340f512f,
          984304912,3005047866,2874125293,2152413264] 每个 ror32(值, countV2)
chosen = 每个 ivV0 一个 11 元组（见 Signer.kt 的 chosen 表）
d = init 的可变副本
for i in 0..chosen[0]-1:
    base = ivV1 + i
    n1 = ((d[c3] ^ d[c4]) & d[c1]) ^ d[c3]
    n2 = rol32(d[c1],26) ^ rol32(d[c1],21) ^ rol32(d[c1],7)
    n4 = u32(di[base & 127] + n1 + n2 + tt[base & 63] + d[c5])
    n5 = rol32(d[c2],30) ^ rol32(d[c2],19) ^ rol32(d[c2],10)
    n6 = (d[c2] & d[c6]) | ((d[c2] | d[c6]) & d[c7])
    old = d[c9]
    d 旋转：d.unshift(d.pop())                 // Kotlin: d.add(0, d.removeAt(7))
    d[c10] = u32(n5 + n6 + n4); d[c8] = u32(old + n4)

ret    = 8 × be32(d[i] + init[i])              // 32 字节
folded = ret[0:16] ^ ret[16:32]
return folded + le32(sumMd5(folded))           // 20 字节
```

### 6.2 金鑰派生 keyHash + 混淆 xmxor

```text
signKey = 8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5
hash    = sm3(signKey + le32(rand32) + signKey)          // 32 字节
d1      = (rand32 >> 16) & 255
d2      = u32(~(((d1 << 11) | (rand32 >>> 24)) ^ (d1 >> 5) ^ d1))
seed    = le32(d2)                                       // 4 字节
```

`xmxor(data, key)`：對每個位元組取 `key[(i*4) & 28]` / `key[(i*4)&28 + 1]` 做「半位元組交換 → 加 → 取反異或 → 迴圈移位 → 加 → 異或 → 取反」，
結果**倒序**寫入；隨後 4 步定點修正（首位元組、次位元組、第三位元組、逐位元組鏈式修正、末位元組、首位元組再修）。Kotlin 與 JS 實現逐句對應，見 `Signer.kt` 的 `xmxor`。

### 6.3 message（protobuf）

```text
nested = {1:111, 2:10, 3:694367, 5:586952199}        // 全部 sint
env    = {1: 100+rand(21), 2:146331399, 3:146331396, 5:7,
          6:"v04.06.04.03-bugfix", 7: 10001+rand(2000),
          12: deviceProto(), 13: {1: now/1000, 2:-2, 4:200}, 14: version_name}
message = {
  1:  f7e85ffad7d7dc3bd62ac87057cf6118   (bytes)
  2:  3        3:  rand32          4:  "8662"
  5:  device_id                6:  "1588093228"
  7:  version_name             8:  "v04.06.04-ml-android"
  9:  67503104                 10: 4001000000000000 (bytes)
  12: ts                       13: queryBodyTs = hashF13(sm3(query), bodyMd5, le32(ts), ts) (bytes)
  14: sm3(query)[0:6] (bytes)  15: nested (message)
  16: "AXYQOS6n2m60x1fVZHIrH3iol"
  17: ts                       19: sm3(query + bodyMd5 + "none") (bytes)
  20: "none"                   21: 312
  23: env (message)            24: "{\"cmr\":16777216,\"cmr2\":16777216,\"un_h\":1879194040,\"vpn\":0,\"kd\":0,\"fkd\":3672518972,\"pd\":-1872573247,\"dyn\":\"\",\"do\":0,\"tk\":true}"
}
```

`deviceProto` 是 38 個欄位的固定裝置指紋（欄位表見 `Signer.deviceProto`），其中 `device_id` / `version_name`
取自 `VendorConstants.device`。

### 6.4 外層包裹

```text
transformed = reverse(hex("4001000000000000") + xmxor(message, hash))
for i: transformed[i] ^= seed[(~i) & 3]

check = ((sm3(query)[0] & 63) << 14) | 0x18000001 | ((queryBodyTs[0] & 63) << 8)
packed = 0x35 + le32(rand32b) + le32(check) + transformed + [rand32>>16, rand32>>24]
encrypted = AesV3(key=f1593376766ea98d34f31b057a9d5be4, ts).encrypt(packed, iv=1fe109a4125283f418de9e051a969e12)

version = 03000000f7e85ffad7d7dc3bd62ac87057cf6118          // 20 字节
prefix  = 每 4 字节：le32(version.u32LE(i) ^ ts)              // 共 20 字节
result  = base64(prefix + [rand32&255, (rand32>>8)&255, 0x00, 0x01] + encrypted)
```

`packed` 必須 ≥ 248 位元組（`AesV3.encrypt` 的硬性前置條件）。

### 6.5 AesV3（紅果自有分組變換，非標準 AES）

| 環節 | 規則 |
| --- | --- |
| sbox | `VendorConstants.sbox` 1024 位元組，按 `ts & 3` 取第 `word` 個 256 位元組塊 |
| 常量 | `con = [[1,0,2,3],[2,0,3,1],[0,1,3,2],[1,0,2,3]][ts&3]`（JS 裡叫 `con2`，`con` 是死程式碼） |
| 初始向量 | `init = [0xca025ddc,0x823dc546,0xc9420583,0xc298225f][ts&3]` |
| 金鑰擴充套件 | `xor(le32(init)×4, key)` 拼 32 位元組 0 填充 → 8 輪步進到 12 組 4 位元組輪金鑰 |
| 加密輪 | 2 輪：addCon → sub(帶行置換) → shift(order 表) → 第 1 輪加 shiftCon + MixColumns(0x1b) → addCon；收尾 add |
| 明文壓縮 | 輸入 ≥248 位元組，每 8 位元組抽 1 位拼成 32 位元組「明文」，第 32 位元組固定為 1 |
| 金鑰匯出 | 32 位元組明文分 2 塊，CBC（iv 給定）加密 → 兩塊共 32 位元組作為「金鑰」 |
| 回寫 | 把金鑰的 8 位逐位寫進原位元組的固定位元位（`&0xdf|…` 等 8 條掩碼），密文前置 1 位元組金鑰末位 |

---

## 7. 其餘請求標頭

| 頭 | 值 |
| --- | --- |
| `x-khronos` | `ts` |
| `x-ss-req-ticket` | 抖動試出來的 `_rticket` |
| `x-ladon` | `base64(be32(ts))` |
| `x-argus` | `base64(le32(ts))` |
| `x-tt-request-tag` | `t=0;n=0` |
| `sdk-version` | `2` |
| `passport-sdk-version` | `50561` |
| `x-xs-from-web` | `0` |
| `x-tt-dt` | 空 |
| `Accept` | `application/json; charset=utf-8,application/x-protobuf` |
| `User-Agent` | 有 body → `VIDEO_UA`（播放器 71332）；無 body → `APP_UA`（App 72232） |

POST 額外：

| 頭 | 值 |
| --- | --- |
| `Content-Type` | `application/json; charset=UTF-8` |
| `x-vc-bdturing-sdk-version` | `3.7.2.cn` |
| `x-ss-stub` | `MD5(body)` 的**大寫** hex |

彈幕介面另外覆蓋 `x-ss-stub=""` 並追加 `comment-source` / `server-channel`。

query 編碼：`encodeURIComponent` 風格，空格用 `%20`，`~` 不轉義（Kotlin 端 `Signer.encode`）。

---

## 8. 常量清單

| 名稱 | 值 |
| --- | --- |
| IV 種子 | `0x20230928` |
| sumMd5 種子 | `0x20220420` |
| gorgon SDK 版本 | `67503104` |
| helios 鹽 | `8662`、`1588093228` |
| medusa signKey | `8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5` |
| medusa AesV3 key | `f1593376766ea98d34f31b057a9d5be4` |
| medusa AesV3 iv | `1fe109a4125283f418de9e051a969e12` |
| medusa version 字首 | `03000000f7e85ffad7d7dc3bd62ac87057cf6118` |
| medusa message 常量 | `f7e85ffad7d7dc3bd62ac87057cf6118`、`4001000000000000` |
| hashF13 尾部 | `00000000000001a0` |
| branch2 順序表 | `BRANCH2_ORDERS` 64×64（本工程未使用） |

---

## 9. 實現落點與差異

1. **Kotlin 只發 branch 0**。`Signer.sign` 用 `_rticket` 從 `now` 起 +0..+127 抖動找 branch 0；
   上游 JS 的播放介面是「跳過 branch 1、其餘都發」，這裡更嚴格。
2. **版本參數兩套**。搜尋/彈幕用 App 版本（`version_code=72232`、`version_name=7.2.2.32`），
   播放地址解析用播放器版本（`71332` / `7.1.3.32`）；混用會被平臺按舊客戶端處理。
3. **隨機數注入**。Kotlin `Signer(clock, random)` 兩個參數都可注入，測試裡用固定 LCG 復現。
4. **編碼細節**。JS `pythonUrlEncode` 額外轉義 `!'()`，Kotlin `Signer.encode` 不轉義；
   現有參數集合不含這些字元，兩者等價。
5. **JS `AesV3.con` 是死程式碼**，生效的是 `con2`；Kotlin 裡只有一份 `con`，語義等同 `con2`。

---

## 10. 驗證

```bash
# Kotlin 侧逐组比对 branch / hashF13 / gorgon / helios / medusa（12 组固定向量）
./android/gradlew -p android :core:test --tests 'com.hgtv.star.core.SignerTest'
```

固定向量 `android/core/src/test/resources/signing.json` 已隨原始碼入庫，構建與測試不需要任何外部參考實現。

向量覆蓋：有 body / 無 body、12 個連續時間戳，`hashF13` 輸出 hex、`gorgon` 40 位 hex、
`helios` 與 `medusa` 為 base64。任何一位常量改動都會讓 12 組全部失敗。

> 平臺隨時可能改欄位或換籤名材料；本文描述的是當前實現所依賴的行為。

---

## 11. 聯網實測結論（2026-10-09）

實測方式：`android/core/src/test/kotlin/com/hgtv/star/core/LiveSigningProbeTest.kt`（Kotlin 簽名器直接發真請求）
＋ 同一批對照實驗逐頭篡改後重發，觀察平臺判定。

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) HONGGUO_LIVE=1 \
  ./android/gradlew -p android --offline :core:test --tests 'com.hgtv.star.core.LiveSigningProbeTest' --info
```

不設 `HONGGUO_LIVE=1` 時該測試自動跳過，離線構建不受影響。

### 11.1 結果

| 端點 | 方法 | 正確簽名 | 結論 |
| --- | --- | --- | --- |
| `POST /novel/player/multi_video_model/v1/` | POST + body | HTTP 200、`code=0`、24 KB video_model | 透過 |
| `POST /novel/commentapi/comment/list/{vid}/v1/` | POST + body | HTTP 200、`code=0`、637 B 彈幕 | 透過 |
| `POST /reading/bookapi/search/tab/v` | POST | HTTP 200 + **空 body** | 無法判定：匿名裝置一律空 body（與簽名無關） |

### 11.2 平臺實際校驗哪些頭

以 `multi_video_model` 為靶，逐個篡改後重發（同一 vid、同一時間視窗）：

| 改動 | 平臺回應 | 判定 |
| --- | --- | --- |
| 基線（branch=0，全套簽名頭） | `code=0` | 透過 |
| `branch=1` | `code=110001` 未知異常 | **branch 是硬閘門** |
| `branch=2` | `code=0` | 透過（該端點接受 2，不只接受 0） |
| `x-medusa` 改 1 位 / 刪除 | `code=110001` | **強校驗** |
| `x-helios` 改 1 位 / 刪除 | `code=110001` | **強校驗** |
| body 改了但簽名不變 | `code=110001` | body 經 medusa 繫結，**強校驗** |
| 去掉全部簽名頭 | `code=110001` | 必須帶簽名 |
| `x-gorgon` 全隨機 40 hex / 刪除 | `code=0` | 該端點**不校驗** gorgon |
| `x-ladon` / `x-argus` 刪除或端序寫反 | `code=0` | 不校驗 |
| `x-ss-stub` 改錯 | `code=0` | 不校驗 |
| `x-ss-req-ticket` 與 query 裡的 `_rticket` 不一致 | `code=0` | 不校驗 |
| `x-khronos` 刪除或與 query `ts` 差 1 小時 | `code=0` | 不校驗（`ts` 以 query 為準） |
| UA 版本號改錯 | `code=0` | 不校驗 |

結論：**實際被校驗的只有 `x-medusa`（含 body 繫結）與 `x-helios`，加上 branch ≠ 1 這一條**；
`x-gorgon` / `x-ladon` / `x-argus` / `x-ss-stub` / `x-ss-req-ticket` / `x-khronos` / UA 當前是「發了不虧、不發也行」。
它們仍然值得保留：平臺可能隨時開始校驗，且部分頭在別的端點（如 CDN、風控）另有用途。

對 Kotlin 端的意義：`Signer` 只發 branch 0 的策略**安全且更嚴格**（0 與 2 都被接受，1 必被拒）；
`x-medusa` / `x-helios` 一旦算錯就一定失敗，這也是固定向量測試必須逐位對齊的原因。
