// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.web

import com.hgtv.star.core.ContentRepository
import com.hgtv.star.core.RemoteVideo
import java.util.concurrent.Executors

/**
 * 正在播放的影片串流快取（每集 + 清晰度一份 [Session]）。
 *
 * 瀏覽器的 `<video>` 會對同一個網址發很多次 Range 請求（開頭探測、拖動、預載），
 * 每次都重新取播放地址與解析加密索引太慢，因此保留最近幾集；超過上限就關掉最舊的。
 * 播放地址是有時效的 CDN 直鏈，讀取失敗時呼叫 [drop]，下一次請求會重新取地址。
 *
 * 自動連播的預先載入（[prefetch]）：先取好下一集的地址與加密索引，再把開頭 [HEAD_BYTES]
 * 解密後放在記憶體裡，換集時第一批請求直接從這裡回，不必等上游。
 */
class VideoSessions(private val repository: ContentRepository, private val limit: Int = 6) {
    class Session(val video: RemoteVideo) {
        /** 已解密的開頭片段（預先載入時才有）。 */
        @Volatile var head: ByteArray = byteArrayOf()
        val total: Long get() = video.total

        fun read(start: Long,length: Int): ByteArray {
            val cached=head
            if(start+length<=cached.size) return cached.copyOfRange(start.toInt(),start.toInt()+length)
            return video.read(start,length)
        }

        @Synchronized fun fillHead() {
            val wanted=minOf(total,HEAD_BYTES).toInt()
            if(head.size>=wanted) return
            val out=java.io.ByteArrayOutputStream(wanted); out.write(head)
            while(out.size()<wanted) out.write(video.read(out.size().toLong(),minOf(CHUNK,wanted-out.size())))
            head=out.toByteArray()
        }
    }

    private val sessions=object: LinkedHashMap<String,Session>(16,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Session>): Boolean =
            (size>limit).also { if(it) runCatching { eldest.value.video.close() } }
    }
    private val locks=java.util.concurrent.ConcurrentHashMap<String,Any>()
    private val prefetching=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pool=Executors.newFixedThreadPool(2) { task -> Thread(task,"hgtv-prefetch").apply { isDaemon=true } }

    private fun key(vid: String,quality: Int)="$vid@$quality"

    fun get(vid: String,quality: Int): Session {
        val key=key(vid,quality)
        synchronized(sessions) { sessions[key]?.let { return it } }
        // 同一集只取一次地址：瀏覽器常常同時發兩三個請求。
        synchronized(locks.getOrPut(key) { Any() }) {
            synchronized(sessions) { sessions[key]?.let { return it } }
            val session=Session(RemoteVideo(repository.http,repository.stream(vid,quality)).prepare())
            synchronized(sessions) { sessions[key]=session }
            return session
        }
    }

    /** 背景預先載入一集；同一集同時只跑一次，失敗就算了（真正播放時會再取）。 */
    fun prefetch(vid: String,quality: Int) {
        val key=key(vid,quality)
        if(!prefetching.add(key)) return
        pool.submit {
            try { get(vid,quality).fillHead() } catch(_: Exception) { drop(vid,quality) } finally { prefetching.remove(key) }
        }
    }

    fun drop(vid: String,quality: Int) {
        synchronized(sessions) { sessions.remove(key(vid,quality))?.let { runCatching { it.video.close() } } }
    }

    companion object {
        /** 預先載入的開頭長度：4MB 大約是 1080P 的前十幾秒，足夠瀏覽器起播並接上後續請求。 */
        const val HEAD_BYTES=4L*1024*1024
        const val CHUNK=1024*1024
    }
}
