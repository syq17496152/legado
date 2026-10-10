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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.legado.app.constant.AppLog
import io.legado.app.help.md.MdRichRenderInjector
import io.legado.app.help.md.ReaderTemplateSandboxDocument
import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplate
import io.legado.app.model.localBook.epubcore.template.ReaderTemplateHostDocument
import io.legado.app.model.localBook.epubcore.template.ReaderTemplateHostEventCodec
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
import java.io.ByteArrayInputStream
import java.io.Closeable

/**
 * 阅读页**模板渲染后端**（epub-md-rich-rendering 阶段 4.14 收口）。
 *
 * 与 [TextRichRenderBackend] 的关系：**同为 WebView 承载，但文档结构完全不同**——
 * | | 文本富渲染后端 | 本后端 |
 * |---|---|---|
 * | 文档 | 直接是正文 HTML（`md-reader.css` + 注入器） | **宿主空壳** + `ReaderTemplateHost.init` 建沙箱 iframe |
 * | 作者代码 | 无 | 模板 HTML/CSS/JS 在**沙箱内**（`allow-scripts`，不同源） |
 * | 与页面通信 | `evaluateJavascript` 读/写同一文档 | **只能消息通道**：Kotlin↔宿主文档走 `WebMessagePort`，宿主↔沙箱走 `window.postMessage` |
 * | 翻转页 | 宿主直接 `scrollBy` | 下 `goto-page` 指令给沙箱（跨源改不了它的滚动） |
 *
 * **为什么必须走消息通道**：沙箱 iframe 不带同源标记 ⇒ 宿主既不能 `evaluateJavascript` 也读不到
 * 它的 DOM，"就绪/页数/渲染计数"只能由沙箱回发（`stable` / `renderState`）。
 *
 * 契约遵守：`textToPosition`/`positionToText` 返回 null（模板排版由作者 CSS 决定，
 * 字符偏移无可靠映射 ⇒ **不做假换算**，AD-14）；位置以 [ReadingPosition.Page] 表达。
 *
 * 降级：模板脚本异常/超时/引擎不可用 ⇒ [RenderFailure.ENGINE_UNAVAILABLE] 交宿主回落既有
 * canvas 渲染（用户仍能正常读书，而不是停在白屏）。
 */
