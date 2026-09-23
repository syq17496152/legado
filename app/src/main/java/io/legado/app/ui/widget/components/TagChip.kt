package io.legado.app.ui.widget.components

import io.legado.app.ui.widget.components.AppShapes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.lib.theme.rememberThemeUiPalette

/**
 * 小型文本标签（原 ExploreShowScreen/ImportBookScreen 同名私有 TagChip 收敛，task 12.1A）。
 *
 * 语义：chip 底 + 主文本字，圆角统一 AppShapes.Chip(8dp)，可通过 [color]/[contentColor] 覆盖默认配色。
 *
 * 取色（R28 收口，2026-09-23）：底走**面 token 归属表**的 chip 唯一 token `tabBackgroundColor`
 * （`ui-standards/color.md` §六）。改造前取 M3 派生键，而本项目 `ThemeSpec` 把该键映射为
 * 背景与前景之间的 lerp 偏移色 ⇒ 只随背景微移、**不随主题色变**（换主题色时 chip 底看不出
 * 变化，即用户报障「部分标签不随主题设置变化」的根因之一）。
 * 字色沿用 `onSecondaryContainer`——在 `ThemeSpec` 中它被映射为主题主文本色（非派生偏移色）。
 */
@Composable
fun TagChip(
    text: String,
    color: Color = Color(rememberThemeUiPalette().tabBackgroundColor),
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = AppShapes.Chip,
        color = color,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
