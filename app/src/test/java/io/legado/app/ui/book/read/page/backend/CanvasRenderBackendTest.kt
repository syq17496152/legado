package io.legado.app.ui.book.read.page.backend

import io.legado.app.model.reader.render.ChapterRef
import io.legado.app.model.reader.render.ReadingPosition
import io.legado.app.model.reader.render.RenderConfig
import io.legado.app.model.reader.render.RenderFailure
import io.legado.app.model.reader.render.RenderHost
import io.legado.app.model.reader.render.RenderToken
import io.legado.app.model.reader.render.TextRange
import io.legado.app.ui.book.read.page.api.DataSource
import io.legado.app.ui.book.read.page.api.PageFactory
import io.legado.app.ui.book.read.page.entities.TextChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Canvas 渲染后端单测（epub-md-rich-rendering 阶段 2.2/2.5 配对）。
 *
 * 用**测试替身**三步替换 `TextPage`/`TextChapter`（其构造依赖 `appCtx`，单测不可实例化），
 * 验证「包装现有自绘能力」的投射是否与现状语义一致：分页驱动、同步重测、字符偏移映射、
 * 失败回报、代际失效、朗读锚点。
 */
class CanvasRenderBackendTest {

    private class FakePage(val text: String)

    private class FakePageFactory(var pages: List<FakePage>) : PageFactory<FakePage>(FakeDataSource()) {
        var currentPageIndex = 0
        override fun moveToFirst() { currentPageIndex = 0 }
        override fun moveToLast() { currentPageIndex = pages.lastIndex.coerceAtLeast(0) }
        override fun moveToNext(upContent: Boolean): Boolean {
            if (currentPageIndex >= pages.lastIndex) return false
            currentPageIndex++
            return true
        }
        override fun moveToPrev(upContent: Boolean): Boolean {
            if (currentPageIndex <= 0) return false
            currentPageIndex--
            return true
        }
        override val nextPage: FakePage get() = pages.getOrElse(currentPageIndex + 1) { pages.last() }
        override val prevPage: FakePage get() = pages.getOrElse(currentPageIndex - 1) { pages.first() }
        override val curPage: FakePage get() = pages[currentPageIndex]
        override val nextPlusPage: FakePage get() = pages.last()
        override fun hasNext(): Boolean = currentPageIndex < pages.lastIndex
        override fun hasPrev(): Boolean = currentPageIndex > 0
        override fun hasNextPlus(): Boolean = currentPageIndex + 1 < pages.lastIndex
    }

    private class FakeDataSource : DataSource {
        var upContentCalls = 0
        var lastResetPageOffset: Boolean? = null
        override val currentChapter: TextChapter? = null
        override val nextChapter: TextChapter? = null
        override val prevChapter: TextChapter? = null
        override val isScroll: Boolean = false
        override fun hasNextChapter(): Boolean = false
        override fun hasPrevChapter(): Boolean = false
        override fun upContent(relativePosition: Int, resetPageOffset: Boolean) {
            upContentCalls++
            lastResetPageOffset = resetPageOffset
        }
    }

    private class RecordingHost : RenderHost {
        var renderedToken: RenderToken? = null
        var renderedPageCount: Int = -1
        var remeasuredToken: RenderToken? = null
        var failure: RenderFailure? = null
        override fun onRendered(token: RenderToken, chapterIndex: Int, pageCount: Int) {
            renderedToken = token
            renderedPageCount = pageCount
        }
        override fun onReMeasured(token: RenderToken, chapterIndex: Int, pageCount: Int) {
            remeasuredToken = token
        }
        override fun onSelection(range: TextRange?) = Unit
        override fun onProgress(position: ReadingPosition) = Unit
        override fun onError(token: RenderToken?, failure: RenderFailure, error: Throwable?) {
            this.failure = failure
        }
    }

    private val projector = object : CanvasRenderBackend.PageProjector<FakePage> {
        override fun pageCount(page: FakePage): Int = page.text.length
        override fun pageText(page: FakePage): String = page.text
    }

    private class Fixture(pages: List<FakePage> = listOf(FakePage("第一页正文"), FakePage("第二页正文"))) {
        val dataSource = FakeDataSource()
        val factory = FakePageFactory(pages)
        var imported: ReadingPosition? = null
        var exported: ReadingPosition = ReadingPosition.Text(0, 0)
        val backend = CanvasRenderBackend(
            dataSource = dataSource,
            pageFactory = factory,
            projector = object : CanvasRenderBackend.PageProjector<FakePage> {
                override fun pageCount(page: FakePage): Int = page.text.length
                override fun pageText(page: FakePage): String = page.text
            },
            positionProvider = { exported },
            positionImporter = { imported = it }
        )
        val host = RecordingHost()
    }

