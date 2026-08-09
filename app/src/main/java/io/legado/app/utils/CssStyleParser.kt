package io.legado.app.utils

import io.legado.app.constant.AppLog
import io.legado.app.help.HighlightStyle

/**
 * B15 高亮捕获组样式（借鉴 Jingshiro CssStyleParser :70-187）
 * 解析替换模板中的 CSS style / HTML 标签 / 颜色，产出组内样式段。
 * 纯函数实现, 无 Android 依赖, JVM 可测。
 */
object CssStyleParser {

    // 匹配 style 字符串中的各 CSS 属性
    private val fontWeightRegex = Regex("""font-weight\s*:\s*(bold|\d+)""")
    private val fontStyleRegex = Regex("""font-style\s*:\s*italic""")
    private val textDecorationRegex = Regex("""text-decoration\s*:\s*underline""")
    private val colorRegex = Regex("""color\s*:\s*([^;]+)""")
    private val fontSizeRegex = Regex("""font-size\s*:\s*([\d.]+)\s*sp""")
    private val fontFamilyRegex = Regex("""font-family\s*:\s*([^;]+)""")

    // 匹配 HTML 标签
    private val boldTagRegex = Regex("""</?(?:b|strong)>""", RegexOption.IGNORE_CASE)
    private val italicTagRegex = Regex("""</?(?:i|em)>""", RegexOption.IGNORE_CASE)
    private val underlineTagRegex = Regex("""</?(?:u)>""", RegexOption.IGNORE_CASE)
    private val fontSizeSpanRegex = Regex(
        """<span\s+[^>]*style\s*=\s*["'][^"']*font-size\s*:\s*([\d.]+)\s*sp[^"']*["'][^>]*>""",
        RegexOption.IGNORE_CASE
    )
    private val fontColorTagRegex = Regex(
        """<font\s+[^>]*color\s*=\s*["']([^"']+)["'][^>]*>""",
        RegexOption.IGNORE_CASE
    )
    private val fontFaceTagRegex = Regex(
        """<font\s+[^>]*face\s*=\s*["']([^"']+)["'][^>]*>""",
        RegexOption.IGNORE_CASE
    )
    private val spanTagRegex = Regex(
        """<span\s+[^>]*style\s*=\s*["']([^"']+)["'][^>]*>""",
        RegexOption.IGNORE_CASE
    )

    // CSS 颜色名 → ARGB Int（20 个常用）
    private val colorNameMap = mapOf(
        "red" to 0xFFFF0000.toInt(),
        "blue" to 0xFF0000FF.toInt(),
        "green" to 0xFF008000.toInt(),
        "yellow" to 0xFFFFFF00.toInt(),
        "white" to 0xFFFFFFFF.toInt(),
        "black" to 0xFF000000.toInt(),
        "gray" to 0xFF808080.toInt(),
        "grey" to 0xFF808080.toInt(),
        "cyan" to 0xFF00FFFF.toInt(),
        "magenta" to 0xFFFF00FF.toInt(),
        "orange" to 0xFFFFA500.toInt(),
        "purple" to 0xFF800080.toInt(),
        "pink" to 0xFFFFC0CB.toInt(),
        "brown" to 0xFFA52A2A.toInt(),
        "gold" to 0xFFFFD700.toInt(),
        "silver" to 0xFFC0C0C0.toInt(),
        "navy" to 0xFF000080.toInt(),
        "teal" to 0xFF008080.toInt(),
        "maroon" to 0xFF800000.toInt(),
        "olive" to 0xFF808000.toInt()
    )

    /** 解析后的 CSS 六通道样式（参考源结构，与本项目 HighlightStyle 分离） */
    data class CssStyle(
        val isBold: Boolean = false,
        val isItalic: Boolean = false,
        val isUnderline: Boolean = false,
        val color: Int? = null,
        val fontSizeSp: Float? = null,
        val fontFamily: String? = null
    ) {
        fun hasStyle(): Boolean =
            isBold || isItalic || isUnderline || color != null || fontSizeSp != null || fontFamily != null
    }

    /** CSS 六通道 → 项目 HighlightStyle 通道映射（B15 4.4.3） */
    fun CssStyle.toHighlightStyle(): HighlightStyle {
        return HighlightStyle(
            textColor = color ?: 0,
            bold = isBold,
            italic = isItalic,
            underline = if (isUnderline) HighlightStyle.Underline(kind = HighlightStyle.Kind.SOLID) else null
            // fontSizeSp/fontFamily: 项目无字号/字体族通道，降级忽略
        )
    }

    /**
     * 解析 CSS style 字符串
     * @param style 如 "font-weight:bold; color:red; font-size:16sp"
     * @return CssStyle
     */
    fun parseStyle(style: String): CssStyle {
        var isBold = false
        var isItalic = false
        var isUnderline = false
        var color: Int? = null
        var fontSizeSp: Float? = null
        var fontFamily: String? = null

        fontWeightRegex.find(style)?.let { match ->
            val value = match.groupValues[1]
            isBold = value == "bold" || (value.toIntOrNull()?.let { it >= 700 } == true)
        }
        if (fontStyleRegex.containsMatchIn(style)) isItalic = true
        if (textDecorationRegex.containsMatchIn(style)) isUnderline = true
        colorRegex.find(style)?.let { match ->
            color = parseColor(match.groupValues[1].trim())
        }
        fontSizeRegex.find(style)?.let { match ->
            fontSizeSp = match.groupValues[1].toFloatOrNull()
        }
        fontFamilyRegex.find(style)?.let { match ->
            fontFamily = match.groupValues[1].trim()
        }
        return CssStyle(isBold, isItalic, isUnderline, color, fontSizeSp, fontFamily)
    }

