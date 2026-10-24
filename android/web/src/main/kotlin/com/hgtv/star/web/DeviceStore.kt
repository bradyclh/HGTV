// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.web

import com.hgtv.star.core.ContentRepository
import org.json.JSONObject
import java.io.File

/**
 * 網頁版的裝置身分：與電視版 `Library.deviceProfile` / `deviceIdentity` 同一套規則，
 * 只是存在本機檔案（預設 `~/.hgtv-web/device.json`）而不是 SharedPreferences。
 *
 * 內容介面只認「平台註冊過的 `device_id` + `install_id`」，因此第一次啟動會匿名註冊一次並落盤，
 * 之後重開伺服器沿用同一台裝置，不重複註冊。
 */
class DeviceStore(private val file: File) {
    private val numberPattern=Regex("[0-9]{15,19}")
    private var json: JSONObject=runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    fun profile(): Map<String,String> {
        val stored=json.optJSONObject("profile")
        if(stored!=null && stored.optString("cdid").isNotBlank() && stored.optString("openudid").isNotBlank())
            return stored.keys().asSequence().associateWith { stored.optString(it) }.filterValues { it.isNotBlank() }
        val fresh=linkedMapOf(
            "cdid" to java.util.UUID.randomUUID().toString(),
            "openudid" to "%016x".format(kotlin.random.Random.nextLong()),
            "device_type" to "25053RT47C",
            "device_brand" to "Redmi",
            "os_version" to "16",
            "os_api" to "36",
            "rom_version" to "BP2A.250605.031.A3 release-keys"
        )
        json.put("profile",JSONObject(fresh as Map<*,*>)); save()
        return fresh
    }

    fun identity(): Map<String,String> {
        val deviceId=json.optString("device_id"); val installId=json.optString("iid")
        return if(deviceId.matches(numberPattern) && installId.matches(numberPattern)) mapOf("device_id" to deviceId,"iid" to installId) else emptyMap()
    }

    /** 把身分交給倉庫；還沒註冊過就註冊一次並落盤。回傳是否已有可用身分。 */
    @Synchronized fun apply(repository: ContentRepository): Boolean {
        repository.registerProfile=profile()
        repository.deviceIdentity=identity()
        if(identity().isNotEmpty()) return true
        val registered=runCatching { repository.registerDevice() }.getOrNull() ?: return false
        json.put("device_id",registered.deviceId).put("iid",registered.installId); save()
        repository.deviceIdentity=identity()
        return true
    }

    private fun save() {
        file.parentFile?.mkdirs()
        val temp=File(file.parentFile,file.name+".tmp")
        temp.writeText(json.toString(2)); temp.renameTo(file)
    }
}
