package io.legado.app.help.md

import io.legado.app.exception.NoStackTraceException
import org.commonmark.Extension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.renderer.text.TextContentRenderer

/**
 * Markdown → 归一化章节 HTML（epub-md-rich-rendering 阶段 3.5；AD-04 v1.1 / AD-22）。
 *
 * **路线**：`.md` 不打包 EPUB3，而是转成**归一化章节 HTML** 后进入「文本渲染模式」
 * （与在线正文 / 本地 txt 同类），富排版由 `md-reader.css` 提供，mermaid/KaTeX 由运行期注入。
 *
 * **纯函数**（commonmark 为纯 Java 实现）⇒ JVM 可测，无 Android 依赖。
 *
 * 安全与边界：
 * - **安全模式（默认）**：`escapeHtml(true)` ⇒ 源文件里的 raw HTML **被转义为文本**，
 *   不进入 DOM（防导入不可信 md 时的注入面）；用户可在 3.x 阶段用「允许内嵌 HTML」开关显式放开；
 * - **单章上限 [MaxChapterChars]（2Mi，P-12）**：超限**抛显式错误**而非静默截断（
 *   `uptream` 对照：archive/NG 对 md 无此约束，截断会让用户以为"文件就是这样"）；
 * - **富渲染标注**：`hasMermaid`/`hasMath` 在**构建期**由 AST 判定（不靠运行期嗅探），
 *   供 `BackendSelectionPolicy` 直接消费（AD-30 要求"构建期标注"）；
 * - **围栏块 mermaid 保形**：` ```mermaid ` 经渲染成为 `<pre class="mermaid">`（mermaid.run 的入参形态），
 *   代码体保持 HTML 转义（mermaid 读 `textContent`，转义后即原文）；
 * - **相对图片内联（S3①）**：可选 [ImageDestinationResolver] 在 AST 层改写 `img` 目标
 *   （把 `./a.png` 换成 `data:` URI）⇒ 承载面拿不到文档目录也不会裂图；不传即完全保持旧行为。
 */
object MdDocumentBuilder {

    /** 单章上限 2Mi（P-12：口径为**单章**，非整本）。 */
    const val MaxChapterChars = 2 * 1024 * 1024

    /** 超限错误（显式、不截断）。 */
    class MdChapterTooLargeException(message: String) : NoStackTraceException(message)

    /**
     * 图片目标改写器（S3① 相对路径图片内联的唯一 seam）。
     *
     * 传参形态保持**纯函数**：本层不碰文件系统，只把"相对 `src`"交给实现方（[MdRelativeImageInliner]）
     * 换回可直接渲染的目标串（如 `data:` URI）；返回 null ⇒ 保持原引用不改写。
     */
    fun interface ImageDestinationResolver {
        fun resolve(src: String): String?
    }

    data class Options(
        /** 允许内嵌 raw HTML（默认关；放开后进入 DOM，需用户显式开启）。 */
        val allowRawHtml: Boolean = false,
        /** 把 ` ```mermaid ` 围栏块转成 `<pre class="mermaid">`。 */
        val mermaidBlocks: Boolean = true,
        /** 裸 URL 自动链接（轻量 AST 后处理，不新增 Gradle 依赖）。 */
        val autolink: Boolean = true
    )

    /**
     * 构建产物。
     *
     * @param html 归一化章节 HTML（**片段**，不含 `<html>` 外壳；外壳由文本渲染会话补齐）。
     * @param plainText 纯文本（供朗读 / 纯文本兜底；与 HTML 同源）。
     * @param hasMermaid 源含 mermaid 围栏块 ⇒ 需注入 mermaid 运行时。
     * @param hasMath 源含 `$...$` / `$$...$$` ⇒ 需注入 KaTeX。
     */
    data class Built(
        val html: String,
        val plainText: String,
        val hasMermaid: Boolean,
        val hasMath: Boolean
    ) {
        /** 是否含富渲染元素（决定后端分流，防 md 落 canvas 丢 mermaid）。 */
        val hasRichRender: Boolean get() = hasMermaid || hasMath
    }

