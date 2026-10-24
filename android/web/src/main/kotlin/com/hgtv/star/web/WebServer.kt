// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.web

import com.hgtv.star.core.CatalogPage
import com.hgtv.star.core.CategoryCatalog
import com.hgtv.star.core.ContentRepository
import com.hgtv.star.core.Danmaku
import com.hgtv.star.core.RankBoard
import com.hgtv.star.core.Series
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * 紅果TV 網頁版：在本機（Mac）跑一個小型 HTTP 伺服器，用瀏覽器觀看。
 *
 * 內容取得、簽章、播放地址與影片解密全部沿用 `core` 模組（與電視版同一套實作）；
 * 這裡只負責三件事：
 *
 * 1. `/api/...`：把 [ContentRepository] 的結果轉成 JSON；
 * 2. `/video/{vid}`：支援 HTTP Range 的**解密後** MP4，瀏覽器 `<video>` 直接播放；
 * 3. 其餘路徑：`resources/web/` 裡的單頁網頁。
 *
 * 用法：`./gradlew -p android :web:run --args="--open"`，參數見 [main]。
 */
fun main(args: Array<String>) {
    fun option(name: String) = args.indexOf(name).takeIf { it>=0 }?.let { args.getOrNull(it+1) }
    val port=option("--port")?.toIntOrNull() ?: 8686
    // 預設只聽本機；要讓區網其他裝置（iPad / 另一台電腦）也能看，傳 --host 0.0.0.0。
    val host=option("--host") ?: "127.0.0.1"
    val dataDir=File(option("--data-dir") ?: (System.getProperty("user.home")+"/.hgtv-web"))
    val repository=ContentRepository()
    repository.sessionToken=(option("--token") ?: System.getenv("HGTV_TOKEN"))?.takeIf { it.isNotBlank() }
    val device=DeviceStore(File(dataDir,"device.json"))
    println(if(device.apply(repository)) "裝置身分已就緒" else "裝置註冊失敗：搜尋與播放可能不可用，播放時會再試一次")
    val server=HttpServer.create(InetSocketAddress(host,port),64)
    server.executor=Executors.newFixedThreadPool(32)
    val api=Api(repository,device)
    server.createContext("/api/") { exchange -> api.handle(exchange) }
    server.createContext("/video/") { exchange -> api.video(exchange) }
    server.createContext("/") { exchange -> serveStatic(exchange) }
    server.start()
    val url="http://${if(host=="0.0.0.0") "127.0.0.1" else host}:$port/"
    println("紅果TV 網頁版已啟動：$url（Ctrl+C 結束）")
    if(args.contains("--open")) runCatching { ProcessBuilder("open",url).start() }
}

private class HttpError(val code: Int, message: String): Exception(message)

private class Api(private val repository: ContentRepository, private val device: DeviceStore) {
    private val videos=VideoSessions(repository)
    private val danmaku=DanmakuJobs(repository)
    private val idPattern=Regex("[0-9]{1,30}")

    fun handle(exchange: HttpExchange) = exchange.use {
        val query=parseQuery(exchange.requestURI.rawQuery)
        fun param(name: String) = query[name]?.trim().orEmpty()
        fun id(name: String) = param(name).takeIf { it.matches(idPattern) } ?: throw HttpError(400,"參數 $name 無效")
        fun page() = param("page").toIntOrNull()?.coerceIn(1,500) ?: 1
        val body: Any=try {
            when(exchange.requestURI.path.removePrefix("/api/").trimEnd('/')) {
                "home" -> JSONArray(repository.homeFeed().map { (board,page) ->
                    page.toJson().put("board",board.storedValue).put("title",board.homeTitle).put("source",board.source) })
                "boards" -> JSONArray(RankBoard.entries.map { JSONObject().put("key",it.storedValue).put("label",it.label).put("title",it.homeTitle) })
                "rank" -> repository.rank(page(),RankBoard.fromStored(param("board"))).toJson()
                "categories" -> JSONArray(CategoryCatalog.groups.map { group ->
                    JSONObject().put("label",group.label).put("path",group.path)
                        .put("tags",JSONArray(group.tags.map { JSONObject().put("label",it.label).put("path",it.path) })) })
                "category" -> {
                    val path=param("path")
                    if(CategoryCatalog.groupOf(path)==null) throw HttpError(400,"未知的分類")
                    repository.category(path,page()).toJson()
                }
                "search" -> {
                    val keyword=param("q").ifEmpty { throw HttpError(400,"請輸入關鍵字") }
                    repository.search(keyword,page()).toJson()
                }
                "suggest" -> JSONArray(repository.suggestions(param("q"),12).map { row ->
                    JSONObject().put("name",row.name).put("series",row.series?.webJson() ?: JSONObject.NULL) })
                "hot" -> JSONArray(repository.hotSearches(12))
                "detail" -> repository.detail(id("id")).let { detail ->
                    JSONObject().put("series",detail.series.webJson()).put("episodes",JSONArray(detail.episodes)) }
                "related" -> repository.related(id("id"),param("title")).let { works ->
                    JSONObject().put("items",works.items.json()).put("seasons",works.seasons.json()) }
                "danmaku" -> danmaku.snapshot(id("vid"),id("series"))
                // 自動連播的預先載入：下一集的地址、加密索引與開頭片段在背景準備好，彈幕也先開始抓。
                "prefetch" -> {
                    val vid=id("vid")
                    videos.prefetch(vid,param("q").toIntOrNull()?.coerceIn(360,2160) ?: 1080)
                    param("series").takeIf { it.matches(idPattern) }?.let { danmaku.snapshot(vid,it) }
                    JSONObject().put("ok",true)
                }
                else -> throw HttpError(404,"找不到介面")
            }
        } catch(problem: HttpError) {
            return@use sendJson(exchange,problem.code,JSONObject().put("error",problem.message))
        } catch(problem: IllegalArgumentException) {
            return@use sendJson(exchange,400,JSONObject().put("error",problem.message ?: "參數錯誤"))
        } catch(problem: Exception) {
            return@use sendJson(exchange,502,JSONObject().put("error",problem.message ?: "內容暫不可用，請稍後再試"))
        }
        sendJson(exchange,200,body)
    }

