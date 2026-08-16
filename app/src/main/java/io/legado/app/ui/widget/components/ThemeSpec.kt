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

    return if (isLight) {
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