package io.legado.app.help.exoplayer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix-live-hls-manifest-cache：接线不变量（源码文本断言）。
 *
 * 三条易失守约定：
 * 1. 包装点必须在 `cacheDataSourceFactory` 出口（所有视频链路唯一入口）；
 * 2. 缓存开关的两个分支都必须经过包装（关闭缓存时也不能漏）；
 * 3. 分发必须发生在 `open(dataSpec)`，且清单走上游、分片走缓存。
 */
class ManifestBypassWiringTest {

    private fun res(rel: String): String =
        listOf(File(rel), File("../app/$rel"), File("app/$rel")).first { it.isFile }.readText()

    private val helper by lazy { res("src/main/java/io/legado/app/help/exoplayer/ExoPlayerHelper.kt") }
    private val factory by lazy {
        res("src/main/java/io/legado/app/help/exoplayer/ManifestBypassDataSourceFactory.kt")
    }
    private val matcher by lazy {
        res("src/main/java/io/legado/app/help/exoplayer/ManifestUrlMatcher.kt")
    }

    @Test
    fun cacheFactoryIsWrappedAtSingleExit() {
        assertTrue(
            "cacheDataSourceFactory 出口必须包装 ManifestBypassDataSourceFactory",
            helper.contains("ManifestBypassDataSourceFactory(") &&
                helper.contains("cachedFactory,") &&
                helper.contains("directFactory,")
        )
        assertTrue(
            "缓存开关两分支必须先收敛到 cachedFactory",
            helper.contains("val cachedFactory: DataSource.Factory = if (!VideoPlay.videoCache)")
        )
        assertTrue(
            "清单直连上游必须复用 directFactory（不得绕过 TLS 回退/头注入）",
            helper.contains("val directFactory = DefaultDataSource.Factory(appCtx, upstreamFactory)")
        )
    }

    @Test
    fun dispatchHappensInOpen() {
        assertTrue("必须在 open(dataSpec) 分发", factory.contains("override fun open(dataSpec: DataSpec): Long"))
        assertTrue(
            "清单判定必须用 URI 字符串",
            factory.contains("val uri = dataSpec.uri.toString()") &&
                factory.contains("isManifestUrl(uri)")
        )
        assertTrue(
            "清单请求必须投递观察器（预取以播放实际加载的媒体清单为准）",
            factory.contains("onManifestOpened(uri)")
        )
        assertTrue(
            "清单走 direct、其余走 cache",
            factory.contains("active = if (useDirect) direct else cache")
        )
    }

    @Test
    fun matcherCoversHlsAndDash() {
        assertTrue(matcher.contains(".m3u8"))
        assertTrue(matcher.contains(".m3u"))
        assertTrue(matcher.contains(".mpd"))
        assertTrue(matcher.contains("format"))
        assertTrue(matcher.contains("type"))
    }
}
