package io.legado.app.ui.book.read.epub

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
import io.legado.app.model.localBook.epubcore.archive.EpubArchive
import io.legado.app.model.localBook.epubcore.direct.EpubDirectPublisherCss
import io.legado.app.model.localBook.epubcore.web.EpubArchiveWebResponse
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
import java.io.Closeable

/**
 * 可见 WebView 直渲染后端（epub-md-rich-rendering 阶段 2.3）。
 *
 * **定位**：出版方控制型内容（PUBLISHER_STYLED / FIXED / MEDIA / INTERACTIVE）的**保真天花板**——
 * 把出版方 HTML/CSS 交给 Chromium 原样渲染，不注入阅读器主题（不覆盖字体/颜色/行高/盒模型）。
 * 可重排纯文字**不走本后端**（走 canvas 快路径，AD-15）。
 *
 * **与 archive v15 的关系**：取能力不取结构——archive 的可见 WebView 层是 8530 行巨文件；
 * 本实现按职责拆分：回源复用 [EpubArchiveWebResponse]（单源）、分页数学复用 [EpubWebDirectPageMath]、
 * 出版方 CSS 内联复用 [EpubDirectPublisherCss]，本类只负责 **View 生命周期 + 契约投射**。
 *
 * **线程约束**（项目 landmine）：所有 WebView 操作必须在 UI 线程；[close]/[dispose] 亦然。
 */
