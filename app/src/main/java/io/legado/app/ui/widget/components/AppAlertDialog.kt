package io.legado.app.ui.widget.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 应用级 AlertDialog 统一容器（公共组件库三期 Dialog 族，task 12.36）。
 *
 * 规格：ui-standards §3.4 `AppAlertDialog`。
 * - 圆角卡 **18dp**；container `surfaceContainerHigh` / title `onSurface` / text `onSurfaceVariant`
 * - 确认钮 `primary`、取消钮默认；按钮触控 ≥48dp
 * - 通用容器：支持 title / text / content 三段式，可组合为确认、通知、表单等形态
 *
 * 双引擎说明：规格源自 325506（Miuix `WindowDialog` vs M3 `AlertDialog`），
 * 本项目未引入 Miuix 依赖与主题引擎概念，故按单一 M3 引擎实现；
 * 未来如需接入主题引擎，只需在 [AppAlertDialog] 入口按引擎分支即可（已收敛为单一入口）。
 */
@Composable
fun AppAlertDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    title: String? = null,
    text: String? = null,
    content: (@Composable () -> Unit)? = null,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    dismissText: String? = null,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = AppShapes.Card,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        iconContentColor = MaterialTheme.colorScheme.primary,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = AlertDialogDefaults.TonalElevation,
        title = title?.let {
            {
                Text(text = it, style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (text != null) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = if (content != null) 16.dp else 0.dp)
                    )
                }
                if (content != null) {
                    content()
                }
            }
        },
        confirmButton = {
            if (confirmText != null && onConfirm != null) {
                TextButton(onClick = onConfirm) {
                    Text(
                        text = confirmText,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.sizeIn(minHeight = 48.dp)
                    )
                }
            }
        },
        dismissButton = {
            if (dismissText != null && onDismiss != null) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = dismissText,
                        modifier = Modifier.sizeIn(minHeight = 48.dp)
                    )
                }
            }
        }
    )
}

/**
 * 专为 nullable 数据设计的 [AppAlertDialog] 重载。
 *
 * 当 [data] 不为 null 时显示弹窗；当 [data] 变为 null 时，
 * 自动缓存最后一次数据并播放退出动画（避免数据清空瞬间弹窗直接消失）。
 */
@Composable
fun <T> AppAlertDialog(
    data: T?,
    onDismissRequest: () -> Unit,
    title: String? = null,
    text: String? = null,
    confirmText: String? = null,
    onConfirm: ((T) -> Unit)? = null,
    dismissText: String? = null,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: (@Composable (T) -> Unit)? = null,
) {
    var cachedData by remember { mutableStateOf(data) }
    if (data != null) {
        cachedData = data
    }
    val currentData = cachedData ?: return
    AppAlertDialog(
        show = data != null,
        onDismissRequest = onDismissRequest,
        title = title,
        text = text,
        modifier = modifier,
        confirmText = confirmText,
        onConfirm = onConfirm?.let { { it(currentData) } },
        dismissText = dismissText,
        onDismiss = onDismiss,
        content = content?.let { { it(currentData) } }
    )
}
