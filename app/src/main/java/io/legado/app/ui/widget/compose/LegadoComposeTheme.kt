package io.legado.app.ui.widget.compose

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.legado.app.lib.theme.rememberThemeFontFamilies
import io.legado.app.lib.theme.withThemeFontFamilies

/**
 * 统一的 Compose 主题字体下发。
 *
 * 项目早先只在 AppDialogFrame 等个别作用域里 `LocalTextStyle.copy(fontFamily=...)`，导致大量
 * Compose Text 回退到系统字体、标题字体几乎不生效。用本包裹在 ComposeView 的 setContent 根或
 * 各共享脚手架（AppManagementScaffold / ComposePreferenceScreen / AppDialogFrame）外层，即可让
 * 其内所有未显式指定 fontFamily 的文本都继承主题「界面字体」，标题样式继承「标题字体」。
 *
 * ⚠ NEW-71 单源收口：字体族映射已搬到 `lib/theme/UiTypography.kt`（[rememberThemeFontFamilies] +
 * [withThemeFontFamilies]），并由**唯一主题入口** `ui/theme/LegadoTheme` 统一执行 ⇒ 本组件已属
 * **冗余**，仅保留以兼容既有嵌套写法（`LegadoTheme { LegadoComposeTheme { … } }`，重复下发幂等）。
 * 新代码禁止再把它单独当作主题作用域（G-37 仍会拦「内容根只有 LegadoComposeTheme」）。
 */
@Composable
fun LegadoComposeTheme(content: @Composable () -> Unit) {
    val (bodyFamily, titleFamily) = rememberThemeFontFamilies()
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography.withThemeFontFamilies(bodyFamily, titleFamily)
    ) {
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = bodyFamily)
        ) {
            content()
        }
    }
}
