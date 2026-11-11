package io.legado.app.help.exoplayer

/**
 * 直播播放策略（纯函数）。
 *
 * 设计依据：docs/specs/video-buffer-prefetch-v2/design.md §1.14
 *
 * 关键区分：
 * - `isLive`（播放器动态时间轴 / isCurrentMediaItemLive）用于**写缓存 gate**；
 * - `hasEndList=false` 只用于**预取 gate**（VOD 清单偶见缺 ENDLIST，不能据此关写缓存）。
 */
object LivePlaybackPolicy {

    /** 直播判定：任一为真即直播 */
    fun isLive(isCurrentMediaItemLive: Boolean, windowIsDynamic: Boolean): Boolean =
        isCurrentMediaItemLive || windowIsDynamic

    /**
     * 直播目标偏移规划。
     *
     * 结论：**不覆盖 Media3 默认值**（默认 3×targetDuration），仅在日志里记录期望下限。
     * 旧实现硬编码 3s/5s，在 2s 分片场景低于默认 6s，更贴直播边缘、更容易周期性 stall。
     */
    data class OffsetPlan(val overrideMs: Long?, val expectedMinMs: Long)

    fun offsetPlan(targetDurationMs: Long): OffsetPlan {
        val expectedMin = if (targetDurationMs > 0) {
            maxOf(3 * targetDurationMs, 6_000L)
        } else {
            6_000L
        }
        return OffsetPlan(overrideMs = null, expectedMinMs = expectedMin)
    }

    /**
     * 是否允许写缓存。
     *
     * - 直播：一律不写（用过即弃 + 污染 LRU）；
     * - 长视频且启用回看窗口（backCacheSec>0）：写，用于回看；
     * - 其余沿用基线：时长未知或短视频写，长视频不写。
     */
    fun shouldWriteCache(isLive: Boolean, backCacheSec: Int, durationMs: Long): Boolean {
        if (isLive) return false
        if (backCacheSec > 0) return true
        return durationMs <= 0L || durationMs < LONG_VIDEO_CACHE_THRESHOLD_MS
    }
}
