package io.legado.app.help.gsyVideo

import io.legado.app.help.exoplayer.LONG_VIDEO_CACHE_THRESHOLD_MS
import io.legado.app.help.exoplayer.LivePlaybackPolicy
import io.legado.app.help.exoplayer.shouldWriteCache
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tasks §13.3/§13.4：缓存写策略在 `Exo2MediaPlayer` 侧的**接线不变量**。
 *
 * 分两类断言（互补）：
 * ① 行为断言：分流矩阵本身有确定期望值（长视频不写 / 短视频写 / 时长未知按短视频）；
 * ② 接线断言：纯 JVM 无法驱动 ExoPlayer 运行时，故守住三处易失守约定——
 *    · `attachToPlayer` 必须注册时长提供者（否则 sink 永远拿到 0 ⇒ 长视频也被写入）；
 *    · `detachFromPlayer` 必须清空（否则池化复用后旧实例的 lambda 持有已回收播放器）；
 *    · 时长读取必须走 `mInternalPlayer?.duration` 且做空安全回退 0。
 */
class Exo2MediaPlayerCacheWriteWiringTest {

    private val player by lazy {
        listOf(
            File("src/main/java/io/legado/app/help/gsyVideo/Exo2MediaPlayer.kt"),
            File("../app/src/main/java/io/legado/app/help/gsyVideo/Exo2MediaPlayer.kt"),
            File("app/src/main/java/io/legado/app/help/gsyVideo/Exo2MediaPlayer.kt")
        ).first { it.isFile }.readText()
    }

    @Test
    fun `长视频不写缓存_短视频写入`() {
        // 40 分钟档长剧集：不边播边写
        assertFalse(shouldWriteCache(40 * 60 * 1000L))
        // 5 分钟短剧：保持边下边存（重播秒开）
        assertTrue(shouldWriteCache(5 * 60 * 1000L))
        // 阈值边界：等于阈值归为长视频，阈值前 1ms 仍是短视频
        assertFalse(shouldWriteCache(LONG_VIDEO_CACHE_THRESHOLD_MS))
        assertTrue(shouldWriteCache(LONG_VIDEO_CACHE_THRESHOLD_MS - 1))
        // 时长未知（prepare 前/直播）：按短视频处理，宁多写不漏写
        assertEquals(true, shouldWriteCache(0L))
    }

    @Test
    fun `直播优先于时长_一律不写缓存`() {
        // 审计 B3：直播 duration<=0 不能当短视频写盘
        assertFalse(LivePlaybackPolicy.shouldWriteCache(isLive = true, backCacheSec = 120, durationMs = 0L))
        assertFalse(LivePlaybackPolicy.shouldWriteCache(isLive = true, backCacheSec = 0, durationMs = 5_000L))
        // 非直播长视频启用回看窗口时写（受控写盘例外）
        assertTrue(LivePlaybackPolicy.shouldWriteCache(isLive = false, backCacheSec = 120, durationMs = 40 * 60 * 1000L))
    }

    @Test
    fun `attach 注册时长提供者_detach 清空`() {
        val attachBody = player.substring(
            player.indexOf("private fun attachToPlayer"),
            player.indexOf("private fun detachFromPlayer")
        )
        assertTrue(
            "attachToPlayer 必须注册 currentDurationProvider，否则长视频判定永远拿到 0",
            attachBody.contains("ExoPlayerHelper.currentDurationProvider = { knownDurationMs }")
        )
        val detachBody = player.substring(
            player.indexOf("private fun detachFromPlayer"),
            player.indexOf("override fun release()")
        )
        assertTrue(
            "detachFromPlayer 必须清空 currentDurationProvider（池化复用后不得残留旧实例 lambda）",
            detachBody.contains("ExoPlayerHelper.currentDurationProvider = null")
        )
    }

    @Test
    fun `时长供应器不得跨线程访问播放器`() {
        // 真机铁证 2026-11-08 02:12：sink 在加载线程创建，lambda 内读 player.duration
        // 抛 IllegalStateException: Player is accessed on the wrong thread（errorCode=1004）
        val attachBody = player.substring(
            player.indexOf("private fun attachToPlayer"),
            player.indexOf("private fun detachFromPlayer")
        )
        assertFalse(
            "currentDurationProvider 的 lambda 不得直接读 mInternalPlayer（跨线程访问会崩播放）",
            attachBody.contains("currentDurationProvider = { mInternalPlayer")
        )
        assertTrue(
            "时长必须先由状态回调（应用线程）写入快照",
            player.contains("if (dur > 0L) knownDurationMs = dur")
        )
        assertTrue(
            "新会话必须归零时长快照（避免沿用上一集时长误判分流）",
            player.contains("knownDurationMs = 0L")
        )
        // READY 处必须给出确定性决策日志（sink 不创建时仍可取证）
        assertTrue(
            "STATE_READY 必须输出 cache_policy 决策",
            player.contains("cache_policy durMs=\$dur write=")
        )
    }
}
