package io.legado.app.model.localBook.epubcore.facade

import android.graphics.RectF
import android.text.TextPaint
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import io.legado.app.model.localBook.epubcore.layout.EpubMeasuredTextFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 降级页工厂单测（epub-md-rich-rendering 阶段 1.6 配对）。
 *
 * 覆盖：①TEXT 级产出单页正文；②空内容自动升格为 RAW 说明页；
 * ③RAW 级恒非空（终态兜底不崩溃）；④空白原因回退到通用文案。
 *
 * 注：JVM 单测下 android.jar 为 stub（`returnDefaultValues = true`），故只断言结构与文本，
 * 不断言 [RectF] 的数值。
 */
class EpubFallbackPageFactoryTest {

    private fun config(width: Int = 600, height: Int = 800) = EpubCoreLayoutConfig(
        pageWidthPx = width,
        pageHeightPx = height,
        textPaint = TextPaint()
    )

    @Test
    fun `textPages 产出单页正文且带可绘制文本片段`() {
        val pages = EpubFallbackPageFactory.textPages(
            chapterIndex = 3,
            chapterHref = "OEBPS/text/ch3.xhtml",
            text = "  第一段正文  ",
            config = config()
        )
        assertEquals(1, pages.size)
        val page = pages.single()
        assertEquals(3, page.chapterIndex)
        assertEquals("OEBPS/text/ch3.xhtml", page.chapterHref)
        assertEquals(1, page.totalPagesInChapter)
        assertEquals("第一段正文", page.text.toString())
        assertNull("降级页无源锚点，不得臆造", page.start)
        val fragment = page.fragments.single()
        assertTrue(fragment is EpubMeasuredTextFragment)
        assertEquals("第一段正文", (fragment as EpubMeasuredTextFragment).text.toString())
    }

    @Test
    fun `空正文自动升格为 RAW 说明页`() {
        val pages = EpubFallbackPageFactory.textPages(
            chapterIndex = 0,
            chapterHref = "ch.xhtml",
            text = "   \n  ",
            config = config()
        )
        assertEquals(1, pages.size)
        assertTrue("空内容必须有兜底文案", pages.single().text.isNotBlank())
    }

    @Test
    fun `rawPages 恒非空且保留原因`() {
        val pages = EpubFallbackPageFactory.rawPages(
            chapterIndex = 7,
            chapterHref = "OEBPS/ch7.xhtml",
            reason = "本章渲染失败（EMPTY_RESULT）",
            config = config()
        )
        assertEquals(1, pages.size)
        assertTrue(pages.single().text.contains("EMPTY_RESULT"))
        assertEquals(1, pages.single().fragments.size)
    }

    @Test
    fun `rawPages 对空白原因回退到通用文案`() {
        val pages = EpubFallbackPageFactory.rawPages(
            chapterIndex = 0,
            chapterHref = "ch.xhtml",
            reason = "  ",
            config = config()
        )
        assertTrue("不得产出空页", pages.single().text.isNotBlank())
    }
}