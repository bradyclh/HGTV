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
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * 2026-11-23 起平台按「设备可信度」过滤播放模型：画像不完整的请求一律 200 + 0 字节。
 * 修法对齐 OOIUa/hongguo-android c0821c6f：注册后 app_alert_check 激活、带 klink_egdi、
 * 走 sinfonlinea `multi_video_model/player/v1`（v5 签名、28 项设备参数、gzip 正文）。
 */
class DevicePlaybackProfileTest {
    private val playerPath = "/novel/player/multi_video_model/player/v1"
    private val tabPath = "/reading/bookapi/video_tab/multi_video_model/v1/"
    private val registerPath = "/service/2/device_register/"
    private val activatePath = "/service/2/app_alert_check/"

    private fun repository(respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
        }.build()
    ).apply {
        pause = {}
        registerProfile = mapOf("cdid" to "11111111-2222-3333-4444-555555555555", "openudid" to "0000000000000001")
        deviceIdentity = mapOf("device_id" to "1111111111111111", "iid" to "2222222222222222")
        deviceEgdi = "egdi-token"
    }

    private fun mobileModel(vid: String) = JSONObject().put("code", 0).put("data", JSONObject().put("1004", JSONObject().put(vid,
        JSONObject().put("video_model", JSONObject().put("video_list", JSONArray().put(
            JSONObject().put("main_url", "https://cdn.example.com/$vid").put("video_meta", JSONObject().put("definition", "720p").put("codec_type", "bytevc1"))
        )).toString())))).toString()

    @Test fun registrationResponseCarriesKlinkEgdiAtTopLevelOrUnderData() {
        assertEquals("abc", DeviceRegister.parse("""{"device_id_str":"7000000000000001","install_id_str":"8000000000000001","klink_egdi":"abc"}""")!!.egdi)
        assertEquals("xyz", DeviceRegister.parse("""{"device_id_str":"7000000000000001","install_id_str":"8000000000000001","data":{"klink_egdi":"xyz"}}""")!!.egdi)
        assertEquals("", DeviceRegister.parse("""{"device_id_str":"7000000000000001","install_id_str":"8000000000000001"}""")!!.egdi)
    }

    /** 官方客户端注册后必走一次 app_alert_check；未激活的匿名设备会被当成低质量设备。 */
    @Test fun registrationActivatesTheDeviceAndKeepsTheEgdi() {
        val requests = mutableListOf<Request>()
        val repo = repository { request ->
            requests += request
            if (request.url.encodedPath == registerPath)
                """{"device_id_str":"7000000000000001","install_id_str":"8000000000000001","klink_egdi":"fresh-egdi"}""" else ""
        }
        repo.deviceIdentity = emptyMap(); repo.deviceEgdi = ""
        val registered = repo.registerDevice()!!
        assertEquals("fresh-egdi", registered.egdi)
        assertEquals("fresh-egdi", repo.deviceEgdi)
        val activation = requests.single { it.url.encodedPath == activatePath }
        assertEquals("GET", activation.method)
        assertEquals("7000000000000001", activation.url.queryParameter("device_id"))
        assertEquals("8000000000000001", activation.url.queryParameter("iid"))
    }

    /** 激活失败（网络异常）不影响注册结果。 */
    @Test fun activationFailureDoesNotFailRegistration() {
        val repo = ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath == activatePath) throw java.io.IOException("boom")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"device_id_str":"7000000000000001","install_id_str":"8000000000000001"}""".toResponseBody()).build()
        }.build())
        assertNotNull(repo.registerDevice())
        assertEquals("7000000000000001", repo.deviceIdentity["device_id"])
    }

    /** 播放模型优先走 sinfonlinea player/v1：v5 签名、gzip 正文、28 项设备参数并带 klink_egdi，不带 Cookie。 */
    @Test fun playbackPrefersTheV5PlayerRouteWithTheFullDeviceProfile() {
        val requests = mutableListOf<Request>()
        val repo = repository { request ->
            requests += request
            if (request.url.encodedPath == playerPath) mobileModel("123") else ""
        }
        assertEquals("https://cdn.example.com/123", repo.stream("123").url)
        val sent = requests.first()
        assertEquals("api5-normal-sinfonlinea.fqnovel.com", sent.url.host)
        assertEquals(playerPath, sent.url.encodedPath)
        assertEquals("POST", sent.method)
        assertEquals("7.4.3.32", sent.url.queryParameter("version_name"))
        assertEquals("egdi-token", sent.url.queryParameter("klink_egdi"))
        assertEquals("1111111111111111", sent.url.queryParameter("device_id"))
        for (key in listOf("resolution", "dpi", "os_api", "rom_version", "host_abi", "dragon_device_type", "player_so_load"))
            assertNotNull(key, sent.url.queryParameter(key))
        assertEquals("gzip", sent.header("Content-Encoding"))
        assertNotNull(sent.header("x-medusa"))
        assertNull(sent.header("Cookie"))
        val body = okio.Buffer().also { sent.body!!.writeTo(it) }.readByteArray()
        val json = GZIPInputStream(ByteArrayInputStream(body)).readBytes().toString(Charsets.UTF_8)
        assertTrue(json, json.contains("\"mixed_video_id_map\":{\"1004\":[\"123\"]}"))
        assertEquals(1, requests.size)
    }

    /** player/v1 回空时换 v5 签名的 video_tab GET（同样带完整设备参数）。 */
    @Test fun emptyPlayerRouteFallsBackToTheV5VideoTab() {
        val requests = mutableListOf<Request>()
        val repo = repository { request ->
            requests += request
            if (request.url.encodedPath == tabPath && request.url.queryParameter("klink_egdi") == "egdi-token")
                JSONObject().put("code", 0).put("data", JSONObject().put("video_model_data", JSONObject().put("123",
                    JSONObject(mobileModel("123")).getJSONObject("data").getJSONObject("1004").getJSONObject("123")))).toString()
            else ""
        }
        assertEquals("https://cdn.example.com/123", repo.stream("123").url)
        assertEquals(listOf(playerPath, tabPath), requests.map { it.url.encodedPath }.distinct().take(2))
    }

    /** 旧版本存下的身份没有 egdi：首次取地址前补注册一次（带激活），之后不再补。 */
    @Test fun legacyIdentityWithoutEgdiIsReRegisteredOnce() {
        var registrations = 0
        val saved = mutableListOf<DeviceRegister.Registered>()
        val repo = repository { request ->
            when {
                request.url.encodedPath == registerPath ->
                    """{"device_id_str":"700000000000000${++registrations}","install_id_str":"8000000000000001","klink_egdi":"new-egdi"}"""
                request.url.encodedPath == playerPath && request.url.queryParameter("klink_egdi") == "new-egdi" -> mobileModel("123")
                else -> ""
            }
        }
        repo.deviceEgdi = ""
        repo.onIdentityRotated = { registered, _ -> saved += registered }
        assertEquals("https://cdn.example.com/123", repo.stream("123").url)
        assertEquals("https://cdn.example.com/123", repo.stream("123").url)
        assertEquals(1, registrations)
        assertEquals("new-egdi", saved.single().egdi)
    }
}