    /**
     * `GET /video/{vid}?q=1080`：解密後的 MP4，支援 `Range`。
     *
     * 每次回應最多 [MAX_RESPONSE] 位元組（瀏覽器會自己接著要下一段）；
     * 以 [CHUNK] 為單位邊取邊解密邊寫，用戶端斷線（拖動、換集）時寫入失敗即停止，不再往上游取資料。
     */
    fun video(exchange: HttpExchange) = exchange.use {
        val vid=exchange.requestURI.path.removePrefix("/video/").substringBefore('.')
        val quality=parseQuery(exchange.requestURI.rawQuery)["q"]?.toIntOrNull()?.coerceIn(360,2160) ?: 1080
        if(!vid.matches(idPattern)) return@use sendJson(exchange,400,JSONObject().put("error","集數無效"))
        val remote=try {
            videos.get(vid,quality)
        } catch(first: Exception) {
            // 第一次播放時裝置可能還沒註冊成功：補註冊一次再取地址。
            if(device.apply(repository)) runCatching { videos.get(vid,quality) }.getOrNull() else null
        } ?: return@use sendJson(exchange,502,JSONObject().put("error","取不到播放地址，請稍後再試"))
        val total=remote.total
        val range=parseRange(exchange.requestHeaders.getFirst("Range"),total)
        val headers=exchange.responseHeaders
        headers["Content-Type"]="video/mp4"; headers["Accept-Ranges"]="bytes"; headers["Cache-Control"]="no-store"
        if(range==null) {
            headers["Content-Range"]="bytes */$total"
            exchange.sendResponseHeaders(416,-1); return@use
        }
        val start=range.first; val end=minOf(range.last,start+MAX_RESPONSE-1)
        val partial=exchange.requestHeaders.containsKey("Range")
        if(partial) headers["Content-Range"]="bytes $start-$end/$total"
        if(exchange.requestMethod=="HEAD") { exchange.sendResponseHeaders(if(partial) 206 else 200,-1); return@use }
        // 先取第一塊再送標頭：上游失敗時還能回錯誤碼，而不是一個中途斷掉的 206。
        var position=start
        var block=try { remote.read(position,minOf(CHUNK.toLong(),end-position+1).toInt()) } catch(problem: IOException) {
            videos.drop(vid,quality)
            return@use sendJson(exchange,502,JSONObject().put("error",problem.message ?: "影片讀取失敗"))
        }
        exchange.sendResponseHeaders(if(partial) 206 else 200,end-start+1)
        val out=exchange.responseBody
        while(true) {
            try { out.write(block) } catch(_: IOException) { return@use }
            position+=block.size
            if(position>end || block.isEmpty()) break
            block=try { remote.read(position,minOf(CHUNK.toLong(),end-position+1).toInt()) } catch(_: IOException) {
                videos.drop(vid,quality); return@use
            }
        }
    }

    private fun parseRange(header: String?,total: Long): LongRange? {
        if(total<=0) return null
        if(header.isNullOrBlank()) return 0L until total
        val match=Regex("bytes=(\\d*)-(\\d*)").find(header) ?: return 0L until total
        val (a,b)=match.destructured
        val range=when {
            a.isEmpty() && b.isEmpty() -> return null
            a.isEmpty() -> maxOf(0,total-b.toLong()) until total
            else -> a.toLong()..minOf(total-1,b.toLongOrNull() ?: (total-1))
        }
        return range.takeIf { it.first in 0 until total && it.last>=it.first }
    }

