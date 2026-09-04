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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.R
import io.legado.app.service.DownloadStatus
import io.legado.app.service.DownloadTaskType
import io.legado.app.ui.book.cache.formatBytes
import io.legado.app.ui.widget.RoundedTagBarView
import io.legado.app.ui.widget.components.AppEditDialog
import io.legado.app.ui.widget.components.AppMenuSheet
import io.legado.app.ui.widget.components.BookListCardMetrics
import io.legado.app.ui.widget.components.ConfirmDialog
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.ui.widget.components.EditField
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.ListCard
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.ShelfListSkeleton
import io.legado.app.ui.widget.components.VerticalScrollbar
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.compose.rememberAppManagementPalette

/** C6 单源：Tab 枚举唯一权威定义（Activity 与 Screen 共用，消除双份裸 Int 对齐） */
enum class DownloadTab(val labelRes: Int) {
    ALL(R.string.download_tab_all),
    RUNNING(R.string.download_tab_running),
    PAUSED(R.string.download_tab_paused),
    COMPLETED(R.string.download_tab_completed),
    FAILED(R.string.download_tab_failed)
}

/** C6 单源：本地视频扩展名（Activity 播放判定与 Screen 菜单判定共用） */
val DOWNLOAD_VIDEO_EXTS = setOf(
    "mp4", "mkv", "webm", "avi", "mov", "flv", "wmv",
    "3gp", "m4v", "m2ts", "ts", "rmvb", "rm", "f4v"
)

data class DownloadDisplayItem(
    val id: Long,
    val fileName: String,
    val url: String,
    val status: DownloadStatus,
    /** B9：体积全程 Long */
    val totalSize: Long,
    val downloadedSize: Long,
    /** D1：实时速度（字节/秒），仅 RUNNING 有意义 */
    val speed: Long = 0,
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
    // D4：旋转屏保持（布尔态直接 saveable；item 态存任务 id 回查列表，DisplayItem 非 Parcelable）
    var menuItemId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteChoiceItemId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmItemId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmDeleteFiles by rememberSaveable { mutableStateOf(false) }
    var clearConfirmVisible by rememberSaveable { mutableStateOf(false) }
    var dirSettingVisible by rememberSaveable { mutableStateOf(false) }
    var errorDetailItemId by rememberSaveable { mutableStateOf<Long?>(null) }

    val menuItem = items.find { it.id == menuItemId }
    val deleteChoiceItem = items.find { it.id == deleteChoiceItemId }
    val confirmItem = items.find { it.id == confirmItemId }
    val errorDetailItem = items.find { it.id == errorDetailItemId }

    val listState = rememberLazyListState()

    // followup F5：统一管理族壳（AppManagementScaffold 平移，删页内自绘 GlassTopAppBar）
    val palette = rememberAppManagementPalette()
    val moreMenuActions = listOf(
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
                onOnlyWifiChange(true)
            }
        ),
        MenuAction(
            icon = Icons.Default.Public,
            title = stringResource(R.string.download_network_any),
            checked = !onlyWifi,
            onClick = {
                onOnlyWifiChange(false)
            }
        ),
        MenuAction(
            icon = Icons.Default.Download,
            title = stringResource(R.string.download_dir_setting),
            onClick = {
                dirSettingVisible = true
            }
        ),
        MenuAction(
            icon = Icons.Default.DeleteSweep,
            title = stringResource(R.string.clear_completed_tasks),
            onClick = {
                clearConfirmVisible = true
            }
        )
    )

    AppManagementScaffold(
        title = stringResource(R.string.download_manage),
        selectedCount = 0,
        totalCount = items.size,
        modifier = modifier,
        palette = palette,
        onBack = onBack,
        topActions = listOf(
            AppManagementAction(
                text = stringResource(R.string.more_menu),
                menuActions = {
                    moreMenuActions.map { menuAction ->
                        AppManagementMenuAction(
                            text = menuAction.title,
                            // header 分组标签映射为禁用行（ModernActionPopup 无 header 语义）
                            enabled = !menuAction.header,
                            checked = menuAction.checked == true,
                            onClick = menuAction.onClick
                        )
                    }
                }
            )
        )
    ) { _ ->
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部标签栏：嵌入 RoundedTagBarView（与新版发现头部同一组件，样式/主题联动完全一致——
            // 胶囊选中态 + tagBar 圆角底 + TopBarConfig 配色），对齐 bookshelf_tag_bar_height(38dp)
            // C6：标签文案来自 DownloadTab 单源枚举
            AndroidView(
                factory = { ctx ->
                    RoundedTagBarView(ctx).apply {
                        setOnTagClickListener { index ->
                            if (index in DownloadTab.entries.indices) onTabChange(index)
                        }
                    }
                },
                update = { view ->
                    val labelItems = DownloadTab.entries.map {
                        RoundedTagBarView.Item(view.context.getString(it.labelRes))
                    }
                    view.submitItems(labelItems, tabIndex.coerceIn(0, DownloadTab.entries.lastIndex))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
            )

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
                                onClick = { menuItemId = item.id },
                                onErrorDetailClick = { errorDetailItemId = item.id }
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
    }

    // 任务操作菜单（按状态区分：删除 / 重试+删除 / 打开+复制路径+删除）
    menuItem?.let { item ->
        AppMenuSheet(
            title = item.fileName,
            actions = buildTaskMenuActions(
                item = item,
                onPause = {
                    menuItemId = null
                    onPauseTask(item)
                },
                onResume = {
                    menuItemId = null
                    onResumeTask(item)
                },
                onDelete = {
                    menuItemId = null
                    deleteChoiceItemId = item.id
                },
                onOpen = {
                    menuItemId = null
                    onOpenFile(item)
                },
                onOpenWithPlayer = {
                    menuItemId = null
                    onOpenWithPlayer(item)
                },
                onCopy = {
                    menuItemId = null
                    onCopyPath(item)
                }
            ),
            onDismiss = { menuItemId = null }
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
                        deleteChoiceItemId = null
                        confirmDeleteFiles = false
                        confirmItemId = item.id
                    }
                ),
                MenuAction(
                    icon = Icons.Default.DeleteSweep,
                    title = stringResource(R.string.download_delete_with_files),
                    onClick = {
                        deleteChoiceItemId = null
                        confirmDeleteFiles = true
                        confirmItemId = item.id
                    }
                )
            ),
            onDismiss = { deleteChoiceItemId = null }
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
                confirmItemId = null
                onDeleteTask(item, confirmDeleteFiles)
            },
            onDismiss = { confirmItemId = null }
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

    // D2：失败原因完整详情（errorText 单行省略 → 点击查看完整错误）
    errorDetailItem?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.download_error_detail),
            text = "${item.fileName}\n${errorText(item.errorCode)}" +
                (item.errorCode?.let { "\n${stringResource(R.string.download_error_code, it)}" } ?: ""),
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            destructive = false,
            onConfirm = { errorDetailItemId = null },
            onDismiss = { errorDetailItemId = null }
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

