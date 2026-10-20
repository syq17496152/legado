package io.legado.app.ui.book.read.textweb

import io.legado.app.help.md.MdDocumentBuilder
import org.jsoup.Jsoup

/**
 * 文本富渲染内容探测（epub-md-rich-rendering 阶段 3.1/3.2 接线配套）。
 *
 * 两个职责（都是**纯函数** ⇒ JVM 可测）：
 * 1. **抽取正文**：从章节原文中取出 `<usehtml>` 内层 HTML（与 `TextChapterLayout.getTextChapter`
 *    的判定口径**逐字一致** —— `startsWith("<usehtml")` + 首个 `>` 到最后一个 `<`，避免两套口径打架）；
 * 2. **探测富渲染元素**：判断该章是否含 mermaid 块 / 公式，决定是否注入 2.4MB 的 mermaid 运行时
 *    （与 `MdRichRenderInjector.build(needsMermaid, needsMath)` 的"构建期标注"契约对接）。
 *
 * 非 `<usehtml>` 内容返回 null ⇒ 调用方**回落 canvas**（保持既有渲染路径可用，不硬切后端）。
 */
object TextRichContentProbe {

    data class RichContent(
        val html: String,
        val hasMermaid: Boolean,
        val hasMath: Boolean
    )

    /**
     * 抽取 `<usehtml>` 内层 HTML；非该形态返回 null。
     *
     * 判定复刻 `TextChapterLayout.kt`（`text.startsWith("<usehtml")` → 首个 `>` → 最后一个 `<`）。
     */
    fun extractUseHtmlInner(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || !text.startsWith("<usehtml")) return null
        val contentStart = text.indexOf('>')
        val contentEnd = text.lastIndexOf('<')
        if (contentStart < 0 || contentEnd <= contentStart) return null
        val inner = text.substring(contentStart + 1, contentEnd)
        return inner.ifBlank { null }
    }

    /**
     * 一步到位：抽取 + 探测；不可用返回 null。
     *
     * @param allowPlainText 是否允许**非 `<usehtml>` 的文本类内容**（本地 txt 纯文本 / 在线正文 HTML）
     *   归一化后使用。
     *
     * **为什么需要（本轮用户反馈驱动的修正）**：`<usehtml>` 是 **md 独有**的包裹形态（`MdFile`
     * 是唯一显式产出它的格式）；而本地 txt 的章节内容是无标签纯文本、在线正文是 HTML 片段，
     * **都不带 `<usehtml>`** ⇒ 原实现（只认 `<usehtml>`）使其一律返回 null、回落 canvas ⇒
     * **用户在 txt / 在线缓存书上开启「阅读页模板」后看不到任何变化**（"设置完没效果"）。
     * 归一化只做最保守的事：**能判定为 HTML 片段就原样使用**，否则**一行一段**、文本转义后包
     * `<p class="reader-paragraph">`（archive 段落契约）；**不清洗、不改写文本内容**，
     * 避免引入与 canvas 不一致的渲染差异。
     *
     * 默认 `false` ⇒ 保持既有语义（仅 md 走富渲染面）；非 md 的放开由调用方按「阅读页模板总开关」
     * 决定（默认关 ⇒ 零回归）。
     *
     * **有界文档（AD-15 / §1.3「单章 2Mi UTF-16 / 20,000 blocks」）**：超限一律返回 null ⇒
     * 调用方按既有语义**回落 canvas 基础排版**（软切，不是报错、更不是静默截断）。口径与 md 侧
     * **同源复用** [MdDocumentBuilder.MaxChapterChars]/[MdDocumentBuilder.MaxChapterBlocks]，
     * 避免"在线正文/txt 一套上限、md 另一套上限"的两套口径分裂。
     */
    fun prepare(raw: String?, allowPlainText: Boolean = false): RichContent? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        // 字符上界：输入体量超限即"不承载"（避免把超大文档交给 WebView 承载面）
        if (text.length > MdDocumentBuilder.MaxChapterChars) return null
        val inner = extractUseHtmlInner(raw)
        val html = when {
            inner != null -> inner
            !allowPlainText -> return null
            // 能判定为 HTML 片段就原样使用，否则按空行切段、文本转义后包 <p>
            HtmlTagRegex.containsMatchIn(text) -> text
            else -> toParagraphHtml(text)
        }
        // 块数上界：字符数达标但节点数爆炸（十几万个极短段落/引用块）同样会拖垮排版与分页 ⇒ 一并拦
        if (countBlocks(html) > MdDocumentBuilder.MaxChapterBlocks) return null
        return RichContent(html, detectMermaid(html), detectMath(html))
    }

    /**
     * 统计 HTML 块级节点数（DOM 元素计数）。
     *
     * 用 jsoup 解析后计数（纯 Java ⇒ JVM 可测）：比"正则数标签名"更接近真实 DOM 规模，
     * 也避免"仅按标签名判断"这类既往失守在计数层重演。**解析结果只用于计数**，不回流到输出
     * （不把 jsoup 的规范化改写带进正文）。
     *
     * 解析异常时返回 0（**宽松放行**）：计数失败不该把正常章节误判为不可渲染。
     */
    private fun countBlocks(html: String): Int =
        runCatching { Jsoup.parseBodyFragment(html).body().allElements.size }.getOrDefault(0)

    /**
     * 纯文本 → 段落 HTML：**一行一段**，文本按 HTML 转义（与 md 路线"安全模式：默认转义 raw HTML"同口径）。
     *
     * ⚠️ 为什么按"行"而不是按"空行"切段（2026-10-20 真机取证修正）：来源正文普遍是
     * **每行一段、行间无空行、无任何标签**（真机抽检 47 个章节缓存**全部**为该形态：空行 0、标签 0）。
     * 旧实现按空行切段 ⇒ 这类正文整章塌成**一个 `<p>`** ⇒ 段落语义装饰（首段 / 对白段 / 场景分隔段）
     * 最多只可能命中首段一个 ⇒ 用户可感知的「段落效果全没了」；且**同时**打在自研主题与 archive 主题上
     * （archive 的 `as-lead`/`as-dialogue`/`as-scene` 与本方的 `dh-*` 都只点 `.reader-paragraph`，
     * 段落只剩一段时判据自然落到空处）。
     *
     * 分段口径与 archive / [io.legado.app.model.localBook.epubcore.direct.TextReaderDocument] 的
     * `lineSequence` 逐行成段**完全同源** ⇒ "同一段正文在两侧得到同一分段"，导入导出与换主题后观感连续。
     * 首尾空白（含全角空格 `\u3000`，来源常用作段首缩进）统一 trim ⇒ 与 CSS `text-indent` 不叠加。
     */
    private fun toParagraphHtml(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n") { "<p class=\"reader-paragraph\">" + escapeHtml(it) + "</p>" }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** 判定"已是 HTML 片段"（用于区分在线正文 HTML 与 txt 纯文本）；只认常见块级/行内标签。 */
    private val HtmlTagRegex = Regex(
        "<(p|div|br|img|span|h[1-6]|ul|ol|li|table|tr|td|th|pre|code|blockquote|em|strong|a|hr|figure)\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * mermaid 块判定：`class="mermaid"`（可含其它类，单双引号均可）。
     *
     * 不用 `contains("mermaid")` 的宽判定 —— 正文提到 "mermaid" 一词（如本书本身在讲 mermaid）
     * 会误注入 2.4MB 运行时。
     */
    fun detectMermaid(html: String): Boolean = MermaidClassRegex.containsMatchIn(html)

    /**
     * 公式判定：`$$...$$`（块级）或 `$...$`（行内，不跨行、有长度上限）。
     *
     * 上限用于避免"正文里两个孤立的 `$`（如两个货币金额）被当成一个巨型公式"。
     */
    fun detectMath(html: String): Boolean {
        // 去掉代码块再判：代码块里的 `$` 不应触发公式渲染
        val withoutCode = CodeBlockRegex.replace(html, "")
        return BlockMathRegex.containsMatchIn(withoutCode) || InlineMathRegex.containsMatchIn(withoutCode)
    }

    /** 单双引号均可的 `class="...mermaid..."`。 */
    private val MermaidClassRegex = Regex("class\\s*=\\s*[\"'][^\"']*\\bmermaid\\b")

    /** 块级公式 `$$...$$`（允许跨行，2000 字符上限）。 */
    private val BlockMathRegex = Regex("\\$\\$[\\s\\S]{1,2000}?\\$\\$")

    /**
     * 行内公式 `$...$`（不跨行，200 字符上限；排除 `\$` 转义）。
     *
     * **定界符内侧不得是空白**：`$ 5 与 8 $` 这种（货币金额）会被判成公式 —— 行内公式的通行约定是
     * 开 `$` 后、闭 `$` 前紧跟非空白字符，据此排除绝大多数误判。
     */
    private val InlineMathRegex = Regex("(?<!\\\\)\\$(?!\\s)[^\\$\\n]{1,200}(?<!\\s)\\$")

    /** `<pre>...</pre>` / `<code>...</code>`（公式判定前剔除，代码里的 `$` 不算公式）。 */
    private val CodeBlockRegex = Regex("<(pre|code)\\b[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE)
}