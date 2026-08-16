package io.legado.app.ui.widget.components

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * M3 AlertDialog 封装（公共组件库三期 Dialog 族）。
 *
 * 卡 **18dp** 圆角；title `titleLarge` / body `bodyMedium`；
 * 确认钮 `primary` / destructive 确认钮 `error`；按钮 ≥48dp。
 * 规格：ui-standards §3.4 `AppConfirmDialog`（task 12.34，from Legado_Max 14 处实战）。
 */
@Composable
fun AppConfirmDialog(
    title: String,
    body: String? = null,
    confirmText: String = "确定",
    dismissText: String = "取消",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    val confirmColor = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = body?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmText,
                    color = confirmColor,
                    // 保证按钮 ≥48dp 触控高度
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = dismissText,
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                )
            }
        },
        modifier = modifier,
    )
}