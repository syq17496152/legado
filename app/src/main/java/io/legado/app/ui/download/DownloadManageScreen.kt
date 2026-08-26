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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
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
import io.legado.app.ui.widget.components.AppEditDialog
import io.legado.app.ui.widget.components.AppMenuSheet
import io.legado.app.ui.widget.components.BookListCardMetrics
import io.legado.app.ui.widget.components.ConfirmDialog
import io.legado.app.ui.widget.components.EditField
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.ListCard
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
    val localPath: String? = null,
    val errorCode: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManageScreen(
    items: List<DownloadDisplayItem>,
    tabIndex: Int,
    isLoading: Boolean,
    onlyWifi: Boolean,
    onOnlyWifiChange: (Boolean) -> Unit,
    onTabChange: (Int) -> Unit,
    onCancelTask: (DownloadDisplayItem) -> Unit,
    onPauseTask: (DownloadDisplayItem) -> Unit,
    onResumeTask: (DownloadDisplayItem) -> Unit,
    onDeleteTask: (DownloadDisplayItem, Boolean) -> Unit,
    onOpenFile: (DownloadDisplayItem) -> Unit,
    onOpenWithPlayer: (DownloadDisplayItem) -> Unit,
    onCopyPath: (DownloadDisplayItem) -> Unit,
    currentDir: String,
    onSaveTargetDir: (String) -> Unit,
    onClearCompleted: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var moreMenuVisible by remember { mutableStateOf(false) }
    var menuItem by remember { mutableStateOf<DownloadDisplayItem?>(null) }
    var deleteChoiceItem by remember { mutableStateOf<DownloadDisplayItem?>(null) }
    var confirmItem by remember { mutableStateOf<DownloadDisplayItem?>(null) }
    var confirmDeleteFiles by remember { mutableStateOf(false) }
    var clearConfirmVisible by remember { mutableStateOf(false) }
    var dirSettingVisible by remember { mutableStateOf(false) }

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
                                icon = Icons.Default.Wifi,
                                title = stringResource(R.string.network_policy),
                                header = true,
                                onClick = {}
                            ),
                            MenuAction(
                                icon = Icons.Default.Wifi,
                                title = stringResource(R.string.download_network_wifi_only),
                                checked = onlyWifi,
                                onClick = {
                                    moreMenuVisible = false
                                    onOnlyWifiChange(true)
                                }
                            ),
                            MenuAction(
                                icon = Icons.Default.Public,
                                title = stringResource(R.string.download_network_any),
                                checked = !onlyWifi,
                                onClick = {
                                    moreMenuVisible = false
                                    onOnlyWifiChange(false)
                                }
                            ),
                            MenuAction(
                                icon = Icons.Default.Download,
                                title = stringResource(R.string.download_dir_setting),
                                onClick = {
                                    moreMenuVisible = false
                                    dirSettingVisible = true
                                }
                            ),
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
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp, vertical = 8.dp
                    ),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                ) {
                    itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                        DownloadTaskItemRow(
                            item = item,
                            onClick = { menuItem = item }
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
                onPause = {
                    menuItem = null
                    onPauseTask(item)
                },
                onResume = {
                    menuItem = null
                    onResumeTask(item)
                },
                onDelete = {
                    menuItem = null
                    deleteChoiceItem = item
                },
                onOpen = {
                    menuItem = null
                    onOpenFile(item)
                },
                onOpenWithPlayer = {
                    menuItem = null
                    onOpenWithPlayer(item)
                },
                onCopy = {
                    menuItem = null
                    onCopyPath(item)
                }
            ),
            onDismiss = { menuItem = null }
        )
    }

    // 删除任务方式选择（FR-12 二分：仅删记录保留文件 / 删任务并清理文件）
    deleteChoiceItem?.let { item ->
        AppMenuSheet(
            title = item.fileName,
            actions = listOf(
                MenuAction(
                    icon = Icons.Default.Delete,
                    title = stringResource(R.string.download_delete_only),
                    onClick = {
                        deleteChoiceItem = null
                        confirmDeleteFiles = false
                        confirmItem = item
                    }
                ),
                MenuAction(
                    icon = Icons.Default.DeleteSweep,
                    title = stringResource(R.string.download_delete_with_files),
                    onClick = {
                        deleteChoiceItem = null
                        confirmDeleteFiles = true
                        confirmItem = item
                    }
                )
            ),
            onDismiss = { deleteChoiceItem = null }
        )
    }

    // 删除任务确认（二选一删除方式的二次确认）
    confirmItem?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.delete),
            text = if (confirmDeleteFiles) {
                stringResource(R.string.download_confirm_delete_with_files)
            } else {
                stringResource(R.string.download_confirm_delete_only)
            },
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            destructive = true,
            onConfirm = {
                confirmItem = null
                onDeleteTask(item, confirmDeleteFiles)
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

    // 下载目录设置（4.7）：复用 Dialog 族 AppEditDialog（ui-standards S6，不私造裸 AlertDialog）
    if (dirSettingVisible) {
        AppEditDialog(
            title = stringResource(R.string.download_dir_setting),
            fields = listOf(
                EditField(
                    label = stringResource(R.string.download_configure_dir),
                    initial = currentDir
                )
            ),
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            onConfirm = { values ->
                dirSettingVisible = false
                onSaveTargetDir(values.firstOrNull() ?: "")
            },
            onDismiss = { dirSettingVisible = false }
        )
    }
}

