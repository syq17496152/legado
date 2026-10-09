package io.legado.app.model.localBook.epubcore.facade

import io.legado.app.model.localBook.epubcore.direct.EpubDirectLayoutMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节「五态分类 → 渲染参数」解析器单测（epub-md-rich-rendering 阶段 1.4 配对）。
 *
 * 覆盖：①纯文字章节归一化；②`rendition:layout=pre-paginated` 声明 → 固定版式单页；
 * ③整页图（无文字 + 视口声明）→ 固定版式；④出版方语义排版 → 保真；
 * ⑤图库（duokan gallery）→ 交互态；⑥样式表加载失败不阻断（退化归一化）。
 */
class EpubChapterContentModeResolverTest {

    private val noStyles = { _: String, _: Long -> null as ByteArray? }

    private fun resolve(
        html: String,
        renditionLayout: String? = null,
        spineProperties: Set<String> = emptySet(),
        mediaType: String? = "application/xhtml+xml",
        viewportWidth: Float? = null,
        viewportHeight: Float? = null,
        loader: (String, Long) -> ByteArray? = noStyles
    ) = EpubChapterContentModeResolver.resolve(
        chapterHref = "OEBPS/text/ch1.xhtml",
        chapterHtml = html,
        renditionLayout = renditionLayout,
        spineProperties = spineProperties,
        manifestProperties = emptySet(),
        mediaType = mediaType,
        packageViewportWidth = viewportWidth,
        packageViewportHeight = viewportHeight,
        resourceHost = "epub.local",
        loadStylesheet = { path, maxBytes -> loader(path, maxBytes) }
    )

    @Test
    fun `纯文字章节走归一化路径`() {
        val mode = resolve(
            html = "<html><body><p>正文第一段</p><p>正文第二段</p></body></html>"
        )
        assertEquals(EpubDirectLayoutMode.REFLOWABLE, mode.layoutMode)
        assertFalse(mode.preservePublisherLayout)
        assertFalse(mode.singlePage)
    }

    @Test
    fun `pre-paginated 声明走固定版式单页`() {
        val mode = resolve(
            html = "<html><body><p>正文</p></body></html>",
            renditionLayout = "pre-paginated"
        )
        assertEquals(EpubDirectLayoutMode.FIXED, mode.layoutMode)
        assertTrue(mode.preservePublisherLayout)
        assertTrue(mode.singlePage)
    }

    @Test
    fun `整页图配视口声明走固定版式并按视口缩放`() {
        val mode = resolve(
            html = """
                <html><head><meta name="viewport" content="width=900, height=1200"/></head>
                <body><img src="art.png"/></body></html>
            """.trimIndent(),
            viewportWidth = 900f,
            viewportHeight = 1200f
        )
        assertEquals(EpubDirectLayoutMode.FIXED, mode.layoutMode)
        assertTrue(mode.singlePage)
        assertTrue(mode.scaleToPublisherViewport)
        assertEquals(900f, mode.publisherViewportWidthPx!!, 0.001f)
        assertEquals(1200f, mode.publisherViewportHeightPx!!, 0.001f)
    }

    @Test
    fun `出版方竖排语义排版走保真且透传竖排`() {
        val mode = resolve(
            html = """
                <html><body style="writing-mode: vertical-rl"><div><p>竖排文字</p></div></body></html>
            """.trimIndent()
        )
        assertEquals(EpubDirectLayoutMode.PUBLISHER_STYLED, mode.layoutMode)
        assertTrue(mode.preservePublisherLayout)
        assertTrue("竖排规格透传", mode.verticalWriting)
    }

    @Test
    fun `图库页走交互态单页且不缩放`() {
        val mode = resolve(
            html = """
                <html><body>
                  <div class="duokan-image-gallery">
                    <div class="duokan-image-gallery-cell"><img src="1.png"/></div>
                    <div class="duokan-image-gallery-cell"><img src="2.png"/></div>
                  </div>
                </body></html>
            """.trimIndent(),
            viewportWidth = 800f,
            viewportHeight = 600f
        )
        assertEquals(EpubDirectLayoutMode.INTERACTIVE, mode.layoutMode)
        assertTrue(mode.singlePage)
        assertFalse("交互态按声明画布渲染，不做缩放", mode.scaleToPublisherViewport)
    }

    @Test
    fun `样式表加载失败时退化归一化且不抛异常`() {
        val mode = resolve(
            html = """
                <html><head><link rel="stylesheet" href="style.css"/></head>
                <body><p>正文</p></body></html>
            """.trimIndent(),
            loader = { _, _ -> throw IllegalStateException("archive read failed") }
        )
        assertEquals(EpubDirectLayoutMode.REFLOWABLE, mode.layoutMode)
        assertFalse(mode.preservePublisherLayout)
    }
}