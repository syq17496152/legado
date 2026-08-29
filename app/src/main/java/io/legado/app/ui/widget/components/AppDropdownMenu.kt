package io.legado.app.ui.widget.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.widget.compose.LegadoMiuixChoiceRow
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

/**
 * 顶栏/条目「更多」下拉菜单（替代页面私有 PopupMenu，§7 第 6 步门禁）。
 *
 * H8（2026-08-27）：复用 [MenuAction] 数据驱动；保留 M3 [DropdownMenu] 定位/滚动/点外关闭能力，
 * 面板与条目渲染层改为 `rememberAppDialogStyle()` 取色——调用点签名零改动（44 文件 59 调用点）。
 *
 * H16（2026-08-28）：六项差异对齐 ModernActionPopup 视觉语言（用户实锤"菜单不符"主因）——
 * ①条目行组件统一 [LegadoMiuixChoiceRow]（选中 accent 14% 底/actionRadius 圆角/可撑高）
 * ②面板+条目字体 bodyFontFamily ③面板海拔 tonal/shadow 0dp（消 M3 默认投影差异）
 * ④主题设置面板描边时补条件 1dp 边框（对齐 UiCorner.panelBorderColor 口径）
 * ⑤面板宽度 124~244dp 策略（同 ModernActionPopup MIN/MAX）
 * ⑥行高 44dp 固定 → 42dp minHeight 可撑高。
 */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: List<MenuAction>,
    modifier: Modifier = Modifier
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    // 仅当主题确实设置了面板边框色才画边框；无边框主题不显描边线（同 ModernActionPopup）
    val hasPanelBorder = UiCorner.panelBorderColor(LocalContext.current) != null
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier.widthIn(min = 124.dp, max = 244.dp),
        shape = RoundedCornerShape(style.panelRadius),
        containerColor = style.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = if (hasPanelBorder) BorderStroke(1.dp, style.stroke) else null
    ) {
        actions.forEach { action ->
            if (action.header) {
                Text(
                    text = action.title,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = style.bodyFontFamily),
                    color = action.tint ?: style.secondaryText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            } else {
                LegadoMiuixChoiceRow(
                    text = action.title,
                    selected = action.checked == true,
                    palette = palette,
                    onClick = {
                        onDismiss()
                        action.onClick()
                    },
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                    minHeight = 42.dp,
                    compact = true,
                    showSelectedMark = action.checked != null,
                    leadingIcon = action.icon,
                    tint = action.tint,
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}
