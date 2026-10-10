package io.legado.app.model.localBook.epubcore.template

/**
 * 模板装饰策略（epub-md-rich-rendering 阶段 4.18；AD-33 / TPL-17）。
 *
 * 三条硬约束来自设计文档，逐条落为可验证判据：
 * 1. **装饰分层**：装饰**不参与正文测量**（layout-independent）——它的显隐/强度变化不得改变正文页数；
 * 2. **不侵入正文安全区**：正文区高度须 ≥ 页高 [MinContentHeightRatio]（蓝图硬约束①：正文≥80% 页高）；
 * 3. **默认克制 + 专注模式**：默认强度"中"；专注模式隐藏全部装饰；`reduce-motion` 停止装饰动效
 *    （电量/发热与无障碍要求）。
 *
 * 另明确**与原生排版的优先级**：模板启用 ⇒ 原生排版项（段距/缩进/对齐等）置灰/隐藏并说明，
 * 避免"设置了没反应"（4.8d）。
 *
 * 纯函数 ⇒ JVM 可测。
 */
internal object ReaderTemplateDecorationPolicy {

    /** 正文区占页高的下限（蓝图硬约束①）。 */
    const val MinContentHeightRatio = 0.80f

    /** 装饰强度的默认值（AD-33：默认"中"）。 */
    val DefaultIntensity = Intensity.MEDIUM

    /**
     * 装饰强度。
     *
     * @param decorationScale 装饰元素尺寸缩放（相对基准，仅影响装饰本身）。
     * @param motionAllowed 该强度下是否**允许**装饰动效（仍受 reduce-motion 与专注模式约束）。
     */
    enum class Intensity(
        val level: Int,
        val decorationScale: Float,
        val motionAllowed: Boolean,
        /** 下发给沙箱的档位字面量（`html[data-rp-decoration]`；与引擎装饰基线 CSS 的取值一致）。 */
        val cssValue: String
    ) {
        NONE(0, 0f, false, "none"),
        LIGHT(1, 0.5f, false, "light"),
        MEDIUM(2, 1f, true, "medium"),
        STRONG(3, 1.4f, true, "strong");

        companion object {
            fun fromLevel(level: Int): Intensity = entries.firstOrNull { it.level == level } ?: DefaultIntensity
        }
    }

    data class Decision(
        val intensity: Intensity,
        val showDecorations: Boolean,
        val motionEnabled: Boolean,
        /** 原生排版项是否应置灰/隐藏（模板接管排版）。 */
        val nativeTypographyDisabled: Boolean,
        /** 给用户的说明；空串 ⇒ 不打扰。 */
        val notice: String
    )

    /**
     * 决策。
     *
     * @param storedIntensity 用户记忆的强度（无记忆传 null ⇒ 用 [DefaultIntensity]）。
     * @param templatesEnabled 模板系统总开关（关闭 ⇒ 完全不参与，原生排版恢复可用）。
     * @param focusMode 专注模式（隐藏装饰，只留正文）。
     * @param reduceMotion 系统/用户要求减少动效。
     */
    fun decide(
        templatesEnabled: Boolean,
        storedIntensity: Intensity? = null,
        focusMode: Boolean = false,
        reduceMotion: Boolean = false
    ): Decision {
        if (!templatesEnabled) {
            // 关闭 ⇒ 等同纯正文渲染：无装饰、无动效，且**不接管**原生排版
            return Decision(
                intensity = Intensity.NONE,
                showDecorations = false,
                motionEnabled = false,
                nativeTypographyDisabled = false,
                notice = ""
            )
        }
        val baseline = storedIntensity ?: DefaultIntensity
        if (focusMode) {
            return Decision(
                intensity = Intensity.NONE,
                showDecorations = false,
                motionEnabled = false,
                nativeTypographyDisabled = true,
                notice = "专注模式：已隐藏页面装饰"
            )
        }
        // 动效 = 档位允许 && 未要求减少动效。**档位本身不变**（reduce-motion 只停动效、不降观感）
        return Decision(
            intensity = baseline,
            showDecorations = baseline != Intensity.NONE,
            motionEnabled = baseline.motionAllowed && !reduceMotion,
            nativeTypographyDisabled = true,
            notice = if (baseline.motionAllowed && reduceMotion) "已按系统设置关闭装饰动效" else ""
        )
    }

    /**
     * 正文安全区校验：装饰布局后正文区仍须 ≥ 页高 [MinContentHeightRatio]。
     *
     * 调用方（模板分页/装饰布局）在**每次装饰变更后**调用；返回 false 即视为装饰越界，须回落装饰强度。
     */
    fun contentAreaSatisfiesBlueprint(contentHeightPx: Int, pageHeightPx: Int): Boolean {
        if (pageHeightPx <= 0) return false
        if (contentHeightPx <= 0) return false
        return contentHeightPx.toFloat() >= pageHeightPx * MinContentHeightRatio
    }

    /**
     * 装饰是否会改变正文测量（**必须为 false**）。
     *
     * 这是"装饰分层"的可执行判据：装饰变更前后正文区尺寸与页数应完全一致。
     */
    fun decorationChangesLayout(before: LayoutSnapshot, after: LayoutSnapshot): Boolean {
        return before != after
    }

    /** 正文布局快照（用于"装饰变更不得影响正文"的一致性断言）。 */
    data class LayoutSnapshot(val contentWidthPx: Int, val contentHeightPx: Int, val pageCount: Int)
}