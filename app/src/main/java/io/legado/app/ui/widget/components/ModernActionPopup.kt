package io.legado.app.ui.widget.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 通用弹层菜单（全仓 PopupMenu/菜单入口替代，公共组件库三期菜单族）。
 *
 * [DropdownMenu] 容器，Action(title,icon,invoke) 数据驱动；条目 h12、bodyMedium；
 * 色槽：图标 `primary` / 文字 `onSurface` / destructive `error`；条目 ≥48dp。
 * 规格：ui-standards §3.4 `ModernActionPopup`（task 12.31，from legado-archive）。
 */

data class PopupAction(
    val title: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val invoke: () -> Unit,
)

@Composable
fun ModernActionPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: List<PopupAction>,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
    ) {
        actions.forEach { action ->
            val tint = if (action.destructive) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            }
            val textColor = if (action.destructive) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            DropdownMenuItem(
                text = {
                    Text(
                        text = action.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                    )
                },
                leadingIcon = action.icon?.let { {
                    Icon(
                        imageVector = it,
                        contentDescription = action.title,
                        tint = tint,
                        modifier = Modifier.sizeIn(maxWidth = 20.dp, maxHeight = 20.dp)
                    )
                } },
                onClick = {
                    action.invoke()
                    onDismiss()
                },
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            )
        }
    }
}