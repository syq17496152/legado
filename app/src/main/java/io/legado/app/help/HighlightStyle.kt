package io.legado.app.help

/**
 * F-P1-2 高亮规则系统（借鉴阅读T）
 * 高亮样式(可组合)。各通道独立可选:0 / null = 该通道关闭。
 * 颜色为 ARGB Int;线类装饰 color == 0 表示「跟随字色」。
 * 纯数据,序列化为 BookHighlight.style(JSON);手动高亮与后续关键词/正则规则共用。
 */
data class HighlightStyle(
    val fill: Int = 0,                 // 背景填充(含 alpha;0=不填充)
    val textColor: Int = 0,            // 字体色(0=保持阅读默认字色)
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Underline? = null,  // 下划线(含波浪/虚线/点线/双线);null=无
    val strike: Deco? = null,          // 删除线;null=无
    val box: Deco? = null,             // 方框;null=无
    val emphasis: Deco? = null,        // 着重号(字下圆点);null=无
    val fontPath: String = ""          // 自定义字体路径(空=跟随阅读字体)
) {
    data class Underline(val kind: Kind = Kind.SOLID, val color: Int = 0)
    data class Deco(val color: Int = 0)              // color==0 跟随字色
    enum class Kind { SOLID, WAVY, DASHED, DOTTED, DOUBLE }

    /** 完全空样式(等价于「无高亮」) */
    val isEmpty: Boolean
        get() = fill == 0 && textColor == 0 && !bold && !italic &&
                underline == null && strike == null && box == null && emphasis == null &&
                fontPath.isEmpty()

    /** 是否需要「逐列绘制」(任何非纯背景填充的通道都需要) */
    val needsPerColumnDraw: Boolean
        get() = textColor != 0 || bold || italic ||
                underline != null || strike != null || box != null || emphasis != null ||
                fontPath.isNotEmpty()

    companion object {
        /** 按通道 last-wins 叠加:other 只覆盖它设过(非默认)的通道;布尔取或 */
        fun merge(base: HighlightStyle?, other: HighlightStyle): HighlightStyle {
            val b = base ?: HighlightStyle()
            return b.copy(
                fill = if (other.fill != 0) other.fill else b.fill,
                textColor = if (other.textColor != 0) other.textColor else b.textColor,
                bold = other.bold || b.bold,
                italic = other.italic || b.italic,
                underline = other.underline ?: b.underline,
                strike = other.strike ?: b.strike,
                box = other.box ?: b.box,
                emphasis = other.emphasis ?: b.emphasis,
                fontPath = other.fontPath.ifEmpty { b.fontPath }
            )
        }
    }
}
