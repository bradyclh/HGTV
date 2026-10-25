// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.web

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class UserLibraryTest {
    private val dir = Files.createTempDirectory("hgtv-users").toFile()
    private val library = UserLibrary(dir)
    private val alice = "alice@example.com"
    private val bob = "bob@example.com"

    private fun series(id: String) = JSONObject().put("id", id).put("title", "劇 $id").put("cover", "https://img/$id.jpg").put("badge", "全10集")
    private fun progress(id: String, episode: Int, at: Long, watched: List<Int> = emptyList()) =
        JSONObject().put("series", series(id)).put("episode", episode).put("time", 12.5).put("duration", 60.0).put("total", 10).put("watched", JSONArray(watched)).put("at", at)

    @Test fun usersAreSeparate() {
        library.putFavorite(alice, series("1"))
        assertEquals(1, library.get(alice).getJSONArray("favorites").length())
        assertEquals(0, library.get(bob).getJSONArray("favorites").length())
    }

    @Test fun newerProgressWinsAndWatchedIsMerged() {
        library.putProgress(alice, "1", progress("1", 5, at = 200, watched = listOf(1, 2)))
        library.putProgress(alice, "1", progress("1", 3, at = 100, watched = listOf(0)))  // 另一台裝置較舊的進度
        val entry = library.get(alice).getJSONObject("progress").getJSONObject("1")
        assertEquals(5, entry.getInt("episode"))
        assertEquals(setOf(0, 1, 2), (0 until entry.getJSONArray("watched").length()).map { entry.getJSONArray("watched").getInt(it) }.toSet())
    }

    @Test fun favoritesMoveToFrontWithoutDuplicates() {
        library.putFavorite(alice, series("1"))
        library.putFavorite(alice, series("2"))
        library.putFavorite(alice, series("1"))
        val ids = library.get(alice).getJSONArray("favorites").let { list -> (0 until list.length()).map { list.getJSONObject(it).getString("id") } }
        assertEquals(listOf("1", "2"), ids)
        library.deleteFavorite(alice, "1")
        assertEquals(1, library.get(alice).getJSONArray("favorites").length())
    }

    @Test fun importMergesLocalRecords() {
        library.putFavorite(alice, series("1"))
        library.putProgress(alice, "1", progress("1", 2, at = 500))
        library.putSettings(alice, JSONObject().put("quality", 720))
        val local = JSONObject()
            .put("favorites", JSONArray(listOf(series("2"), series("1"))))
            .put("progress", JSONObject().put("1", progress("1", 1, at = 100)).put("3", progress("3", 4, at = 300)))
            .put("settings", JSONObject().put("quality", 480))
        val merged = library.import(alice, local)
        val ids = merged.getJSONArray("favorites").let { list -> (0 until list.length()).map { list.getJSONObject(it).getString("id") } }
        assertEquals(listOf("1", "2"), ids)
        assertEquals(2, merged.getJSONObject("progress").getJSONObject("1").getInt("episode"))
        assertEquals(4, merged.getJSONObject("progress").getJSONObject("3").getInt("episode"))
        assertEquals(720, merged.getJSONObject("settings").getInt("quality"))  // 雲端已有設定，不被本機蓋掉
    }

    @Test fun invalidInputIsIgnored() {
        library.putFavorite(alice, JSONObject().put("id", "../etc").put("title", "x"))
        library.putProgress(alice, "1", JSONObject().put("series", series("2")).put("at", 1))  // ID 不一致
        library.putSettings(alice, JSONObject().put("quality", 9999).put("danmaku", "evil").put("rate", 1.5))
        val doc = library.get(alice)
        assertEquals(0, doc.getJSONArray("favorites").length())
        assertFalse(doc.getJSONObject("progress").has("1"))
        assertEquals("{\"rate\":1.5}", doc.getJSONObject("settings").toString())
    }

    @Test fun persistsAcrossInstances() {
        library.putFavorite(alice, series("7"))
        val reopened = UserLibrary(dir)
        assertEquals("7", reopened.get(alice).getJSONArray("favorites").getJSONObject(0).getString("id"))
        assertTrue(dir.listFiles()!!.none { it.name.contains("alice") })  // 檔名是雜湊，不含 email
    }
}
