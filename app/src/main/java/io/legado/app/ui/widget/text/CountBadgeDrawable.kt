package io.legado.app.ui.widget.text

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import androidx.annotation.ColorInt
import io.legado.app.R

/**
 * F143（ui-subpage-optimization）：分类 Tab 未读数徽标。
 *
 * 以 compound drawable 形式挂在分类 TextView 右侧——不改 Tab 的 View 结构，选中态仍由
 * 分类 TextView 自身承担（避免 `updateTabSelection` / 无障碍树的选中语义被容器截走）。
 * 胶囊高度按字号、宽度按文本自适应，单字符时退化为圆形；计数超过 [MAX_DISPLAY] 时封顶
 * 显示（如 `999+`），避免长数字撑破胶囊。
 */
class CountBadgeDrawable(
    context: Context,
    count: Int,
    @ColorInt badgeColor: Int,
    @ColorInt textColor: Int
) : Drawable() {

    private val text: String = if (count > MAX_DISPLAY) "$MAX_DISPLAY+" else count.toString()
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        typeface = Typeface.DEFAULT_BOLD
        textSize = context.resources.getDimension(R.dimen.text_10sp)
        textAlign = Paint.Align.CENTER
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = badgeColor }
    private val rect = RectF()
    private val width: Int
    private val height: Int

    init {
        val density = context.resources.displayMetrics.density
        val fm = textPaint.fontMetrics
        val minSize = (BADGE_MIN_DP * density).toInt()
        height = ((fm.descent - fm.ascent) + BADGE_V_PADDING_DP * density * 2)
            .toInt().coerceAtLeast(minSize)
        width = (textPaint.measureText(text) + BADGE_H_PADDING_DP * density * 2)
            .toInt().coerceAtLeast(height)
    }

    override fun getIntrinsicWidth(): Int = width

    override fun getIntrinsicHeight(): Int = height

    override fun draw(canvas: Canvas) {
        val radius = bounds.height() / 2f
        rect.set(bounds)
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        val fm = textPaint.fontMetrics
        // 基线补偿：让字面（而非行框）垂直居中，否则数字视觉偏上
        val baseline = bounds.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, bounds.centerX().toFloat(), baseline, textPaint)
    }

    override fun setAlpha(alpha: Int) {
        textPaint.alpha = alpha
        bgPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        textPaint.colorFilter = colorFilter
        bgPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** 计数超过该值时封顶显示（如 999+） */
        private const val MAX_DISPLAY = 999

        private const val BADGE_MIN_DP = 16f
        private const val BADGE_H_PADDING_DP = 5f
        private const val BADGE_V_PADDING_DP = 2f
    }
}