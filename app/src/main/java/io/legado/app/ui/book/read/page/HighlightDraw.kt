package io.legado.app.ui.book.read.page

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import io.legado.app.help.HighlightGeometry
import io.legado.app.help.HighlightStyle
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.dpToPx

/**
 * 高亮「文字装饰」绘制。逐列装饰(着重号)与按 run 装饰(下划线/删除线/方框)。
 * 文字底色填充不在此处(见 ContentTextView.highlightPaint)。
 */
object HighlightDraw {

    private val strokePaint by lazy {
        Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE }
    }
    private val fillPaint by lazy {
        Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    }
    private val dash by lazy { DashPathEffect(floatArrayOf(6f.dpToPx(), 4f.dpToPx()), 0f) }
    private val dot by lazy { DashPathEffect(floatArrayOf(2f.dpToPx(), 3f.dpToPx()), 0f) }
    private val wavePath = Path()

    /** R1a：胶囊填充用（端部两半径可不同，需 8 值 radii 版本） */
    private val fillPath = Path()
    private val fillRect = RectF()

    private fun lineWidth() = 1.5f.dpToPx()

    /**
     * R3：段首缩进列是否允许渲染该高亮样式。
     *
     * 规则：**装饰类**（下划线 / 删除线 / 方框 / 着重号）不覆盖缩进列
     * （否则方框会把段首空白一起框住、下划线从缩进处起画）；
     * **纯色背景填充照常渲染** —— 整段底色若跳过缩进会形成明显缺口。
     *
     * 注：R1a 引入 `fillShape` 后，此处需追加「非矩形形状填充同样跳过缩进列」的判定。
     */
    fun shouldRenderOnIndentColumn(style: HighlightStyle?): Boolean {
        if (style == null) return false
        val isDecoration = style.underline != null || style.strike != null ||
            style.box != null || style.emphasis != null
        // R1a：非矩形填充形状同样跳过缩进列（矩形填充照常铺满，避免整段底色在缩进处缺口）
        if (style.hasNonRectFillShape) return false
        return !isDecoration
    }

    /**
     * R1a：按「合并后的 run」绘制背景填充（6 形状）。
     *
     * 坐标语义固定：`x0..x1` 为 run 左右边界、`top..bottom` 为**行内** y 边界
     * （调用点已在行级平移 `withTranslation(0, lineTop)`，故与改造前的逐列矩形**逐像素等价**）。
     */
    fun drawFillRun(
        canvas: Canvas,
        x0: Float, x1: Float, top: Float, bottom: Float,
        fill: Int,
        shape: HighlightStyle.FillShape,
        pillLeftRadius: Float, pillRightRadius: Float
    ) {
        if (x1 <= x0 || bottom <= top) return
        fillPaint.color = fill
        when (shape) {
            HighlightStyle.FillShape.ROUNDED -> {
                val r = minOf(3f.dpToPx(), (bottom - top) / 2f, (x1 - x0) / 2f)
                canvas.drawRoundRect(x0, top, x1, bottom, r, r, fillPaint)
            }

            HighlightStyle.FillShape.PILL -> {
                val maxR = minOf((bottom - top) / 2f, (x1 - x0) / 2f)
                val lr = pillLeftRadius.coerceIn(0f, maxR)
                val rr = pillRightRadius.coerceIn(0f, maxR)
                fillRect.set(x0, top, x1, bottom)
                fillPath.reset()
                // 8 值 radii 顺序：左上、右上、右下、左下
                fillPath.addRoundRect(
                    fillRect,
                    floatArrayOf(lr, lr, rr, rr, rr, rr, lr, lr),
                    Path.Direction.CW
                )
                canvas.drawPath(fillPath, fillPaint)
            }

            // 矩形 / 荧光笔 / 半高 / 基线带：y 范围已由 HighlightGeometry.fillBand 收窄
            else -> canvas.drawRect(x0, top, x1, bottom, fillPaint)
        }
    }

    /** applyTextStyle 需还原的原始 Paint 状态 */
    class SavedTextStyle(val bold: Boolean, val skew: Float, val typeface: Typeface?)

    /** 用样式配置文字 Paint(加粗/斜体/自定义字体/阴影)。返回需要还原的原值以便调用方复位。 */
    fun applyTextStyle(paint: Paint, style: HighlightStyle): SavedTextStyle {
        val saved = SavedTextStyle(paint.isFakeBoldText, paint.textSkewX, paint.typeface)
        paint.isFakeBoldText = saved.bold || style.bold
        if (style.italic) paint.textSkewX = -0.25f
        if (style.fontPath.isNotEmpty()) {
            ChapterProvider.getHighlightTypeface(style.fontPath)?.let { paint.typeface = it }
        }
        // R1a：文字阴影（color == 0 表示跟随当前字色）
        style.shadow?.let { s ->
            paint.setShadowLayer(s.radius, s.dx, s.dy, if (s.color != 0) s.color else paint.color)
        }
        return saved
    }

    fun restoreTextStyle(paint: Paint, saved: SavedTextStyle) {
        paint.isFakeBoldText = saved.bold
        paint.textSkewX = saved.skew
        paint.typeface = saved.typeface
        // D2：Paint 无法读回阴影（无 getter）→ 必须显式清除，否则会泄漏到后续列/行
        paint.clearShadowLayer()
    }

    /** 着重号:每列一个字下圆点(逐列调用) */
    fun drawEmphasis(canvas: Canvas, start: Float, end: Float, height: Float, color: Int) {
        val r = 1.6f.dpToPx()
        val cy = height - r - 0.5f.dpToPx()
        fillPaint.color = color
        canvas.drawCircle((start + end) / 2f, cy, r, fillPaint)
    }

    /**
     * 按 run 画线类/方框装饰。x0..x1 为连续同样式列的合并区间。
     * baseline = textLine.lineBase - textLine.lineTop;height = textLine.height。
     */
    fun drawRun(
        canvas: Canvas, x0: Float, x1: Float, baseline: Float, height: Float,
        underline: HighlightStyle.Underline?, strike: HighlightStyle.Deco?,
        box: HighlightStyle.Deco?, fallbackColor: Int
    ) {
        strokePaint.strokeWidth = lineWidth()
        strokePaint.pathEffect = null

        underline?.let { u ->
            val color = if (u.color != 0) u.color else fallbackColor
            strokePaint.color = color
            // B2-④：线宽/线距来自样式（未设置 → 回退 1.5dp / 2dp，与改造前硬编码一致，观感零回归）
            val width = u.resolvedWidth.dpToPx()
            strokePaint.strokeWidth = width
            val y = height - u.resolvedDistance.dpToPx()
            when (u.kind) {
                HighlightStyle.Kind.SOLID -> canvas.drawLine(x0, y, x1, y, strokePaint)
                HighlightStyle.Kind.DOUBLE -> {
                    canvas.drawLine(x0, y, x1, y, strokePaint)
                    // 第二线间距随线宽推导（默认线宽 1.5dp → 2dp，与原硬编码一致）
                    val gap = width + 0.5f.dpToPx()
                    canvas.drawLine(x0, y + gap, x1, y + gap, strokePaint)
                }
                HighlightStyle.Kind.DASHED -> {
                    strokePaint.pathEffect = dash
                    canvas.drawLine(x0, y, x1, y, strokePaint)
                    strokePaint.pathEffect = null
                }
                HighlightStyle.Kind.DOTTED -> {
                    strokePaint.pathEffect = dot
                    canvas.drawLine(x0, y, x1, y, strokePaint)
                    strokePaint.pathEffect = null
                }
                HighlightStyle.Kind.WAVY -> {
                    // 振幅不小于 1dp 且随线宽适配（默认线宽 1.5dp → 1.5dp，与原硬编码一致）
                    val amplitude = maxOf(1f.dpToPx(), width)
                    val pts = HighlightGeometry.wavePoints(
                        x0, x1, y - 1f.dpToPx(), amplitude, 6f.dpToPx(), 2f.dpToPx()
                    )
                    if (pts.size >= 4) {
                        wavePath.reset()
                        wavePath.moveTo(pts[0], pts[1])
                        var i = 2
                        while (i < pts.size) { wavePath.lineTo(pts[i], pts[i + 1]); i += 2 }
                        canvas.drawPath(wavePath, strokePaint)
                    }
                }
            }
            // 复位线宽：strokePaint 为删除线/方框共用，不复位会把它们一并加粗
            strokePaint.strokeWidth = lineWidth()
        }

        strike?.let { s ->
            strokePaint.color = if (s.color != 0) s.color else fallbackColor
            val y = baseline - (baseline) * 0.30f
            canvas.drawLine(x0, y, x1, y, strokePaint)
        }

        box?.let { bx ->
            strokePaint.color = if (bx.color != 0) bx.color else fallbackColor
            val inset = 0.5f.dpToPx()
            canvas.drawRect(x0 + inset, inset, x1 - inset, height - inset, strokePaint)
        }
    }
}
