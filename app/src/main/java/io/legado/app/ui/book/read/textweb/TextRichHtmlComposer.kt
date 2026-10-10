package io.legado.app.ui.book.read.textweb

/**
 * 文本富渲染文档组装（epub-md-rich-rendering 阶段 3.1/3.2 渲染面配套）。
 *
 * **职责**：把「章节 HTML 片段 + 阅读样式 + 富渲染注入片段 + 主题变量」组装成一个可直接
 * `loadDataWithBaseURL` 的完整文档。**纯字符串构造**（无 Android 依赖）⇒ 契约与覆盖顺序可 JVM 断言。
 *
 * 覆盖顺序（关键正确性点，失误会导致"设置字号/夜间主题无效"）：
 * ```
 * 1. md-reader.css            —— 基线（:root 默认值 + html[data-md-theme="night"] 夜间块）
 * 2. 宿主主题变量             —— 用 html[data-md-theme] 选择器覆盖字号/行高/字体
 * 3. 富渲染注入（<style>+<script>）
 * 4. 正文
 * ```
 * 为什么第 2 步必须用 `html[data-md-theme]` 而不是 `html` 或 `:root`：
 * - `:root` 特异性 (0,1,0) **高于** `html` (0,0,1) ⇒ 只写 `html{--md-font-size}` **覆盖不了** md-reader.css 的 `:root`；
 * - `html[data-md-theme]` 特异性 (0,1,1) ⇒ 稳超 `:root`，且与 md-reader.css 自身的夜间选择器同族，不会打架。
 *
 * 昼夜切换交给 `data-md-theme` 属性（md-reader.css 已定义两套变量），宿主只改属性、不重写色值 ⇒ 单一色源。
 */
object TextRichHtmlComposer {

    /** 文档主题（昼夜由 `data-md-theme` 驱动；色值不在本层硬编码，见 md-reader.css）。 */
    const val ThemeDay = "day"
    const val ThemeNight = "night"

    /** 正文容器 id（宿主注入正文与轮询脚本的锚点，供真机断言）。 */
    const val BodyElementId = "legado-md-body"

    data class Theme(
        /** 是否夜间主题（决定 `data-md-theme`）。 */
        val dark: Boolean,
        /** 正文字号（px）；非正数视为无效，退回 md-reader.css 默认。 */
        val fontSizePx: Float? = null,
        /** 行高倍数；非正数视为无效，退回默认。 */
        val lineHeight: Float? = null,
        /** 正文字体族（CSS `font-family` 值）；空/非法视为不覆盖。 */
        val fontFamily: String? = null
    ) {
        val dataTheme: String get() = if (dark) ThemeNight else ThemeDay
    }

    /**
     * 组装完整文档。
     *
     * @param bodyHtml 章节正文 HTML 片段（`MdDocumentBuilder.Built.html` 或在线正文的 `<usehtml>` 内层）。
     * @param readerCss `assets/md/md-reader.css` 内容（空则只靠主题变量，仍可读）。
     * @param richInjectionHtml `MdRichRenderInjector.wrapHtml(...)` 的产物（空则不注入富渲染）。
     */
    fun compose(
        bodyHtml: String,
        readerCss: String,
        richInjectionHtml: String,
        theme: Theme
    ): String = buildString {
        append("<!DOCTYPE html><html data-md-theme=\"").append(theme.dataTheme).append("\"><head>")
        append("<meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\">")
        if (readerCss.isNotBlank()) {
            append("<style id=\"legado-md-reader-css\">").append(readerCss).append("</style>")
        }
        append(themeStyleBlock(theme))
        if (richInjectionHtml.isNotBlank()) {
            append(richInjectionHtml)
        }
        append("</head><body>")
        append("<div id=\"").append(BodyElementId).append("\" class=\"legado-md-content\">")
        append(bodyHtml)
        append("</div>")
        append("</body></html>")
    }

    /**
     * 宿主主题变量块；**只产出有效值**（无效输入不写声明，避免把 `--md-font-size:0px` 这种
     * 破坏性值注入文档 —— 用户会看到"字没了"）。
     */
    fun themeStyleBlock(theme: Theme): String {
        val declarations = buildString {
            val size = theme.fontSizePx?.takeIf { it.isFinite() && it > 0f }
            if (size != null) append("--md-font-size:").append(formatPx(size)).append("px;")
            val line = theme.lineHeight?.takeIf { it.isFinite() && it > 0f }
            if (line != null) append("--md-line-height:").append(formatScalar(line)).append(";")
            theme.fontFamily?.takeIf { isSafeFontFamily(it) }?.let {
                append("--md-font-family:").append(it.trim()).append(";")
            }
        }
        if (declarations.isEmpty()) return ""
        return "<style id=\"legado-md-theme-override\">html[data-md-theme]{$declarations}</style>"
    }

    /**
     * 字体族白名单校验：只允许"字母数字/空格/逗号/引号/连字符/中文"构成的常规字体栈。
     * 目的是**结构校验**（拒 `;` `{` `}` 造成的声明逃逸注入），不校验字体是否真实存在。
     */
    fun isSafeFontFamily(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > MaxFontFamilyChars) return false
        return trimmed.all { it.isLetterOrDigit() || it == ' ' || it == ',' || it == '\'' ||
            it == '"' || it == '-' || it in '\u4e00'..'\u9fff' }
    }

    /** px 取整到 0.1 位（避免 `17.999999px` 这类脏值进入文档）。 */
    private fun formatPx(value: Float): String = formatScalar(value)

    private fun formatScalar(value: Float): String {
        val rounded = Math.round(value * 10f) / 10f
        return if (rounded == rounded.toLong().toFloat()) rounded.toLong().toString() else rounded.toString()
    }

    /** 字体族长度上限（防超长串拖慢样式解析）。 */
    const val MaxFontFamilyChars = 200
}