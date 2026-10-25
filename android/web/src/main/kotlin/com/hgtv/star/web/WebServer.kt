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
    // Cloudflare Access（見 docs/CLOUDFLARE.md）：給了團隊名與 AUD tag，經過 Cloudflare 進來的請求才放行登入者；
    // 沒給時，任何經過 Cloudflare 的請求一律拒絕，避免 Tunnel 忘了套 Access 時整站裸露在外。
    val team=(option("--access-team") ?: System.getenv("HGTV_ACCESS_TEAM"))?.takeIf { it.isNotBlank() }
    val audience=(option("--access-aud") ?: System.getenv("HGTV_ACCESS_AUD"))?.takeIf { it.isNotBlank() }
    // HGTV_ACCESS_JWKS_FILE 只給測試用：從本機檔案讀公開金鑰，才能用自己簽的 JWT 跑整套登入流程。
    val jwksFile=System.getenv("HGTV_ACCESS_JWKS_FILE")?.takeIf { it.isNotBlank() }?.let(::File)
    val auth=when {
        team==null || audience==null -> null
        jwksFile!=null -> AccessAuth(team,audience,fetchKeys={ jwksFile.readText() })
        else -> AccessAuth(team,audience)
    }
    val gate=Gate(auth)
    val server=HttpServer.create(InetSocketAddress(host,port),64)
    server.executor=Executors.newFixedThreadPool(32)
    val api=Api(repository,device,UserLibrary(File(dataDir,"users")))
    server.createContext("/api/") { exchange -> gate.run(exchange) { user -> api.handle(exchange,user) } }
    server.createContext("/video/") { exchange -> gate.run(exchange) { api.video(exchange) } }
    server.createContext("/") { exchange -> gate.run(exchange) { serveStatic(exchange) } }
    server.start()
    val url="http://${if(host=="0.0.0.0") "127.0.0.1" else host}:$port/"
    println("紅果TV 網頁版已啟動：$url（Ctrl+C 結束）")
    println(if(auth!=null) "Cloudflare Access 已啟用（團隊 $team）：經過 Tunnel 的請求需要登入" else "未設定 Cloudflare Access：只接受本機直接連線，經過 Cloudflare 的請求會被拒絕")
    if(args.contains("--open")) runCatching { ProcessBuilder("open",url).start() }
}

private class HttpError(val code: Int, message: String): Exception(message)

/**
 * 每個請求的身分判斷。
 *
 * - 帶 `Cf-Connecting-IP` / `Cf-Ray` 的請求是經過 Cloudflare（Tunnel）進來的：必須帶有效的 Access JWT，
 *   通過就是該 email 的使用者；沒設定 Access 或驗證失敗一律 403。
 * - 其他請求只可能來自這台機器（伺服器預設只聽 127.0.0.1）：當成本機使用者，紀錄存在瀏覽器裡。
 */
private class Gate(private val auth: AccessAuth?) {
    fun run(exchange: HttpExchange, handler: (user: String?) -> Unit) {
        val headers=exchange.requestHeaders
        if(!headers.containsKey("Cf-Connecting-IP") && !headers.containsKey("Cf-Ray")) return handler(null)
        val token=headers.getFirst("Cf-Access-Jwt-Assertion") ?: cookie(headers.getFirst("Cookie"),"CF_Authorization")
        val email=auth?.verify(token)
        if(email!=null) return handler(email)
        exchange.use { sendJson(exchange,403,JSONObject().put("error",if(auth==null) "這台伺服器沒有啟用 Cloudflare Access" else "請先登入")) }
    }

    private fun cookie(header: String?,name: String): String? =
        header?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
}

private class Api(private val repository: ContentRepository, private val device: DeviceStore, private val library: UserLibrary) {
    private val videos=VideoSessions(repository)
    private val danmaku=DanmakuJobs(repository)
    private val idPattern=Regex("[0-9]{1,30}")

    fun handle(exchange: HttpExchange,user: String?) = exchange.use {
        val query=parseQuery(exchange.requestURI.rawQuery)
        fun param(name: String) = query[name]?.trim().orEmpty()
        fun id(name: String) = param(name).takeIf { it.matches(idPattern) } ?: throw HttpError(400,"參數 $name 無效")
        fun page() = param("page").toIntOrNull()?.coerceIn(1,500) ?: 1
        val path=exchange.requestURI.path.removePrefix("/api/").trimEnd('/')
        val body: Any=try {
            when {
                path=="me" -> JSONObject().put("email",user ?: JSONObject.NULL)
                    .put("logout",if(user!=null) "/cdn-cgi/access/logout" else JSONObject.NULL)
                path=="library" || path.startsWith("library/") -> libraryRoute(exchange,user ?: throw HttpError(401,"沒有登入，紀錄存在這個瀏覽器"),path.removePrefix("library").trimStart('/'))
                else -> contentRoute(path,::param,::id,::page)
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
     * 登入者的紀錄：
     * `GET library`、`POST library/import`、`PUT|DELETE library/progress/{id}`、`DELETE library/progress`、
     * `PUT|DELETE library/favorites/{id}`、`PUT library/settings`。寫入後回傳整份最新紀錄。
     */
    private fun libraryRoute(exchange: HttpExchange,user: String,rest: String): JSONObject {
        val method=exchange.requestMethod.uppercase()
        fun body(): JSONObject {
            val bytes=exchange.requestBody.readNBytes(MAX_BODY+1)
            if(bytes.size>MAX_BODY) throw HttpError(413,"資料太大")
            return runCatching { JSONObject(String(bytes,Charsets.UTF_8)) }.getOrElse { throw HttpError(400,"資料格式錯誤") }
        }
        val parts=rest.split('/').filter { it.isNotEmpty() }
        fun target() = parts.getOrNull(1)?.takeIf { it.matches(idPattern) } ?: throw HttpError(400,"劇集 ID 無效")
        return when {
            parts.isEmpty() && method=="GET" -> library.get(user)
            parts==listOf("import") && method=="POST" -> library.import(user,body())
            parts==listOf("settings") && method=="PUT" -> library.putSettings(user,body())
            parts==listOf("progress") && method=="DELETE" -> library.clearProgress(user)
            parts.firstOrNull()=="progress" && parts.size==2 && method=="PUT" -> library.putProgress(user,target(),body())
            parts.firstOrNull()=="progress" && parts.size==2 && method=="DELETE" -> library.deleteProgress(user,target())
            parts.firstOrNull()=="favorites" && parts.size==2 && method=="PUT" -> library.putFavorite(user,body().put("id",target()))
            parts.firstOrNull()=="favorites" && parts.size==2 && method=="DELETE" -> library.deleteFavorite(user,target())
            else -> throw HttpError(404,"找不到介面")
        }
    }

    private fun contentRoute(path: String,param: (String) -> String,id: (String) -> String,page: () -> Int): Any =
            when(path) {
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
        /** 紀錄寫入的請求上限（匯入整份本機紀錄時最大）。 */
        const val MAX_BODY=2*1024*1024
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
