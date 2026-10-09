package io.legado.app.model.reader.render

/**
 * 渲染后端契约（epub-md-rich-rendering 阶段 2.1 / AD-30）。
 *
 * 分层说明（**偏离设计文档落点，理由见 tasks.md AOAdapt**）：
 * 设计文档给的是 `epubcore/render/RendererContract.kt`，但 canvas 后端要适配
 * `ui.book.read.page.api.PageFactory`（UI 层）——契约若放 `epubcore` 会造成
 * **model → ui 反向依赖**。故契约下沉到中性层 `model.reader.render`：
 * ```
 * ui.book.read.page.backend   ──┐
 * model.localBook.epubcore.render ├──> model.reader.render（本包，仅依赖 android.*）
 * service.kernel / web（未来）    ──┘
 * ```
 * 宿主只依赖本包的抽象；两实现：Canvas（默认，零回归）/ DirectWeb（高保真）。
 */
interface ReaderRenderBackend {

    /** 能力描述符（AD-30 契约闭合的关键：宿主据此决定功能开关，而非 if-else 探测实现类）。 */
    val capabilities: BackendCapabilities

    /** 绑定宿主回调（渲染完成/重测完成/选区/错误/进度）。 */
    fun attach(host: RenderHost)

    /** 准备并渲染一章；返回渲染代际 token（用于失效与晚到结果丢弃）。 */
    fun render(chapter: ChapterRef, position: ReadingPosition?, config: RenderConfig): RenderToken

    /** 异步重测分页（富渲染注入完成后回调 [RenderHost.onReMeasured]）。 */
    fun remeasure(token: RenderToken)

    /** 文本 ↔ 坐标双向映射；不支持时返回 null（**不做假换算**，AD-14）。 */
    fun textToPosition(range: TextRange): ReadingPosition?

    fun positionToText(position: ReadingPosition): TextRange?

    /** 进度导出/导入（跨后端无损由 [ReadingPosition] 的降级规则保证）。 */
    fun exportPosition(): ReadingPosition

    fun importPosition(position: ReadingPosition): Boolean

    /** 朗读定位锚点（供朗读与视觉高亮对齐）。 */
    fun readAloudAnchors(): List<ReadAloudAnchor>

    /** 失效指定代际（其晚到异步结果必须被丢弃）。 */
    fun invalidate(token: RenderToken)

    fun dispose()
}

/** 后端能力矩阵（AD-30）。 */
data class BackendCapabilities(
    val supportsRichRender: Boolean,
    val supportsFixedLayout: Boolean,
    val supportsInteractive: Boolean,
    val supportsVerticalWriting: Boolean,
    val supportsTextSelection: Boolean,
    val supportsReadAloudAnchor: Boolean,
    val supportsTemplates: Boolean
) {
    companion object {
        /** canvas 快路径：富渲染/固定版式/模板降级；竖排有限支持。 */
        val Canvas = BackendCapabilities(
            supportsRichRender = false,
            supportsFixedLayout = false,
            supportsInteractive = false,
            supportsVerticalWriting = true,
            supportsTextSelection = true,
            supportsReadAloudAnchor = true,
            supportsTemplates = false
        )

        /** WebView 直渲染：浏览器级保真。 */
        val DirectWeb = BackendCapabilities(
            supportsRichRender = true,
            supportsFixedLayout = true,
            supportsInteractive = true,
            supportsVerticalWriting = true,
            supportsTextSelection = true,
            supportsReadAloudAnchor = true,
            supportsTemplates = true
        )
    }
}

/** 章节引用（跨后端稳定标识 = 章号 + href + 标题）。 */
data class ChapterRef(
    val chapterIndex: Int,
    val chapterHref: String,
    val title: String? = null
)

/** 渲染代际令牌。`generation` 在 Switching/Degraded 时递增；晚到结果需校验相等。 */
data class RenderToken(val generation: Long)

/** 文本区间（字符偏移，章内）。 */
data class TextRange(
    val chapterIndex: Int,
    val startOffset: Int,
    val endOffset: Int
) {
    init {
        require(startOffset >= 0 && endOffset >= startOffset) { "非法的文本区间：$startOffset..$endOffset" }
    }
}

/** 朗读锚点：章内字符偏移 + 长度（视觉高亮据此对齐）。 */
data class ReadAloudAnchor(
    val chapterIndex: Int,
    val startOffset: Int,
    val length: Int
)

/** 渲染配置（宿主 → 后端；仅含后端真正需要的排版参数）。 */
data class RenderConfig(
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val fontSizePx: Float,
    val lineHeightPx: Float,
    val textColor: Int,
    /** 保留出版方排版（五态非 REFLOWABLE）。 */
    val preservePublisherLayout: Boolean = false,
    /** 单页独占（固定版式/媒体/交互）。 */
    val singlePage: Boolean = false,
    val scrollMode: Boolean = false,
    /** 主题/模板标识（参与缓存键；无则空串）。 */
    val styleKey: String = ""
) {
    init {
        require(pageWidthPx > 0 && pageHeightPx > 0) { "页面尺寸必须为正：${pageWidthPx}x$pageHeightPx" }
    }
}

/** 渲染失败阶段（供宿主按 [RenderFailure] 决定降级）。 */
enum class RenderFailure {
    ENGINE_UNAVAILABLE,
    LAYOUT_ERROR,
    EMPTY_RESULT,
    TIMEOUT,

    /** 压缩包/OPF 结构损坏，内容不可读（纠错：原实现遗漏该档，WebView 后端无法上报结构损坏）。 */
    MALFORMED_CONTENT
}

/** 宿主回调（后端 → 宿主）。全部为「晚到结果必须自行校验代际」的语义。 */
interface RenderHost {
    fun onRendered(token: RenderToken, chapterIndex: Int, pageCount: Int)

    fun onReMeasured(token: RenderToken, chapterIndex: Int, pageCount: Int)

    fun onSelection(range: TextRange?)

    fun onProgress(position: ReadingPosition)

    fun onError(token: RenderToken?, failure: RenderFailure, error: Throwable? = null)
}