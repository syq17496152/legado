package io.legado.app.help.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 续播决策矩阵单测（design §1.4 / tasks 2.2，≥12 例）
 */
class EpisodeAdvancePolicyTest {

    private fun series(
        index: Int,
        total: Int,
        isBook: Boolean = false,
        isArticleMode: Boolean = false,
        autoAdvance: Boolean = true,
        played: Int = 0,
        limit: Int = 0,
        routeAtEnd: Boolean = false,
        hasNextRoute: Boolean = false,
        isSingleUrl: Boolean = false,
        mode: PlaybackMode = PlaybackMode.SERIES
    ) = AdvanceSnapshot(
        mode = mode,
        isBook = isBook,
        isArticleMode = isArticleMode,
        currentIndex = index,
        total = total,
        autoAdvance = autoAdvance,
        playedInSession = played,
        sessionLimit = limit,
        routeAtEnd = routeAtEnd,
        hasNextRoute = hasNextRoute,
        isSingleUrl = isSingleUrl
    )

    @Test
    fun `订阅源集数模式 非末集 推进下一集`() {
        val d = EpisodeAdvancePolicy.decide(series(index = 2, total = 5))
        assertEquals(AdvanceTarget.NextEpisode(3), d)
    }

    @Test
    fun `订阅源集数模式 末集 停止`() {
        val d = EpisodeAdvancePolicy.decide(series(index = 4, total = 5))
        assertTrue(d is AdvanceTarget.Stop)
        assertEquals(EpisodeAdvancePolicy.REASON_LAST_EPISODE, (d as AdvanceTarget.Stop).reason)
    }

    @Test
    fun `订阅源文章模式 非末篇 切下一篇`() {
        val d = EpisodeAdvancePolicy.decide(
            series(index = 0, total = 3, isArticleMode = true)
        )
        assertEquals(AdvanceTarget.NextArticle(1), d)
    }

    @Test
    fun `订阅源文章模式 末篇 停止`() {
        val d = EpisodeAdvancePolicy.decide(
            series(index = 2, total = 3, isArticleMode = true)
        )
        assertEquals(EpisodeAdvancePolicy.REASON_LAST_ARTICLE, (d as AdvanceTarget.Stop).reason)
    }

    @Test
    fun `书源同线路中间集 推进下一集`() {
        val d = EpisodeAdvancePolicy.decide(series(index = 1, total = 8, isBook = true))
        assertEquals(AdvanceTarget.NextEpisode(2), d)
    }

    @Test
    fun `书源线路末集且有下一线路 提示不自动跨线路`() {
        val d = EpisodeAdvancePolicy.decide(
            series(index = 7, total = 8, isBook = true, routeAtEnd = true, hasNextRoute = true)
        )
        assertEquals(EpisodeAdvancePolicy.REASON_ROUTE_END, (d as AdvanceTarget.Stop).reason)
    }

    @Test
    fun `书源末线路末集 停止`() {
        val d = EpisodeAdvancePolicy.decide(
            series(index = 7, total = 8, isBook = true, routeAtEnd = true, hasNextRoute = false)
        )
        assertEquals(EpisodeAdvancePolicy.REASON_LAST_EPISODE, (d as AdvanceTarget.Stop).reason)
    }

    @Test
    fun `单 URL 恒停止`() {
        val d = EpisodeAdvancePolicy.decide(
            series(index = 0, total = 1, isSingleUrl = true)
        )
        assertEquals(EpisodeAdvancePolicy.REASON_SINGLE_URL, (d as AdvanceTarget.Stop).reason)
    }

    @Test
    fun `自动续播关闭 停止`() {
        val d = EpisodeAdvancePolicy.decide(series(index = 0, total = 5, autoAdvance = false))
        assertEquals(EpisodeAdvancePolicy.REASON_AUTO_OFF, (d as AdvanceTarget.Stop).reason)
    }

    @Test
    fun `连播预算耗尽 停止`() {
        val d = EpisodeAdvancePolicy.decide(series(index = 2, total = 9, played = 3, limit = 3))
        assertEquals(EpisodeAdvancePolicy.REASON_SESSION_LIMIT, (d as AdvanceTarget.Stop).reason)
    }

    @Test
    fun `连播上限为零表示不限 仍推进`() {
        val d = EpisodeAdvancePolicy.decide(series(index = 2, total = 9, played = 99, limit = 0))
        assertEquals(AdvanceTarget.NextEpisode(3), d)
    }

    @Test
    fun `SINGLE 模式 停止`() {
        val d = EpisodeAdvancePolicy.decide(
            series(index = 2, total = 9, mode = PlaybackMode.SINGLE)
        )
        assertEquals(EpisodeAdvancePolicy.REASON_SINGLE_MODE, (d as AdvanceTarget.Stop).reason)
    }

    @Test
    fun `空列表 停止`() {
        val d = EpisodeAdvancePolicy.decide(series(index = 0, total = 0))
        assertEquals(EpisodeAdvancePolicy.REASON_LAST_EPISODE, (d as AdvanceTarget.Stop).reason)
    }

    @Test
    fun `优先级 单URL 高于 自动续播关闭`() {
        val d = EpisodeAdvancePolicy.decide(
            series(index = 0, total = 1, isSingleUrl = true, autoAdvance = false)
        )
        assertEquals(EpisodeAdvancePolicy.REASON_SINGLE_URL, (d as AdvanceTarget.Stop).reason)
    }

