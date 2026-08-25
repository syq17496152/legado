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
 * - surface 族：背景色锚定中性面（明/暗模式混向白/黑，等价原 lerp 三件套）
 * - 彩色角色：primary=accent、secondary=primary、tertiary=secondary
 * - on* 色：contrastOn（亮底黑 / 暗底白）
 * - error 固定 M3 标准红（暗 #FF5252 / 亮 #E53935）
 *
 * video-player-theme-unify 复诊：必须映射**全部 34 槽位**。此前仅映射 18 个，
 * primaryContainer（开关选中轨道）/ surfaceContainerHigh（AlertDialog 容器）/
 * onSecondaryContainer（PanelButton 文字）等 16 个槽位回落到 M3 内置默认紫色系，
 * 导致视频设置面板的开关/单选弹框颜色不随主题。现全部从主题色推导：
 * - primaryContainer = 主题色浅色调容器（开关轨道/进度条等高亮容器）
 * - surfaceContainer 族 = 背景色中性面（弹框/浮层容器锚定主题背景色，非 M3 紫色）
 * - on*Container = 主题文字色 onBg（可读性对齐 View 体系）
 * - surfaceTint = primary（Elevation 表面色调随主题，杜绝紫色 tint）
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

    val neutral = if (isLight) Color.White else Color.Black
    val surface = lerp(bg, neutral, if (isLight) 0.04f else 0.10f)
    val surfaceVariant = lerp(bg, onBg, if (isLight) 0.05f else 0.14f)
    val outline = lerp(bg, onBg, if (isLight) 0.12f else 0.24f)

    val onPrimary = contrastOn(primaryC)
    val onSecondary = contrastOn(secondaryC)

    // 补全槽位推导（视频设置面板开关/单选弹框随主题的关键）
    val primaryContainer = lerp(surface, primaryC, if (isLight) 0.12f else 0.30f)
    val surfaceContainerLowest = lerp(bg, neutral, if (isLight) 0.02f else 0.06f)
    val surfaceContainerLow = lerp(bg, neutral, if (isLight) 0.03f else 0.08f)
    val surfaceContainerHigh = lerp(bg, neutral, if (isLight) 0.06f else 0.16f)
    val surfaceContainerHighest = lerp(bg, neutral, if (isLight) 0.08f else 0.20f)
    val error = if (isLight) Color(0xFFE53935) else Color(0xFFFF5252)
    val onError = if (isLight) Color.White else Color.Black
    val errorContainer = lerp(surface, error, if (isLight) 0.12f else 0.28f)
    val inverseSurface = if (isLight) Color(0xFF322F35) else Color(0xFFE6E0E9)
    val inverseOnSurface = contrastOn(inverseSurface)

    val scheme = if (isLight) {
        lightColorScheme(
            primary = primaryC,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onBg,
            inversePrimary = primaryC,
            secondary = secondaryC,
            onSecondary = onSecondary,
            secondaryContainer = surfaceVariant,
            onSecondaryContainer = onBg,
            tertiary = secondaryC,
            onTertiary = onSecondary,
            tertiaryContainer = surfaceVariant,
            onTertiaryContainer = onBg,
            background = bg,
            onBackground = onBg,
            surface = surface,
            onSurface = onBg,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onBgVariant,
            surfaceTint = primaryC,
            surfaceContainerLowest = surfaceContainerLowest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainer = surface,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            outline = outline,
            outlineVariant = outline.copy(alpha = 0.75f),
            scrim = Color.Black,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = error
        )
    } else {
        darkColorScheme(
            primary = primaryC,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onBg,
            inversePrimary = primaryC,
            secondary = secondaryC,
            onSecondary = onSecondary,
            secondaryContainer = surfaceVariant,
            onSecondaryContainer = onBg,
            tertiary = secondaryC,
            onTertiary = onSecondary,
            tertiaryContainer = surfaceVariant,
            onTertiaryContainer = onBg,
            background = bg,
            onBackground = onBg,
            surface = surface,
            onSurface = onBg,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onBgVariant,
            surfaceTint = primaryC,
            surfaceContainerLowest = surfaceContainerLowest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainer = surface,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            outline = outline,
            outlineVariant = outline.copy(alpha = 0.8f),
            scrim = Color.Black,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = error
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