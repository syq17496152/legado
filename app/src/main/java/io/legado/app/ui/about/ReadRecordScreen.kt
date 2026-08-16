package io.legado.app.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.ConfirmDialog
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.MetricGrid
import io.legado.app.ui.widget.components.MetricItem
import io.legado.app.ui.widget.components.SettingsSearchBar
import io.legado.app.ui.widget.components.ShelfListSkeleton
import io.legado.app.utils.formatDuring
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 阅读记录页 Compose 内容区（L-C20 阅读记录，S2 列表管理页范式）。
 * 顶栏（返回 + 更多菜单：记录开关/排序三选一/清除全部）+
 * 顶部总时长统计卡 MetricGrid + 搜索实时过滤 + 列表（书名/时长/最近阅读 + 行内删除）+
 * 空态/骨架屏 + 清除/删除确认弹窗。
 * 数据加载/排序/查书/删除业务由宿主 Activity 承接。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadRecordScreen(
    records: List<ReadRecordShow>,
    totalReadTime: String,
    isLoading: Boolean,
    recordEnabled: Boolean,
    sortMode: Int,
    searchKey: String,
    onSearchChange: (String) -> Unit,
    onToggleRecord: (Boolean) -> Unit,
    onSort: (Int) -> Unit,
    onClearAll: () -> Unit,
    onItemClick: (ReadRecordShow) -> Unit,
    onDeleteItem: (ReadRecordShow) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var moreMenuVisible by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ReadRecordShow?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        GlassTopAppBar(
            title = stringResource(R.string.read_record),
            navIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onNavClick = onBack,
            actions = {
                Box {
                    IconButton(onClick = { moreMenuVisible = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null)
                    }
                    AppDropdownMenu(
                        expanded = moreMenuVisible,
                        onDismiss = { moreMenuVisible = false },
                        actions = listOf(
                            MenuAction(
                                icon = Icons.Filled.History,
                                title = stringResource(R.string.enable_record),
                                checked = recordEnabled,
                                onClick = {
                                    moreMenuVisible = false
                                    onToggleRecord(!recordEnabled)
                                }
                            ),
                            MenuAction(
                                icon = Icons.Filled.SwapVert,
                                title = stringResource(R.string.sort_by_name),
                                checked = sortMode == 0,
                                onClick = {
                                    moreMenuVisible = false
                                    onSort(0)
                                }
                            ),
                            MenuAction(
                                icon = Icons.Filled.Schedule,
                                title = stringResource(R.string.reading_time_sort),
                                checked = sortMode == 1,
                                onClick = {
                                    moreMenuVisible = false
                                    onSort(1)
                                }
                            ),
                            MenuAction(
                                icon = Icons.Filled.History,
                                title = stringResource(R.string.last_read_time_sort),
                                checked = sortMode == 2,
                                onClick = {
                                    moreMenuVisible = false
                                    onSort(2)
                                }
                            ),
                            MenuAction(
                                icon = Icons.Filled.DeleteSweep,
                                title = stringResource(R.string.clear),
                                onClick = {
                                    moreMenuVisible = false
                                    clearConfirm = true
                                }
                            )
                        )
                    )
                }
            }
        )

        MetricGrid(
            metrics = listOf(
                MetricItem(
                    label = stringResource(R.string.all_read_time),
                    value = totalReadTime,
                    icon = Icons.Filled.Schedule
                )
            ),
            columns = 1
        )

        SettingsSearchBar(
            query = searchKey,
            onQueryChange = onSearchChange,
            placeholder = stringResource(R.string.search)
        )

        when {
            isLoading && records.isEmpty() -> ShelfListSkeleton(compact = true)
            records.isEmpty() -> EmptyStatePlaceholder(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                title = stringResource(R.string.read_record_empty),
                modifier = Modifier.fillMaxSize()
            )
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                items(records, key = { it.bookName }) { record ->
                    ReadRecordItemRow(
                        record = record,
                        onClick = { onItemClick(record) },
                        onDelete = { deleteTarget = record }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        thickness = 0.5.dp
                    )
                }
            }
        }
    }

    // 清除全部记录确认
    if (clearConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.clear),
            text = stringResource(R.string.sure_del),
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            destructive = true,
            onConfirm = {
                clearConfirm = false
                onClearAll()
            },
            onDismiss = { clearConfirm = false }
        )
    }

    // 行内删除单条确认
    deleteTarget?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.delete),
            text = stringResource(R.string.sure_del_any, target.bookName),
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            destructive = true,
            onConfirm = {
                deleteTarget = null
                onDeleteItem(target)
            },
            onDismiss = { deleteTarget = null }
        )
    }
}

@Composable
private fun ReadRecordItemRow(
    record: ReadRecordShow,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.bookName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.reading_time_tag),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatDuring(record.readTime),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.last_read_time_tag),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatLastRead(record.lastRead),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun formatLastRead(lastRead: Long): String = if (lastRead > 0) {
    remember(lastRead) {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(lastRead))
    }
} else {
    ""
}
