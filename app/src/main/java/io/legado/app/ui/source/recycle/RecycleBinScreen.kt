package io.legado.app.ui.source.recycle

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.source.SourceRecycleBinHelp
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.ConfirmDialog
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.ShelfListSkeleton
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class RecycleBinDisplayItem(
    val id: Long,
    val name: String,
    val type: String,
    val deletedAt: Long,
    val isSelected: Boolean
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RecycleBinScreen(
    items: List<RecycleBinDisplayItem>,
    isLoading: Boolean,
    pendingRestoreItems: List<RecycleBinDisplayItem>,
    selectionCount: Int,
    onBack: () -> Unit,
    onToggleSelect: (Int, Boolean) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onRevertSelection: () -> Unit,
    onRestoreSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
    onRestore: (RecycleBinDisplayItem) -> Unit,
    onDelete: (RecycleBinDisplayItem) -> Unit,
    onConfirmRestoreOverwrite: () -> Unit,
    onDismissRestoreOverwrite: () -> Unit,
    onEmptyRecycleBin: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier
) {
    var moreMenuVisible by remember { mutableStateOf(false) }
    var deleteItem by remember { mutableStateOf<RecycleBinDisplayItem?>(null) }
    var deleteSelectionVisible by remember { mutableStateOf(false) }
    var emptyConfirmVisible by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    Column(modifier = modifier.fillMaxSize()) {
        GlassTopAppBar(
            title = stringResource(R.string.recycle_bin),
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
                                title = stringResource(R.string.recycle_bin_empty),
                                onClick = {
                                    moreMenuVisible = false
                                    emptyConfirmVisible = true
                                }
                            ),
                            MenuAction(
                                icon = Icons.Default.HelpOutline,
                                title = stringResource(R.string.help),
                                onClick = {
                                    moreMenuVisible = false
                                    onHelp()
                                }
                            )
                        )
                    )
                }
            }
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            when {
                isLoading -> ShelfListSkeleton(compact = true)
                items.isEmpty() -> EmptyStatePlaceholder(
                    icon = Icons.Default.Restore,
                    title = stringResource(R.string.recycle_bin_empty),
                    modifier = Modifier.fillMaxSize()
                )
                else -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        // 滑选多选：选择模式常驻（与原版 activeSlideSelect 一致），长按拖动批量勾选
                        .pointerInput(Unit) {
                            var slideStart: Int? = null
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    val idx = listState.indexOfPoint(offset.y)
                                    slideStart = idx
                                    idx?.let { onToggleSelect(it, true) }
                                },
                                onDrag = { change, _ ->
                                    val idx = listState.indexOfPoint(change.position.y)
                                    val start = slideStart
                                    if (start != null && idx != null && idx != start) {
                                        val lo = min(start, idx)
                                        val hi = max(start, idx)
                                        for (i in lo..hi) {
                                            items.getOrNull(i)?.let { item ->
                                                if (!item.isSelected) onToggleSelect(i, true)
                                            }
                                        }
                                    }
                                },
                                onDragEnd = { slideStart = null },
                                onDragCancel = { slideStart = null }
                            )
                        }
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                            RecycleBinItemRow(
                                item = item,
                                onToggleSelect = { checked -> onToggleSelect(index, checked) },
                                onRestore = { onRestore(item) },
                                onDelete = { deleteItem = item }
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                thickness = 0.5.dp
                            )
                        }
                    }
                }
            }
        }

        RecycleBinActionBar(
            selectionCount = selectionCount,
            totalCount = items.size,
            onSelectAll = onSelectAll,
            onRevertSelection = onRevertSelection,
            onRestoreSelection = onRestoreSelection,
            onDeleteSelection = {
                deleteSelectionVisible = true
            }
        )
    }

    // 单个删除确认
    deleteItem?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.draw),
            text = stringResource(R.string.recycle_bin_delete_msg),
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            destructive = true,
            onConfirm = {
                deleteItem = null
                onDelete(item)
            },
            onDismiss = { deleteItem = null }
        )
    }

    // 批量删除选中确认
    if (deleteSelectionVisible) {
        ConfirmDialog(
            title = stringResource(R.string.draw),
            text = stringResource(R.string.recycle_bin_delete_selection_msg),
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            destructive = true,
            onConfirm = {
                deleteSelectionVisible = false
                onDeleteSelection()
            },
            onDismiss = { deleteSelectionVisible = false }
        )
    }

    // 清空回收站确认
    if (emptyConfirmVisible) {
        ConfirmDialog(
            title = stringResource(R.string.draw),
            text = stringResource(R.string.recycle_bin_empty_msg),
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            destructive = true,
            onConfirm = {
                emptyConfirmVisible = false
                onEmptyRecycleBin()
            },
            onDismiss = { emptyConfirmVisible = false }
        )
    }

    // 恢复冲突覆盖确认（Activity 检测 hasConflict 后驱动）
    if (pendingRestoreItems.isNotEmpty()) {
        ConfirmDialog(
            title = stringResource(R.string.draw),
            text = stringResource(R.string.recycle_bin_restore_conflict) + "\n" +
                pendingRestoreItems.joinToString("\n") { it.name },
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            onConfirm = onConfirmRestoreOverwrite,
            onDismiss = onDismissRestoreOverwrite
        )
    }
}

