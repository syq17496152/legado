package io.legado.app.ui.autoTask

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsSearchBar
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.ui.widget.components.SettingsSelectableRow
import io.legado.app.ui.widget.components.ShelfListSkeleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * L-C16 自动任务列表页（S2 列表管理页）：全 Compose 内容区。
 *
 * 交互：点击行编辑 / CheckBox 勾选（选择模式常驻，与原版 activeSlideSelect 一致）/
 * 长按拖动滑选批量勾选 / 右侧手柄长按拖拽排序 / 行尾更多菜单（登录·日志·删除）/
 * 底部批量操作栏（全选·反选·删除·选择菜单：启用·停用·导出·批量 cron）。
 */
data class AutoTaskDisplayItem(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val summary: String,
    val hasLogin: Boolean,
    val isSelected: Boolean
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AutoTaskScreen(
    items: List<AutoTaskDisplayItem>,
    isLoading: Boolean,
    searchKey: String,
    onSearchChange: (String) -> Unit,
    topMenuActions: List<MenuAction>,
    selMenuActions: List<MenuAction>,
    selectionCount: Int,
    onBack: () -> Unit,
    onItemClick: (Int) -> Unit,
    onToggleSelect: (Int, Boolean) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onRevertSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
    onToggleEnable: (Int, Boolean) -> Unit,
    onLogin: (Int) -> Unit,
    onShowLog: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onOrderCommitted: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    // 拖拽排序期间本地维护的顺序（数据源更新时若无拖拽则同步）
    var localItems by remember { mutableStateOf(items) }
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    var dragTotalY by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val itemHeightPx = with(LocalDensity.current) { 72.dp.toPx() }

    LaunchedEffect(items) {
        if (dragIndex == null) {
            localItems = items
        }
    }

    var moreMenuVisible by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        GlassTopAppBar(
            title = stringResource(R.string.auto_task_manage),
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
                        actions = topMenuActions
                    )
                }
            }
        )

        SettingsSearchBar(
            query = searchKey,
            onQueryChange = onSearchChange,
            placeholder = stringResource(R.string.search)
        )

        Box(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            when {
                isLoading -> ShelfListSkeleton(compact = true)
                items.isEmpty() -> EmptyStatePlaceholder(
                    icon = Icons.Default.Schedule,
                    title = stringResource(R.string.auto_task_no_task),
                    modifier = Modifier.fillMaxSize()
                )
                else -> Box(
                    modifier = Modifier
                        .fillMaxSize()
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
                                            localItems.getOrNull(i)?.let { item ->
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
                        itemsIndexed(localItems, key = { _, item -> item.id }) { index, item ->
                            SettingsSelectableRow(
                                checked = item.isSelected,
                                title = item.name,
                                subtitle = item.summary,
                                enabled = item.enabled,
                                onClick = { onItemClick(index) },
                                onToggleSelect = { checked -> onToggleSelect(index, checked) },
                                onToggleEnable = { checked -> onToggleEnable(index, checked) },
                                moreActions = buildList {
                                    if (item.hasLogin) {
                                        add(
                                            MenuAction(
                                                icon = Icons.Default.Login,
                                                title = stringResource(R.string.login),
                                                onClick = { onLogin(index) }
                                            )
                                        )
                                    }
                                    add(
                                        MenuAction(
                                            icon = Icons.Default.Info,
                                            title = stringResource(R.string.log),
                                            onClick = { onShowLog(index) }
                                        )
                                    )
                                    add(
                                        MenuAction(
                                            icon = Icons.Default.Delete,
                                            title = stringResource(R.string.delete),
                                            tint = MaterialTheme.colorScheme.error,
                                            onClick = { onDelete(index) }
                                        )
                                    )
                                },
                                dragStartIndex = { dragIndex = index; dragTotalY = 0f },
                                onDrag = { dragAmount ->
                                    dragTotalY += dragAmount
                                    dragIndex?.let { cur ->
                                        val target = (cur + (dragTotalY / itemHeightPx).roundToInt())
                                            .coerceIn(0, localItems.lastIndex)
                                        if (target != cur) {
                                            val list = localItems.toMutableList()
                                            val moved = list.removeAt(cur)
                                            list.add(target, moved)
                                            localItems = list
                                            dragIndex = target
                                            onMove(cur, target)
                                        }
                                    }
                                },
                                onDragEnd = {
                                    dragIndex = null
                                    dragTotalY = 0f
                                    onOrderCommitted(localItems.map { it.id })
                                }
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

        AutoTaskSelectionActionBar(
            selectionCount = selectionCount,
            totalCount = localItems.size,
            selMenuActions = selMenuActions,
            onSelectAll = onSelectAll,
            onRevertSelection = onRevertSelection,
            onDeleteSelection = onDeleteSelection
        )
    }
}

/** 底部批量操作栏（SelectActionBar 的 Compose 版，同 12.54/12.55 复用） */
@Composable
private fun AutoTaskSelectionActionBar(
    selectionCount: Int,
    totalCount: Int,
    selMenuActions: List<MenuAction>,
    onSelectAll: (Boolean) -> Unit,
    onRevertSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled = selectionCount > 0
    val allSelected = totalCount > 0 && selectionCount >= totalCount
    var menuVisible by remember { mutableStateOf(false) }
    // H11: 选择操作栏直色（palette.row），替代 M3 surface 派生色
    val palette = rememberAppSettingPalette()

    Surface(
        modifier = modifier.fillMaxWidth(),
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
                onClick = onRevertSelection,
                enabled = enabled
            ) {
                Text(stringResource(R.string.revert_selection))
            }
            TextButton(
                onClick = onDeleteSelection,
                enabled = enabled
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.error
                    else palette.primaryText.copy(alpha = 0.38f),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.delete),
                    color = if (enabled) MaterialTheme.colorScheme.error
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
                    actions = selMenuActions
                )
            }
        }
    }
}

/** 根据可视 Y 坐标定位 LazyColumn 中对应 item 的 index */
private fun LazyListState.indexOfPoint(y: Float): Int? {
    return layoutInfo.visibleItemsInfo.firstOrNull {
        y >= it.offset && y <= it.offset + it.size
    }?.index
}