class EpubWebDirectBackend(
    private val context: Context,
    private val archive: EpubArchive,
    private val chapterHtmlProvider: (ChapterRef) -> String?,
    private val container: (View) -> Unit = {}
) : ReaderRenderBackend, Closeable {

    override val capabilities: BackendCapabilities = BackendCapabilities.DirectWeb

    private val handler = Handler(Looper.getMainLooper())
    private val archiveWebResponse = EpubArchiveWebResponse(archive)

    private var webView: WebView? = null
    private var host: RenderHost? = null
    private var generation: Long = 0L
    private var currentChapter: ChapterRef? = null
    private var currentConfig: RenderConfig? = null
    private var pageCount: Int = 1
    private var disposed = false

    /** 供宿主挂载到容器（可见 WebView 后端必须由宿主提供显示位置）。 */
    fun view(): View = ensureWebView()

    /** 当前页数（渲染完成后有效）。 */
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
        val html = chapterHtmlProvider(chapter)
        if (html.isNullOrBlank()) {
            host?.onError(token, RenderFailure.MALFORMED_CONTENT)
            return token
        }
        runOnUI {
            if (disposed || token.generation != generation) return@runOnUI
            runCatching {
                val view = ensureWebView()
                container(view)
                // 出版方 CSS 内联（link→style / @import 递归），与测量链路同源。
                val inlined = EpubDirectPublisherCss.inline(
                    sourceHtml = html,
                    chapterHref = chapter.chapterHref,
                    resourceHost = EpubArchiveWebResponse.Host,
                    load = { path, maxBytes -> runCatching { archive.readBytes(path, maxBytes) }.getOrNull() }
                )
                view.webViewClient = DirectClient(token, config)
                view.loadDataWithBaseURL(
                    archiveWebResponse.baseUrl(chapter.chapterHref),
                    wrapPage(inlined, config),
                    "text/html",
                    "UTF-8",
                    null
                )
            }.onFailure { error ->
                AppLog.putDebug(
                    "EPUB direct web render start failed: chapter=${chapter.chapterIndex}, ${error.localizedMessage}",
                    error
                )
                host?.onError(token, RenderFailure.LAYOUT_ERROR, error)
            }
        }
        return token
    }

    override fun remeasure(token: RenderToken) {
        if (token.generation != generation) return
        runOnUI {
            if (disposed || token.generation != generation) return@runOnUI
            val view = webView ?: return@runOnUI
            val viewport = currentConfig?.pageHeightPx ?: return@runOnUI
            view.evaluateJavascript(ContentHeightScript) { raw ->
                if (token.generation != generation) return@evaluateJavascript
                val contentHeight = raw?.trim()?.toDoubleOrNull()?.toInt() ?: 0
                pageCount = EpubWebDirectPageMath.pageCount(contentHeight, viewport)
                val chapterIndex = currentChapter?.chapterIndex ?: return@evaluateJavascript
                host?.onReMeasured(token, chapterIndex, pageCount)
            }
        }
    }

    /**
     * 固定/媒体/交互态**不提供字符偏移**（AD-14：不做假换算）⇒ 返回 null。
     */
    override fun textToPosition(range: TextRange): ReadingPosition? = null

    override fun positionToText(position: ReadingPosition): TextRange? = null

    override fun exportPosition(): ReadingPosition {
        val view = webView
        val chapterIndex = currentChapter?.chapterIndex ?: 0
        val viewport = currentConfig?.pageHeightPx ?: 0
        val scrollY = view?.scrollY ?: 0
        return EpubWebDirectPageMath.positionFor(chapterIndex, scrollY, viewport, pageCount)
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

    /** 固定版式无「朗读同源文本模型」保证 ⇒ 不产出锚点（阶段 2.7 统一后再补）。 */
    override fun readAloudAnchors(): List<ReadAloudAnchor> = emptyList()

    override fun invalidate(token: RenderToken) {
        if (token.generation == generation) {
            generation++
        }
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
                allowFileAccess = false
                allowContentAccess = false
                textZoom = 100
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
            }
            setBackgroundColor(Color.TRANSPARENT)
        }.also { webView = it }
    }

    /** 只给出版方 DOM 画布尺寸，不注入阅读器字体/颜色/行高（保真模式）。 */
    private fun wrapPage(html: String, config: RenderConfig): String {
        val pageStyle = """
            <style id="legado-direct-web-frame">
              html, body { margin: 0 !important; padding: 0 !important; }
              html { width: ${config.pageWidthPx}px; }
              img, svg, video, canvas { max-width: 100%; }
            </style>
        """.trimIndent()
        val headClose = Regex("</head\\s*>", RegexOption.IGNORE_CASE)
        return when {
            headClose.containsMatchIn(html) -> html.replace(headClose, "$pageStyle</head>")
            Regex("<html[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(html) ->
                html.replaceFirst(
                    Regex("<html([^>]*)>", RegexOption.IGNORE_CASE),
                    "<html$1><head>$pageStyle</head>"
                )
            else -> "<html><head>$pageStyle</head><body>$html</body></html>"
        }
    }

    private inner class DirectClient(
        private val token: RenderToken,
        private val config: RenderConfig
    ) : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? = archiveWebResponse.responseFor(request?.url?.toString())

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
            return archiveWebResponse.responseFor(url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (token.generation != generation) return
            val chapterIndex = currentChapter?.chapterIndex ?: return
            view.evaluateJavascript(ContentHeightScript) { raw ->
                if (token.generation != generation) return@evaluateJavascript
                val contentHeight = raw?.trim()?.toDoubleOrNull()?.toInt() ?: 0
                pageCount = EpubWebDirectPageMath.pageCount(contentHeight, config.pageHeightPx)
                host?.onRendered(token, chapterIndex, pageCount)
            }
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            // 渲染进程崩溃：销毁并上报「引擎不可用」，由宿主按降级链回落 canvas（阶段 2.8）。
            if (webView === view) {
                webView = null
            }
            runCatching { view?.destroy() }
            AppLog.putDebug(
                "EPUB direct web render process gone: crashed=${detail?.didCrash()}, chapter=${currentChapter?.chapterIndex}"
            )
            if (token.generation == generation) {
                host?.onError(token, RenderFailure.ENGINE_UNAVAILABLE)
            }
            return true
        }
    }

    private fun runOnUI(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            handler.post(action)
        }
    }

    companion object {
        private const val WebViewBlank = "about:blank"

        /** 读取内容高（px），用于固定版式分页；两侧取整避免 NaN。 */
        private const val ContentHeightScript =
            "(function(){var d=document;var b=d.body;var e=d.documentElement;" +
                "return Math.max(b?b.scrollHeight:0,e?e.scrollHeight:0,0);})()"
    }
}