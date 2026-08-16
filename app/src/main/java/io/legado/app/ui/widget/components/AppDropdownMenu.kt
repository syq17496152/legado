package io.legado.app.ui.widget.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 顶栏/条目「更多」下拉菜单（替代页面私有 PopupMenu，§7 第 6 步门禁）。
 *
 * 复用 [MenuAction] 数据驱动：M3 [DropdownMenu] + [DropdownMenuItem] 逐项渲染。
 * 用法：`Box { trigger; AppDropdownMenu(expanded, onDismiss, actions) }`。
 */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: List<MenuAction>,
    modifier: Modifier = Modifier
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier
    ) {
        actions.forEach { action ->
            if (action.header) {
                Text(
                    text = action.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = action.tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            } else {
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = null,
                                tint = action.tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(22.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = action.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = action.tint ?: MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (action.checked == true) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.width(20.dp)
                                )
                            }
                        }
                    },
                    onClick = {
                        onDismiss()
                        action.onClick()
                    },
                    modifier = Modifier.height(48.dp)
                )
            }
        }
    }
}