@Composable
private fun buildTaskMenuActions(
    item: DownloadDisplayItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onOpenWithPlayer: () -> Unit,
    onCopy: () -> Unit
): List<MenuAction> = when (item.status) {
    DownloadStatus.WAITING, DownloadStatus.RUNNING -> listOf(
        MenuAction(Icons.Default.Pause, stringResource(R.string.download_pause_task), onClick = onPause),
        MenuAction(Icons.Default.Delete, stringResource(R.string.download_delete_task), onClick = onDelete)
    )
    DownloadStatus.PAUSED -> listOf(
        MenuAction(Icons.Default.PlayArrow, stringResource(R.string.download_resume_task), onClick = onResume),
        MenuAction(Icons.Default.Delete, stringResource(R.string.download_delete_task), onClick = onDelete)
    )
    DownloadStatus.FAILED -> listOf(
        MenuAction(Icons.Default.Refresh, stringResource(R.string.download_retry), onClick = onResume),
        MenuAction(Icons.Default.Delete, stringResource(R.string.download_delete_task), onClick = onDelete)
    )
    DownloadStatus.COMPLETED -> buildList {
        // 视频类产物（mp4/ts 等）：主操作显示"播放"（内置播放器），替换"打开文件"（用户明确诉求：
        // "点击三点后弹窗只有打开文件，没有播放按钮——视频格式显示的不是打开文件而是播放"）。
        // 非视频产物（txt/zip 等）保持"打开文件"交给系统处理。
        // 注意：必须按 localPath 的真实扩展名判断（fileName 是下载标题，如 verifyS2_hls 不含扩展名），
        // 否则视频任务会被误判为非视频、显示"打开文件"（铁证：u2 实测菜单无"播放"）。
        if (isVideoFileName(item.localPath ?: item.fileName)) {
            add(
                MenuAction(
                    Icons.Default.PlayCircle,
                    stringResource(R.string.download_play_with_builtin),
                    onClick = onOpenWithPlayer
                )
            )
        } else {
            add(
                MenuAction(Icons.Default.OpenInNew, stringResource(R.string.download_open_file), onClick = onOpen)
            )
        }
        add(
            MenuAction(Icons.Default.ContentCopy, stringResource(R.string.download_copy_path), onClick = onCopy)
        )
        add(
            MenuAction(Icons.Default.Delete, stringResource(R.string.download_delete_task), onClick = onDelete)
        )
    }
}

/** 按文件名扩展名判断是否为视频产物（与 Activity 端 VIDEO_EXTS 对齐） */
private fun isVideoFileName(fileName: String): Boolean {
    val ext = fileName.substringAfterLast(".", "").lowercase()
    return ext in setOf(
        "mp4", "mkv", "webm", "avi", "mov", "flv", "wmv",
        "3gp", "m4v", "m2ts", "ts", "rmvb", "rm", "f4v"
    )
}

@Composable
private fun DownloadTaskItemRow(
    item: DownloadDisplayItem,
    onClick: () -> Unit
) {
    // S2 列表条目卡片原语：18dp 圆角 clip + surface 容器 + combinedClickable（ui-standards §3.4 ListCard）
    ListCard(
        onClick = onClick,
        metrics = BookListCardMetrics(minHeight = 80.dp),
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column {
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
                if (item.status == DownloadStatus.FAILED && !item.errorCode.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = errorText(item.errorCode),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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

/** 将错误码枚举 name 映射为本地化失败原因（无硬编码中文，逐一映射字符串资源） */
@Composable
private fun errorText(errorCode: String): String = when (errorCode) {
    "HTTP" -> stringResource(R.string.download_error_http)
    "IO" -> stringResource(R.string.download_error_io)
    "NETWORK" -> stringResource(R.string.download_error_network)
    "ENCRYPT" -> stringResource(R.string.download_error_encrypt)
    "NATIVE_REMUX" -> stringResource(R.string.download_error_remux)
    "UNSUPPORTED" -> stringResource(R.string.download_error_unsupported)
    "INCOMPLETE" -> stringResource(R.string.download_error_incomplete)
    else -> stringResource(R.string.download_error_code, errorCode)
}

private fun sizeText(item: DownloadDisplayItem): String =
    if (item.totalSize > 0) {
        "${formatBytes(item.downloadedSize.toLong())} / ${formatBytes(item.totalSize.toLong())}"
    } else {
        formatBytes(item.downloadedSize.toLong())
    }