    /**
     * 从 HTML 标签片段的 style 属性提取样式信息
     * @param html 含 HTML 标签的字符串片段
     * @return CssStyle
     */
    fun parseHtmlStyle(html: String): CssStyle {
        var isBold = false
        var isItalic = false
        var isUnderline = false
        var color: Int? = null
        var fontSizeSp: Float? = null
        var fontFamily: String? = null

        if (boldTagRegex.containsMatchIn(html)) isBold = true
        if (italicTagRegex.containsMatchIn(html)) isItalic = true
        if (underlineTagRegex.containsMatchIn(html)) isUnderline = true

        fontColorTagRegex.find(html)?.let { match ->
            color = parseColor(match.groupValues[1].trim())
        }
        fontFaceTagRegex.find(html)?.let { match ->
            fontFamily = match.groupValues[1].trim()
        }
        spanTagRegex.find(html)?.let { match ->
            val parsed = parseStyle(match.groupValues[1])
            if (parsed.isBold) isBold = true
            if (parsed.isItalic) isItalic = true
            if (parsed.isUnderline) isUnderline = true
            if (parsed.color != null) color = parsed.color
            if (parsed.fontSizeSp != null) fontSizeSp = parsed.fontSizeSp
            if (parsed.fontFamily != null) fontFamily = parsed.fontFamily
        }
        fontSizeSpanRegex.find(html)?.let { match ->
            fontSizeSp = match.groupValues[1].toFloatOrNull()
        }
        return CssStyle(isBold, isItalic, isUnderline, color, fontSizeSp, fontFamily)
    }

    // 允许带样式的内联标签（div/p 等结构标签不参与组样式）
    private val styleTagNames = "b|i|u|font|span|strong|em|big|small"

    /** LRU 100：replacement 模板 → 组样式映射 */
    private val groupStylesCache = LinkedHashMap<String, Map<Int, CssStyle>>(128, 0.75f, true)

    /**
     * 从替换模板提取捕获组样式
     * 模板示例: <b><font color="red">$1</font></b><i>$2</i>
     * @param replacement 替换模板字符串
     * @return Map<Int, CssStyle> 组序号 → 样式
     */
    fun extractGroupStyles(replacement: String): Map<Int, CssStyle> {
        synchronized(groupStylesCache) {
            groupStylesCache[replacement]?.let {
                runCatching {
                    AppLog.putDebugWithTag(
                        AppLog.TAG_HIGHLIGHT_STYLE,
                        "捕获组样式缓存命中 ${it.size} 组",
                        level = AppLog.Level.INFO
                    )
                }
                return it
            }
        }
        val result = mutableMapOf<Int, CssStyle>()

        // 匹配 $N 周围带样式标签的片段（排除 div/p 等结构标签）
        val pattern = Regex(
            """((?:<(?!\/)(?:$styleTagNames)\b[^>]*>)+)\$(\d+)((?:<\/(?:$styleTagNames)>)+)""",
            RegexOption.IGNORE_CASE
        )
        pattern.findAll(replacement).forEach { match ->
            val groupIndex = match.groupValues[2].toIntOrNull() ?: return@forEach
            val openTags = match.groupValues[1].lowercase()
            val style = parseHtmlStyle(openTags)
            result[groupIndex] = style
        }

        // 其余 $N（无样式标签）使用默认样式
        val pattern2 = Regex("""\$(\d+)""")
        pattern2.findAll(replacement).forEach { match ->
            val groupIndex = match.groupValues[1].toIntOrNull() ?: return@forEach
            if (groupIndex !in result) {
                result[groupIndex] = CssStyle()
            }
        }

        synchronized(groupStylesCache) {
            if (groupStylesCache.size >= 100) {
                groupStylesCache.entries.firstOrNull()?.let { groupStylesCache.remove(it.key) }
            }
            groupStylesCache[replacement] = result
        }
        runCatching {
            AppLog.putDebugWithTag(
                AppLog.TAG_HIGHLIGHT_STYLE,
                "捕获组解析 ${result.size} 组",
                level = AppLog.Level.INFO
            )
        }
        return result
    }

    /**
     * 解析颜色字符串
     * 支持: #RGB, #RRGGBB, #AARRGGBB, 颜色名
     */
    fun parseColor(colorStr: String): Int? {
        val str = colorStr.trim().lowercase()
        if (str.startsWith("#")) {
            val hex = str.substring(1)
            return try {
                when (hex.length) {
                    3 -> { // #RGB → ARGB
                        val r = (hex[0].digitToInt(16)) * 17
                        val g = (hex[1].digitToInt(16)) * 17
                        val b = (hex[2].digitToInt(16)) * 17
                        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    6 -> { // #RRGGBB → ARGB
                        (0xFF shl 24) or hex.toInt(16)
                    }
                    8 -> { // #AARRGGBB
                        hex.toLong(16).toInt()
                    }
                    else -> {
                        runCatching {
                            AppLog.putDebugWithTag(
                                AppLog.TAG_HIGHLIGHT_STYLE,
                                "颜色格式不支持跳过 $colorStr",
                                level = AppLog.Level.WARN
                            )
                        }
                        null
                    }
                }
            } catch (_: Exception) {
                runCatching {
                    AppLog.putDebugWithTag(
                        AppLog.TAG_HIGHLIGHT_STYLE,
                        "颜色解析失败 $colorStr",
                        level = AppLog.Level.WARN
                    )
                }
                null
            }
        }
        colorNameMap[str]?.let { return it }
        runCatching {
            AppLog.putDebugWithTag(
                AppLog.TAG_HIGHLIGHT_STYLE,
                "未知颜色跳过 $colorStr",
                level = AppLog.Level.WARN
            )
        }
        return null
    }
}
