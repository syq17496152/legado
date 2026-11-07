package io.legado.app.help.gsyVideo

import io.legado.app.help.exoplayer.LONG_VIDEO_CACHE_THRESHOLD_MS
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
    fun `attach 注册时长提供者_detach 清空`() {
        val attachBody = player.substring(
            player.indexOf("private fun attachToPlayer"),
            player.indexOf("private fun detachFromPlayer")
        )
        assertTrue(
            "attachToPlayer 必须注册 currentDurationProvider，否则长视频判定永远拿到 0",
            attachBody.contains("ExoPlayerHelper.currentDurationProvider = { mInternalPlayer?.duration ?: 0L }")
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
}
