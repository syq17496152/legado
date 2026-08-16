package io.legado.app.ui.widget.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R

/**
 * 动作列表弹窗（公共组件库三期 Dialog 族）。
 *
 * 规格：ui-standards §3.4 `ActionListDialog`（task 12.32，from Legado_Max ActionListDialog）
 * - M3 `AlertDialog` 卡 **18dp** 圆角
 * - `LazyColumn` 包裹动作列表，整行点击
 * - 动作 `bodyLarge`；destructive 项 `error` 色 / 其余 `onSurface`
 * - 项 ≥48dp；内容 maxHeight **70%** 屏高（LocalConfiguration）
 *
 * @param title 可选标题（null 时不显示标题栏）
 * @param actions 动作项列表，点击执行各自 [DialogAction.onClick]
 * @param onDismiss 点击外部区域关闭回调
 */
data class DialogAction(
    val title: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun ActionListDialog(
    title: String? = null,
    actions: List<DialogAction>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.7f

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = title?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
            ) {
                items(actions) { action ->
                    Text(
                        text = action.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (action.destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = 48.dp)
                            .clickable { action.onClick() }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.cancel),
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                )
            }
        },
    )
}