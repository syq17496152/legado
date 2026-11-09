package io.legado.app.model

import io.legado.app.help.video.NextEpisodeResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * §14.5 预 prepared 释放契约（会话结束 / 换源后无残留）。
 *
 * `VideoPlay.clearPreloadCache(reason)` 的释放动作之一是对 [NextEpisodeResolver] 调 `clear()`，
 * 这里锁定该契约的行为结果：释放后条目数归零、旧键不再命中、重复释放幂等。
 */
class PreloadReleaseStateTest {

    @Before
    fun setUp() {
        NextEpisodeResolver.clear()
    }

    private fun pre(url: String) = NextEpisodeResolver.PreResolved(
        finalUrl = url,
        headers = emptyMap(),
        resolvedAt = 1000L,
        engine = "fast_direct"
    )

    @Test
    fun `会话结束释放后条目归零且不再命中`() {
        val key = NextEpisodeResolver.key("http://e/1", "srcA", "http://a/1")
        NextEpisodeResolver.put(key, pre("http://x/1.m3u8"))
        assertEquals(1, NextEpisodeResolver.size())

        // 等同 clearPreloadCache("session_end") 的释放动作
        NextEpisodeResolver.clear()

        assertEquals(0, NextEpisodeResolver.size())
        assertNull(NextEpisodeResolver.get(key, now = 1000L))
    }

    @Test
    fun `换源释放后旧键不可命中 新键可命中`() {
        val oldKey = NextEpisodeResolver.key("http://e/old", "srcA", null)
        NextEpisodeResolver.put(oldKey, pre("http://x/old.m3u8"))
        NextEpisodeResolver.clear()

        val newKey = NextEpisodeResolver.key("http://e/new", "srcB", null)
        NextEpisodeResolver.put(newKey, pre("http://x/new.m3u8"))

        assertNull(NextEpisodeResolver.get(oldKey, now = 1000L))
        assertEquals("http://x/new.m3u8", NextEpisodeResolver.get(newKey, now = 1000L)?.finalUrl)
    }

    @Test
    fun `重复释放保持幂等`() {
        NextEpisodeResolver.put(NextEpisodeResolver.key("http://e/1", "srcA", null), pre("http://x/1.m3u8"))
        NextEpisodeResolver.clear()
        NextEpisodeResolver.clear()
        assertEquals(0, NextEpisodeResolver.size())
    }

    @Test
    fun `预取与回看默认值契约`() {
        assertEquals(
            true,
            io.legado.app.model.VideoPlay.DEFAULT_SEGMENT_PREFETCH_ENABLED,
        )
        assertEquals(120, io.legado.app.model.VideoPlay.DEFAULT_LONG_VIDEO_BACK_CACHE_SEC)
    }
}
