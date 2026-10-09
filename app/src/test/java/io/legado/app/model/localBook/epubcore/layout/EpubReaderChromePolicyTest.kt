package io.legado.app.model.localBook.epubcore.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读器页眉/页脚（chrome）策略单测（epub-md-rich-rendering 阶段 1.3 配对）
 *
 * 覆盖：默认禁用契约、几何收敛、几何键不含动态内容、经典字段整数映射、
 * 六槽位文本解析（页码/进度/章节名）、Direct EPUB 高级翻页模式屏蔽。
 */
class EpubReaderChromePolicyTest {

    @Test
    fun `默认配置保持禁用且保留高为 0`() {
        val disabled = EpubReaderChromeConfig.DISABLED
        assertFalse(disabled.enabled)
        assertEquals(0, disabled.reservedHeaderHeightPx)
        assertEquals(0, disabled.reservedFooterHeightPx)
        assertTrue(disabled.geometryKey().startsWith("false|false|false"))
    }

    @Test
    fun `几何收敛不超出页面高度且非负`() {
        val config = EpubReaderChromeConfig(
            enabled = true,
            headerEnabled = true,
            footerEnabled = true,
            headerHeightPx = 300,
            footerHeightPx = 300
        )
        val normalized = config.normalizedForPage(200)
        assertEquals(200, normalized.headerHeightPx)
        assertEquals(0, normalized.footerHeightPx)
        assertTrue(normalized.textSizePx > 0f)
    }

    @Test
    fun `几何键随几何变化但不含随页文本`() {
        val a = EpubReaderChromeConfig(enabled = true, headerEnabled = true, headerHeightPx = 30)
        val b = a.copy(headerHeightPx = 31)
        assertFalse(a.geometryKey() == b.geometryKey())
        // 动态数据不属于 config，因此键与 EpubReaderChromeData 无关
        assertFalse(a.geometryKey().contains("chapterTitle"))
    }

    @Test
    fun `经典字段整数映射`() {
        assertEquals(EpubReaderChromeField.CHAPTER_TITLE, EpubReaderChromeLegacyFieldPolicy.resolve(1))
        assertEquals(EpubReaderChromeField.TIME_BATTERY_PERCENTAGE, EpubReaderChromeLegacyFieldPolicy.resolve(9))
        assertEquals(EpubReaderChromeField.NONE, EpubReaderChromeLegacyFieldPolicy.resolve(99))
    }

    @Test
    fun `页眉六槽位解析出页码与总进度`() {
        val config = EpubReaderChromeConfig(
            enabled = true,
            headerEnabled = true,
            headerHeightPx = 40,
            headerLeft = EpubReaderChromeField.PAGE,
            headerRight = EpubReaderChromeField.TOTAL_PROGRESS
        )
        val resolved = EpubReaderChromeDataPolicy.resolve(
            config = config,
            template = EpubReaderChromeData(bookName = "书", chapterCount = 10),
            page = EpubReaderChromeDataPolicy.Page(
                chapterIndex = 0,
                chapterTitle = "第一章",
                pageIndex = 0,
                pageCount = 1
            )
        )
        assertEquals("1/1", resolved.headerLeft)
        assertEquals("10.0%", resolved.headerRight)
        assertEquals("第一章", resolved.chapterTitle)
        assertTrue(resolved.chapterFirstPage)
    }

    @Test
    fun `Direct EPUB 屏蔽高级翻页模式且不改写可选集合`() {
        assertFalse(EpubReaderChromeModePolicy.isSupported(directEpub = true, mode = 7, advancedMode = 7))
        assertTrue(EpubReaderChromeModePolicy.isSupported(directEpub = true, mode = 1, advancedMode = 7))
        assertTrue(EpubReaderChromeModePolicy.isSupported(directEpub = false, mode = 7, advancedMode = 7))

        val modes = mapOf(1 to "a", 7 to "b")
        val filtered = EpubReaderChromeModePolicy.selectableModes(modes, directEpub = true, advancedMode = 7)
        assertEquals(setOf(1), filtered.keys)
        assertEquals(modes, EpubReaderChromeModePolicy.selectableModes(modes, directEpub = false, advancedMode = 7))
    }
}