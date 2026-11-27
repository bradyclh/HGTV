// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * 分集目录的取得与校验。
 *
 * 2026-11-27 起官网详情页的 `vid_list` 只给 3 个混淆过的预览 ID（`v1_xxx`），
 * 完整、有序的分集改从 App 目录接口 `GET /reading/bookapi/directory/all_items/v/` 取。
 * 移植自 N3urda/hongguoTV 615b0c5b（GPL-3.0）。
 *
 * 保留上游顺序：排序或丢弃坏 ID 都会让已存的观看进度对错集。
 */
internal object EpisodeDirectory {
    private val numericId = Regex("[0-9]{1,30}")

    fun expectedCount(detail: JSONObject): Int = maxOf(
        0,
        detail.optInt("episode_cnt", 0),
        detail.optJSONObject("series_episode_info")?.optInt("episode_cnt", 0) ?: 0,
    )

    /** 官网目录仍完整可用时直接用；否则回 null，交给 App 目录接口。 */
    fun fromWeb(detail: JSONObject): List<String>? {
        val raw = detail.optJSONArray("vid_list") ?: return null
        val ids = (0 until raw.length()).map { raw.optString(it) }
        return ids.takeIf { valid(it, expectedCount(detail)) }
    }

    fun parse(body: String, expectedCount: Int): List<String> {
        if (body.isBlank()) throw IOException("完整分集接口暂无响应")
        val root = try { JSONObject(body) } catch (_: org.json.JSONException) {
            throw IOException("完整分集响应格式异常")
        }
        val success = when {
            root.has("code") -> root.optInt("code", -1) == 0
            root.has("Code") -> root.optInt("Code", -1) == 0
            else -> root.optString("message").equals("SUCCESS", ignoreCase = true)
        }
        if (!success) throw IOException("完整分集接口暂不可用：${root.optString("message").take(60)}")
        val rows = root.optJSONObject("data")?.optJSONArray("item_data_list") ?: JSONArray()
        val ids = (0 until rows.length()).map { rows.optJSONObject(it)?.str("item_id").orEmpty() }
        if (!valid(ids, expectedCount)) throw IOException("完整分集数据缺失或异常")
        return ids
    }

    /** `video_detail/v1` 的分集：`data.video_data.video_list[]`，按 `vid_index` 排序。 */
    fun parseVideoDetail(body: String, expectedCount: Int): List<String> {
        if (body.isBlank()) throw IOException("剧集详情暂无响应")
        val root = try { JSONObject(body) } catch (_: org.json.JSONException) {
            throw IOException("剧集详情响应格式异常")
        }
        if (root.has("code") && root.optInt("code", -1) != 0) throw IOException("剧集详情暂不可用：${root.optString("message").take(60)}")
        val rows = root.optJSONObject("data")?.optJSONObject("video_data")?.optJSONArray("video_list") ?: JSONArray()
        val ids = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .sortedBy { it.optInt("vid_index", 0) }.map { it.str("vid") }
        if (!valid(ids, expectedCount)) throw IOException("剧集详情分集缺失或异常（${ids.size}/$expectedCount）")
        return ids
    }

    private fun valid(ids: List<String>, expectedCount: Int): Boolean =
        ids.isNotEmpty() && ids.size >= expectedCount &&
            ids.all { it.matches(numericId) } && ids.distinct().size == ids.size
}
