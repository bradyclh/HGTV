// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** 播放模型的路径冷却与匿名身份轮换（移植自 N3urda/hongguoTV）。 */
class PlaybackIdentityRotationTest {
    private val tab = "/reading/bookapi/video_tab/multi_video_model/v1/"
    private val registerPath = "/service/2/device_register/"
    private var now = 1_000_000L

    private fun repository(respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
        }.build(), { now }
    ).apply {
        pause = {}
        registerProfile = mapOf("cdid" to "old-cdid", "openudid" to "0000000000000001")
        deviceIdentity = mapOf("device_id" to "1111111111111111", "iid" to "2222222222222222")
    }

    private fun model(vid: String) = JSONObject().put("code", 0).put("data", JSONObject().put("video_model_data",
        JSONObject().put(vid, JSONObject().put("video_model", JSONObject().put("video_list", JSONArray().put(
            JSONObject().put("main_url", "https://cdn.example.com/$vid").put("video_meta", JSONObject().put("definition", "720p").put("codec_type", "bytevc1"))
        )).toString())))).toString()

    private fun registration(n: Int) = """{"device_id_str":"700000000000000$n","install_id_str":"800000000000000$n"}"""

    /** 回空的路径冷却期间不再试：第二次取地址直接从成功过的那条开始。 */
    @Test fun blockedRoutesAreSkippedUntilTheirCooldownEnds() {
        val hosts = mutableListOf<String>()
        val repo = repository { request ->
            hosts += request.url.host
            if (request.url.host == "api5-normal-hl.fqnovel.com" && request.url.encodedPath == tab) model("123") else ""
        }
        repo.stream("123")
        // v5 player/v1（sinfonlinea）与 v5 video_tab（sinfonlineb）排在最前，回空后才轮到旧的 video_tab 线路。
        assertEquals(listOf("api5-normal-sinfonlinea.fqnovel.com", "api5-normal-sinfonlineb.fqnovel.com", "api5-normal-hl.fqnovel.com"), hosts.distinct())
        hosts.clear(); now += 1_000
        repo.stream("123")
        assertEquals(listOf("api5-normal-hl.fqnovel.com"), hosts)
    }

    /** 一轮全空 → 用新档案注册一台新设备，同一次调用里换上它再取，并交给回调落盘。 */
    @Test fun allEmptyRoundRotatesToAFreshlyRegisteredIdentity() {
        var registrations = 0
        val rotated = mutableListOf<Pair<DeviceRegister.Registered, Map<String, String>>>()
        val repo = repository { request ->
            when {
                request.url.encodedPath == registerPath -> registration(++registrations)
                request.url.encodedPath == tab && request.url.queryParameter("device_id") == "7000000000000001" -> model("123")
                else -> ""
            }
        }
        repo.onIdentityRotated = { registered, profile -> rotated += registered to profile }
        assertEquals("https://cdn.example.com/123", repo.stream("123").url)
        assertEquals(1, registrations)
        val (registered, profile) = rotated.single()
        assertEquals("7000000000000001", registered.deviceId)
        assertNotEquals("old-cdid", profile["cdid"])
        assertNotEquals("0000000000000001", profile["openudid"])
        assertEquals(mapOf("device_id" to "7000000000000001", "iid" to "8000000000000001"), repo.deviceIdentity)
        assertEquals(profile, repo.registerProfile)
    }

    /** 没有落盘回调（例如预取用的临时仓库）就不换身份，照常报错。 */
    @Test fun withoutPersistenceCallbackIdentityIsNeverRotated() {
        var registrations = 0
        val repo = repository { request ->
            if (request.url.encodedPath == registerPath) registration(++registrations) else ""
        }
        assertThrows(IOException::class.java) { repo.stream("123") }
        assertEquals(0, registrations)
        assertEquals("1111111111111111", repo.deviceIdentity["device_id"])
    }

    /**
     * 新身份也被挡时不连续狂注册：十分钟内不再换，过了冷却才再换一次。
     * 换设备会加速消耗匿名配额（N3urda/hongguoTV#118），所以冷却要按分钟算而不是按秒。
     */
    @Test fun rotationCoolsDownWhenTheNewIdentityIsAlsoBlocked() {
        var registrations = 0
        val repo = repository { request ->
            if (request.url.encodedPath == registerPath) registration(++registrations) else ""
        }
        repo.onIdentityRotated = { _, _ -> }
        assertThrows(IOException::class.java) { repo.stream("123") }
        assertEquals(1, registrations)
        now += 1_000
        assertThrows(IOException::class.java) { repo.stream("123") }
        assertEquals(1, registrations)
        now += 60_000
        assertThrows(IOException::class.java) { repo.stream("123") }
        assertEquals(1, registrations)
        now += 600_000
        assertThrows(IOException::class.java) { repo.stream("123") }
        assertEquals(2, registrations)
    }

    /** 注册失败时保留原身份，一分钟后允许再试（比换成功后的冷却短）。 */
    @Test fun failedRegistrationKeepsTheOldIdentityAndRetriesSooner() {
        var registrations = 0
        val repo = repository { request ->
            if (request.url.encodedPath == registerPath) { registrations++; """{"device_id":0,"install_id":0}""" } else ""
        }
        var called = false
        repo.onIdentityRotated = { _, _ -> called = true }
        assertThrows(IOException::class.java) { repo.stream("123") }
        assertEquals(1, registrations)
        assertFalse(called)
        assertEquals("1111111111111111", repo.deviceIdentity["device_id"])
        now += 6_000
        assertThrows(IOException::class.java) { repo.stream("123") }
        assertEquals(1, registrations)
        now += 60_000
        assertThrows(IOException::class.java) { repo.stream("123") }
        assertEquals(2, registrations)
    }
}
