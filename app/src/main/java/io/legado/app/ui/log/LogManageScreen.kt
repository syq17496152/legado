package io.legado.app.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.ui.widget.components.AppConfirmDialog
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.compose.AppSettingPalette
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.utils.FileDoc
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志管理中心（log-system-upgrade AD-04）：全屏 Compose 功能页，4 Tab 统一管理日志。
 * 遵循 ui-standards：GlassTopAppBar 顶栏基线 + palette 直色取色 + AppConfirmDialog 弹框族 + 空态占位。
 * 状态 owner = LogManageState（Activity 持有），Screen 为纯展示 + 回调。
 */

enum class LogTab(val titleRes: Int) {
    APP(R.string.log_tab_app),
    CRASH(R.string.crash_log),
    FILES(R.string.log_tab_files),
    HEAP(R.string.log_tab_heap)
}

data class LogFileItem(
    val name: String,
    val size: Long = 0L,
    val file: File? = null,
    val doc: FileDoc? = null
)

class LogManageState {
    var tab by mutableStateOf(LogTab.APP)
    var appLogs by mutableStateOf<List<AppLog.LogEntry>>(emptyList())
    var crashFiles by mutableStateOf<List<LogFileItem>>(emptyList())
    var logFiles by mutableStateOf<List<LogFileItem>>(emptyList())
    var heapFiles by mutableStateOf<List<LogFileItem>>(emptyList())
    var selecting by mutableStateOf(false)
    // 多选集合：应用日志按对象引用（data class equals 会误删重复内容日志），文件按 name key（目录内唯一）
    var selectedAppLogs by mutableStateOf<List<AppLog.LogEntry>>(emptyList())
    var selectedFileKeys by mutableStateOf<Set<String>>(emptySet())
    var searchKey by mutableStateOf("")
    var levelFilter by mutableStateOf<AppLog.Level?>(null)
    var showClearConfirm by mutableStateOf(false)
}

