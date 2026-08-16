package io.legado.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.ui.widget.components.ThemeSpec
import io.legado.app.ui.widget.components.toM3Scheme
import io.legado.app.utils.ColorUtils

/**
 * Legado Compose 主题（F-P0-1 调试工具集，借鉴蛋蛋Max）
 *
 * 将 Legado 原生 ThemeStore 的颜色配置映射到 Material3 ColorScheme，
 * 使 Compose 组件能跟随用户主题色。
 *
 * 色板推导统一收敛到 [ThemeSpec.toM3Scheme]（5 核心色→34 槽位，AD-18）。
 *
 * Typography：与 View 体系字号对齐（View ToolbarTitle=20sp / 正文 14-16sp），
 * 消除 Compose 页与 View 页同屏时的字号不协调（主题统一 AD-19）。
 */
val LegadoTypography = Typography(
    // 顶栏/大标题：对齐 View ToolbarTitle 20sp（原 M3 默认 titleLarge=22sp）
    titleLarge = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.Medium
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium
    ),
    // 正文：对齐 View 14-16sp
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    // 标签/辅助文字
    labelLarge = TextStyle(fontSize = 14.sp),
    labelMedium = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp),
    // 展示/标题族保持 M3 层级
    displaySmall = TextStyle(fontSize = 36.sp),
    headlineMedium = TextStyle(fontSize = 28.sp),
    headlineSmall = TextStyle(fontSize = 24.sp)
)

@Composable
fun LegadoTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    val isNightTheme = AppConfig.isNightTheme
    val primaryColorValue = ThemeStore.primaryColor(context)
    val accentColor = ThemeStore.accentColor(context)
    val bgColor = ThemeStore.backgroundColor(context)
    val textPrimaryColor = ThemeStore.textColorPrimary(context)
    val textSecondaryColor = ThemeStore.textColorSecondary(context)

    val isLight = !isNightTheme && ColorUtils.isColorLight(bgColor)

    val colorScheme = remember(
        isNightTheme, primaryColorValue, accentColor, bgColor,
        textPrimaryColor, textSecondaryColor
    ) {
        ThemeSpec(
            primary = Color(accentColor),
            secondary = Color(primaryColorValue),
            accent = Color(accentColor),
            background = Color(bgColor),
            textPrimary = Color(textPrimaryColor),
            textSecondary = Color(textSecondaryColor),
            isLight = isLight
        ).toM3Scheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LegadoTypography
    ) {
        content()
    }
}