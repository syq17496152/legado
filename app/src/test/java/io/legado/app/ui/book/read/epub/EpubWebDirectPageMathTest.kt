package io.legado.app.ui.book.read.epub

import io.legado.app.model.reader.render.ReadingPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可见 WebView 后端分页/位置数学单测（epub-md-rich-rendering 阶段 2.3 配对）。
 *
 * 覆盖：①内容高→页数（含不足一屏与非法入参）；②滚动偏移 ↔ 页索引夹取；
 * ③页内比例；④位置构造与还原闭环；⑤固定版式只产出页锚点（不产字符偏移）。
 */
class EpubWebDirectPageMathTest {

    @Test
    fun `内容高换算页数含不足一屏与非法入参`() {
        assertEquals(1, EpubWebDirectPageMath.pageCount(contentHeightPx = 800, viewportHeightPx = 1000))
        assertEquals(2, EpubWebDirectPageMath.pageCount(contentHeightPx = 1001, viewportHeightPx = 1000))
        assertEquals("整除时不留空页", 2, EpubWebDirectPageMath.pageCount(contentHeightPx = 2000, viewportHeightPx = 1000))
        assertEquals(3, EpubWebDirectPageMath.pageCount(contentHeightPx = 2001, viewportHeightPx = 1000))
        assertEquals("内容高未知按一页处理", 1, EpubWebDirectPageMath.pageCount(0, 1000))
        assertEquals("视口非法按一页处理", 1, EpubWebDirectPageMath.pageCount(2000, 0))
    }

    @Test
    fun `滚动偏移与页索引互相换算并夹取`() {
        assertEquals(0, EpubWebDirectPageMath.pageIndexForScroll(0, 1000, 3))
        assertEquals(1, EpubWebDirectPageMath.pageIndexForScroll(1500, 1000, 3))
        assertEquals("越界夹取到末页", 2, EpubWebDirectPageMath.pageIndexForScroll(99999, 1000, 3))
        assertEquals(0, EpubWebDirectPageMath.scrollForPage(-5, 1000, 3))
        assertEquals(2000, EpubWebDirectPageMath.scrollForPage(9, 1000, 3))
    }

    @Test
    fun `页内比例取页内偏移`() {
        assertEquals(0.5f, EpubWebDirectPageMath.inPageRatio(1500, 1000), 0.0001f)
        assertEquals(0f, EpubWebDirectPageMath.inPageRatio(2000, 1000), 0.0001f)
        assertEquals(0f, EpubWebDirectPageMath.inPageRatio(500, 0), 0.0001f)
    }

    @Test
    fun `位置构造与还原闭环`() {
        val position = EpubWebDirectPageMath.positionFor(
            chapterIndex = 4,
            scrollY = 2500,
            viewportHeightPx = 1000,
            pageCount = 4
        )
        assertEquals(4, position.chapterIndex)
        assertEquals(2, position.pageIndex)
        assertEquals(0.5f, position.inPageRatio, 0.0001f)
        assertEquals(2500, EpubWebDirectPageMath.scrollForPosition(position, 1000, 4))
    }

    @Test
    fun `位置构造恒为页锚点而非字符偏移`() {
        val position = EpubWebDirectPageMath.positionFor(0, 0, 1000, 1)
        assertTrue("固定版式不得伪造字符偏移（AD-14）", position is ReadingPosition.Page)
    }

    @Test
    fun `非法视口与页数不产生越界页索引`() {
        assertEquals(0, EpubWebDirectPageMath.pageIndexForScroll(500, 0, 3))
        assertEquals(0, EpubWebDirectPageMath.pageIndexForScroll(500, 1000, 0))
        assertEquals(0, EpubWebDirectPageMath.scrollForPage(3, 1000, 0))
    }
}