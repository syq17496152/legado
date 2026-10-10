package io.legado.app.ui.book.read.textweb

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

    /** 一步到位：抽取 + 探测；不可用返回 null。 */
    fun prepare(raw: String?): RichContent? {
        val html = extractUseHtmlInner(raw) ?: return null
        return RichContent(
            html = html,
            hasMermaid = detectMermaid(html),
            hasMath = detectMath(html)
        )
    }

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