package io.legado.app.ui.widget.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R

/**
 * 布局选项：图标 + 文案 + 布局值（如 0=列表/1=紧凑/2-6=网格列数）。
 */
data class ListLayoutOption(
    val value: Int,
    val icon: ImageVector,
    val label: String
)

/**
 * 排序选项：String key（持久化）+ 文案。
 */
data class ListSortOption(
    val key: String,
    val label: String
)

/**
 * 布局切换 + 排序统一菜单（顶栏图标触发）。
 * 布局区横向展示所有布局选项（当前项高亮）；排序区首行独立升降序切换，
 * 维度列表当前项带选中标记，点同维度=切换升降序，点新维度=换 key 保留方向。
 * 当前态由页面持久化，本组件为受控组件（只回传当前值 + 选择回调）。
 */
@Composable
fun ListLayoutMenu(
    layoutOptions: List<ListLayoutOption>,
    sortOptions: List<ListSortOption>,
    currentLayout: Int,
    currentSortKey: String,
    currentAscending: Boolean,
    onLayoutSelect: (Int) -> Unit,
    onSortSelect: (key: String, ascending: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Tune
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = icon,
                contentDescription = stringResource(R.string.list_layout_menu)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            Text(
                text = stringResource(R.string.list_layout),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                layoutOptions.forEach { option ->
                    val selected = option.value == currentLayout
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(AppShapes.Chip)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable {
                                expanded = false
                                onLayoutSelect(option.value)
                            }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = option.icon,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = stringResource(R.string.sort),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(
                            R.string.list_layout_menu_switch_hint,
                            stringResource(
                                if (currentAscending) R.string.sort_asc
                                else R.string.sort_desc
                            )
                        )
                    )
                },
                onClick = {
                    onSortSelect(currentSortKey, !currentAscending)
                }
            )
            sortOptions.forEach { option ->
                val selected = option.key == currentSortKey
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.label,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    trailingIcon = {
                        if (selected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    onClick = {
                        if (selected) {
                            onSortSelect(option.key, !currentAscending)
                        } else {
                            onSortSelect(option.key, currentAscending)
                        }
                    }
                )
            }
        }
    }
}
