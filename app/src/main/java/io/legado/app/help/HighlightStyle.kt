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
    val fontPath: String = "",         // 自定义字体路径(空=跟随阅读字体)
    /**
     * R12.4 语义色板槽位（见 [HighlightPalette.Slot]）：
     * 非空表示本样式的颜色由色板派生 → 渲染时按**当前阅读器态**（白天/夜间/墨水屏）解析；
     * 为 null 表示用户手选色或旧数据 → 原样使用，**不做自动改写**。
     */
    val paletteSlot: String? = null,
    /** R1a：背景填充形状（null = 矩形，与改造前等价） */
    val fillShape: FillShape? = null,
    /** R1a：文字阴影（null = 无阴影，与改造前等价） */
    val shadow: Shadow? = null
) {
    data class Underline(
        val kind: Kind = Kind.SOLID,
        val color: Int = 0,
        /** 线宽（px）；null = 未设置 → 渲染取 [resolvedWidth]（保持旧观感零回归） */
        val width: Float? = null,
        /** 线距（px，基线上方偏移量）；null = 未设置 → 渲染取 [resolvedDistance] */
        val distance: Float? = null
    ) {
        /** 实际绘制线宽：未设置回退默认 1.5f（与改造前硬编码一致） */
        val resolvedWidth: Float get() = width ?: DEFAULT_WIDTH

        /** 实际绘制线距：未设置回退默认 2f（与改造前硬编码一致） */
        val resolvedDistance: Float get() = distance ?: DEFAULT_DISTANCE

        companion object {
            /** 与改造前下划线硬编码线宽一致（零回归基线） */
            const val DEFAULT_WIDTH = 1.5f

            /** 与改造前下划线硬编码线距一致（零回归基线） */
            const val DEFAULT_DISTANCE = 2f
        }
    }
    data class Deco(val color: Int = 0)              // color==0 跟随字色
    enum class Kind { SOLID, WAVY, DASHED, DOTTED, DOUBLE }

    /** R1a：背景填充形状。RECTANGLE 为默认（与改造前逐列矩形等价） */
    enum class FillShape { RECTANGLE, ROUNDED, MARKER, HALF, BASELINE, PILL }

    /**
     * R1a：文字阴影。颜色允许半透明（不参与色板槽位替换，见 [HighlightPalette.resolve]）。
     * radius/dx/dy 单位为 px。
     */
    data class Shadow(
        val radius: Float = 0f,
        val dx: Float = 0f,
        val dy: Float = 0f,
        val color: Int = 0
    ) {
        /**
         * 归一化：`radius` 过小视为**无阴影**（`"shadow": {}` 反序列化得 radius=0 的兜底），
         * 并把偏移/半径夹到合理域，防手改 JSON 写出极端值导致整列描边。
         */
        fun normalized(): Shadow? {
            val r = radius.coerceIn(0f, 25f)
            if (r < 0.5f) return null
            return copy(radius = r, dx = dx.coerceIn(-25f, 25f), dy = dy.coerceIn(-25f, 25f))
        }
    }

    /** R1a：实际填充形状（未设置 → 矩形，零回归） */
    val resolvedFillShape: FillShape get() = fillShape ?: FillShape.RECTANGLE

    /** 是否含非矩形填充形状（缩进列跳过判定的输入） */
    val hasNonRectFillShape: Boolean get() = resolvedFillShape != FillShape.RECTANGLE

    /** 完全空样式(等价于「无高亮」) */
    val isEmpty: Boolean
        get() = fill == 0 && textColor == 0 && !bold && !italic &&
                underline == null && strike == null && box == null && emphasis == null &&
                fontPath.isEmpty() && shadow == null

    /** 是否需要「逐列绘制」(任何非纯背景填充的通道都需要) */
    val needsPerColumnDraw: Boolean
        get() = textColor != 0 || bold || italic ||
                underline != null || strike != null || box != null || emphasis != null ||
                fontPath.isNotEmpty()

    /**
     * 空值兜底（健壮性）。
     *
     * 背景：本类经 GSON 反序列化（`BookHighlight.style` / `HighlightRule.styleJson`）时，
     * 缺失的字段会被写入 **null**（GSON 不调用 Kotlin 默认值），使「非空声明」在运行期失效——
     * 一旦后续调用 `copy()` 就会抛
     * `NullPointerException: Parameter specified as non-null is null: ... parameter fontPath`。
     *
     * 处置（**统一归一化出口**）：补齐可空风险字段（`fontPath`），并归一化新增通道
     * （阴影半径过小 → 无阴影；下划线线宽/线距夹到合法域）。
     * 调用点：反序列化出口（`HighlightRule.toHighlightStyle` / `BookHighlight.styleObj` /
     * `CssStyleParser`）与色板解析入口（[HighlightPalette.resolve]）。
     */
    fun sanitized(): HighlightStyle {
        @Suppress("SENSELESS_COMPARISON")
        var s = if (fontPath == null) copy(fontPath = "") else this
        // R1a：阴影归一化（`radius` 过小 → 视为无阴影，防 `"shadow": {}` 造成整列描边）
        val normalizedShadow = s.shadow?.normalized()
        if (normalizedShadow != s.shadow) s = s.copy(shadow = normalizedShadow)
        // B2-④：线宽/线距越界兜底（手改 JSON / 旧数据；域与 HighlightRuleStore.sanitizeRule 一致）
        s.underline?.let { u ->
            val w = u.width?.coerceIn(0.1f, 10f)
            val d = u.distance?.coerceIn(0f, 20f)
            if (w != u.width || d != u.distance) {
                s = s.copy(underline = u.copy(width = w, distance = d))
            }
        }
        return s
    }
}
