package io.legado.app.ui.book.read.page.backend

import io.legado.app.model.reader.render.BackendCapabilities
import io.legado.app.model.reader.render.ChapterRef
import io.legado.app.model.reader.render.ReadAloudAnchor
import io.legado.app.model.reader.render.ReaderRenderBackend
import io.legado.app.model.reader.render.ReadingPosition
import io.legado.app.model.reader.render.RenderConfig
import io.legado.app.model.reader.render.RenderFailure
import io.legado.app.model.reader.render.RenderHost
import io.legado.app.model.reader.render.RenderToken
import io.legado.app.model.reader.render.TextRange
import io.legado.app.ui.book.read.page.api.DataSource
import io.legado.app.ui.book.read.page.api.PageFactory

/**
 * Canvas 渲染后端（epub-md-rich-rendering 阶段 2.2）。
 *
 * **定位**：把现有自绘阅读器**包装**为 [ReaderRenderBackend] 契约的实现——默认后端，行为与现状逐项一致（零回归）。
 * 本类**不新增渲染逻辑**，只是把既有的分页/翻页/位置换算能力投射到统一契约上：
 *
 * | 契约 | 现有自绘能力 |
 * |------|-------------|
 * | [render] | 驱动既有排版链路（`DataSource.upContent()` → `TextPageFactory`） |
 * | [remeasure] | 自绘排版同步完成 ⇒ 立即回调 `onReMeasured` |
 * | 翻页 | [PageFactory.hasNext] / [PageFactory.moveToNext] 等（由 DataSource 侧完成） |
 * | 位置 | 字符偏移（`ReadBook.durChapterPos` 语义，经 [positionProvider]/[positionImporter] 注入） |
 *
 * **泛型参数 `P`**：页面数据类型（生产为 `TextPage`）。用具名投影 [PageProjector] 而非直接依赖
 * UI 模型，使契约测试可在 JVM 中验证（`TextPage` 构造依赖 `appCtx`，单测无法实例化）。
 */
class CanvasRenderBackend<P>(
    private val dataSource: DataSource,
    private val pageFactory: PageFactory<P>,
    private val projector: PageProjector<P>,
    private val positionProvider: () -> ReadingPosition,
    private val positionImporter: (ReadingPosition) -> Unit
) : ReaderRenderBackend {

    override val capabilities: BackendCapabilities = BackendCapabilities.Canvas

    private var host: RenderHost? = null
    private var generation: Long = 0L
    private var currentChapter: ChapterRef? = null

    override fun attach(host: RenderHost) {
        this.host = host
    }

    override fun render(
        chapter: ChapterRef,
        position: ReadingPosition?,
        config: RenderConfig
    ): RenderToken {
        generation++
        currentChapter = chapter
        val token = RenderToken(generation)
        if (position != null) {
            // 位置恢复：自绘路径以字符偏移为准（AD-14：不做假换算，可表达性由策略层保证）。
            runCatching { positionImporter(position) }
        }
        runCatching {
            // 自绘排版由既有链路完成：驱动一次内容刷新即可（`upContent` 内部完成分页）。
            dataSource.upContent(resetPageOffset = true)
            host?.onRendered(token, chapter.chapterIndex, currentPageCount())
        }.onFailure { error ->
            host?.onError(token, RenderFailure.LAYOUT_ERROR, error)
        }
        return token
    }

    override fun remeasure(token: RenderToken) {
        // 自绘排版天然同步（无异步资源加载）⇒ 无须延迟重测，直接回报。
        if (token.generation != generation) return
        val chapterIndex = currentChapter?.chapterIndex ?: return
        host?.onReMeasured(token, chapterIndex, currentPageCount())
    }

    /** 自绘后端的坐标映射：文本区间 ↔ 章内字符偏移（同后端可无损）。 */
    override fun textToPosition(range: TextRange): ReadingPosition {
        return ReadingPosition.Text(chapterIndex = range.chapterIndex, charOffset = range.startOffset)
    }

    override fun positionToText(position: ReadingPosition): TextRange? {
        return when (position) {
            is ReadingPosition.Text -> TextRange(position.chapterIndex, position.charOffset, position.charOffset)
            // 页/媒体锚点无法逆推为字符区间（AD-14 禁止假换算）⇒ 明确返回 null。
            else -> null
        }
    }

    override fun exportPosition(): ReadingPosition = positionProvider()

    override fun importPosition(position: ReadingPosition): Boolean {
        return runCatching {
            positionImporter(position)
            true
        }.getOrDefault(false)
    }

    override fun readAloudAnchors(): List<ReadAloudAnchor> {
        val chapterIndex = currentChapter?.chapterIndex ?: return emptyList()
        val text = currentPageText()
        if (text.isEmpty()) return emptyList()
        return listOf(ReadAloudAnchor(chapterIndex = chapterIndex, startOffset = 0, length = text.length))
    }

    override fun invalidate(token: RenderToken) {
        if (token.generation == generation) {
            generation++
        }
    }

    override fun dispose() {
        generation++
        host = null
        currentChapter = null
    }

    private fun currentPageCount(): Int {
        return runCatching { projector.pageCount(pageFactory.curPage) }.getOrDefault(0)
    }

    private fun currentPageText(): String {
        return runCatching { projector.pageText(pageFactory.curPage) }.getOrDefault("")
    }

    /** 页面 → 契约所需少量字段的投影（解耦 UI 模型，便于 JVM 测试）。 */
    interface PageProjector<P> {
        fun pageCount(page: P): Int

        fun pageText(page: P): String
    }
}