    @Test
    fun `入口模式判定 多项列表为FEED`() {
        val mode = EntryModeResolver.resolve(EntryContext(listSize = 3, isSeriesEntry = false))
        assertEquals(PlaybackMode.FEED, mode)
    }

    @Test
    fun `入口模式判定 单元素列表为SERIES`() {
        val mode = EntryModeResolver.resolve(EntryContext(listSize = 1, isSeriesEntry = false))
        assertEquals(PlaybackMode.SERIES, mode)
    }

    @Test
    fun `入口模式判定 显式 hint 优先`() {
        val mode = EntryModeResolver.resolve(
            EntryContext(listSize = 5, isSeriesEntry = false, modeHint = PlaybackMode.SINGLE)
        )
        assertEquals(PlaybackMode.SINGLE, mode)
    }

    @Test
    fun `末集单向降级 只触发一次且幂等`() {
        val started = PlaybackModeState(PlaybackMode.SERIES)
        val once = started.degradeToSingle()
        assertEquals(PlaybackMode.SINGLE, once.mode)
        assertTrue(once.degraded)
        assertEquals(once, once.degradeToSingle())
        assertEquals(once, started.degradeToSingle())
    }

    @Test
    fun `非SERIES模式不降级`() {
        val feed = PlaybackModeState(PlaybackMode.FEED)
        assertEquals(feed, feed.degradeToSingle())
    }

    // ==================== §3.1 snapshotOf：播放上下文 → 决策输入 ====================

    @Test
    fun `snapshotOf 订阅源多集 映射为SERIES且可推进`() {
        val s = EpisodeAdvancePolicy.snapshotOf(
            isBook = false, isArticleMode = false, currentIndex = 0, total = 3,
            singleUrl = false, volumesSize = 0, durVolumeIndex = 0
        )
        assertEquals(PlaybackMode.SERIES, s.mode)
        assertEquals(false, s.isSingleUrl)
        assertEquals(AdvanceTarget.NextEpisode(1), EpisodeAdvancePolicy.decide(s))
    }

    @Test
    fun `snapshotOf 直链单片 映射为SINGLE并停止`() {
        val s = EpisodeAdvancePolicy.snapshotOf(
            isBook = false, isArticleMode = false, currentIndex = 0, total = 1,
            singleUrl = true, volumesSize = 0, durVolumeIndex = 0
        )
        assertEquals(PlaybackMode.SINGLE, s.mode)
        assertTrue(s.isSingleUrl)
        assertEquals(EpisodeAdvancePolicy.REASON_SINGLE_URL, (EpisodeAdvancePolicy.decide(s) as AdvanceTarget.Stop).reason)
    }

    @Test
    fun `snapshotOf 书源中间线路 非末线路`() {
        val s = EpisodeAdvancePolicy.snapshotOf(
            isBook = true, isArticleMode = false, currentIndex = 3, total = 5,
            singleUrl = false, volumesSize = 3, durVolumeIndex = 1
        )
        assertTrue(s.routeAtEnd)
        assertTrue(s.hasNextRoute)
        assertEquals(AdvanceTarget.NextEpisode(4), EpisodeAdvancePolicy.decide(s))
    }

    @Test
    fun `snapshotOf 书源单线路末集 不再提示跨线路`() {
        val s = EpisodeAdvancePolicy.snapshotOf(
            isBook = true, isArticleMode = false, currentIndex = 4, total = 5,
            singleUrl = false, volumesSize = 0, durVolumeIndex = 0
        )
        assertEquals(false, s.routeAtEnd)
        assertEquals(false, s.hasNextRoute)
        assertEquals(EpisodeAdvancePolicy.REASON_LAST_EPISODE, (EpisodeAdvancePolicy.decide(s) as AdvanceTarget.Stop).reason)
    }

    @Test
    fun `snapshotOf 书源末线路末集 停止且不提示跨线路`() {
        val s = EpisodeAdvancePolicy.snapshotOf(
            isBook = true, isArticleMode = false, currentIndex = 4, total = 5,
            singleUrl = false, volumesSize = 3, durVolumeIndex = 2
        )
        assertTrue(s.routeAtEnd)
        assertEquals(false, s.hasNextRoute)
        assertEquals(EpisodeAdvancePolicy.REASON_LAST_EPISODE, (EpisodeAdvancePolicy.decide(s) as AdvanceTarget.Stop).reason)
    }

    @Test
    fun `snapshotOf 书源线路末集有下一线路 提示可切换`() {
        val s = EpisodeAdvancePolicy.snapshotOf(
            isBook = true, isArticleMode = false, currentIndex = 4, total = 5,
            singleUrl = false, volumesSize = 3, durVolumeIndex = 1
        )
        assertTrue(s.routeAtEnd)
        assertTrue(s.hasNextRoute)
        assertEquals(EpisodeAdvancePolicy.REASON_ROUTE_END, (EpisodeAdvancePolicy.decide(s) as AdvanceTarget.Stop).reason)
    }

    @Test
    fun `snapshotOf 订阅源文章模式 推进下一篇`() {
        val s = EpisodeAdvancePolicy.snapshotOf(
            isBook = false, isArticleMode = true, currentIndex = 0, total = 4,
            singleUrl = false, volumesSize = 0, durVolumeIndex = 0
        )
        assertEquals(AdvanceTarget.NextArticle(1), EpisodeAdvancePolicy.decide(s))
    }
}
