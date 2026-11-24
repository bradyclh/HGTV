// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 播放模型各条路径的健康度（移植自 N3urda/hongguoTV `PlaybackRoutes`，按本仓库的路径表改写）。
 *
 * 每个匿名身份各有一份：换身份（见 [ContentRepository.rotatePlaybackIdentity]）就整份重建，
 * 不把旧身份的失败记录带到新身份上。只记时间与结果，不保存请求参数、身份或媒体地址。
 *
 * - 回 `200 + 0 字节`（网关挡掉）的路径冷却 [EMPTY_COOLDOWN]；其他失败（业务码、没有模型、网络）冷却 [FAILURE_COOLDOWN]；
 * - 成功过的路径排到最前，下次先试它；
 * - 全部在冷却时只放行**最早解冻**的那一条，不再挨个撞已知的空响应。
 */
internal class PlaybackRoutes(private val routes: List<String>) {
    private class Health(var success: Long = Long.MIN_VALUE, var blockedUntil: Long = 0, var empty: Boolean = false)
    private val health = routes.associateWith { Health() }

    /** 这一轮要试的路径，按「最近成功」优先、其余保持原顺序。 */
    @Synchronized
    fun plan(now: Long): List<String> {
        val available = routes.filter { health.getValue(it).blockedUntil <= now }
            .sortedByDescending { health.getValue(it).success }
        return available.ifEmpty { listOf(routes.minBy { health.getValue(it).blockedUntil }) }
    }

    /** 所有路径都因空响应处于冷却中：这个身份大概率已被平台挡掉。 */
    @Synchronized
    fun exhausted(now: Long) = routes.all { health.getValue(it).let { h -> h.empty && h.blockedUntil > now } }

    @Synchronized
    fun success(route: String, now: Long) {
        health[route]?.apply { success = now; blockedUntil = 0; empty = false }
    }

    @Synchronized
    fun failed(route: String, now: Long, empty: Boolean) {
        health[route]?.apply {
            blockedUntil = now + if (empty) EMPTY_COOLDOWN else FAILURE_COOLDOWN
            this.empty = empty
        }
    }

    companion object {
        const val EMPTY_COOLDOWN = 30_000L
        const val FAILURE_COOLDOWN = 5_000L
    }
}