    private val extensions: List<Extension> = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create()
    )

    /** 裸 URL（仅 http/https，避免误伤 `mailto:`/相对路径）。 */
    private val BARE_URL = Regex("""https?://[^\s<>()\[\]"']+""")

    /** 行内公式 `$...$`（同一行、成对、非空白紧邻）与块级 `$$...$$`。 */
    private val BLOCK_MATH = Regex("""\$\$[\s\S]+?\$\$""")
    private val INLINE_MATH = Regex("""(?<![\w$\\])\$(?!\s)([^$\n]+?)(?<!\s)\$(?!\w)""")

    fun build(
        markdown: String,
        options: Options = Options(),
        /** 相对图片内联（`MdFile` 传入；null ⇒ 不改写任何图片引用）。 */
        imageResolver: ImageDestinationResolver? = null
    ): Built {
        if (markdown.length > MaxChapterChars) {
            throw MdChapterTooLargeException(
                "本章 Markdown 过大（${markdown.length} > $MaxChapterChars 字符），请拆分为更小的章节"
            )
        }
        val document = Parser.builder().extensions(extensions).build().parse(markdown)
        if (options.autolink) linkifyBareUrls(document)
        // 图片目标改写必须在渲染之前（在 AST 上改 destination，不做 HTML 事后正则替换 ⇒
        // 不会误伤属性/正文里恰好同形的字符串）
        if (imageResolver != null) inlineRelativeImages(document, imageResolver)
        val hasMermaid = hasMermaidBlock(document)
        val html = renderHtml(document, options, hasMermaid)
        val plainText = TextContentRenderer.builder()
            .extensions(extensions)
            .build()
            .render(document)
            .trimEnd('\n')
        return Built(
            html = html,
            plainText = plainText,
            hasMermaid = hasMermaid,
            hasMath = hasMath(document)
        )
    }

    private fun renderHtml(document: Node, options: Options, hasMermaid: Boolean): String {
        val renderer = HtmlRenderer.builder()
            .extensions(extensions)
            // 安全模式：raw HTML 默认转义为文本（allowRawHtml 显式开启才进 DOM）。
            .escapeHtml(!options.allowRawHtml)
            .softbreak("\n")
            .build()
        val html = renderer.render(document).trimEnd('\n')
        if (!options.mermaidBlocks || !hasMermaid) return html
        return html.replace(MERMAID_BLOCK) { match -> mermaidBlockHtml(match.groupValues[1]) }
    }

    /** ` ```mermaid ` 渲染形态 → `<pre class="mermaid">`（保留已转义的代码体）。 */
    private fun mermaidBlockHtml(escapedBody: String): String {
        return "<pre class=\"mermaid\">$escapedBody</pre>"
    }

    private val MERMAID_BLOCK = Regex(
        """<pre><code class="language-mermaid">([\s\S]*?)</code></pre>"""
    )

    /** AST 判定：存在 info 以 `mermaid` 开头的围栏代码块。 */
    private fun hasMermaidBlock(document: Node): Boolean {
        var found = false
        document.accept(object : AbstractVisitor() {
            override fun visit(fencedCodeBlock: FencedCodeBlock) {
                val info = fencedCodeBlock.info?.trim()?.lowercase().orEmpty()
                if (info == MermaidInfo || info.startsWith("$MermaidInfo ")) found = true
            }
        })
        return found
    }

    /** AST 判定：文本节点含公式定界符（块级或行内）。代码块内容不在文本节点中，天然排除。 */
    private fun hasMath(document: Node): Boolean {
        var found = false
        document.accept(object : AbstractVisitor() {
            override fun visit(text: Text) {
                if (found) return
                val literal = text.literal.orEmpty()
                if (BLOCK_MATH.containsMatchIn(literal) || INLINE_MATH.containsMatchIn(literal)) {
                    found = true
                }
            }
        })
        return found
    }

    /**
     * 在 AST 上改写图片目标（S3①）。
     *
     * 只跳过空目标；**是否值得内联由 [ImageDestinationResolver] 自己判定**（单点决策 —— 判定规则
     * 在 [MdImageRefPolicy]，不在这里再写一份，避免两套口径打架）。改写失败（抛异常）按"不改写"处理：
     * 图片裂一张也不该让整章渲染失败。
     */
    private fun inlineRelativeImages(document: Node, resolver: ImageDestinationResolver) {
        document.accept(object : AbstractVisitor() {
            override fun visit(image: Image) {
                val destination = image.destination
                if (destination.isNullOrBlank()) return
                val resolved = runCatching { resolver.resolve(destination) }.getOrNull()
                if (!resolved.isNullOrBlank()) image.destination = resolved
            }
        })
    }

    /**
     * 裸 URL 自动链接（GFM autolink 的轻量等价实现）。
     *
     * 在**文本节点**层面改写（而非正则改 HTML）⇒ 不会误伤标签/属性；已是链接内的文本不重复包裹。
     */
    private fun linkifyBareUrls(document: Node) {
        val targets = ArrayList<Text>()
        document.accept(object : AbstractVisitor() {
            override fun visit(text: Text) {
                if (text.parent is Link) return
                if (BARE_URL.containsMatchIn(text.literal.orEmpty())) targets += text
            }
        })
        targets.forEach { text ->
            val literal = text.literal.orEmpty()
            var cursor = 0
            var last: Node = text
            BARE_URL.findAll(literal).forEach { match ->
                val start = match.range.first
                val url = match.value
                if (start > cursor) {
                    val head = Text(literal.substring(cursor, start))
                    last.insertAfter(head)
                    last = head
                }
                val link = Link(url, null)
                link.appendChild(Text(url))
                last.insertAfter(link)
                last = link
                cursor = match.range.last + 1
            }
            if (cursor < literal.length) {
                last.insertAfter(Text(literal.substring(cursor)))
            }
            if (cursor > 0) {
                text.unlink()
            }
        }
    }

    private const val MermaidInfo = "mermaid"
}