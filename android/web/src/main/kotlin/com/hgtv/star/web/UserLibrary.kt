// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.web

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 每位登入者（Cloudflare Access 的 email）自己的收藏、觀看進度與播放設定。
 *
 * 一人一個 JSON 檔（`{dataDir}/users/{sha256(email)}.json`），格式與網頁前端的本機紀錄相同：
 *
 * ```
 * { "email": "...", "favorites": [{id,title,cover,badge}], "progress": {"<劇集 ID>": {series,episode,time,duration,total,watched,at}},
 *   "settings": {quality,rate,danmaku,autoNext,volume}, "settingsAt": 0 }
 * ```
 *
 * 寫入都是「單筆操作」而不是整份覆蓋：兩台裝置同時在看時，各自的進度不會互相蓋掉；
 * 同一部劇的進度以 `at`（更新時間）較新的為準。
 */
class UserLibrary(private val dir: File) {
    private val cache = HashMap<String, JSONObject>()
    private val idPattern = Regex("[0-9]{1,30}")

    fun get(email: String): JSONObject = synchronized(this) { JSONObject(load(email).toString()) }

    fun putProgress(email: String, id: String, entry: JSONObject) = update(email) { doc ->
        val clean = cleanProgress(id, entry) ?: return@update
        mergeProgress(doc.getJSONObject("progress"), id, clean)
    }

    fun deleteProgress(email: String, id: String) = update(email) { it.getJSONObject("progress").remove(id) }

    fun clearProgress(email: String) = update(email) { it.put("progress", JSONObject()) }

    fun putFavorite(email: String, series: JSONObject) = update(email) { doc ->
        val clean = cleanSeries(series) ?: return@update
        val rest = doc.getJSONArray("favorites").objects().filter { it.optString("id") != clean.optString("id") }
        doc.put("favorites", JSONArray(listOf(clean) + rest.take(MAX_FAVORITES - 1)))
    }

    fun deleteFavorite(email: String, id: String) = update(email) { doc ->
        doc.put("favorites", JSONArray(doc.getJSONArray("favorites").objects().filter { it.optString("id") != id }))
    }

    fun putSettings(email: String, settings: JSONObject) = update(email) { doc ->
        doc.put("settings", cleanSettings(settings)).put("settingsAt", System.currentTimeMillis())
    }

    /**
     * 第一次登入時把瀏覽器裡原有的本機紀錄併進來：進度逐部取較新的、收藏取聯集（雲端的排前面），
     * 設定只在雲端還沒有時才採用本機的。
     */
    fun import(email: String, local: JSONObject) = update(email) { doc ->
        local.optJSONObject("progress")?.let { incoming ->
            val progress = doc.getJSONObject("progress")
            for (id in incoming.keySet()) incoming.optJSONObject(id)?.let { cleanProgress(id, it) }?.let { mergeProgress(progress, id, it) }
        }
        local.optJSONArray("favorites")?.let { incoming ->
            val current = doc.getJSONArray("favorites").objects()
            val seen = current.map { it.optString("id") }.toMutableSet()
            val added = incoming.objects().mapNotNull(::cleanSeries).filter { seen.add(it.optString("id")) }
            doc.put("favorites", JSONArray((current + added).take(MAX_FAVORITES)))
        }
        if (doc.optLong("settingsAt") == 0L) local.optJSONObject("settings")?.let { doc.put("settings", cleanSettings(it)).put("settingsAt", System.currentTimeMillis()) }
    }

    private fun update(email: String, change: (JSONObject) -> Unit): JSONObject = synchronized(this) {
        val doc = load(email)
        change(doc)
        save(email, doc)
        JSONObject(doc.toString())
    }

    private fun file(email: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(email.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.json")
    }

    private fun load(email: String): JSONObject = cache.getOrPut(email) {
        val doc = runCatching { JSONObject(file(email).readText()) }.getOrDefault(JSONObject())
        if (doc.optJSONArray("favorites") == null) doc.put("favorites", JSONArray())
        if (doc.optJSONObject("progress") == null) doc.put("progress", JSONObject())
        if (doc.optJSONObject("settings") == null) doc.put("settings", JSONObject())
        doc.put("email", email)
    }

    private fun save(email: String, doc: JSONObject) {
        dir.mkdirs()
        val target = file(email)
        val temp = File(dir, target.name + ".tmp")
        temp.writeText(doc.toString())
        if (!temp.renameTo(target)) { target.delete(); temp.renameTo(target) }
    }

    private fun mergeProgress(progress: JSONObject, id: String, entry: JSONObject) {
        val old = progress.optJSONObject(id)
        val watched = linkedSetOf<Int>()
        old?.optJSONArray("watched")?.let { list -> (0 until list.length()).forEach { watched += list.optInt(it) } }
        entry.optJSONArray("watched")?.let { list -> (0 until list.length()).forEach { watched += list.optInt(it) } }
        val newer = if (old == null || entry.optLong("at") >= old.optLong("at")) entry else old
        newer.put("watched", JSONArray(watched.toList().takeLast(MAX_WATCHED)))
        progress.put(id, newer)
        // 紀錄太多時丟掉最久沒看的。
        if (progress.length() > MAX_PROGRESS) {
            progress.keySet().sortedBy { progress.optJSONObject(it)?.optLong("at") ?: 0 }.take(progress.length() - MAX_PROGRESS).forEach(progress::remove)
        }
    }

    private fun cleanSeries(series: JSONObject): JSONObject? {
        val id = series.optString("id")
        if (!id.matches(idPattern)) return null
        return JSONObject().put("id", id).put("title", series.optString("title").take(200))
            .put("cover", series.optString("cover").take(1000).takeIf { it.startsWith("https://") }.orEmpty())
            .put("badge", series.optString("badge").take(50))
    }

    private fun cleanProgress(id: String, entry: JSONObject): JSONObject? {
        if (!id.matches(idPattern)) return null
        val series = entry.optJSONObject("series")?.let(::cleanSeries)?.takeIf { it.optString("id") == id } ?: return null
        val watched = entry.optJSONArray("watched")?.let { list -> (0 until list.length()).map { list.optInt(it, -1) }.filter { it in 0..100_000 } }.orEmpty()
        return JSONObject().put("series", series)
            .put("episode", entry.optInt("episode").coerceIn(0, 100_000))
            .put("time", entry.optDouble("time", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 86_400.0) ?: 0.0)
            .put("duration", entry.optDouble("duration", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 86_400.0) ?: 0.0)
            .put("total", entry.optInt("total").coerceIn(0, 100_000))
            .put("watched", JSONArray(watched.takeLast(MAX_WATCHED)))
            .put("at", entry.optLong("at").coerceIn(0, Long.MAX_VALUE / 2))
    }

    private fun cleanSettings(settings: JSONObject): JSONObject {
        val clean = JSONObject()
        settings.optInt("quality", 0).takeIf { it in listOf(480, 720, 1080) }?.let { clean.put("quality", it) }
        settings.optDouble("rate", 0.0).takeIf { it in 0.25..4.0 }?.let { clean.put("rate", it) }
        settings.optString("danmaku").takeIf { it in listOf("full", "top", "off") }?.let { clean.put("danmaku", it) }
        if (settings.has("autoNext")) clean.put("autoNext", settings.optBoolean("autoNext", true))
        settings.optDouble("volume", -1.0).takeIf { it in 0.0..1.0 }?.let { clean.put("volume", it) }
        return clean
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    companion object {
        const val MAX_FAVORITES = 2000
        const val MAX_PROGRESS = 2000
        const val MAX_WATCHED = 2000
    }
}
