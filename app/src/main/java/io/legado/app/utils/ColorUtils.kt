package io.legado.app.utils

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.annotation.FloatRange
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 字体/容器最低对比度下限（**运行时统一口径单源**，AD-16 / A3.4b）。
 *
 * XML 侧（`ThemeConfig` 的撞色 sanitize）与 Compose 侧（`ThemeSpec.withContrastGuard`）
 * **必须复用本常量**，禁止各自声明——改造前两处分别为 `1.3` / `3.0f`，
 * 导致同主题下 View 页与 Compose 页的文字改写分叉。
 *
 * ⚠️ 本值是**运行时兜底下限**（低于它才替换文字色），验收标准仍按 WCAG AA ≥4.5:1 执行。
 */
const val MIN_FONT_SURFACE_CONTRAST = 3.0

@Suppress("unused", "MemberVisibilityCanBePrivate")
object ColorUtils {

    fun isColorLight(@ColorInt color: Int): Boolean {
        return ColorUtils.calculateLuminance(color) >= 0.5
    }

    /**
     * 前景对比色单源（R31，2026-09-23）：亮底取黑、暗底取白。
     *
     * View 侧与 Compose 侧**必须共用本函数**：Compose 的 `contrastOn`（ThemeSpec）与
     * View 的 `RoundedTagBarView.readableTagTextColor` 均转发此处，禁止各自实现——
     * 改造前曾三套并存（`readableTagTextColor` / `badgeTextBright` / `contrastOn`），
     * 导致同主题下不同控件的兜底文字色分叉。
     *
     * 本函数是 `theme-consistency-iron-rule` §四 S3 的**登记豁免点**（对比度兜底真值单源，
     * 见 `ai_tests/config/theme_token_allowlist.json`）。
     */
    @ColorInt
    fun contrastOnColor(@ColorInt background: Int): Int =
        if (isColorLight(background)) Color.BLACK else Color.WHITE

    fun intToString(intColor: Int): String {
        return String.format("#%06X", 0xFFFFFF and intColor)
    }

    fun stripAlpha(@ColorInt color: Int): Int {
        return -0x1000000 or color
    }

    @ColorInt
    fun shiftColor(@ColorInt color: Int, @FloatRange(from = 0.0, to = 2.0) by: Float): Int {
        if (by == 1f) return color
        val alpha = Color.alpha(color)
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] *= by // value component
        return (alpha shl 24) + (0x00ffffff and Color.HSVToColor(hsv))
    }

    @ColorInt
    fun darkenColor(@ColorInt color: Int): Int {
        return shiftColor(color, 0.9f)
    }

    @ColorInt
    fun lightenColor(@ColorInt color: Int): Int {
        return shiftColor(color, 1.1f)
    }

    @ColorInt
    fun invertColor(@ColorInt color: Int): Int {
        val r = 255 - Color.red(color)
        val g = 255 - Color.green(color)
        val b = 255 - Color.blue(color)
        return Color.argb(Color.alpha(color), r, g, b)
    }

    @ColorInt
    fun adjustAlpha(@ColorInt color: Int, @FloatRange(from = 0.0, to = 1.0) factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).roundToInt()
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return Color.argb(alpha, red, green, blue)
    }

    @ColorInt
    fun withAlpha(@ColorInt baseColor: Int, @FloatRange(from = 0.0, to = 1.0) alpha: Float): Int {
        val a = min(255, max(0, (alpha * 255).toInt())) shl 24
        val rgb = 0x00ffffff and baseColor
        return a + rgb
    }

    /**
     * Taken from CollapsingToolbarLayout's CollapsingTextHelper class.
     */
    fun blendColors(color1: Int, color2: Int, @FloatRange(from = 0.0, to = 1.0) ratio: Float): Int {
        val inverseRatio = 1f - ratio
        val a = Color.alpha(color1) * inverseRatio + Color.alpha(color2) * ratio
        val r = Color.red(color1) * inverseRatio + Color.red(color2) * ratio
        val g = Color.green(color1) * inverseRatio + Color.green(color2) * ratio
        val b = Color.blue(color1) * inverseRatio + Color.blue(color2) * ratio
        return Color.argb(a.toInt(), r.toInt(), g.toInt(), b.toInt())
    }

    fun argb(r: Int, g: Int, b: Int): Int {
        return argb(Byte.MAX_VALUE.toInt(), r, g, b)
    }

    fun argb(alpha: Int, r: Int, g: Int, b: Int): Int {
        val colorByteArr =
            byteArrayOf(alpha.toByte(), r.toByte(), g.toByte(), b.toByte())
        return byteArrToInt(colorByteArr)
    }

    fun rgb(argb: Int): IntArray {
        return intArrayOf(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF)
    }

    fun byteArrToInt(colorByteArr: ByteArray): Int {
        return ((colorByteArr[0].toInt() shl 24) + (colorByteArr[1].toInt() and 0xFF shl 16)
                + (colorByteArr[2].toInt() and 0xFF shl 8) + (colorByteArr[3].toInt() and 0xFF))
    }

    /**
     * Computes the difference between two RGB colors by converting them to the L*a*b scale and
     * comparing them using the CIE76 algorithm { http://en.wikipedia.org/wiki/Color_difference#CIE76}
     */
    fun getColorDifference(a: Int, b: Int): Double {
        val lab1 = DoubleArray(3)
        val lab2 = DoubleArray(3)
        ColorUtils.colorToLAB(a, lab1)
        ColorUtils.colorToLAB(b, lab2)
        return sqrt(
            (lab2[0] - lab1[0])
                .pow(2.0) + (lab2[1] - lab1[1])
                .pow(2.0) + (lab2[2] - lab1[2])
                .pow(2.0)
        )
    }
}