@Composable
fun LogManageScreen(
    state: LogManageState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDeleteAppLogs: (List<AppLog.LogEntry>) -> Unit,
    onDeleteFiles: (List<LogFileItem>) -> Unit,
    onClearAll: () -> Unit,
    onExport: () -> Unit,
    onShareFile: (LogFileItem) -> Unit,
    onViewFile: (LogFileItem) -> Unit,
    onViewAppLog: (AppLog.LogEntry) -> Unit,
    onCopyAppLog: (AppLog.LogEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = rememberAppSettingPalette()
    val rowColor = Color(palette.row)

    // 切 Tab：刷新数据 + 重置选择模式（避免跨 Tab 选中集合串扰）
    LaunchedEffect(state.tab) {
        onRefresh()
        state.selecting = false
        state.selectedAppLogs = emptyList()
        state.selectedFileKeys = emptySet()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.page)
    ) {
        val selectedCount = if (state.tab == LogTab.APP) state.selectedAppLogs.size
        else state.selectedFileKeys.size
        GlassTopAppBar(
            title = if (state.selecting) stringResource(R.string.log_selected_count, selectedCount)
            else stringResource(R.string.log_manage),
            navIcon = if (state.selecting) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
            onNavClick = {
                if (state.selecting) {
                    state.selecting = false
                } else {
                    onBack()
                }
            },
            actions = {
                if (state.selecting) {
                    // 选择模式：删除所选（危险色）
                    IconButton(onClick = {
                        when (state.tab) {
                            LogTab.APP -> onDeleteAppLogs(state.selectedAppLogs)
                            else -> onDeleteFiles(currentFileItems(state).filter {
                                it.name in state.selectedFileKeys
                            })
                        }
                    }) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = stringResource(R.string.delete),
                            tint = palette.danger
                        )
                    }
                } else {
                    // 一级动作 ≤3（topbar-icon-semantics-fix）：多选 / 导出 / 一键清除
                    IconButton(onClick = { state.selecting = true }) {
                        Icon(
                            Icons.Default.Done,
                            contentDescription = stringResource(R.string.log_select)
                        )
                    }
                    IconButton(onClick = onExport) {
                        Icon(
                            Icons.Default.SaveAlt,
                            contentDescription = stringResource(R.string.log_export_logs)
                        )
                    }
                    IconButton(onClick = { state.showClearConfirm = true }) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = stringResource(R.string.log_clear_all)
                        )
                    }
                }
            }
        )
        TabRow(
            selectedTabIndex = state.tab.ordinal,
            containerColor = rowColor,
            contentColor = palette.primaryText
        ) {
            LogTab.entries.forEach { tab ->
                val selected = state.tab == tab
                Tab(
                    selected = selected,
                    onClick = { state.tab = tab },
                    text = {
                        Text(
                            stringResource(tab.titleRes),
                            color = if (selected) palette.accent else palette.secondaryText,
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                    }
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
            when (state.tab) {
                LogTab.APP -> AppLogsContent(
                    state = state,
                    rowColor = rowColor,
                    palette = palette,
                    onViewAppLog = onViewAppLog,
                    onCopyAppLog = onCopyAppLog
                )
                else -> FilesContent(
                    state = state,
                    rowColor = rowColor,
                    palette = palette,
                    items = currentFileItems(state),
                    emptyText = stringResource(R.string.log_empty_files),
                    onViewFile = onViewFile,
                    onShareFile = onShareFile,
                    shareEnabled = state.tab == LogTab.CRASH
                )
            }
            // 选择模式底部操作栏
            if (state.selecting) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(rowColor)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.log_selected_count, selectedCount),
                        color = palette.primaryText,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        stringResource(R.string.cancel),
                        color = palette.secondaryText,
                        modifier = Modifier
                            .clickable { state.selecting = false }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                    Text(
                        stringResource(R.string.delete),
                        color = if (selectedCount > 0) palette.danger else palette.secondaryText,
                        modifier = Modifier
                            .clickable(enabled = selectedCount > 0) {
                                when (state.tab) {
                                    LogTab.APP -> onDeleteAppLogs(state.selectedAppLogs)
                                    else -> onDeleteFiles(currentFileItems(state).filter {
                                        it.name in state.selectedFileKeys
                                    })
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }

    if (state.showClearConfirm) {
        val body = stringResource(R.string.log_confirm_clear) + "\n\n" +
            stringResource(R.string.log_tab_app) + " ${state.appLogs.size} · " +
            stringResource(R.string.log_tab_files) + " ${state.logFiles.size} · " +
            stringResource(R.string.crash_log) + " ${state.crashFiles.size} · " +
            stringResource(R.string.log_tab_heap) + " ${state.heapFiles.size}"
        AppConfirmDialog(
            title = stringResource(R.string.log_clear_all),
            body = body,
            confirmText = stringResource(R.string.clear),
            destructive = true,
            onConfirm = {
                state.showClearConfirm = false
                onClearAll()
            },
            onDismiss = { state.showClearConfirm = false }
        )
    }
}

private fun currentFileItems(state: LogManageState): List<LogFileItem> = when (state.tab) {
    LogTab.CRASH -> state.crashFiles
    LogTab.FILES -> state.logFiles
    LogTab.HEAP -> state.heapFiles
    else -> emptyList()
}

// ===================== 应用日志 Tab（搜索/级别筛选/多选删除/详情/复制） =====================

@Composable
private fun AppLogsContent(
    state: LogManageState,
    rowColor: Color,
    palette: AppSettingPalette,
    onViewAppLog: (AppLog.LogEntry) -> Unit,
    onCopyAppLog: (AppLog.LogEntry) -> Unit
) {
    val filtered = remember(state.appLogs, state.searchKey, state.levelFilter) {
        state.appLogs.filter { entry ->
            (state.levelFilter == null || entry.level == state.levelFilter) &&
                (state.searchKey.isBlank() ||
                    entry.message.contains(state.searchKey, ignoreCase = true) ||
                    entry.throwable?.stackTraceToString()
                        ?.contains(state.searchKey, ignoreCase = true) == true)
        }
    }
    Column(Modifier.fillMaxSize()) {
        // 搜索框 + 级别筛选
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .background(rowColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = state.searchKey,
                    onValueChange = { state.searchKey = it },
                    singleLine = true,
                    textStyle = TextStyle(color = palette.primaryText, fontSize = 13.sp),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerField ->
                        Box {
                            if (state.searchKey.isBlank()) {
                                Text(
                                    text = stringResource(R.string.log_search_hint),
                                    color = palette.secondaryText,
                                    fontSize = 13.sp,
                                    maxLines = 1
                                )
                            }
                            innerField()
                        }
                    }
                )
                if (state.searchKey.isNotBlank()) {
                    Text(
                        "×",
                        color = palette.secondaryText,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .clickable { state.searchKey = "" }
                            .padding(start = 8.dp)
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            val levels: List<AppLog.Level?> = listOf(null) + AppLog.Level.entries
            levels.forEach { level ->
                val selected = state.levelFilter == level
                Text(
                    text = level?.name ?: stringResource(R.string.all),
                    color = if (selected) palette.accent else palette.secondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(
                            if (selected) palette.accent.copy(alpha = 0.12f) else rowColor,
                            RoundedCornerShape(50)
                        )
                        .clickable { state.levelFilter = level }
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
        if (filtered.isEmpty()) {
            EmptyStatePlaceholder(
                icon = Icons.Default.SaveAlt,
                title = stringResource(R.string.log_empty),
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(filtered) { entry ->
                    val selected = state.selectedAppLogs.any { it === entry }
                    AppLogRow(
                        entry = entry,
                        selected = state.selecting && selected,
                        selecting = state.selecting,
                        rowColor = rowColor,
                        palette = palette,
                        onClick = {
                            if (state.selecting) {
                                state.selectedAppLogs =
                                    if (selected) state.selectedAppLogs.filter { it !== entry }
                                    else state.selectedAppLogs + entry
                            } else {
                                onViewAppLog(entry)
                            }
                        },
                        onLongClick = {
                            state.selecting = true
                            if (!selected) state.selectedAppLogs = state.selectedAppLogs + entry
                        },
                        onCopy = { onCopyAppLog(entry) }
                    )
                    HorizontalDivider(
                        color = palette.divider.copy(alpha = 0.5f),
                        thickness = 0.5.dp
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun AppLogRow(
    entry: AppLog.LogEntry,
    selected: Boolean,
    selecting: Boolean,
    rowColor: Color,
    palette: AppSettingPalette,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCopy: () -> Unit
) {
    val levelColor = when (entry.level) {
        AppLog.Level.ERROR -> palette.danger
        AppLog.Level.WARN -> palette.accent
        else -> palette.secondaryText
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) palette.accent.copy(alpha = 0.12f) else rowColor)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.level.name,
                    color = levelColor,
                    fontSize = 11.sp
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = logTimeFormat.format(Date(entry.time)),
                    color = palette.secondaryText,
                    fontSize = 11.sp
                )
            }
            Text(
                text = entry.message,
                color = palette.primaryText,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!selecting) {
            IconButton(onClick = onCopy) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.log_copy_done),
                    tint = palette.secondaryText
                )
            }
        }
    }
}

// ===================== 文件类 Tab（崩溃日志/文件日志/堆转储共用：多选删除/查看/分享） =====================

@Composable
private fun FilesContent(
    state: LogManageState,
    rowColor: Color,
    palette: AppSettingPalette,
    items: List<LogFileItem>,
    emptyText: String,
    onViewFile: (LogFileItem) -> Unit,
    onShareFile: (LogFileItem) -> Unit,
    shareEnabled: Boolean,
) {
    Column(Modifier.fillMaxSize()) {
        if (state.tab == LogTab.HEAP) {
            Text(
                text = stringResource(R.string.log_heap_hint),
                color = palette.secondaryText,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        if (items.isEmpty()) {
            EmptyStatePlaceholder(
                icon = Icons.Default.SaveAlt,
                title = emptyText,
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(items) { item ->
                    val selected = item.name in state.selectedFileKeys
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (state.selecting && selected) palette.accent.copy(alpha = 0.12f) else rowColor)
                            .combinedClickable(
                                onClick = {
                                    if (state.selecting) {
                                        state.selectedFileKeys =
                                            if (selected) state.selectedFileKeys - item.name
                                            else state.selectedFileKeys + item.name
                                    } else {
                                        onViewFile(item)
                                    }
                                },
                                onLongClick = {
                                    state.selecting = true
                                    state.selectedFileKeys = state.selectedFileKeys + item.name
                                }
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                color = palette.primaryText,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatSize(item.size),
                                color = palette.secondaryText,
                                fontSize = 11.sp
                            )
                        }
                        if (!state.selecting && shareEnabled && item.file != null) {
                            IconButton(onClick = { onShareFile(item) }) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = stringResource(R.string.share),
                                    tint = palette.secondaryText
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        color = palette.divider.copy(alpha = 0.5f),
                        thickness = 0.5.dp
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

private val logTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576f)
    bytes >= 1 shl 10 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
    else -> "$bytes B"
}