/** 按文件名扩展名判断是否为视频产物（C6 单源：与 Activity 端共用 DOWNLOAD_VIDEO_EXTS） */
private fun isVideoFileName(fileName: String): Boolean =
    fileName.substringAfterLast(".", "").lowercase() in DOWNLOAD_VIDEO_EXTS

@Composable
private fun DownloadTaskItemRow(
    item: DownloadDisplayItem,
    onClick: () -> Unit,
    onErrorDetailClick: () -> Unit
) {
    val palette = rememberAppSettingPalette()
    // H10: 列表项卡片直色（palette.row），与书源/订阅源管理列表项同源
            ListCard(
                onClick = onClick,
                metrics = BookListCardMetrics(minHeight = 80.dp),
                containerColor = Color(palette.row)
            ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.primaryText,
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
                        // D1：速度 + 剩余时间（ETA）
                        text = speedText(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.secondaryText
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = sizeText(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.secondaryText
                )
                if (item.status == DownloadStatus.FAILED && !item.errorCode.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        // D2：点击查看失败详情
                        text = errorText(item.errorCode),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClick = onErrorDetailClick)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                // D3：URL 脱敏展示（仅保留 host，路径/参数隐藏，降低截屏泄漏面）
                text = maskUrl(item.url),
                style = MaterialTheme.typography.bodySmall,
                color = palette.secondaryText,
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
    // 语义状态色：成功→primary，失败→error（M3 语义色收敛，原 0xFF43A047/0xFFE53935）；默认→palette.secondaryText（H10 归位）
    DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> rememberAppSettingPalette().secondaryText
}

private fun progressFraction(item: DownloadDisplayItem): Float {
    if (item.totalSize <= 0) return 0f
    return (item.downloadedSize.toFloat() / item.totalSize.toFloat()).coerceIn(0f, 1f)
}

/** 将错误码枚举 name 映射为本地化失败原因（无硬编码中文，逐一映射字符串资源） */
@Composable
private fun errorText(errorCode: String?): String = when (errorCode) {
    "HTTP" -> stringResource(R.string.download_error_http)
    "IO" -> stringResource(R.string.download_error_io)
    "NETWORK" -> stringResource(R.string.download_error_network)
    "ENCRYPT" -> stringResource(R.string.download_error_encrypt)
    "NATIVE_REMUX" -> stringResource(R.string.download_error_remux)
    "UNSUPPORTED" -> stringResource(R.string.download_error_unsupported)
    "INCOMPLETE" -> stringResource(R.string.download_error_incomplete)
    null -> ""
    else -> stringResource(R.string.download_error_code, errorCode)
}

private fun sizeText(item: DownloadDisplayItem): String =
    if (item.totalSize > 0) {
        "${formatBytes(item.downloadedSize)} / ${formatBytes(item.totalSize)}"
    } else {
        formatBytes(item.downloadedSize)
    }

/** D1：速度 + 剩余时间（ETA = 剩余字节 / 速度） */
@Composable
private fun speedText(item: DownloadDisplayItem): String {
    val sizePart = sizeText(item)
    if (item.speed <= 0) return sizePart
    val speedPart = stringResource(R.string.download_speed, formatBytes(item.speed))
    val etaPart = if (item.totalSize > item.downloadedSize) {
        val etaSeconds = (item.totalSize - item.downloadedSize) / item.speed
        stringResource(R.string.download_eta_left, formatEta(etaSeconds))
    } else ""
    return listOf(sizePart, speedPart, etaPart).filter { it.isNotBlank() }.joinToString("  ")
}

/** ETA 秒数格式化：>1h → xh ym；>1min → xm ys；否则 xs */
private fun formatEta(seconds: Long): String {
    if (seconds <= 0) return "0s"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

/** D3：URL 脱敏（保留 scheme+host，路径与参数隐藏，降低截屏/录屏泄漏面） */
private fun maskUrl(url: String): String = runCatching {
    val uri = java.net.URI(url)
    "${uri.scheme}://${uri.host}/…"
}.getOrDefault(url.take(48) + "…")
