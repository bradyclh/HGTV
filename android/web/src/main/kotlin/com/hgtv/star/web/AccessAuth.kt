// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.web

import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * Cloudflare Access 的登入驗證。
 *
 * 經過 Cloudflare Tunnel 進來的請求，Access 驗證通過後會在 `Cf-Access-Jwt-Assertion` 標頭
 * （以及 `CF_Authorization` Cookie）附上一份 RS256 簽章的 JWT。這裡驗證：
 *
 * - 簽章：用團隊公開金鑰（`https://{team}.cloudflareaccess.com/cdn-cgi/access/certs`）；
 * - `aud` 含本應用的 AUD tag、`iss` 是本團隊、`exp` 未過期；
 *
 * 通過就回傳 `email`。**只信 JWT，不信 `Cf-Access-Authenticated-User-Email` 這類純文字標頭**：
 * 萬一 Tunnel 沒套上 Access，那些標頭任何人都能自己帶。
 *
 * [fetchKeys] 回傳 JWKS 的 JSON 文字；抽出來是為了讓單元測試能塞自己產生的金鑰。
 */
class AccessAuth(
    team: String,
    private val audience: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fetchKeys: () -> String = { defaultFetch(team) }
) {
    private val issuer = "https://${team.removeSuffix(".cloudflareaccess.com")}.cloudflareaccess.com"
    @Volatile private var keys: Map<String, PublicKey> = emptyMap()
    @Volatile private var fetchedAt = 0L

    /** 驗證一份 JWT，通過回傳 email（小寫），否則回傳 null。 */
    fun verify(token: String?): String? {
        if (token.isNullOrBlank()) return null
        val parts = token.trim().split('.')
        if (parts.size != 3) return null
        return runCatching {
            val header = JSONObject(String(decode(parts[0]), Charsets.UTF_8))
            if (header.optString("alg") != "RS256") return null
            val key = key(header.optString("kid")) ?: return null
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(key)
            verifier.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            if (!verifier.verify(decode(parts[2]))) return null
            val claims = JSONObject(String(decode(parts[1]), Charsets.UTF_8))
            val audiences = claims.optJSONArray("aud")?.let { list -> (0 until list.length()).map { list.optString(it) } }
                ?: listOf(claims.optString("aud"))
            val now = clock() / 1000
            if (audience !in audiences) return null
            if (claims.optString("iss") != issuer) return null
            if (claims.optLong("exp", 0) <= now) return null
            if (claims.has("nbf") && claims.optLong("nbf") > now + 60) return null
            claims.optString("email").trim().lowercase().takeIf { it.contains('@') }
        }.getOrNull()
    }

    /** 找不到 kid 時重新抓金鑰（Cloudflare 會輪替），但最多每分鐘抓一次。 */
    @Synchronized private fun key(kid: String): PublicKey? {
        keys[kid]?.let { return it }
        val now = clock()
        if (now - fetchedAt < 60_000 && keys.isNotEmpty()) return null
        fetchedAt = now
        keys = runCatching { parseKeys(fetchKeys()) }.getOrDefault(keys)
        return keys[kid]
    }

    companion object {
        private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

        fun parseKeys(json: String): Map<String, PublicKey> {
            val list = JSONObject(json).optJSONArray("keys") ?: return emptyMap()
            val factory = KeyFactory.getInstance("RSA")
            return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                .filter { it.optString("kty") == "RSA" && it.optString("kid").isNotEmpty() }
                .associate { jwk ->
                    jwk.optString("kid") to factory.generatePublic(
                        RSAPublicKeySpec(BigInteger(1, decode(jwk.getString("n"))), BigInteger(1, decode(jwk.getString("e"))))
                    )
                }
        }

        private fun defaultFetch(team: String): String {
            val host = "${team.removeSuffix(".cloudflareaccess.com")}.cloudflareaccess.com"
            val request = okhttp3.Request.Builder().url("https://$host/cdn-cgi/access/certs").build()
            okhttp3.OkHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("Access 金鑰取得失敗（HTTP ${response.code}）")
                return response.body?.string().orEmpty()
            }
        }
    }
}
