package io.legado.app.ui.widget.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R

/**
 * 分组多选弹窗（公共组件库三期 Dialog 族）。
 *
 * 规格：ui-standards §3.4 `MultiSelectDialog`（task 12.35，from Legado_Max MultiSelectItem/Group）
 * - M3 `AlertDialog` 卡 **18dp**
 * - 列表项 h16 v12
 * - 项 `bodyLarge` / 组头 `titleSmall`
 * - 选中项 `primary` / 其余 `onSurface`
 * - 内容 maxHeight **70%** 屏高
 * - 项整行 ≥48dp
 *
 * 受控组件：选中态由调用方 [selectedKeys] 提供，切换经 [onToggle] 回传。
 */
data class MultiSelectItem(
    val key: String,               // 唯一标识
    val title: String,             // 主标题
    val subtitle: String? = null,  // 副标题（如文件名/路径）
    val group: String = ""         // 分组名称
)

/** 分组数据模型。 */
data class MultiSelectGroup(
    val name: String,
    val items: List<MultiSelectItem>
)

@Composable
fun MultiSelectDialog(
    title: String,
    groups: List<MultiSelectGroup>,
    selectedKeys: Set<String>,
    onToggle: (key: String, selected: Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.7f
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
            ) {
                groups.forEach { group ->
                    item(key = "header_${group.name}") {
                        Text(
                            text = group.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 4.dp)
                        )
                    }
                    items(group.items, key = { it.key }) { item ->
                        val selected = item.key in selectedKeys
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { onToggle(item.key, !selected) }
                                .padding(horizontal = 16.dp)
                        ) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = { onToggle(item.key, it) }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!item.subtitle.isNullOrBlank()) {
                                    Text(
                                        text = item.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}
