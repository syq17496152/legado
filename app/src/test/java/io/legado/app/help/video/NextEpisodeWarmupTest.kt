package io.legado.app.help.video

import io.legado.app.help.exoplayer.ManifestFetcher
import io.legado.app.help.exoplayer.VideoSegmentPrefetcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 倒计时下一集预热编排（R36 / AD-24）。
 *
 * 覆盖：参数门禁、在途幂等、取消语义（取消续播不取消预热）。
 */
class NextEpisodeWarmupTest {

    @After
    fun tearDown() {
        NextEpisodeWarmup.resetForTest()
        VideoSegmentPrefetcher.resetForTest()
    }

    private fun context(
        key: String = "k1",
        count: Int = 3,
        isLive: Boolean = false,
        resolve: suspend () -> NextEpisodeWarmup.Resolved? = {
            NextEpisodeWarmup.Resolved("https://cdn/x/index.m3u8", emptyMap())
        },
    ) = NextEpisodeWarmup.Context(
        key = key,
        targetUrl = "https://site/ep1",
        isLive = isLive,
        count = count,
        limitBytesPerSec = 8_000_000L,
        resolve = resolve,
    )

    @Test
    fun `直播或零片数不启动`() {
        assertFalse(NextEpisodeWarmup.start(context(isLive = true)))
        assertFalse(NextEpisodeWarmup.start(context(count = 0)))
        assertFalse(NextEpisodeWarmup.start(context().copy(targetUrl = " ")))
    }

    @Test
    fun `解析为空的失败不影响状态`() = runBlocking {
        VideoSegmentPrefetcher.resetForTest(fetcher = ManifestFetcher { _, _ -> null })
        NextEpisodeWarmup.start(context { null })
        assertTrue(NextEpisodeWarmup.isRunning("k1"))
        delay(300)
        assertFalse(NextEpisodeWarmup.isRunning("k1"))
    }

    @Test
    fun `同一目标在途时不重复启动`() = runBlocking {
        VideoSegmentPrefetcher.resetForTest(fetcher = ManifestFetcher { _, _ -> null })
        val started = NextEpisodeWarmup.start(context { delay(500); null })
        assertTrue(started)
        assertTrue(NextEpisodeWarmup.isRunning("k1"))
        // 第二次同 key：返回 true 但复用同一任务
        assertTrue(NextEpisodeWarmup.start(context { delay(500); null }))
    }

    @Test
    fun `取消全部后不再运行`() = runBlocking {
        VideoSegmentPrefetcher.resetForTest(fetcher = ManifestFetcher { _, _ -> null })
        NextEpisodeWarmup.start(context { delay(5_000); null })
        assertTrue(NextEpisodeWarmup.isRunning())
        NextEpisodeWarmup.cancelAll("test")
        assertFalse(NextEpisodeWarmup.isRunning())
    }

    @Test
    fun `取消指定目标只影响该目标`() = runBlocking {
        VideoSegmentPrefetcher.resetForTest(fetcher = ManifestFetcher { _, _ -> null })
        NextEpisodeWarmup.start(context(key = "a") { delay(5_000); null })
        NextEpisodeWarmup.start(context(key = "b") { delay(5_000); null })
        NextEpisodeWarmup.cancel("a", "manual")
        assertFalse(NextEpisodeWarmup.isRunning("a"))
        assertTrue(NextEpisodeWarmup.isRunning("b"))
    }
}
