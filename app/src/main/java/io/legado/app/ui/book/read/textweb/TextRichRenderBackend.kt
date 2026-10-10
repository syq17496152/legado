package io.legado.app.ui.book.read.textweb

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.constant.AppLog
import io.legado.app.help.md.MdRichRenderInjector
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
import io.legado.app.ui.book.read.epub.EpubWebDirectPageMath
import java.io.Closeable

/**
 * 文本富渲染后端（epub-md-rich-rendering 阶段 3.1/3.2 的 WebView 承载面）。
 *
 * **为什么需要它（硬事实）**：txt/md/在线正文的 `<usehtml>` 正文当前经
 * `TextChapterLayout.setTypeHtml` 走自绘 canvas（`HtmlCompat` + `StaticLayout`），
 * **该链路无 WebView ⇒ JS 不可执行**，故 [MdRichRenderInjector]（mermaid / KaTeX / 代码高亮）
 * 在文本类内容上**不可能生效**。含富渲染元素的章节必须换到能跑 JS 的渲染面，
 * 这正是 [io.legado.app.model.reader.render.BackendSelectionPolicy] 里 `DIRECT_WEB` 分支的落点。
 *
 * **与 [io.legado.app.ui.book.read.epub.EpubWebDirectBackend] 的分工**（同为 WebView 承载，语义相反）：
 * | | EPUB 直渲染后端 | 本后端 |
 * |---|---|---|
 * | 内容源 | 出版方压缩包 HTML | 文本渲染文档 HTML（构建期产出） |
 * | 样式 | **不注入**阅读器主题（保真出版方排版） | **必须注入**阅读器主题（字号/行高/昼夜） |
 * | 富渲染 | 不需要（出版方自带） | **必须注入** mermaid/KaTeX/高亮 |
 * | 分页 | 固定版式页锚点 | **滚动**（长文阅读习惯；页锚点由滚动偏移折算） |
 *
 * **契约遵守**：`textToPosition` / `positionToText` 返回 null —— 滚动面没有可靠的
 * "字符偏移 ↔ 滚动位置"映射，**不做假换算**（AD-14）；位置以 `ReadingPosition.Page`
 * （滚动页 + 页内比例）如实表达。
 *
 * **滚动页数学复用** [EpubWebDirectPageMath]（同属"WebView 滚动面"语义）——刻意**不另造一套**
 * 「内容高↔页数↔滚动偏移」判据，避免两条 WebView 面口径分裂（同一类坑曾由"两套分流判据"引发）。
 *
 * **线程约束**（项目 landmine）：所有 WebView 操作必须在 UI 线程。
 */