class TemplateRenderBackend(
    private val context: Context,
    private val templateProvider: () -> EpubReaderTemplate?,
    private val chapterProvider: (ChapterRef) -> Chapter?,
    private val valuesProvider: () -> ReaderValues,
    private val baseUrl: String = ReaderTemplateHostDocument.BaseUrl,
    private val container: (View) -> Unit = {}
) : ReaderRenderBackend, Closeable {

    /** 章节正文（`hasMermaid`/`hasMath` 为构建期标注 ⇒ 决定是否把厂商运行时放进沙箱）。 */
    data class Chapter(val html: String, val hasMermaid: Boolean, val hasMath: Boolean)

    /** 模板槽位真值（`data-reader-field` + 内容感知变量）。 */
    data class ReaderValues(
        val dark: Boolean,
        val bookName: String,
        val chapterTitle: String,
        val progressPercent: Int,
        val hour: Int,
        /** 装饰强度字面量（`none|light|medium|strong`，4.8d；见 `ReaderTemplateDecorationPolicy`）。 */
        val decoration: String = DecorationMedium,
        /** 装饰动效是否允许（专注模式 / reduce-motion ⇒ false ⇒ 沙箱 `<html data-reader-motion="paused">`）。 */
        val decorationMotion: Boolean = true,
        /**
         * 稳定随机种子（4.15 C4）：由书名 + 章节名稳定哈希得出
         * （`ReaderTemplateSeed.of`），沙箱注入 `--rp-seed` / `data-rp-seed`。
         * 传 0 表示"本次不注入"（作者读到 0 也不会崩）。
         */
        val seed: Int = 0
    ) {
        val themeId: String get() = if (dark) ThemeNight else ThemeDay
    }

    override val capabilities: BackendCapabilities = BackendCapabilities.DirectWeb

    private val handler = Handler(Looper.getMainLooper())
    private val assetReader = AssetTextReader(context)

    private var webView: WebView? = null
    private var host: RenderHost? = null
    private var generation: Long = 0L
    private var currentChapter: ChapterRef? = null
    private var pageCount: Int = 1
    private var pageIndex: Int = 0
    private var expectedToken: String = ""
    private var reportedFirstPage: Boolean = false
    private var richReported: Boolean = false
    private var currentNeedsMermaid: Boolean = false
    private var currentNeedsMath: Boolean = false
    private var disposed = false

    fun view(): View = ensureWebView()

    fun currentPageCount(): Int = pageCount

    /** 供宿主调用的初始页索引（用于位置恢复）。 */
    var initialPageIndex: Int = 0

    fun edge(): TextRichScrollPolicy.Edge {
        if (pageCount <= 1) return TextRichScrollPolicy.Edge(atTop = true, atBottom = true)
        return TextRichScrollPolicy.Edge(atTop = pageIndex <= 0, atBottom = pageIndex >= pageCount - 1)
    }

    /** 下一屏/上一屏（沙箱内执行；宿主只下指令）。 */
    fun pageNext() = gotoPage(pageIndex + 1)

    fun pagePrev() = gotoPage(pageIndex - 1)

    /**
     * 动效运行时闸门（4.15 C6）：拖动中 / 离页 / 翻页结算期间停装饰动效并回静态帧。
     *
     * 为什么必须由宿主下令：沙箱**看不到**宿主的手势与 Activity 生命周期（跨源、也不共享事件），
     * 只有宿主知道"用户现在正在拖"。停动效的目的有二：拖动跟手（不抢主线程）+ 省电降温。
     */
    fun setMotionPaused(paused: Boolean) {
        // 渲染就绪前沙箱还没建帧/还没结算 ⇒ 下指令无意义（也不该在 idle 期刷消息）
        if (!reportedFirstPage) return
        runOnUI {
            webView?.evaluateJavascript(
                ReaderTemplateHostDocument.postScript(
                    "set-motion",
                    if (paused) """{"paused":true}""" else """{"paused":false}"""
                ),
                null
            )
        }
    }

    fun gotoPage(target: Int) {
        val clamped = target.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        runOnUI {
            val view = webView ?: return@runOnUI
            view.evaluateJavascript(
                ReaderTemplateHostDocument.postScript(
                    "goto-page",
                    """{"pageIndex":$clamped}"""
                ),
                null
            )
        }
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
        pageCount = 1
        pageIndex = (position as? ReadingPosition.Page)?.pageIndex ?: initialPageIndex
        reportedFirstPage = false
        richReported = false
        val template = templateProvider()
        val content = chapterProvider(chapter)
        if (template == null || content == null || content.html.isBlank()) {
            AppLog.putDebugWithTag(
                AppLog.TAG_READER_TEMPLATE,
                "render skip: chapter=${chapter.chapterIndex}, templateNull=${template == null}, contentNull=${content == null}"
            )
            host?.onError(token, RenderFailure.EMPTY_RESULT)
            return token
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // 旧内核没有消息通道 ⇒ 无法安全承载模板（不用 addJavascriptInterface 兜底）
            AppLog.putDebugWithTag(
                AppLog.TAG_READER_TEMPLATE,
                "message listener unsupported ⇒ degrade: chapter=${chapter.chapterIndex}"
            )
            host?.onError(token, RenderFailure.ENGINE_UNAVAILABLE)
            return token
        }
        currentNeedsMermaid = content.hasMermaid
        currentNeedsMath = content.hasMath
        runOnUI {
            if (disposed || token.generation != generation) return@runOnUI
            runCatching {
                val view = ensureWebView()
                container(view)
                view.webViewClient = TemplateClient(token)
                view.loadDataWithBaseURL(
                    baseUrl,
                    ReaderTemplateHostDocument.build(hostJs()),
                    "text/html",
                    "UTF-8",
                    null
                )
                AppLog.putDebugWithTag(
                    AppLog.TAG_READER_TEMPLATE,
                    "host load: chapter=${chapter.chapterIndex}, template=${template.id}, " +
                            "mermaid=$currentNeedsMermaid, math=$currentNeedsMath"
                )
                pollHostReady(token, template, content, 0)
            }.onFailure { error ->
                AppLog.putDebugWithTag(
                    AppLog.TAG_READER_TEMPLATE,
                    "host load failed: ${error.localizedMessage}",
                    error
                )
                host?.onError(token, RenderFailure.LAYOUT_ERROR, error)
            }
        }
        return token
    }

    /** 模板页数由沙箱 `stable` 回发；宿主只做一次重测请求（内容已由沙箱自管）。 */
    override fun remeasure(token: RenderToken) {
        if (token.generation != generation) return
        runOnUI {
            if (disposed || token.generation != generation) return@runOnUI
            webView?.evaluateJavascript(ReaderTemplateHostDocument.postScript("remeasure"), null)
        }
    }

    /** 模板排版由作者 CSS 决定 ⇒ 无可靠字符偏移映射，不假换算（AD-14）。 */
    override fun textToPosition(range: TextRange): ReadingPosition? = null

    override fun positionToText(position: ReadingPosition): TextRange? = null

    override fun exportPosition(): ReadingPosition = ReadingPosition.Page(
        chapterIndex = currentChapter?.chapterIndex ?: 0,
        pageIndex = pageIndex.coerceAtLeast(0)
    )

    override fun importPosition(position: ReadingPosition): Boolean {
        if (position !is ReadingPosition.Page) return false
        gotoPage(position.pageIndex)
        return true
    }

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
            val view = webView
            if (view != null) {
                runCatching {
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                        WebViewCompat.removeWebMessageListener(view, ReaderTemplateHostDocument.WebMessageBridgeObjectName)
                    }
                }
                runCatching { view.evaluateJavascript(ReaderTemplateHostDocument.destroyScript(), null) }
                runCatching { view.stopLoading() }
                runCatching { view.loadUrl(WebViewBlank) }
                runCatching { view.destroy() }
            }
            webView = null
        }
    }

    // === 内部：宿主文档就绪后下发 init ===

    private fun hostJs(): String = assetReader.read(HostJsAsset).orEmpty()

    private fun pollHostReady(
        token: RenderToken,
        template: EpubReaderTemplate,
        content: Chapter,
        attempt: Int
    ) {
        if (disposed || token.generation != generation) return
        if (attempt > MaxHostPollAttempts) {
            AppLog.putDebugWithTag(
                AppLog.TAG_READER_TEMPLATE,
                "host ready timeout: chapter=${currentChapter?.chapterIndex}"
            )
            host?.onError(token, RenderFailure.TIMEOUT)
            return
        }
        handler.postDelayed({
            if (disposed || token.generation != generation) return@postDelayed
            val view = webView ?: return@postDelayed
            view.evaluateJavascript(HostReadyScript) { raw ->
                if (token.generation != generation) return@evaluateJavascript
                if (raw?.contains(ReadyComplete) == true) {
                    sendInit(template, content)
                    // ⚠️ 必须先按**宿主文档自身视口**以 px 定尺寸，再发 init：真机实测该 WebView
                    // （Compose `AndroidView` 承载）里 `100vh` 与百分比高度都塌成 0px（样式表已解析、
                    // `position:absolute` 生效，仅高度为 0）⇒ 沙箱 iframe 高 0 ⇒ 视口 0 ⇒ 页框算成负数
                    // ⇒ "预览空白且页数恒 1"。px 尺寸来自文档自身的 clientWidth/Height（CSS px，无需换算密度）。
                    sizeSandboxContainer(view)
                } else {
                    pollHostReady(token, template, content, attempt + 1)
                }
            }
        }, HostPollIntervalMillis)
    }

    private fun sendInit(template: EpubReaderTemplate, content: Chapter) {
        val view = webView ?: return
        val values = valuesProvider()
        val srcdoc = ReaderTemplateSandboxDocument.build(sandboxScripts(content))
        val payload = JsonObject().apply {
            addProperty("containerId", ReaderTemplateHostDocument.ContainerId)
            addProperty("srcdoc", srcdoc)
            add("template", templateJson(template))
            add("fields", fieldsJson(values))
            addProperty("bodyHtml", content.html)
            addProperty("themeId", values.themeId)
            // 装饰强度与动效闸门（4.8d）：沙箱 runtime 写到 <html> 上，由 md/template-decoration.css 执行
            addProperty("decoration", values.decoration)
            addProperty("motion", values.decorationMotion)
            // 稳定随机种子（4.15 C4）：同章任何时候同值 ⇒ 装饰"看似随机"但可复现
            addProperty("seed", values.seed)
            // 厂商脚本**按 URL 交给沙箱自行加载**（宿主经 shouldInterceptRequest 从 assets 供给）：
            // ①体量走浏览器流式加载，不经消息体（实测 2.4MB postMessage 会静默不达）；
            // ②不内联 srcdoc（`mermaid.min.js` 含 `<!--`，内联会被 HTML 解析器截断）
            add("vendorUrls", JsonArray().apply { vendorUrls(content).forEach { add(it) } })
            addProperty("pageIndex", pageIndex)
            add("flow", JsonObject().apply {
                addProperty("type", template.type)
                addProperty("pageIndex", pageIndex)
            })
            addProperty("sessionId", generation)
        }
        val script = ReaderTemplateHostDocument.initScript(
            ReaderTemplateHostDocument.escapeForJsLiteral(payload.toString())
        )
        // init 返回一次性 token：用它做 Kotlin 侧权威校验（与宿主 JS 侧粗筛双保险）
        view.evaluateJavascript(script) { raw ->
            expectedToken = raw?.trim()?.trim('"').orEmpty()
            AppLog.putDebugWithTag(
                AppLog.TAG_READER_TEMPLATE,
                "init sent: chapter=${currentChapter?.chapterIndex}, tokenLen=${expectedToken.length}, " +
                        "srcdocLen=${srcdoc.length}, bodyLen=${content.html.length}"
            )
        }
        scheduleReadyTimeout()
    }

    /** 沙箱容器按宿主文档视口以 px 定尺寸（见 `pollHostReady` 内的铁证注释）。 */
    private fun sizeSandboxContainer(view: WebView) {
        val script = "(function(){var f=document.getElementById('" +
            ReaderTemplateHostDocument.ContainerId + "');if(!f)return 'no-container';" +
            "f.style.position='absolute';f.style.left='0';f.style.top='0';" +
            "f.style.width=document.documentElement.clientWidth+'px';" +
            "f.style.height=document.documentElement.clientHeight+'px';return 'sized';})()"
        view.evaluateJavascript(script) { result ->
            AppLog.putDebugWithTag(
                AppLog.TAG_READER_TEMPLATE,
                "sandbox container sized: $result"
            )
        }
    }

    private fun scheduleReadyTimeout() {
        val token = RenderToken(generation)
        handler.postDelayed({
            if (disposed || token.generation != generation) return@postDelayed
            if (reportedFirstPage) return@postDelayed
            AppLog.putDebugWithTag(
                AppLog.TAG_READER_TEMPLATE,
                "sandbox stable timeout: chapter=${currentChapter?.chapterIndex}"
            )
            host?.onError(token, RenderFailure.TIMEOUT)
        }, ReadyTimeoutMillis)
    }

    private fun sandboxScripts(content: Chapter): ReaderTemplateSandboxDocument.Scripts {
        val options = MdRichRenderInjector.Options(
            mermaidTheme = if (valuesProvider().dark) {
                MdRichRenderInjector.MermaidThemeDark
            } else {
                MdRichRenderInjector.MermaidThemeDefault
            }
        )
        val mermaidJs = if (content.hasMermaid) assetReader.read(MdRichRenderInjector.MermaidAsset) else null
        val katexJs = if (content.hasMath) assetReader.read(MdRichRenderInjector.KatexAsset) else null
        val katexCss = if (content.hasMath) assetReader.read(MdRichRenderInjector.KatexCssAsset) else null
        val highlightJs = assetReader.read(MdRichRenderInjector.HighlightAsset)
        val highlightCss = assetReader.read(
            if (valuesProvider().dark) {
                MdRichRenderInjector.HighlightDarkCssAsset
            } else {
                MdRichRenderInjector.HighlightLightCssAsset
            }
        )
        val wantMermaid = options.renderMermaid && content.hasMermaid && !mermaidJs.isNullOrBlank()
        val wantMath = options.renderMath && content.hasMath && !katexJs.isNullOrBlank()
        val wantHighlight = options.highlightCode && !highlightJs.isNullOrBlank()
        val any = wantMermaid || wantMath || wantHighlight
        val css = buildString {
            // 正文排版基线（模板 CSS 是外观层，md-reader.css 提供正文元素基线）
            append(assetReader.read(MdRichRenderInjector.MdReaderCssAsset).orEmpty())
            // 装饰层基线（4.8d）：按 <html data-rp-decoration> / data-reader-motion 施加强度与动效闸门
            append(assetReader.read(MdRichRenderInjector.TemplateDecorationCssAsset).orEmpty())
            if (wantMath) append(katexCss.orEmpty())
            if (wantHighlight) append(highlightCss.orEmpty())
        }
        return ReaderTemplateSandboxDocument.Scripts(
            flowJs = assetReader.read(FlowJsAsset),
            runtimeJs = assetReader.read(RuntimeJsAsset),
            injectorRuntimeJs = if (any) {
                MdRichRenderInjector.runtimeScript(options, wantMermaid, wantMath, wantHighlight)
            } else {
                null
            },
            injectorCss = css
        )
    }

    /**
     * 厂商脚本（mermaid / KaTeX / hljs）：以**绝对 URL** 交给沙箱自行 `<script src>` 加载，
     * 本 WebView 的 [TemplateClient.shouldInterceptRequest] 从 assets 供给字节。
     *
     * 两条硬事实共同决定了这个形态：①内联进 srcdoc 会被 `<!--` 拖入 HTML 转义态而截断；
     * ②2.4MB 经 `postMessage` 下发**静默不达**（实测：init 从未到达沙箱）。
     */
    private fun vendorUrls(content: Chapter): List<String> {
        val mermaidReady = content.hasMermaid && !assetReader.read(MdRichRenderInjector.MermaidAsset).isNullOrBlank()
        val katexReady = content.hasMath && !assetReader.read(MdRichRenderInjector.KatexAsset).isNullOrBlank()
        val highlightReady = !assetReader.read(MdRichRenderInjector.HighlightAsset).isNullOrBlank()
        val assets = buildList {
            if (mermaidReady) add(MdRichRenderInjector.MermaidAsset)
            if (katexReady) add(MdRichRenderInjector.KatexAsset)
            if (highlightReady) add(MdRichRenderInjector.HighlightAsset)
        }
        return assets.map { baseUrl.trimEnd('/') + "/" + it }
    }

    private fun templateJson(template: EpubReaderTemplate): JsonObject = JsonObject().apply {
        addProperty("id", template.id)
        addProperty("name", template.name)
        addProperty("type", template.type)
        addProperty("firstPageHtml", template.firstPageHtml)
        addProperty("otherPageHtml", template.otherPageHtml)
        addProperty("scrollHtml", template.scrollHtml)
        addProperty("css", template.css)
        addProperty("javascript", template.javascript)
    }

    private fun fieldsJson(values: ReaderValues): JsonObject = JsonObject().apply {
        addProperty("bookName", values.bookName)
        addProperty("chapterTitle", values.chapterTitle)
        addProperty("progress", values.progressPercent)
        addProperty("hour", values.hour)
    }

    // === 内部：沙箱消息 ===

    private fun onSandboxMessage(raw: String?) {
        val result = ReaderTemplateHostEventCodec.parse(raw, expectedToken)
        val rejection = result.rejection
        if (rejection != null) {
            // 拒收只留痕不抛（模板不可信；抛异常会拖垮宿主）
            AppLog.putDebugWithTag(
                AppLog.TAG_READER_TEMPLATE,
                "message rejected: ${rejection.code} / ${rejection.message}"
            )
            return
        }
        when (val event = result.event) {
            is ReaderTemplateHostEventCodec.Event.Stable -> onStable(event)
            is ReaderTemplateHostEventCodec.Event.RenderState -> onRenderState(event.state)
            is ReaderTemplateHostEventCodec.Event.Metrics -> AppLog.putDebugWithTag(
                AppLog.TAG_READER_TEMPLATE,
                "metrics: costMs=${event.costMs}"
            )

            is ReaderTemplateHostEventCodec.Event.SandboxError -> {
                AppLog.putDebugWithTag(
                    AppLog.TAG_READER_TEMPLATE,
                    "sandbox error: ${event.code} / ${event.message.take(120)}"
                )
                onSandboxFailure(event.code)
            }

            is ReaderTemplateHostEventCodec.Event.Ignored -> Unit
            null -> Unit
        }
    }

    private fun onStable(event: ReaderTemplateHostEventCodec.Event.Stable) {
        pageCount = event.pageCount.coerceAtLeast(1)
        pageIndex = event.pageIndex.coerceIn(0, pageCount - 1)
        val token = RenderToken(generation)
        val chapterIndex = currentChapter?.chapterIndex ?: return
        AppLog.putDebugWithTag(
            AppLog.TAG_READER_TEMPLATE,
            "stable: chapter=$chapterIndex, page=${pageIndex + 1}/$pageCount"
        )
        if (!reportedFirstPage) {
            reportedFirstPage = true
            host?.onRendered(token, chapterIndex, pageCount)
        }
        host?.onReMeasured(token, chapterIndex, pageCount)
    }

    private fun onRenderState(state: String) {
        when {
            // 状态串可能带诊断后缀（如 `ready,slots=1,bodyLen=303`）⇒ 用前缀判定
            state.startsWith(StateReady) -> {
                // 含富渲染元素 ⇒ 让沙箱内的注入器跑起来（沙箱跨源 ⇒ 只能下指令）
                val type = when {
                    currentNeedsMermaid -> "inject-mermaid"
                    currentNeedsMath -> "inject-katex"
                    else -> null
                }
                if (type != null && !richReported) {
                    richReported = true
                    webView?.evaluateJavascript(ReaderTemplateHostDocument.postScript(type), null)
                }
                AppLog.putDebugWithTag(
                    AppLog.TAG_READER_TEMPLATE,
                    // 必须带上原始状态串：沙箱跨源，`decor=/decorLevel=` 等 DOM 事实只在这里出现；
                    // 纯文字章节没有 inject-done ⇒ 不在此留痕就等于"装饰档位有没有到达沙箱"无从取证
                    "sandbox ready: chapter=${currentChapter?.chapterIndex}, inject=$type, state=$state"
                )
            }

            state.startsWith(StateInjectDonePrefix) -> {
                // 计数在同一字段内编码：inject-done,mermaid=N,math=N,code=N
                AppLog.putDebugWithTag(
                    AppLog.TAG_READER_TEMPLATE,
                    "rich rendered: chapter=${currentChapter?.chapterIndex}, $state"
                )
                // 富渲染改变了正文高度 ⇒ 让沙箱重测并回发新的 stable
                webView?.evaluateJavascript(ReaderTemplateHostDocument.postScript("remeasure"), null)
            }

            state == StateInjectUnavailable -> AppLog.putDebugWithTag(
                AppLog.TAG_READER_TEMPLATE,
                "rich unavailable (runtime not injected): chapter=${currentChapter?.chapterIndex}"
            )

            else -> AppLog.putDebugWithTag(AppLog.TAG_READER_TEMPLATE, "render state: $state")
        }
    }

    /** 模板侧失败 ⇒ 回落既有渲染（不把用户留在白屏/空页）。 */
    private fun onSandboxFailure(code: String) {
        val token = RenderToken(generation)
        if (code == "template-missing-body-slot" || code.startsWith("template-render") ||
            code.startsWith("template-flow") || code.startsWith("template-init")
        ) {
            AppLog.putDebugWithTag(
                AppLog.TAG_READER_TEMPLATE,
                "template failure ⇒ fallback canvas: $code"
            )
            host?.onError(token, RenderFailure.ENGINE_UNAVAILABLE)
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
            // 消息通道（AD-23：**禁** addJavascriptInterface）；仅放行宿主文档自身 origin
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(
                    this,
                    ReaderTemplateHostDocument.WebMessageBridgeObjectName,
                    setOf(originOf(baseUrl)),
                    object : WebViewCompat.WebMessageListener {
                        override fun onPostMessage(
                            view: WebView,
                            message: androidx.webkit.WebMessageCompat,
                            sourceOrigin: android.net.Uri,
                            isMainFrame: Boolean,
                            replyProxy: androidx.webkit.JavaScriptReplyProxy
                        ) {
                            if (!isMainFrame) return
                            onSandboxMessage(message.data)
                        }
                    }
                )
            }
        }.also { webView = it }
    }

    private inner class TemplateClient(private val token: RenderToken) : WebViewClient() {

        /**
         * 只服务 `md/` 下的资产（沙箱按 URL 加载的厂商脚本与运行时资产）。
         *
         * 白名单是 `/md/` 前缀 ⇒ 沙箱即使构造别的路径也读不到其它 assets；
         * 未命中返回 null（交默认处理，即加载失败 → 沙箱按降级链继续）。
         */
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val path = request?.url?.path ?: return null
            if (!path.startsWith(ServedAssetPrefix)) return null
            val asset = path.removePrefix("/")
            val bytes = runCatching { context.assets.open(asset).use { it.readBytes() } }.getOrNull()
                ?: return null
            val mime = when {
                asset.endsWith(".js") -> "application/javascript"
                asset.endsWith(".css") -> "text/css"
                else -> "application/octet-stream"
            }
            return WebResourceResponse(mime, "UTF-8", ByteArrayInputStream(bytes))
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            if (webView === view) webView = null
            runCatching { view?.destroy() }
            AppLog.putDebugWithTag(
                AppLog.TAG_READER_TEMPLATE,
                "render process gone: crashed=${detail?.didCrash()}"
            )
            if (token.generation == generation) {
                host?.onError(token, RenderFailure.ENGINE_UNAVAILABLE)
            }
            return true
        }
    }

    private fun runOnUI(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post(action)
    }

    companion object {
        private const val WebViewBlank = "about:blank"

        /** 允许沙箱按 URL 读取的 assets 前缀（白名单，防止读任意 asset）。 */
        private const val ServedAssetPrefix = "/md/"
        private const val HostJsAsset = "md/template-host.js"
        private const val FlowJsAsset = "md/template-browser-flow.js"
        private const val RuntimeJsAsset = "md/template-runtime.js"
        private const val ThemeNight = "night"
        private const val ThemeDay = "day"

        /** 装饰强度默认字面量（与 `ReaderTemplateDecorationPolicy.Intensity.MEDIUM.cssValue` 同值）。 */
        private const val DecorationMedium = "medium"
        private const val ReadyComplete = "complete"
        private const val StateReady = "ready"
        private const val StateInjectDonePrefix = "inject-done"
        private const val StateInjectUnavailable = "inject-unavailable"
        private const val HostPollIntervalMillis = 60L
        private const val MaxHostPollAttempts = 100          // 100 × 60ms ≈ 6s（宿主文档只有几十 KB）
        private const val ReadyTimeoutMillis = 8_000L

        /** 宿主文档就绪探针（只读 readyState，不碰沙箱）。 */
        private const val HostReadyScript = "document.readyState"

        /** 由基址推 origin 规则（`addWebMessageListener` 要求 scheme://host[:port]）。 */
        private fun originOf(baseUrl: String): String =
            runCatching { android.net.Uri.parse(baseUrl).let { "${it.scheme}://${it.host}" } }
                .getOrDefault("*")
    }
}