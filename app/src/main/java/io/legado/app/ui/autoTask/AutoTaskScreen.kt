package io.legado.app.ui.autoTask

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsSelectableRow
import io.legado.app.ui.widget.components.ShelfListSkeleton
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementLazyColumn
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.compose.measuredRowPitchPx
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
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
    // 拖拽起始时实测的行节拍（px）：真实行距随字体缩放/副标题行数变化，禁止用页内 dp 常量推算
    var dragPitchPx by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    // 兜底行节拍（仅当可视项不足 2 个、拖不动时使用）：minHeight 56 + Card 内边距 8×2 + 外边距 4×2
    // + 列表项间距 8（AppListSpacing.Normal）= 88dp
    val fallbackPitchPx = with(LocalDensity.current) { 88.dp.toPx() }

    LaunchedEffect(items) {
        if (dragIndex == null) {
            localItems = items
        }
    }

    // 行组件收敛（2026-09-26）第二批：壳层由页内自绘（GlassTopAppBar + SettingsSearchBar +
    // AutoTaskSelectionActionBar）平移为「我的」管理族唯一壳 `AppManagementScaffold`，
    // 与字典规则/TXT目录规则同壳（消除「同壳不同栏」）；搜索槽、多选底栏、全选/反选一律由壳承载。
    val palette = rememberAppManagementPalette()
    val allSelected = localItems.isNotEmpty() && selectionCount >= localItems.size

    AppManagementScaffold(
        title = stringResource(R.string.auto_task_manage),
        selectedCount = selectionCount,
        totalCount = localItems.size,
        modifier = modifier,
        palette = palette,
        searchQuery = searchKey,
        searchHint = stringResource(R.string.search),
        onSearchChange = onSearchChange,
        onBack = onBack,
        topActions = buildList {
            // 顶栏分级语义：alwaysShow 项直出一级图标，其余进溢出菜单（与 DictRuleScreen 同口径）
            topMenuActions.filter { it.alwaysShow }.forEach { action ->
                add(
                    AppManagementAction(
                        text = action.title,
                        icon = action.icon,
                        // 图标双源同链透传（漏传 iconRes ⇒ iconRes-only 动作静默退化成三点图标）
                        iconRes = action.iconRes,
                        onClick = action.onClick
                    )
                )
            }
            val overflowActions = topMenuActions.filter { !it.alwaysShow }
            if (overflowActions.isNotEmpty()) {
                add(
                    AppManagementAction(
                        text = stringResource(R.string.more_menu),
                        menuActions = {
                            overflowActions.map { menuAction ->
                                AppManagementMenuAction(
                                    text = menuAction.title,
                                    icon = menuAction.icon,
                                    iconRes = menuAction.iconRes,
                                    checked = menuAction.checked == true,
                                    onClick = menuAction.onClick
                                )
                            }
                        }
                    )
                )
            }
        },
        bottomActions = selMenuActions.map { action ->
            AppManagementAction(text = action.title, onClick = action.onClick)
        } + AppManagementAction(
            text = stringResource(R.string.delete),
            danger = true,
            onClick = onDeleteSelection
        ),
        // 壳内底栏计数文本点按即「全选/取消全选」，语义与原页 AutoTaskSelectionActionBar 逐字一致
        onSelectAll = { onSelectAll(!allSelected) },
        onInvertSelection = onRevertSelection
    ) { _ ->
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
                    // 列表容器收敛（2026-09-26）：与书源管理同容器（项间距 8dp + 快速滚动条 + 导航栏内边距），
                    // 行间距不再由页内自绘分隔线承载
                    AppManagementLazyColumn(
                        palette = palette,
                        state = listState
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
                                dragStartIndex = {
                                    dragIndex = index
                                    dragTotalY = 0f
                                    dragPitchPx = listState.measuredRowPitchPx(fallbackPitchPx)
                                },
                                onDrag = { dragAmount ->
                                    dragTotalY += dragAmount
                                    dragIndex?.let { cur ->
                                        val pitch = dragPitchPx.takeIf { it > 0f } ?: fallbackPitchPx
                                        val target = (cur + (dragTotalY / pitch).roundToInt())
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
                        }
                    }
                }
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