/** 底部批量操作栏（SelectActionBar 的 Compose 版，主按钮为「恢复」） */
@Composable
private fun RecycleBinActionBar(
    selectionCount: Int,
    totalCount: Int,
    onSelectAll: (Boolean) -> Unit,
    onRevertSelection: () -> Unit,
    onRestoreSelection: () -> Unit,
    onDeleteSelection: () -> Unit
) {
    val enabled = selectionCount > 0
    val allSelected = totalCount > 0 && selectionCount >= totalCount
    var menuVisible by remember { mutableStateOf(false) }
    // H11: 选择操作栏直色（palette.row），替代 M3 surface 派生色
    val palette = rememberAppSettingPalette()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(palette.row),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClick = { onSelectAll(!allSelected) },
                        onLongClick = { onRevertSelection() },
                        enabled = totalCount > 0
                    )
                    .height(48.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = allSelected,
                    onCheckedChange = { onSelectAll(it) },
                    enabled = totalCount > 0,
                    colors = CheckboxDefaults.colors()
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (allSelected) {
                        stringResource(R.string.select_cancel_count, selectionCount, totalCount)
                    } else {
                        stringResource(R.string.select_all_count, selectionCount, totalCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.primaryText
                )
            }
            TextButton(
                onClick = onRestoreSelection,
                enabled = enabled
            ) {
                Icon(
                    imageVector = Icons.Default.Restore,
                    contentDescription = null,
                    tint = if (enabled) palette.accent
                    else palette.primaryText.copy(alpha = 0.38f),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.recycle_bin_restore),
                    color = if (enabled) palette.accent
                    else palette.primaryText.copy(alpha = 0.38f)
                )
            }
            Box {
                IconButton(
                    onClick = { menuVisible = true },
                    enabled = enabled
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = null,
                        tint = if (enabled) palette.primaryText
                        else palette.primaryText.copy(alpha = 0.38f)
                    )
                }
                AppDropdownMenu(
                    expanded = menuVisible,
                    onDismiss = { menuVisible = false },
                    actions = listOf(
                        MenuAction(
                            icon = Icons.Default.Delete,
                            title = stringResource(R.string.recycle_bin_delete_selection),
                            tint = MaterialTheme.colorScheme.error,
                            onClick = {
                                menuVisible = false
                                onDeleteSelection()
                            }
                        )
                    )
                )
            }
        }
    }
}

@Composable
private fun RecycleBinItemRow(
    item: RecycleBinDisplayItem,
    onToggleSelect: (Boolean) -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Checkbox(
            checked = item.isSelected,
            onCheckedChange = onToggleSelect
        )
        Spacer(modifier = Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row {
                Text(
                    text = typeText(item.type),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = timeText(item.deletedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onRestore) {
            Icon(
                imageVector = Icons.Default.Restore,
                contentDescription = stringResource(R.string.recycle_bin_restore),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun typeText(type: String): String = when (type) {
    SourceRecycleBinHelp.TYPE_BOOK_SOURCE -> stringResource(R.string.recycle_bin_type_book_source)
    SourceRecycleBinHelp.TYPE_RSS_SOURCE -> stringResource(R.string.recycle_bin_type_rss_source)
    SourceRecycleBinHelp.TYPE_REPLACE_RULE -> stringResource(R.string.recycle_bin_type_replace_rule)
    SourceRecycleBinHelp.TYPE_TXT_TOC_RULE -> stringResource(R.string.recycle_bin_type_txt_toc_rule)
    SourceRecycleBinHelp.TYPE_HTTP_TTS -> stringResource(R.string.recycle_bin_type_http_tts)
    SourceRecycleBinHelp.TYPE_DICT_RULE -> stringResource(R.string.recycle_bin_type_dict_rule)
    SourceRecycleBinHelp.TYPE_HIGHLIGHT_RULE -> stringResource(R.string.recycle_bin_type_highlight_rule)
    else -> type
}

@Composable
private fun timeText(time: Long): String {
    val str = remember(time) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))
    }
    return str
}

private fun LazyListState.indexOfPoint(y: Float): Int? {
    return layoutInfo.visibleItemsInfo.firstOrNull {
        y >= it.offset && y <= it.offset + it.size
    }?.index
}
