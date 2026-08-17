package io.legado.app.ui.widget.components

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import io.legado.app.utils.ColorUtils as LegadoColorUtils

/**
 * 主题实体：5 核心色 → M3 34 槽位推导（收敛 AD-12/AD-18 的落地算法）。
 *
 * 只做运行时内存推导，不改写 themeConfig.json / SharedPreferences 旧格式。
 */
data class ThemeSpec(
    val primary: Color,
    val secondary: Color,
    val accent: Color,
    val background: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val isLight: Boolean
)

/**
 * 5 色→34 槽位公式（MoRealm 思路，仅算法参考不抄代码）：
 * - surface 族：背景色锚定中性面（明/暗模式混向白/黑 4%/10%，等价原 lerp 三件套）
 * - 彩色角色：primary=accent、secondary=primary、tertiary=secondary
 * - on* 色：contrastOn（亮底黑 / 暗底白）
 * - error 固定 M3 标准红（暗 #FF5252 / 亮 #E53935）
 *
 * 生成后经 [withContrastGuard] 后处理（Archive 思路）：文字槽位对实际容器槽位
 * 校验最低对比度，撞色时跨昼夜取对比度更高的 M3 中性文字色兜底，
 * 防自定义主题出现「不可读」组合。
 */
fun ThemeSpec.toM3Scheme(): ColorScheme {
    val isLight = isLight
    val bg = background
    val onBg = textPrimary
    val onBgVariant = textSecondary
    val accentC = accent
    val primaryC = primary
    val secondaryC = secondary

    val surface = lerp(bg, if (isLight) Color.White else Color.Black, if (isLight) 0.04f else 0.10f)
    val surfaceVariant = lerp(bg, onBg, if (isLight) 0.05f else 0.14f)
    val outline = lerp(bg, onBg, if (isLight) 0.12f else 0.24f)

    val onPrimary = contrastOn(primaryC)
    val onSecondary = contrastOn(secondaryC)

    val scheme = if (isLight) {
        lightColorScheme(
            primary = primaryC,
            secondary = secondaryC,
            tertiary = secondaryC,
            background = bg,
            surface = surface,
            surfaceVariant = surfaceVariant,
            secondaryContainer = surfaceVariant,
            tertiaryContainer = surfaceVariant,
            outline = outline,
            outlineVariant = outline.copy(alpha = 0.75f),
            onPrimary = onPrimary,
            onSecondary = onSecondary,
            onTertiary = onSecondary,
            onBackground = onBg,
            onSurface = onBg,
            onSurfaceVariant = onBgVariant,
            error = Color(0xFFE53935),
            onError = Color.White
        )
    } else {
        darkColorScheme(
            primary = primaryC,
            secondary = secondaryC,
            tertiary = secondaryC,
            background = bg,
            surface = surface,
            surfaceVariant = surfaceVariant,
            secondaryContainer = surfaceVariant,
            tertiaryContainer = surfaceVariant,
            outline = outline,
            outlineVariant = outline.copy(alpha = 0.8f),
            onPrimary = onPrimary,
            onSecondary = onSecondary,
            onTertiary = onSecondary,
            onBackground = onBg,
            onSurface = onBg,
            onSurfaceVariant = onBgVariant,
            error = Color(0xFFFF5252),
            onError = Color.Black
        )
    }
    return scheme.withContrastGuard()
}

/** 最低字面/容器对比度（防完全不可读，非 WCAG 合规线；Archive 同源阈值） */
private const val MIN_FONT_SURFACE_CONTRAST = 1.3

/** M3 中性文字兜底色（跨昼夜二选一，取对比度更高者） */
private val contrastFallbackLight = Color(0xFF1D1B20)
private val contrastFallbackDark = Color(0xFFE6E0E9)

/**
 * 主题撞色守卫（from legado-archive ThemeConfig 后处理 pass）：
 * onSurface/onBackground/onSurfaceVariant 对 surface/background 校验对比度，
 * 低于阈值时用「对比度更高的 M3 中性文字色」替换，保证任何自定义主题下文字可读。
 */
private fun ColorScheme.withContrastGuard(): ColorScheme {
    fun guard(foreground: Color, container: Color): Color {
        // 压平 alpha：calculateContrast 要求不透明色，主题色可能带透明度（如 #fde5e5e5）
        val opaqueMask = -0x1000000 // 0xFF000000
        val fg = foreground.toArgb() or opaqueMask
        val bg = container.toArgb() or opaqueMask
        if (ColorUtils.calculateContrast(fg, bg) >= MIN_FONT_SURFACE_CONTRAST) {
            return foreground
        }
        val lightC = contrastFallbackLight.toArgb()
        val darkC = contrastFallbackDark.toArgb()
        return if (
            ColorUtils.calculateContrast(lightC, bg) >= ColorUtils.calculateContrast(darkC, bg)
        ) {
            contrastFallbackLight
        } else {
            contrastFallbackDark
        }
    }

    return copy(
        onSurface = guard(onSurface, surface),
        onBackground = guard(onBackground, background),
        onSurfaceVariant = guard(onSurfaceVariant, surfaceVariant)
    )
}

/** contrastOn：亮底取黑、暗底取白 */
fun contrastOn(color: Color): Color =
    if (LegadoColorUtils.isColorLight(color.toArgb())) Color.Black else Color.White

/** hueShift：HSL 旋转色相（tertiary 相临色用，MoRealm 同源思路），度数如 60f */
fun Color.hueShift(degrees: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(this.toArgb(), hsl)
    var h = (hsl[0] + degrees / 360f) % 1f
    if (h < 0f) h += 1f
    hsl[0] = h
    return Color(ColorUtils.HSLToColor(hsl))
}