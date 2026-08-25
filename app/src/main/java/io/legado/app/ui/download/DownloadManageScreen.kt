package io.legado.app.ui.download

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.service.DownloadStatus
import io.legado.app.service.DownloadTaskType
import io.legado.app.ui.book.cache.formatBytes
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.AppMenuSheet
import io.legado.app.ui.widget.components.ConfirmDialog
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.ShelfListSkeleton
import io.legado.app.ui.widget.components.VerticalScrollbar

data class DownloadDisplayItem(
    val id: Long,
    val fileName: String,
    val url: String,
    val status: DownloadStatus,
    val totalSize: Int,
    val downloadedSize: Int,
    val taskType: DownloadTaskType = DownloadTaskType.DIRECT,
    val localPath: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManageScreen(
    items: List<DownloadDisplayItem>,
    tabIndex: Int,
    isLoading: Boolean,
    onTabChange: (Int) -> Unit,
    onCancelTask: (DownloadDisplayItem) -> Unit,
    onRetryTask: (DownloadDisplayItem) -> Unit,
    onOpenFile: (DownloadDisplayItem) -> Unit,
    onCopyPath: (DownloadDisplayItem) -> Unit,
    onClearCompleted: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var moreMenuVisible by remember { mutableStateOf(false) }
    var menuItem by remember { mutableStateOf<DownloadDisplayItem?>(null) }
    var confirmItem by remember { mutableStateOf<DownloadDisplayItem?>(null) }
    var clearConfirmVisible by remember { mutableStateOf(false) }

    val tabRes = listOf(
        R.string.download_tab_all,
        R.string.download_tab_running,
        R.string.download_tab_paused,
        R.string.download_tab_completed,
        R.string.download_tab_failed
    )

    val listState = rememberLazyListState()

    Column(modifier = modifier.fillMaxSize()) {
        GlassTopAppBar(
            title = stringResource(R.string.download_manage),
            navIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onNavClick = onBack,
            actions = {
                Box {
                    IconButton(onClick = { moreMenuVisible = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    AppDropdownMenu(
                        expanded = moreMenuVisible,
                        onDismiss = { moreMenuVisible = false },
                        actions = listOf(
                            MenuAction(
                                icon = Icons.Default.DeleteSweep,
                                title = stringResource(R.string.clear_completed_tasks),
                                onClick = {
                                    moreMenuVisible = false
                                    clearConfirmVisible = true
                                }
                            )
                        )
                    )
                }
            }
        )

        PrimaryScrollableTabRow(
            selectedTabIndex = tabIndex.coerceIn(0, tabRes.lastIndex)
        ) {
            tabRes.forEachIndexed { index, res ->
                Tab(
                    selected = tabIndex == index,
                    onClick = { onTabChange(index) },
                    text = { Text(stringResource(res)) }
                )
            }
        }

        when {
            isLoading && items.isEmpty() -> ShelfListSkeleton(compact = true)
            items.isEmpty() -> EmptyStatePlaceholder(
                icon = Icons.Default.Download,
                title = stringResource(R.string.download_empty),
                modifier = Modifier.fillMaxSize()
            )
            else -> Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                ) {
                    itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                        DownloadTaskItemRow(
                            item = item,
                            onClick = { menuItem = item }
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 0.5.dp
                        )
                    }
                }
                VerticalScrollbar(
                    listState = listState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxSize()
                )
            }
        }
    }

    // 任务操作菜单（按状态区分：删除 / 重试+删除 / 打开+复制路径+删除）
    menuItem?.let { item ->
        AppMenuSheet(
            title = item.fileName,
            actions = buildTaskMenuActions(
                item = item,
                onDelete = {
                    menuItem = null
                    confirmItem = item
                },
                onRetry = {
                    menuItem = null
                    onRetryTask(item)
                },
                onOpen = {
                    menuItem = null
                    onOpenFile(item)
                },
                onCopy = {
                    menuItem = null
                    onCopyPath(item)
                }
            ),
            onDismiss = { menuItem = null }
        )
    }

    // 删除任务确认
    confirmItem?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.draw),
            text = stringResource(R.string.sure_del) + "\n" + item.fileName,
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            destructive = true,
            onConfirm = {
                confirmItem = null
                onCancelTask(item)
            },
            onDismiss = { confirmItem = null }
        )
    }

    // 清除已完成/失败任务确认
    if (clearConfirmVisible) {
        ConfirmDialog(
            title = stringResource(R.string.clear_completed_tasks),
            text = stringResource(R.string.clear_completed_confirm),
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            destructive = true,
            onConfirm = {
                clearConfirmVisible = false
                onClearCompleted()
            },
            onDismiss = { clearConfirmVisible = false }
        )
    }
}

@Composable
private fun buildTaskMenuActions(
    item: DownloadDisplayItem,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onCopy: () -> Unit
): List<MenuAction> = when (item.status) {
    DownloadStatus.WAITING, DownloadStatus.RUNNING -> listOf(
        MenuAction(Icons.Default.Delete, stringResource(R.string.download_delete_task), onClick = onDelete)
    )
    DownloadStatus.PAUSED, DownloadStatus.FAILED -> listOf(
        MenuAction(Icons.Default.Refresh, stringResource(R.string.download_retry), onClick = onRetry),
        MenuAction(Icons.Default.Delete, stringResource(R.string.download_delete_task), onClick = onDelete)
    )
    DownloadStatus.COMPLETED -> listOf(
        MenuAction(Icons.Default.OpenInNew, stringResource(R.string.download_open_file), onClick = onOpen),
        MenuAction(Icons.Default.ContentCopy, stringResource(R.string.download_copy_path), onClick = onCopy),
        MenuAction(Icons.Default.Delete, stringResource(R.string.download_delete_task), onClick = onDelete)
    )
}

@Composable
private fun DownloadTaskItemRow(
    item: DownloadDisplayItem,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.fileName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = statusText(item.status),
                style = MaterialTheme.typography.bodySmall,
                color = statusColor(item.status)
            )
            if (item.status == DownloadStatus.COMPLETED) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (item.status == DownloadStatus.RUNNING) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { progressFraction(item) },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = sizeText(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = sizeText(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = item.url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun statusText(status: DownloadStatus): String = when (status) {
    DownloadStatus.WAITING -> stringResource(R.string.wait_download)
    DownloadStatus.RUNNING -> stringResource(R.string.downloading)
    DownloadStatus.PAUSED -> stringResource(R.string.pause)
    DownloadStatus.COMPLETED -> stringResource(R.string.download_success)
    DownloadStatus.FAILED -> stringResource(R.string.download_error)
}

@Composable
private fun statusColor(status: DownloadStatus): Color = when (status) {
    // 语义状态色：成功→colorScheme.primary，失败→colorScheme.error（M3 语义色收敛，原 0xFF43A047/0xFFE53935）
    DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun progressFraction(item: DownloadDisplayItem): Float {
    if (item.totalSize <= 0) return 0f
    return (item.downloadedSize.toFloat() / item.totalSize.toFloat()).coerceIn(0f, 1f)
}

private fun sizeText(item: DownloadDisplayItem): String =
    if (item.totalSize > 0) {
        "${formatBytes(item.downloadedSize.toLong())} / ${formatBytes(item.totalSize.toLong())}"
    } else {
        formatBytes(item.downloadedSize.toLong())
    }
