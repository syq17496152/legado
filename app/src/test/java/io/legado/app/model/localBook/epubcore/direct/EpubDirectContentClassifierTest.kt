package io.legado.app.model.localBook.epubcore.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 五态分类与 Direct 纯策略单测（epub-md-rich-rendering 阶段 1.1/1.3 配对）
 *
 * 覆盖：REFLOWABLE / PUBLISHER_STYLED / FIXED / MEDIA / INTERACTIVE 五态判定，
 * 出版方背景识别；`@media` 屏幕相关性；Range 解析；排版/页眉可用性闸门；源缓存键。
 */
class EpubDirectContentClassifierTest {

    private fun classify(
        renditionLayout: String? = null,
        spineProperties: Set<String> = emptySet(),
        manifestProperties: Set<String> = emptySet(),
        mediaType: String? = null,
        html: String,
        publisherCss: String = ""
    ) = EpubDirectContentClassifier.classify(
        renditionLayout = renditionLayout,
        spineProperties = spineProperties,
        manifestProperties = manifestProperties,
        mediaType = mediaType,
        sourceHtml = html,
        publisherCss = publisherCss
    )

    @Test
    fun `普通纯文字段落判定为可重排`() {
        val mode = classify(html = "<html><body><p>hello</p></body></html>")
        assertEquals(EpubDirectLayoutMode.REFLOWABLE, mode)
    }

    @Test
    fun `图片媒体类型判定为 MEDIA`() {
        val mode = classify(mediaType = "image/jpeg", html = "<html><body><img src='a.jpg'/></body></html>")
        assertEquals(EpubDirectLayoutMode.MEDIA, mode)
    }

    @Test
    fun `rendition pre-paginated 判定为固定版式`() {
        val mode = classify(
            renditionLayout = "pre-paginated",
            html = "<html><body><p>text</p></body></html>"
        )
        assertEquals(EpubDirectLayoutMode.FIXED, mode)
    }

    @Test
    fun `整页图且无出版方排版判定为固定版式`() {
        val mode = classify(html = "<html><body><img src='page.jpg'/></body></html>")
        assertEquals(EpubDirectLayoutMode.FIXED, mode)
    }

    @Test
    fun `含脚本的固定画布判定为交互型`() {
        val mode = classify(
            renditionLayout = "pre-paginated",
            spineProperties = setOf("scripted"),
            html = "<html><body><p>text</p></body></html>"
        )
        assertEquals(EpubDirectLayoutMode.INTERACTIVE, mode)
    }

    @Test
    fun `出版方分栏样式判定为出版方排版`() {
        val mode = classify(
            html = "<html><body><p>text</p></body></html>",
            publisherCss = "body{column-count:2}"
        )
        assertEquals(EpubDirectLayoutMode.PUBLISHER_STYLED, mode)
    }

    @Test
    fun `rendition 可重排显式声明优先于画布启发式`() {
        val mode = classify(
            spineProperties = setOf("rendition:layout-reflowable"),
            html = "<html><body><img src='page.jpg'/></body></html>"
        )
        assertEquals(EpubDirectLayoutMode.REFLOWABLE, mode)
    }

    @Test
    fun `analyze 暴露整页图与出版方页面背景标志`() {
        val profile = EpubDirectContentClassifier.analyze(
            renditionLayout = null,
            spineProperties = emptySet(),
            manifestProperties = emptySet(),
            mediaType = null,
            sourceHtml = "<html><body><p>text</p></body></html>",
            publisherCss = "body{background-image:url(bg.png)}"
        )
        assertFalse(profile.fullPageArtwork)
        assertTrue(profile.publisherPageBackground)
    }

    @Test
    fun `media 查询屏幕相关性判定`() {
        assertTrue(EpubDirectCssMediaPolicy.mayApplyToScreen(""))
        assertTrue(EpubDirectCssMediaPolicy.mayApplyToScreen("screen and (min-width: 400px)"))
        assertTrue(EpubDirectCssMediaPolicy.mayApplyToScreen("all"))
        assertFalse(EpubDirectCssMediaPolicy.mayApplyToScreen("print"))
        assertFalse(EpubDirectCssMediaPolicy.mayApplyToScreen("speech"))
    }

    @Test
    fun `screenRelevantCss 剔除打印块并保留屏幕块`() {
        val css = "@media print { p { color: red } } @media screen { p { color: blue } } p{margin:0}"
        val filtered = EpubDirectCssMediaPolicy.screenRelevantCss(css)
        assertFalse(filtered.contains("red"))
        assertTrue(filtered.contains("blue"))
        assertTrue(filtered.contains("margin:0"))
    }

    @Test
    fun `Range 头解析支持闭区间与后缀形式`() {
        val range = EpubDirectRangePolicy.parse("bytes=10-19", 100)
        assertEquals(10L, range?.start)
        assertEquals(19L, range?.endInclusive)
        assertEquals(10L, range?.length)

        val suffix = EpubDirectRangePolicy.parse("bytes=-20", 100)
        assertEquals(80L, suffix?.start)
        assertEquals(99L, suffix?.endInclusive)

        assertNull(EpubDirectRangePolicy.parse("bytes=100-200", 100))
        assertNull(EpubDirectRangePolicy.parse(null, 100))
        assertTrue(EpubDirectRangePolicy.shouldPrepareDiskCache("bytes=5-9", 100))
        assertFalse(EpubDirectRangePolicy.shouldPrepareDiskCache("bytes=0-9", 100))
    }

    @Test
    fun `排版与页眉可用性闸门只放行普通可重排章节`() {
        val reflowable = EpubDirectReaderTypographyPolicy.Input(EpubDirectLayoutMode.REFLOWABLE)
        assertTrue(EpubDirectReaderTypographyPolicy.isSupported(reflowable))
        assertFalse(
            EpubDirectReaderTypographyPolicy.isSupported(
                EpubDirectReaderTypographyPolicy.Input(
                    EpubDirectLayoutMode.REFLOWABLE,
                    scripted = true
                )
            )
        )

        assertTrue(EpubDirectReaderChromePolicy.isSupported(EpubDirectReaderChromePolicy.Input(EpubDirectLayoutMode.REFLOWABLE)))
        assertFalse(
            EpubDirectReaderChromePolicy.isSupported(
                EpubDirectReaderChromePolicy.Input(EpubDirectLayoutMode.PUBLISHER_STYLED)
            )
        )
        assertFalse(
            EpubDirectReaderChromePolicy.isSupported(
                EpubDirectReaderChromePolicy.Input(EpubDirectLayoutMode.REFLOWABLE, scrollMode = true)
            )
        )
    }

    @Test
    fun `源缓存键对媒体型用标题 对文本型用续页链`() {
        val media = EpubDirectSourceCacheKey.create("m/1", "image/PNG;charset=x", "封面", listOf("a"))
        assertTrue(media.startsWith("m/1|image/png"))
        assertTrue(media.endsWith("|封面"))

        val text = EpubDirectSourceCacheKey.create("t/1", "application/xhtml+xml", "标题", listOf("a", "b"))
        assertTrue(text.startsWith("t/1|application/xhtml+xml"))
        assertTrue(text.endsWith("|a|b"))
    }
}