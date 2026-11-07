package io.legado.app.ui.rss.read

import io.legado.app.data.entities.RssArticle
import io.legado.app.help.video.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * design §1.13 #8：订阅源收藏/历史/单篇直入应进入 SERIES（上滑=下一集）。
 *
 * 既有实现会为这类入口从 DB 补齐「同源同分类」文章列表（保证上下滑可用），
 * 若只看列表长度会被推导成 FEED。这里锁定：无调用方列表（null/≤1）⇒ 注入 SERIES hint；
 * 已带多篇列表（列表页/搜索列表）⇒ 不注入，保持 FEED 推导。
 */
class RssEntryModeHintTest {

    private fun article(link: String) = RssArticle(
        origin = "https://example.invalid/rss",
        sort = "默认",
        title = "t",
        link = link
    )

    @Test
    fun `收藏与历史直入无列表时注入SERIES`() {
        assertEquals(PlaybackMode.SERIES.name, ReadRss.rssEntryModeHint(null))
    }

    @Test
    fun `单篇列表同样注入SERIES`() {
        assertEquals(PlaybackMode.SERIES.name, ReadRss.rssEntryModeHint(listOf(article("a"))))
    }

    @Test
    fun `空列表注入SERIES`() {
        assertEquals(PlaybackMode.SERIES.name, ReadRss.rssEntryModeHint(emptyList()))
    }

    @Test
    fun `多篇列表不注入保持FEED推导`() {
        assertNull(ReadRss.rssEntryModeHint(listOf(article("a"), article("b"))))
    }
}