class TextRichRenderBackend(
    private val context: Context,
    private val readerCssProvider: () -> String,
    private val chapterProvider: (ChapterRef) -> RichChapter?,
    private val themeProvider: () -> TextRichHtmlComposer.Theme,
    /** 本地书允许读取同目录图片（`file://`）；在线正文为 false。 */
    private val allowLocalFile: Boolean = false,
    private val baseUrl: String = "about:blank",
    private val container: (View) -> Unit = {}
) : ReaderRenderBackend, Closeable {

    /** 章节富渲染内容（构建期标注：是否需要 mermaid / 公式，决定是否注入 2.4MB 运行时）。 */
    data class RichChapter(
        val html: String,
        val hasMermaid: Boolean,
        val hasMath: Boolean
    )

    override val capabilities: BackendCapabilities = BackendCapabilities.DirectWeb

    private val handler = Handler(Looper.getMainLooper())

    /** 资产读取器（内部缓存：mermaid 2.4MB 不得每次渲染重读）。 */
    private val assetReader = AssetTextReader(context)

    private var webView: WebView? = null
    private var host: RenderHost? = null
    private var generation: Long = 0L
    private var currentChapter: ChapterRef? = null
    private var currentConfig: RenderConfig? = null
    private var pageCount: Int = 1
    private var contentHeight: Int = 0
    private var rendered = false
    private var disposed = false

    /** 供宿主挂载（可见 WebView 必须由宿主给出显示位置）。 */
    fun view(): View = ensureWebView()

    fun currentPageCount(): Int = pageCount

    override fun attach(host: RenderHost) {
        this.host = host
    }

    override fun render(
        chapter: ChapterRef,
        position: ReadingPosition?,
        config: RenderConfig
    ): RenderToken {
        generation++
        val token = RenderToken(generation)
        currentChapter = chapter
        currentConfig = config
        pageCount = 1
        contentHeight = 0
        rendered = false
        val content = chapterProvider(chapter)
        if (content == null || content.html.isBlank()) {
            host?.onError(token, RenderFailure.EMPTY_RESULT)
            return token
        }
        runOnUI {
            if (disposed || token.generation != generation) return@runOnUI
            runCatching {
                val view = ensureWebView()
                container(view)
                view.webViewClient = RichClient(token, config)
                view.loadDataWithBaseURL(baseUrl, composeDocument(content, config), "text/html", "UTF-8", null)
            }.onFailure { error ->
                AppLog.putDebug(
                    "text rich render start failed: chapter=${chapter.chapterIndex}, ${error.localizedMessage}",
                    error
                )
                host?.onError(token, RenderFailure.LAYOUT_ERROR, error)
            }
        }
        return token
    }

    /** 富渲染注入完成后（或超时后）由宿主调用，重测内容高并回报页数。 */
    override fun remeasure(token: RenderToken) {
        if (token.generation != generation) return
        runOnUI {
            if (disposed || token.generation != generation) return@runOnUI
            val view = webView ?: return@runOnUI
            view.evaluateJavascript(ContentHeightScript) { raw ->
                if (token.generation != generation) return@evaluateJavascript
                val height = raw?.trim()?.toDoubleOrNull()?.toInt() ?: 0
                contentHeight = height
                val viewport = currentConfig?.pageHeightPx ?: 0
                pageCount = EpubWebDirectPageMath.pageCount(height, viewport)
                val chapterIndex = currentChapter?.chapterIndex ?: return@evaluateJavascript
                host?.onReMeasured(token, chapterIndex, pageCount)
            }
        }
    }

    /** 滚动面无可信字符偏移映射 ⇒ 不假换算（AD-14）。 */
    override fun textToPosition(range: TextRange): ReadingPosition? = null

    override fun positionToText(position: ReadingPosition): TextRange? = null

    override fun exportPosition(): ReadingPosition {
        val chapterIndex = currentChapter?.chapterIndex ?: 0
        val viewport = currentConfig?.pageHeightPx ?: 0
        return EpubWebDirectPageMath.positionFor(chapterIndex, webView?.scrollY ?: 0, viewport, pageCount)
    }

    override fun importPosition(position: ReadingPosition): Boolean {
        val view = webView ?: return false
        if (position !is ReadingPosition.Page) return false
        val viewport = currentConfig?.pageHeightPx ?: return false
        val target = EpubWebDirectPageMath.scrollForPosition(position, viewport, pageCount)
        return runCatching {
            view.scrollTo(0, target)
            true
        }.getOrDefault(false)
    }

    /** 滚动面不产出朗读锚点（文本同源模型属文本渲染文档层，不在本 WebView 面内）。 */
    override fun readAloudAnchors(): List<ReadAloudAnchor> = emptyList()

    override fun invalidate(token: RenderToken) {
        if (token.generation == generation) generation++
    }

    override fun dispose() {
        disposed = true
        generation++
        host = null
        close()
    }

    override fun close() {
        disposed = true
        handler.removeCallbacksAndMessages(null)
        runOnUI {
            webView?.run {
                runCatching { stopLoading() }
                runCatching { loadUrl(WebViewBlank) }
                runCatching { destroy() }
            }
            webView = null
        }
    }

    /** 文档组装：读阅读样式 → 组装富渲染注入 → 合并主题。 */
    private fun composeDocument(content: RichChapter, config: RenderConfig): String {
        val injection = runCatching {
            val assets = readRichAssets(content.hasMermaid, content.hasMath)
            MdRichRenderInjector.wrapHtml(
                MdRichRenderInjector.build(
                    options = MdRichRenderInjector.Options(
                        mermaidTheme = if (themeProvider().dark) {
                            MdRichRenderInjector.MermaidThemeDark
                        } else {
                            MdRichRenderInjector.MermaidThemeDefault
                        }
                    ),
                    assets = assets,
                    needsMermaid = content.hasMermaid,
                    needsMath = content.hasMath
                )
            )
        }.getOrElse { error ->
            // 注入失败不阻断阅读：正文仍以 BASIC_HTML 呈现（mermaid/公式保留为代码块）
            AppLog.putDebug("text rich injection build failed: ${error.localizedMessage}", error)
            ""
        }
        return TextRichHtmlComposer.compose(
            bodyHtml = content.html,
            readerCss = runCatching { readerCssProvider() }.getOrDefault(""),
            richInjectionHtml = injection,
            theme = themeProvider()
        )
    }

    /** 只读需要的资产（无 mermaid 不读 2.4MB 运行时）。 */
    private fun readRichAssets(needsMermaid: Boolean, needsMath: Boolean): MdRichRenderInjector.Assets {
        return MdRichRenderInjector.Assets(
            mermaidJs = if (needsMermaid) assetReader.read(MdRichRenderInjector.MermaidAsset) else null,
            katexJs = if (needsMath) assetReader.read(MdRichRenderInjector.KatexAsset) else null,
            katexCss = if (needsMath) assetReader.read(MdRichRenderInjector.KatexCssAsset) else null,
            highlightJs = assetReader.read(MdRichRenderInjector.HighlightAsset),
            highlightCss = assetReader.read(
                if (themeProvider().dark) {
                    MdRichRenderInjector.HighlightDarkCssAsset
                } else {
                    MdRichRenderInjector.HighlightLightCssAsset
                }
            )
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        return WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = false
                databaseEnabled = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                loadsImagesAutomatically = true
                blockNetworkImage = false
                // 本地书需读取同目录图片；在线正文不开（最小权限）
                allowFileAccess = allowLocalFile
                allowContentAccess = false
                textZoom = 100
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                if (allowLocalFile && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = true
                }
            }
            setBackgroundColor(Color.TRANSPARENT)
        }.also { webView = it }
    }

    private inner class RichClient(
        private val token: RenderToken,
        private val config: RenderConfig
    ) : WebViewClient() {

        /** 文本渲染文档不引用压缩包资源；图片走 data:/本地 file: ⇒ 不回源。 */
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? = null

        override fun onPageFinished(view: WebView, url: String?) {
            if (token.generation != generation) return
            rendered = true
            val chapterIndex = currentChapter?.chapterIndex ?: return
            // 首帧先回报（富渲染可能尚未完成），随后轮询注入完成信号再重测
            view.evaluateJavascript(ContentHeightScript) { raw ->
                if (token.generation != generation) return@evaluateJavascript
                contentHeight = raw?.trim()?.toDoubleOrNull()?.toInt() ?: 0
                pageCount = EpubWebDirectPageMath.pageCount(contentHeight, config.pageHeightPx)
                host?.onRendered(token, chapterIndex, pageCount)
                pollRichRenderDone(token, chapterIndex, config)
            }
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            // 渲染进程崩溃：销毁并上报「引擎不可用」，由宿主按降级链回落 canvas（阶段 2.8/S10）。
            if (webView === view) webView = null
            runCatching { view?.destroy() }
            AppLog.putDebug(
                "text rich render process gone: crashed=${detail?.didCrash()}, chapter=${currentChapter?.chapterIndex}"
            )
            if (token.generation == generation) {
                host?.onError(token, RenderFailure.ENGINE_UNAVAILABLE)
            }
            return true
        }
    }

    /**
     * 轮询富渲染完成信号（`window.__legadoMdRichRender.done`）。
     * 注入器内部已有超时（[MdRichRenderInjector.DefaultTimeoutMillis]）⇒ 此处只做有界退避重试，
     * 不会永久等待；重试上限后仍**照常回报一次重测**（避免分页永久挂起）。
     */
    private fun pollRichRenderDone(token: RenderToken, chapterIndex: Int, config: RenderConfig, attempt: Int = 0) {
        if (disposed || token.generation != generation) return
        if (attempt > MaxPollAttempts) {
            remeasure(token)
            return
        }
        handler.postDelayed({
            if (disposed || token.generation != generation) return@postDelayed
            val view = webView ?: return@postDelayed
            view.evaluateJavascript(RichDoneScript) { raw ->
                if (token.generation != generation) return@evaluateJavascript
                val done = raw?.contains("true") == true
                if (done) {
                    AppLog.putDebug("text rich render done: chapter=$chapterIndex, attempt=$attempt")
                    remeasure(token)
                } else {
                    pollRichRenderDone(token, chapterIndex, config, attempt + 1)
                }
            }
        }, PollIntervalMillis)
    }

    private fun runOnUI(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post(action)
    }

    companion object {
        private const val WebViewBlank = "about:blank"
        private const val PollIntervalMillis = 120L
        private const val MaxPollAttempts = 40          // 40 × 120ms ≈ 4.8s（注入器自带 4s 超时）
        private const val ContentHeightScript =
            "(function(){var d=document;var b=d.body;var e=d.documentElement;" +
                "return Math.max(b?b.scrollHeight:0,e?e.scrollHeight:0,0);})()"
        /** 读注入状态：`__legadoMdRichRender.done` 为真即完成（未注入时亦视为完成，直接回报）。 */
        private const val RichDoneScript =
            "(function(){var s=window.${MdRichRenderInjector.StatusGlobal};" +
                "return (!s||s.done===true)?'true':'false';})()"
    }
}