    companion object {
        const val CHUNK=512*1024
        const val MAX_RESPONSE=16L*1024*1024
    }
}

/**
 * 彈幕在背景分窗口抓（整集要十幾秒，平台有頻控）；網頁每隔幾秒輪詢一次，邊抓邊顯示。
 * 每集只抓一次，保留最近 [LIMIT] 集的結果。
 */
private class DanmakuJobs(private val repository: ContentRepository) {
    private class Job(@Volatile var items: List<Danmaku> = emptyList(), @Volatile var done: Boolean = false)
    private val jobs=object: LinkedHashMap<String,Job>(16,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Job>) = size>LIMIT
    }
    private val pool=Executors.newFixedThreadPool(2) { task -> Thread(task,"hgtv-danmaku").apply { isDaemon=true } }

    fun snapshot(vid: String,seriesId: String): JSONObject {
        val job=synchronized(jobs) {
            jobs[vid] ?: Job().also { created ->
                jobs[vid]=created
                pool.submit {
                    try {
                        created.items=repository.danmaku(vid,seriesId,onWindow={ created.items=it })
                    } catch(_: Exception) {
                    } finally { created.done=true }
                }
            }
        }
        return JSONObject().put("done",job.done).put("items",JSONArray(job.items.map { JSONArray().put(it.offsetMs).put(it.text) }))
    }

    companion object { const val LIMIT=20 }
}

private fun CatalogPage.toJson(): JSONObject {
    val json=JSONObject().put("items",items.json()).put("hasMore",hasMore)
    ranking?.let { rank ->
        json.put("ranking",JSONObject().put("totalPages",rank.totalPages).put("updated",rank.updatedText)
            .put("positions",JSONObject().also { positions ->
                rank.positions.forEach { (id,position) -> positions.put(id,JSONObject().put("rank",position.rank ?: JSONObject.NULL).put("heat",position.heat)) }
            }))
    }
    return json
}

private fun List<Series>.json() = JSONArray(map { it.webJson() })

private val heicCover=Regex("^https://[^/]+/novel-pic/([0-9a-f]{16,64})~[^?]*\\.heic(\\?.*)?$")

/**
 * 搜尋結果的封面是帶簽章的 HEIC（只有 Safari 能顯示，改副檔名會 403）；
 * 同一個圖片 ID 在未簽章的 `byteimg` 網域可以直接取 JPEG，這裡統一換成那個網址。
 */
private fun Series.webJson(): JSONObject {
    val json=toJson()
    heicCover.matchEntire(cover)?.let { json.put("cover","https://p3-novel.byteimg.com/novel-pic/${it.groupValues[1]}~tplv-shrink:640:0.image") }
    return json
}

private fun parseQuery(raw: String?): Map<String,String> =
    raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate { pair ->
        val name=pair.substringBefore('='); val value=pair.substringAfter('=',"")
        URLDecoder.decode(name,Charsets.UTF_8) to URLDecoder.decode(value,Charsets.UTF_8)
    }

private fun sendJson(exchange: HttpExchange,code: Int,body: Any) {
    val bytes=body.toString().toByteArray(Charsets.UTF_8)
    exchange.responseHeaders["Content-Type"]="application/json; charset=utf-8"
    exchange.responseHeaders["Cache-Control"]="no-store"
    exchange.sendResponseHeaders(code,bytes.size.toLong())
    exchange.responseBody.write(bytes)
}

private val staticTypes=mapOf("html" to "text/html; charset=utf-8","js" to "text/javascript; charset=utf-8",
    "css" to "text/css; charset=utf-8","svg" to "image/svg+xml","png" to "image/png","ico" to "image/x-icon")

private fun serveStatic(exchange: HttpExchange) = exchange.use {
    val path=exchange.requestURI.path.let { if(it=="/" || it.isEmpty()) "/index.html" else it }
    val name=path.removePrefix("/")
    val bytes=if(name.matches(Regex("[A-Za-z0-9_-]+\\.[a-z]+"))) Api::class.java.getResourceAsStream("/web/$name")?.use { it.readBytes() } else null
    if(bytes==null) { sendJson(exchange,404,JSONObject().put("error","找不到頁面")); return@use }
    exchange.responseHeaders["Content-Type"]=staticTypes[name.substringAfterLast('.')] ?: "application/octet-stream"
    exchange.responseHeaders["Cache-Control"]="no-cache"
    exchange.sendResponseHeaders(200,bytes.size.toLong())
    exchange.responseBody.write(bytes)
}

private inline fun HttpExchange.use(block: () -> Unit) {
    try { block() } catch(_: IOException) { } finally { close() }
}
