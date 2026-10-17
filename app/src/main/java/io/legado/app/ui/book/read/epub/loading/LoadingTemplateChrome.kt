package io.legado.app.ui.book.read.epub.loading

import io.legado.app.help.config.EpubLoadingTemplate
import io.legado.app.help.config.Scene

/**
 * 首屏模板的**几何与排版纯函数**（无 Android 依赖 ⇒ 可 JVM 逐值断言）。
 *
 * 为什么把几何从绘制里抽出来：绘制（`EpubLoadingOverlay`）要 hold Canvas/Paint/Layout，
 * 在 JVM 上跑不起来；而"插画缩放比例、宽屏判定、insets 夹取、文字缩放"这些**是纯算术**
 * —— 抽出来就能把"比例不变""不裁剪""越界夹取"这类不变式钉死在单测里（本项目的失守多半发生在
 * 这类"看起来对"的算术上）。
 *
 * 基准画幅：插画 `viewBox 0 0 360 336`（见 `Scene.ART_ASPECT`）。
 */
object LoadingTemplateChrome {

    /** 宽屏判定：足够宽（≥ [WIDE_MIN_DP] dp）且宽高比 > [WIDE_ASPECT_MIN]（横屏大屏下首屏横向铺开）。 */
    const val WIDE_MIN_DP = 420f
    const val WIDE_ASPECT_MIN = 1.25f

    /** insets 夹取上界（占视口比例）：极端 insets（如折叠屏悬停/超大刘海）不得吃掉整个首屏。 */
    const val INSET_MAX_WIDTH_RATIO = 0.4f
    const val INSET_MAX_HEIGHT_RATIO = 0.35f

    /** 文字区域占可用高的比例（余下给插画；与参考实现同量级）。 */
    const val TEXT_BLOCK_HEIGHT_RATIO = 0.34f

    /**
     * insets 夹取结果（单位：px）。`left/right ≤ width×0.4`、`top/bottom ≤ height×0.35`，
     * 且夹取后 `width/height` 仍需为正（否则绘制侧直接回落既有遮罩）。
     */
    data class EdgeInsets(val left: Int, val right: Int, val top: Int, val bottom: Int) {
        val width: Int get() = left + right
        val height: Int get() = top + bottom
    }

    /** 插画几何：`width` 恒按 [Scene.ART_ASPECT] 等比推出，`scale` 越大越大（单调）。 */
    data class ArtGeometry(val width: Float, val height: Float) {
        val aspect: Float get() = if (width <= 0f) 0f else height / width
    }

    /** 是否宽屏（入参为 px 与 density）。 */
    fun isWide(widthPx: Int, heightPx: Int, density: Float): Boolean {
        if (widthPx <= 0 || heightPx <= 0 || density <= 0f) return false
        val wideDp = widthPx / density
        val aspect = widthPx.toFloat() / heightPx
        return wideDp >= WIDE_MIN_DP && aspect > WIDE_ASPECT_MIN
    }

    /**
     * 夹取 insets：任一侧超上界即压到上界；**整体不可超过视口的一半**（双侧相加超限时按比例回缩）。
     * 返回 null 表示视口非法（绘制侧据此回落）。
     */
    fun clampInsets(
        rawLeft: Int, rawRight: Int, rawTop: Int, rawBottom: Int,
        viewWidth: Int, viewHeight: Int
    ): EdgeInsets? {
        if (viewWidth <= 0 || viewHeight <= 0) return null
        val maxSideW = (viewWidth * INSET_MAX_WIDTH_RATIO).toInt().coerceAtLeast(0)
        val maxSideH = (viewHeight * INSET_MAX_HEIGHT_RATIO).toInt().coerceAtLeast(0)
        var left = rawLeft.coerceIn(0, maxSideW)
        var right = rawRight.coerceIn(0, maxSideW)
        var top = rawTop.coerceIn(0, maxSideH)
        var bottom = rawBottom.coerceIn(0, maxSideH)
        // 双侧相加仍可超过视口一半（两侧各自都没越界时）⇒ 按比例回缩，保证内容区恒为正
        val totalW = left + right
        val limitW = viewWidth / 2
        if (totalW > limitW && totalW > 0) {
            val k = limitW.toFloat() / totalW
            left = (left * k).toInt()
            right = (right * k).toInt()
        }
        val totalH = top + bottom
        val limitH = viewHeight / 2
        if (totalH > limitH && totalH > 0) {
            val k = limitH.toFloat() / totalH
            top = (top * k).toInt()
            bottom = (bottom * k).toInt()
        }
        return EdgeInsets(left, right, top, bottom)
    }

    /**
     * 计算插画几何。可用区为「视口 − insets」；宽屏下插画占可用**宽**的 [WIDE_ART_WIDTH_RATIO]，
     * 竖屏下占可用**高**的 [PORTRAIT_ART_HEIGHT_RATIO]；两者都再乘 `artScale`（0.7..1.2）。
     * ★不变式：`height/width` 恒等于 [Scene.ART_ASPECT]（**比例不随 artScale 变**），
     * 且尺寸随 `artScale` 单调增。
     */
    fun artGeometry(
        availableWidth: Float,
        availableHeight: Float,
        artScale: Float,
        wide: Boolean
    ): ArtGeometry? {
        if (availableWidth <= 0f || availableHeight <= 0f) return null
        // 区间**单源**在模型（`EpubLoadingTemplate.ART_SCALE_RANGE`），此处只做夹取、不另立常量
        val scale = artScale.coerceIn(
            EpubLoadingTemplate.ART_SCALE_RANGE.start, EpubLoadingTemplate.ART_SCALE_RANGE.endInclusive
        )
        val base = if (wide) availableWidth * WIDE_ART_WIDTH_RATIO else availableHeight * PORTRAIT_ART_HEIGHT_RATIO
        var width = base * scale
        // 超宽/超高的兜底：任一边超可用区就等比回缩（仍保持比例）
        val maxWidth = availableWidth
        val maxHeight = availableHeight
        if (width > maxWidth || width * Scene.ART_ASPECT > maxHeight) {
            val byW = maxWidth / width
            val byH = maxHeight / (width * Scene.ART_ASPECT)
            width *= minOf(byW, byH)
        }
        if (width <= 0f) return null
        return ArtGeometry(width, width * Scene.ART_ASPECT)
    }

    /** 宽屏时插画占可用宽的比例 / 竖屏时占可用高的比例。 */
    const val WIDE_ART_WIDTH_RATIO = 0.42f
    const val PORTRAIT_ART_HEIGHT_RATIO = 0.34f

    /**
     * 文字块缩放：文字按原字号排完后若超出可用宽，则等比缩到 `≤ 1`（**不裁剪**：宁可小一点，
     * 也不能让题记被切掉）。返回 `min(1, availW / bodyW)`；非法入参返回 1。
     */
    fun textScale(bodyWidth: Float, availableWidth: Float): Float {
        if (bodyWidth <= 0f || availableWidth <= 0f) return 1f
        return minOf(1f, availableWidth / bodyWidth)
    }
}

