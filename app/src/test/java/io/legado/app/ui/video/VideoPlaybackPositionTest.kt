package io.legado.app.ui.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 2026-10-27 用户反馈：篇/集必须公共且互不串线。
 * 纯函数锁定书源播放列表 + 订阅源文章列表的组合口径。
 */
class VideoPlaybackPositionTest {

    @Test
    fun bookSourceShowsPlaylistAndEpisode() {
        val position = resolvePlaybackPosition(
            hasBook = true,
            rssArticleIndex = 9,
            rssArticleCount = 30,
            bookPlaylistIndex = 2,
            bookPlaylistTotal = 5,
            bookEpisodeIndex = 3,
            bookEpisodeCount = 12,
            rssEpisodeIndex = 7,
            rssEpisodeCount = 8,
        )
        assertEquals(2, position.listIndex)
        assertEquals(5, position.listTotal)
        assertEquals(3, position.episodeIndex)
        assertEquals(12, position.episodeTotal)
    }

    @Test
    fun rssShowsArticleAndEpisode() {
        val position = resolvePlaybackPosition(
            hasBook = false,
            rssArticleIndex = 1,
            rssArticleCount = 15,
            bookPlaylistIndex = null,
            bookPlaylistTotal = 0,
            bookEpisodeIndex = 0,
            bookEpisodeCount = 0,
            rssEpisodeIndex = 2,
            rssEpisodeCount = 8,
        )
        assertEquals(1, position.listIndex)
        assertEquals(15, position.listTotal)
        assertEquals(2, position.episodeIndex)
        assertEquals(8, position.episodeTotal)
    }

    @Test
    fun rssSingleArticleHidesListButKeepsEpisode() {
        val position = resolvePlaybackPosition(
            hasBook = false,
            rssArticleIndex = 0,
            rssArticleCount = 1,
            bookPlaylistIndex = null,
            bookPlaylistTotal = 0,
            bookEpisodeIndex = 0,
            bookEpisodeCount = 0,
            rssEpisodeIndex = 4,
            rssEpisodeCount = 6,
        )
        assertNull(position.listIndex)
        assertEquals(4, position.episodeIndex)
        assertEquals(6, position.episodeTotal)
    }

    @Test
    fun bookWithoutPlaylistShowsOnlyEpisode() {
        val position = resolvePlaybackPosition(
            hasBook = true,
            rssArticleIndex = 0,
            rssArticleCount = 0,
            bookPlaylistIndex = null,
            bookPlaylistTotal = 0,
            bookEpisodeIndex = 1,
            bookEpisodeCount = 9,
            rssEpisodeIndex = 0,
            rssEpisodeCount = 0,
        )
        assertNull(position.listIndex)
        assertEquals(1, position.episodeIndex)
        assertEquals(9, position.episodeTotal)
    }

    @Test
    fun singleEpisodeAndSingleItemShowNothing() {
        val position = resolvePlaybackPosition(
            hasBook = false,
            rssArticleIndex = 0,
            rssArticleCount = 1,
            bookPlaylistIndex = 0,
            bookPlaylistTotal = 1,
            bookEpisodeIndex = 0,
            bookEpisodeCount = 1,
            rssEpisodeIndex = 0,
            rssEpisodeCount = 1,
        )
        assertNull(position.listIndex)
        assertNull(position.episodeIndex)
        assertEquals(0, position.listTotal)
        assertEquals(0, position.episodeTotal)
    }

    @Test
    fun outOfRangeIndexesDoNotRenderWrongPosition() {
        val position = resolvePlaybackPosition(
            hasBook = false,
            rssArticleIndex = 99,
            rssArticleCount = 15,
            bookPlaylistIndex = null,
            bookPlaylistTotal = 0,
            bookEpisodeIndex = 0,
            bookEpisodeCount = 0,
            rssEpisodeIndex = -1,
            rssEpisodeCount = 8,
        )
        assertNull(position.listIndex)
        assertNull(position.episodeIndex)
    }

    @Test
    fun legacyRssSubInfoDoesNotDuplicateEpisode() {
        assertEquals(
            LegacyRssSubInfo.ARTICLE,
            resolveLegacyRssSubInfo(articleCount = 15, episodeCount = 8),
        )
        // 单篇文章 + 多集：tvAuthor 不再显示集数（由 tvEpisodeLabel 承载）
        assertEquals(
            LegacyRssSubInfo.NONE,
            resolveLegacyRssSubInfo(articleCount = 1, episodeCount = 8),
        )
        // 集数模式（无文章上下文）：仍可由副信息行展示集数
        assertEquals(
            LegacyRssSubInfo.EPISODE,
            resolveLegacyRssSubInfo(articleCount = 0, episodeCount = 8),
        )
        assertEquals(
            LegacyRssSubInfo.NONE,
            resolveLegacyRssSubInfo(articleCount = 0, episodeCount = 0),
        )
    }

    @Test
    fun upVideoInfoBindsBookOrRssBySessionKind() {
        // 订阅源会话才允许走 rss 信息绑定；书源会话必须走 showBook（作者+篇数）
        assertEquals(true, shouldBindRssLegacyInfo(hasBook = false))
        assertEquals(false, shouldBindRssLegacyInfo(hasBook = true))
    }

    // ==================== tasks §7.3：集数入口点击可达（段模型） ====================

    @Test
    fun positionSegmentsArticleThenEpisodeOnlyEpisodeClickable() {
        val position = PlaybackPosition(
            listIndex = 2,
            listTotal = 5,
            episodeIndex = 3,
            episodeTotal = 12,
        )
        val segments = positionSegments(position)
        assertEquals(2, segments.size)
        assertEquals(PositionSegmentKind.ARTICLE, segments[0].kind)
        assertEquals(2, segments[0].index)
        assertEquals(5, segments[0].total)
        assertEquals(false, segments[0].clickable)
        assertEquals(PositionSegmentKind.EPISODE, segments[1].kind)
        assertEquals(3, segments[1].index)
        assertEquals(12, segments[1].total)
        assertEquals(true, segments[1].clickable)
    }

    @Test
    fun positionSegmentsSingleArticleKeepsEpisodeEntryClickable() {
        val segments = positionSegments(
            PlaybackPosition(
                listIndex = null,
                listTotal = 0,
                episodeIndex = 0,
                episodeTotal = 6,
            )
        )
        assertEquals(1, segments.size)
        assertEquals(PositionSegmentKind.EPISODE, segments[0].kind)
        assertEquals(true, segments[0].clickable)
    }

    @Test
    fun positionSegmentsEmptyWhenNothingToShow() {
        assertEquals(
            emptyList<PositionSegment>(),
            positionSegments(
                PlaybackPosition(
                    listIndex = null,
                    listTotal = 0,
                    episodeIndex = null,
                    episodeTotal = 0,
                )
            ),
        )
    }
}
