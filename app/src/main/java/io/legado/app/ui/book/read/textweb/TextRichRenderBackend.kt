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
    private var lastReadyRaw: String? = null
    private var lastLoggedStatus: String? = null

    /** 待注入的厂商脚本（文档就绪后逐条**顶层**执行）。 */
    private var pendingVendors: List<String> = emptyList()

    /** 待注入的运行时脚本（在厂商脚本之后执行；null = 无脚本或已注入）。 */
    private var pendingRuntime: String? = null
    private var disposed = false

    /** 供宿主挂载（可见 WebView 必须由宿主给出显示位置）。 */
    fun view(): View = ensureWebView()

    fun currentPageCount(): Int = pageCount

    /** 当前滚动边界状态（宿主据此决定"章内翻屏"还是"切上/下一章"）。 */
    fun edge(): TextRichScrollPolicy.Edge = TextRichScrollPolicy.edge(
        scrollY = webView?.scrollY ?: 0,
        contentHeight = contentHeight,
        viewportHeight = currentConfig?.pageHeightPx ?: 0
    )

    /** 章内滚动一屏（`dy` 为正向下）。 */
    fun scrollByPixels(dy: Int) {
        runOnUI { webView?.scrollBy(0, dy) }
    }

    /** 回到章首（进入新章时用）。 */
    fun scrollToTop() {
        runOnUI { webView?.scrollTo(0, 0) }
    }

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
        AppLog.putDebug("text rich render invoked: chapter=${chapter.chapterIndex}, token=$generation, disposed=$disposed")
        val content = chapterProvider(chapter)
        if (content == null || content.html.isBlank()) {
            AppLog.putDebug("text rich render empty content: chapter=${chapter.chapterIndex}, contentNull=${content == null}")
            host?.onError(token, RenderFailure.EMPTY_RESULT)
            return token
        }
        runOnUI {
            if (disposed || token.generation != generation) {
                AppLog.putDebug("text rich render skipped: disposed=$disposed, token=${token.generation}, gen=$generation")
                return@runOnUI
            }
            runCatching {
                val view = ensureWebView()
                container(view)
                view.webViewClient = RichClient(token, config)
                val document = composeDocument(content, config)
                pendingVendors = document.vendors
                pendingRuntime = document.runtime.ifBlank { null }
                view.loadDataWithBaseURL(baseUrl, document.html, "text/html", "UTF-8", null)
                AppLog.putDebug(
                    "text rich render start: chapter=${chapter.chapterIndex}, generation=$generation, " +
                            "vendors=${document.vendors.size}, runtimeLen=${document.runtime.length}"
                )
                // 就绪上报走**轮询**而非 onPageFinished：`loadDataWithBaseURL` 场景下 onPageFinished
                // 不保证回调；轮询 readyState + 注入状态是更可靠的就绪判据（并兼做脚本注入时机）。
                pollReady(token, chapter.chapterIndex, config)
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

    /** 组装文档：读阅读样式 → 构建富渲染注入（厂商脚本与运行时脚本**分开**）→ 合并主题。 */
    private fun composeDocument(content: RichChapter, config: RenderConfig): Document {
        val options = MdRichRenderInjector.Options(
            mermaidTheme = if (themeProvider().dark) {
                MdRichRenderInjector.MermaidThemeDark
            } else {
                MdRichRenderInjector.MermaidThemeDefault
            }
        )
        val assets = readRichAssets(content.hasMermaid, content.hasMath)
        val wantMermaid = options.renderMermaid && content.hasMermaid && !assets.mermaidJs.isNullOrBlank()
        val wantMath = options.renderMath && content.hasMath && !assets.katexJs.isNullOrBlank()
        val wantHighlight = options.highlightCode && !assets.highlightJs.isNullOrBlank()
        val any = wantMermaid || wantMath || wantHighlight
        val css = buildString {
            if (wantMath) append(assets.katexCss.orEmpty())
            if (wantHighlight) append(assets.highlightCss.orEmpty())
        }
        return Document(
            html = TextRichHtmlComposer.compose(
                bodyHtml = content.html,
                readerCss = runCatching { readerCssProvider() }.getOrDefault(""),
                richInjectionHtml = if (css.isNotBlank()) {
                    MdRichRenderInjector.wrapHtml(MdRichRenderInjector.Injection(css = css, script = ""))
                } else {
                    ""
                },
                theme = themeProvider()
            ),
            // ⚠️ 厂商 JS **必须各自作为顶层脚本**执行（其 `var` 依赖全局作用域，包进函数即失效）；
            // 内联进 HTML 也不行（会被 `<!--`/`<script` 序列拖入脚本双重转义态）。故二者都经
            // evaluateJavascript 分流注入：厂商脚本逐条顶层执行，运行时脚本再单独执行。
            vendors = MdRichRenderInjector.vendorScripts(wantMermaid, wantMath, wantHighlight, assets),
            runtime = if (any) {
                MdRichRenderInjector.runtimeScript(options, wantMermaid, wantMath, wantHighlight)
            } else {
                ""
            }
        )
    }

    /** 组装结果：文档 HTML + 厂商脚本（各顶层执行）+ 运行时脚本。 */
    private data class Document(val html: String, val vendors: List<String>, val runtime: String)

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
            // 仅留痕（完成上报由 pollReady 负责，不依赖本回调）
            AppLog.putDebug("text rich onPageFinished: chapter=${currentChapter?.chapterIndex}")
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
    private fun pollReady(token: RenderToken, chapterIndex: Int, config: RenderConfig, attempt: Int = 0) {
        if (disposed || token.generation != generation) return
        if (attempt > MaxPollAttempts) {
            // 超时也照常回报一次，避免分页永久挂起（内容已可见，只是富渲染未按时完成）
            AppLog.putDebug("text rich ready timeout: chapter=$chapterIndex, attempt=$attempt, lastRaw=$lastReadyRaw")
            remeasure(token)
            host?.onRendered(token, chapterIndex, pageCount)
            return
        }
        handler.postDelayed({
            if (disposed || token.generation != generation) return@postDelayed
            val view = webView ?: return@postDelayed
            view.evaluateJavascript(ReadyScript) { raw ->
                if (token.generation != generation) return@evaluateJavascript
                // readyState|status|height|mermaidNodes|codeNodes
                val parts = raw?.trim()?.trim('"')?.split('|').orEmpty()
                lastReadyRaw = raw
                if (parts.size < 5) {
                    pollReady(token, chapterIndex, config, attempt + 1)
                    return@evaluateJavascript
                }
                val complete = parts[0] == "complete"
                val status = parts[1]
                if (status != lastLoggedStatus) {
                    lastLoggedStatus = status
                    AppLog.putDebug(
                        "text rich status: chapter=$chapterIndex, attempt=$attempt, status=$status, " +
                                "ready=${parts[0]}, h=${parts[2]}, nodes=${parts[3]}/${parts[4]}, " +
                                "mermaid=${parts.getOrNull(5)}, katex=${parts.getOrNull(6)}, hljs=${parts.getOrNull(7)}"
                    )
                }
                // 文档就绪且尚未注入 ⇒ 此刻注入：厂商脚本**逐条顶层执行**，再执行运行时脚本
                if (complete && status == StatusAbsent && (pendingVendors.isNotEmpty() || pendingRuntime != null)) {
                    val vendors = pendingVendors
                    val runtime = pendingRuntime
                    pendingVendors = emptyList()
                    pendingRuntime = null
                    AppLog.putDebug(
                        "text rich inject: chapter=$chapterIndex, vendors=${vendors.size}, " +
                                "runtimeLen=${runtime?.length ?: 0}"
                    )
                    vendors.forEach { view.evaluateJavascript(it, null) }
                    // 运行时脚本是自建 IIFE，可安全包 try/catch 记录注入期异常
                    runtime?.let {
                        view.evaluateJavascript(
                            "try{" + it + "}catch(e){window.${InjectErrorGlobal}=String((e&&e.stack)||e);}",
                            null
                        )
                    }
                    pollReady(token, chapterIndex, config, attempt + 1)
                    return@evaluateJavascript
                }
                // 未注入过脚本时 `none` 即"纯 HTML 章节"，视为已就绪；注入过则必须等 done
                val settled = (status == StatusAbsent && pendingVendors.isEmpty() && pendingRuntime == null) ||
                    status.startsWith(StatusDonePrefix)
                if (!complete || !settled) {
                    pollReady(token, chapterIndex, config, attempt + 1)
                    return@evaluateJavascript
                }
                val height = parts[2].toIntOrNull() ?: 0
                contentHeight = height
                pageCount = EpubWebDirectPageMath.pageCount(height, config.pageHeightPx)
                // 诊断留痕（L2 证据）：status=done|mermaid数|公式数|代码块数|错误
                AppLog.putDebug(
                    "text rich ready: chapter=$chapterIndex, attempt=$attempt, status=$status, " +
                            "mermaidNodes=${parts[3]}, codeNodes=${parts[4]}, height=$height"
                )
                host?.onRendered(token, chapterIndex, pageCount)
                host?.onReMeasured(token, chapterIndex, pageCount)
            }
        }, PollIntervalMillis)
    }

    private fun runOnUI(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post(action)
    }

    companion object {
        private const val WebViewBlank = "about:blank"
        private const val PollIntervalMillis = 120L
        private const val MaxPollAttempts = 200         // 200 × 120ms ≈ 24s（厂商 JS 首次解析可能偏慢）
        private const val ContentHeightScript =
            "(function(){var d=document;var b=d.body;var e=d.documentElement;" +
                "return Math.max(b?b.scrollHeight:0,e?e.scrollHeight:0,0);})()"
        /** 未注入富渲染运行时（纯 HTML 章节）。 */
        private const val StatusAbsent = "none"

        /** 注入期异常全局变量（宿主注入时包 try/catch 写入，供就绪探针读取）。 */
        private const val InjectErrorGlobal = "__legadoMdRichErr"

        /** 状态前缀：`1,` = 注入完成（注：状态内层用 `,` 分隔，**不得**用外层分隔符 `|`，否则解析必然错位）。 */
        private const val StatusDonePrefix = "1,"

        /**
         * 就绪探针：`readyState|status|内容高|pre.mermaid 节点数|pre code 节点数`。
         *
         * - `status` = `done,mermaid数,公式数,代码块数,错误`（[MdRichRenderInjector.StatusGlobal] 缺失时为 [StatusAbsent]）；
         * - 后两项是 **DOM 侧事实**（选区计数），用来区分"注入脚本跑了但文档里没有目标节点"
         *   与"注入脚本压根没跑"——是 L2 判定的关键证据。
         *
         * ⚠️ 外层字段用 `|`、内层状态用 `,` 是**刻意区分**：早先两者都用 `|`，导致宿主切分错位、
         * 就绪判定恒为假（真机症状：图表已渲染却始终超时）。
         */
        private const val ReadyScript =
            "(function(){var d=document,s=window.${MdRichRenderInjector.StatusGlobal},e=window.$InjectErrorGlobal;" +
                "var st;" +
                "if(e){st='err:'+String(e).slice(0,160);}" +
                "else if(!s){st='${StatusAbsent}';}" +
                "else{st=(s.done===true?'1':'0')+','+s.mermaid+','+s.math+','+s.code+','+" +
                "(s.error?String(s.error).slice(0,60):'');}" +
                "var q=function(sel){try{return document.querySelectorAll(sel).length}catch(x){return -1}};" +
                "var t=function(n){try{return typeof window[n]}catch(x){return '?'}};" +
                "var h=Math.max((d.body?d.body.scrollHeight:0),(d.documentElement?d.documentElement.scrollHeight:0));" +
                "return d.readyState+'|'+st+'|'+h+'|'+q('pre.mermaid')+'|'+q('pre code')+'|'+" +
                "t('mermaid')+'|'+t('katex')+'|'+t('hljs');})()"
    }
}