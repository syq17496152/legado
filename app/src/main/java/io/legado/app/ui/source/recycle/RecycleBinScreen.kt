package io.legado.app.ui.source.recycle

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
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.source.SourceRecycleBinHelp
import io.legado.app.ui.widget.components.ConfirmDialog
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.ShelfListSkeleton
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementLazyColumn
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
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

/**
 * 回收站页（「我的 → 书源管理 → 回收站」子子页）。
 *
 * **2026-09-27 行组件收敛第四批（用户裁定：「我的」下的子页/子子页必须同脚手架同行）**：
 * 原实现是自绘 `GlassTopAppBar` + 页内 `LazyColumn` + 行间自绘 `HorizontalDivider(0.5dp)` +
 * 私有 `RecycleBinActionBar`（`Surface` 底栏）+ 自绘 `RecycleBinItemRow`（`Checkbox` + 双图标按钮，
 * 取色走 M3 派生键 `onSurface/onSurfaceVariant/colorScheme.primary/error`）⇒ 与书源管理基线
 * （`AppManagementScaffold` + `AppManagementLazyColumn` + `AppManagementListRow`）形成「同脚手架不同行」。
 *
 * 现按 §3.1 三件套口径整体收敛：壳/容器/行全部换管理族单源，**行间分隔线清零**（卡片行间距由容器承载），
 * 行的取色/圆角/行高不再自带（M3 派生色usage 一并清零）。
 *
 * 交互等价口径：选择模式**常驻**（原实现 `Checkbox` 常显，与字典/TXT目录/自动任务三页一致）；
 * 长按拖动滑选批量勾选保留；行尾动作（恢复 / 彻底删除）与「清空回收站 / 帮助」收进菜单，
 * 彻底删除走 `danger` 通道（原实现用 M3 派生色 error 硬塞，且薄壳不消费 tint ⇒ 实际无色）。
 */
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
    var deleteItem by remember { mutableStateOf<RecycleBinDisplayItem?>(null) }
    var deleteSelectionVisible by remember { mutableStateOf(false) }
    var emptyConfirmVisible by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val palette = rememberAppManagementPalette()
    val allSelected = items.isNotEmpty() && selectionCount >= items.size
    // 文案在**组合上下文**预取：`AppManagementAction.menuActions` 是普通 lambda（非 @Composable）
    // ⇒ 其内不得调用 `stringResource`（否则报「@Composable invocations can only happen from …」）
    val moreMenuLabel = stringResource(R.string.more_menu)
    val emptyBinLabel = stringResource(R.string.recycle_bin_empty)
    val helpLabel = stringResource(R.string.help)

    AppManagementScaffold(
        title = stringResource(R.string.recycle_bin),
        selectedCount = selectionCount,
        totalCount = items.size,
        modifier = modifier,
        palette = palette,
        onBack = onBack,
        // 顶栏动作：溢出菜单承载「清空回收站 / 帮助」（图标缺省走 TopBarConfig.Icons 契约兜底，
        // 禁止在实现内写死 R.drawable.ic_more_vert —— 顶栏包 §3.2 / 门禁 G-20）
        topActions = listOf(
            AppManagementAction(
                text = moreMenuLabel,
                menuActions = {
                    listOf(
                        AppManagementMenuAction(
                            text = emptyBinLabel,
                            icon = Icons.Default.DeleteSweep,
                            danger = true,
                            onClick = { emptyConfirmVisible = true }
                        ),
                        AppManagementMenuAction(
                            text = helpLabel,
                            icon = Icons.Default.HelpOutline,
                            onClick = onHelp
                        )
                    )
                }
            )
        ),
        // 多选底栏：与字典/TXT目录/自动任务三页同约定（danger 动作作主按钮，其余进溢出）
        bottomActions = listOf(
            AppManagementAction(
                text = stringResource(R.string.recycle_bin_restore),
                onClick = onRestoreSelection
            ),
            AppManagementAction(
                text = stringResource(R.string.recycle_bin_delete_selection),
                danger = true,
                onClick = { deleteSelectionVisible = true }
            )
        ),
        onSelectAll = { onSelectAll(!allSelected) },
        onInvertSelection = onRevertSelection
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            when {
                isLoading -> ShelfListSkeleton(compact = true)
                items.isEmpty() -> EmptyStatePlaceholder(
                    icon = Icons.Default.Restore,
                    // 空态文案修正（docs/UI/AUDIT-OPTIMIZATION-ALIGN.md P1·md#1）：原复用「清空回收站」
                    // （recycle_bin_empty）作空态标题，语义是动作而非状态 ⇒ 改用「回收站为空」
                    title = stringResource(R.string.recycle_bin_is_empty),
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
                    AppManagementLazyColumn(
                        palette = palette,
                        state = listState
                    ) {
                        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                            AppManagementListRow(
                                title = item.name,
                                subtitle = "${typeText(item.type)} · ${timeText(item.deletedAt)}",
                                palette = palette,
                                selected = item.isSelected,
                                onToggleSelection = { onToggleSelect(index, !item.isSelected) },
                                minHeight = 56.dp,
                                drawPanelImage = false,
                                // 行体点按与复选框同语义（选择模式常驻，行体非破坏性）
                                onClick = { onToggleSelect(index, !item.isSelected) },
                                onLongClick = { onToggleSelect(index, true) },
                                moreActions = listOf(
                                    AppManagementMenuAction(
                                        text = stringResource(R.string.recycle_bin_restore),
                                        icon = Icons.Default.Restore,
                                        onClick = { onRestore(item) }
                                    ),
                                    AppManagementMenuAction(
                                        text = stringResource(R.string.recycle_bin_delete_selection),
                                        icon = Icons.Default.Delete,
                                        danger = true,
                                        onClick = { deleteItem = item }
                                    )
                                )
                            )
                        }
                    }
                }
            }
        }
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
            // 覆盖同名规则是破坏性操作（docs/UI/AUDIT-OPTIMIZATION-ALIGN.md P2·md#3：原未传
            // destructive ⇒ 确认按钮无危险语义）
            destructive = true,
            onConfirm = onConfirmRestoreOverwrite,
            onDismiss = onDismissRestoreOverwrite
        )
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