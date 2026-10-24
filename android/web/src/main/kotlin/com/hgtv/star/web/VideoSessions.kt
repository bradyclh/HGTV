// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.web

import com.hgtv.star.core.ContentRepository
import com.hgtv.star.core.RemoteVideo

/**
 * 正在播放的影片串流快取（每集 + 清晰度一份 [RemoteVideo]）。
 *
 * 瀏覽器的 `<video>` 會對同一個網址發很多次 Range 請求（開頭探測、拖動、預載），
 * 每次都重新取播放地址與解析加密索引太慢，因此保留最近幾集；超過上限就關掉最舊的。
 * 播放地址是有時效的 CDN 直鏈，讀取失敗時呼叫 [drop]，下一次請求會重新取地址。
 */
class VideoSessions(private val repository: ContentRepository, private val limit: Int = 6) {
    private val sessions=object: LinkedHashMap<String,RemoteVideo>(16,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,RemoteVideo>): Boolean =
            (size>limit).also { if(it) runCatching { eldest.value.close() } }
    }
    private val locks=java.util.concurrent.ConcurrentHashMap<String,Any>()

    private fun key(vid: String,quality: Int)="$vid@$quality"

    fun get(vid: String,quality: Int): RemoteVideo {
        val key=key(vid,quality)
        synchronized(sessions) { sessions[key]?.let { return it } }
        // 同一集只取一次地址：瀏覽器常常同時發兩三個請求。
        synchronized(locks.getOrPut(key) { Any() }) {
            synchronized(sessions) { sessions[key]?.let { return it } }
            val video=RemoteVideo(repository.http,repository.stream(vid,quality)).prepare()
            synchronized(sessions) { sessions[key]=video }
            return video
        }
    }

    fun drop(vid: String,quality: Int) {
        synchronized(sessions) { sessions.remove(key(vid,quality))?.let { runCatching { it.close() } } }
    }
}