    @Test
    fun `render 驱动既有排版并回报页数`() {
        val fixture = Fixture()
        fixture.backend.attach(fixture.host)
        val token = fixture.backend.render(
            ChapterRef(2, "OEBPS/ch2.xhtml", "第二章"),
            position = null,
            config = RenderConfig(600, 800, 16f, 24f, 0)
        )
        assertEquals("应驱动既有 upContent 完成分页", 1, fixture.dataSource.upContentCalls)
        assertEquals(true, fixture.dataSource.lastResetPageOffset)
        assertEquals(token, fixture.host.renderedToken)
        assertEquals("第一页正文".length, fixture.host.renderedPageCount)
    }

    @Test
    fun `render 带位置时恢复位置且不报错`() {
        val fixture = Fixture()
        fixture.backend.attach(fixture.host)
        val position = ReadingPosition.Text(chapterIndex = 2, charOffset = 120)
        fixture.backend.render(ChapterRef(2, "ch2.xhtml"), position, RenderConfig(600, 800, 16f, 24f, 0))
        assertEquals(position, fixture.imported)
        assertNull(fixture.host.failure)
    }

    @Test
    fun `设备能力为 canvas 且不支撑富渲染与模板`() {
        val fixture = Fixture()
        assertFalse(fixture.backend.capabilities.supportsRichRender)
        assertFalse(fixture.backend.capabilities.supportsTemplates)
        assertTrue(fixture.backend.capabilities.supportsTextSelection)
    }

    @Test
    fun `remeasure 同步回报当前代际页数`() {
        val fixture = Fixture()
        fixture.backend.attach(fixture.host)
        val token = fixture.backend.render(ChapterRef(0, "ch.xhtml"), null, RenderConfig(600, 800, 16f, 24f, 0))
        fixture.backend.remeasure(token)
        assertEquals(token, fixture.host.remeasuredToken)
    }

    @Test
    fun `过期代际的 remeasure 被丢弃`() {
        val fixture = Fixture()
        fixture.backend.attach(fixture.host)
        val stale = RenderToken(0)
        fixture.backend.remeasure(stale)
        assertNull("远古代际不得触发回调", fixture.host.remeasuredToken)
    }

    @Test
    fun `文本区间与字符偏移双向映射且页锚点不假换算`() {
        val fixture = Fixture()
        assertEquals(
            ReadingPosition.Text(chapterIndex = 1, charOffset = 30),
            fixture.backend.textToPosition(TextRange(1, 30, 45))
        )
        assertEquals(
            TextRange(1, 30, 30),
            fixture.backend.positionToText(ReadingPosition.Text(1, 30))
        )
        assertNull(
            "页/媒体锚点不得假换算为字符区间（AD-14）",
            fixture.backend.positionToText(ReadingPosition.Page(1, 3, 0.5f))
        )
        assertNull(fixture.backend.positionToText(ReadingPosition.Media(0, 0.3f)))
    }

    @Test
    fun `导入位置失败时返回 false 而非抛出`() {
        val dataSource = FakeDataSource()
        val backend = CanvasRenderBackend(
            dataSource = dataSource,
            pageFactory = FakePageFactory(listOf(FakePage("a"))),
            projector = projector,
            positionProvider = { ReadingPosition.Text(0, 0) },
            positionImporter = { throw IllegalStateException("导入失败") }
        )
        assertFalse(backend.importPosition(ReadingPosition.Text(0, 5)))
    }

    @Test
    fun `朗读锚点复用当前页文本`() {
        val fixture = Fixture()
        fixture.backend.attach(fixture.host)
        fixture.backend.render(ChapterRef(3, "ch3.xhtml"), null, RenderConfig(600, 800, 16f, 24f, 0))
        val anchors = fixture.backend.readAloudAnchors()
        assertEquals(1, anchors.size)
        assertEquals(3, anchors.single().chapterIndex)
        assertEquals("第一页正文".length, anchors.single().length)
    }

    @Test
    fun `dispose 后朗读锚点为空且旧代际失效`() {
        val fixture = Fixture()
        fixture.backend.attach(fixture.host)
        val token = fixture.backend.render(ChapterRef(0, "ch.xhtml"), null, RenderConfig(600, 800, 16f, 24f, 0))
        fixture.backend.dispose()
        assertTrue(fixture.backend.readAloudAnchors().isEmpty())
        fixture.host.remeasuredToken = null
        fixture.backend.remeasure(token)
        assertNull(fixture.host.remeasuredToken)
    }
}