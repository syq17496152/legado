package io.legado.app.ui.dict.rule

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * L-C6 词典规则列表页（S2 列表管理页）：全 Compose 内容区。
 *
 * 交互：点击行编辑 / CheckBox 勾选（选择模式常驻，与原版 activeSlideSelect 一致）/
 * 长按拖动滑选批量勾选 / 右侧手柄长按拖拽排序 / 底部批量操作栏（全选·反选·删除·选择菜单）。
 */
data class DictRuleDisplayItem(
    val name: String,
    val enabled: Boolean,
    val isSelected: Boolean
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DictRuleScreen(
    items: List<DictRuleDisplayItem>,
    isLoading: Boolean,
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
    onEdit: (Int) -> Unit,
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

    // followup F5：统一管理族壳（AppManagementScaffold 平移，删页内自绘 GlassTopAppBar/SelectionActionBar）
    val palette = rememberAppManagementPalette()
    val allSelected = localItems.isNotEmpty() && selectionCount >= localItems.size

    AppManagementScaffold(
        title = stringResource(R.string.dict_rule),
        selectedCount = selectionCount,
        totalCount = localItems.size,
        modifier = modifier,
        palette = palette,
        onBack = onBack,
        topActions = buildList {
            // topbar-icon-semantics-fix 3.3：alwaysShow 项直出一级图标（对齐原版 dict_rule.xml always）
            topMenuActions.filter { it.alwaysShow }.forEach { action ->
                add(
                    AppManagementAction(
                        text = action.title,
                        icon = action.icon,
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
        onSelectAll = { onSelectAll(!allSelected) },
        onInvertSelection = onRevertSelection
    ) { contentPalette ->
        Box(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            when {
                isLoading -> ShelfListSkeleton(compact = true)
                items.isEmpty() -> EmptyStatePlaceholder(
                    icon = Icons.Default.Rule,
                    title = stringResource(R.string.empty),
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
                        itemsIndexed(localItems, key = { _, item -> item.name }) { index, item ->
                            SettingsSelectableRow(
                                checked = item.isSelected,
                                title = item.name,
                                enabled = item.enabled,
                                onClick = { onItemClick(index) },
                                onToggleSelect = { checked -> onToggleSelect(index, checked) },
                                onToggleEnable = { checked -> onToggleEnable(index, checked) },
                                onEdit = { onEdit(index) },
                                onDelete = { onDelete(index) },
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
                                    onOrderCommitted(localItems.map { it.name })
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
    }
}

/** 根据可视 Y 坐标定位 LazyColumn 中对应 item 的 index */
private fun LazyListState.indexOfPoint(y: Float): Int? {
    return layoutInfo.visibleItemsInfo.firstOrNull {
        y >= it.offset && y <= it.offset + it.size
    }?.index
}
