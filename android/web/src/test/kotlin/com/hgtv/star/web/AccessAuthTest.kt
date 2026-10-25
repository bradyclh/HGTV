// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.web

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class AccessAuthTest {
    private val now = 1_800_000_000_000L
    private val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val other: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val aud = "aud-tag-123"
    private val iss = "https://myteam.cloudflareaccess.com"

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun unsigned(n: java.math.BigInteger) = n.toByteArray().let { if (it[0].toInt() == 0) it.copyOfRange(1, it.size) else it }

    private fun jwks(vararg pairs: Pair<String, KeyPair>) = JSONObject().put("keys", JSONArray(pairs.map { (kid, pair) ->
        val key = pair.public as RSAPublicKey
        JSONObject().put("kid", kid).put("kty", "RSA").put("alg", "RS256").put("n", b64(unsigned(key.modulus))).put("e", b64(unsigned(key.publicExponent)))
    })).toString()

    private fun token(
        claims: JSONObject = JSONObject().put("aud", JSONArray(listOf(aud))).put("iss", iss).put("email", "User@Example.com").put("exp", now / 1000 + 600),
        kid: String = "k1", signer: KeyPair = keys, alg: String = "RS256"
    ): String {
        val head = b64(JSONObject().put("alg", alg).put("kid", kid).toString().toByteArray())
        val body = b64(claims.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(signer.private); update("$head.$body".toByteArray()) }.sign()
        return "$head.$body.${b64(signature)}"
    }

    private fun auth(fetch: () -> String = { jwks("k1" to keys) }) = AccessAuth("myteam", aud, { now }, fetch)

    @Test fun validTokenGivesLowercaseEmail() = assertEquals("user@example.com", auth().verify(token()))

    @Test fun teamMayBeGivenAsFullHost() =
        assertEquals("user@example.com", AccessAuth("myteam.cloudflareaccess.com", aud, { now }, { jwks("k1" to keys) }).verify(token()))

    @Test fun rejectsSignatureFromAnotherKey() = assertNull(auth().verify(token(signer = other)))

    @Test fun rejectsWrongAudience() = assertNull(auth().verify(token(JSONObject().put("aud", "someone-else").put("iss", iss).put("email", "a@b.c").put("exp", now / 1000 + 600))))

    @Test fun rejectsWrongIssuer() = assertNull(auth().verify(token(JSONObject().put("aud", aud).put("iss", "https://evil.cloudflareaccess.com").put("email", "a@b.c").put("exp", now / 1000 + 600))))

    @Test fun rejectsExpired() = assertNull(auth().verify(token(JSONObject().put("aud", aud).put("iss", iss).put("email", "a@b.c").put("exp", now / 1000 - 1))))

    @Test fun rejectsAlgNone() = assertNull(auth().verify(token(alg = "none")))

    @Test fun rejectsGarbage() {
        assertNull(auth().verify(null))
        assertNull(auth().verify(""))
        assertNull(auth().verify("a.b"))
        assertNull(auth().verify("not.a.jwt"))
    }

    @Test fun refetchesKeysWhenCloudflareRotates() {
        var current = jwks("k1" to keys)
        val access = auth { current }
        assertEquals("user@example.com", access.verify(token()))
        current = jwks("k2" to other)
        // 新 kid 第一次出現時會重抓（距上次抓取未滿一分鐘就不抓，這裡 clock 固定，所以先驗證不會無限重抓）
        assertNull(access.verify(token(kid = "k2", signer = other)))
        val fresh = auth { current }
        assertEquals("user@example.com", fresh.verify(token(kid = "k2", signer = other)))
    }
}
