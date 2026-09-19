package io.legado.app.ui.book.source.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.ui.widget.components.EmptyStateAction
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.InlineTaskBar
import io.legado.app.ui.widget.components.InlineTaskState
import io.legado.app.ui.widget.compose.AppManagementLazyColumn
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.material3.MaterialTheme
import io.legado.app.ui.theme.bodyTertiary

/**
 * 空态动作集（优化 2：无结果态导入引导）——聚成数据类避免把 5 个回调平铺进
 * 本已很长的 [BookSourceScreen] 参数表。
 */
internal data class BookSourceEmptyActions(
    val onAdd: () -> Unit,
    val onImportLocal: () -> Unit,
    val onImportOnline: () -> Unit,
    val onImportQr: () -> Unit,
    val onClearSearch: () -> Unit
)

@Composable
internal fun BookSourceScreen(
    sources: List<BookSourcePart>,
    selectedUrls: Set<String>,
    isSelectMode: Boolean,
    showSourceHost: Boolean,
    sourceHostHeaders: Map<String, String?>,
    debugMessages: Map<String, String>,
    // P1/B1-②：书源 → 引用书籍数（副标题在校验消息为空时展示；不参与排序）
    bookCounts: Map<String, Int> = emptyMap(),
    isChecking: Boolean,
    // 批D：校验进度横幅（原 Snackbar 承载，改 Compose 状态驱动）
    checkBannerText: String? = null,
    onCancelCheck: () -> Unit = {},
    // bugfix-0908 T5：数据版本信号（宿主实际变更时递增），替代原万级 joinToString 指纹
    dataVersion: Int = 0,
    /** 当前搜索词（优化 2 空态双语义判据） */
    searchQuery: String,
    emptyActions: BookSourceEmptyActions,
    /** F68 聚合条：一键筛选失效源（走宿主既有 `updateSearchQuery("失效")` 链路） */
    onFilterFailed: () -> Unit,
    /** F68 聚合条：对当前可见源发起校验（复用宿主既有校验流程） */
    onCheck: () -> Unit,
    reorderEnabled: Boolean,
    onReorder: (List<BookSourcePart>) -> Unit,
    onToggleSelect: (BookSourcePart) -> Unit,
    onToggleEnabled: (BookSourcePart, Boolean) -> Unit,
    onEdit: (BookSourcePart) -> Unit,
    sourceMenuActions: (BookSourcePart) -> List<AppManagementMenuAction>
) {
    val palette = rememberAppManagementPalette()
    val lazyListState = rememberLazyListState()
    // bugfix-0908 T5：原 sourcesSignature（万级 joinToString 巨串，每次重组重建+比较）删除，
    // 改 dataVersion 整数版本信号驱动 orderedSources 重置（宿主仅在实际变更时递增）
    val sourceSnapshot = sources.toList()
    // 拖拽过程的本地顺序;sources 内容变化(落库后重新发射)时重置同步。
    var orderedSources by remember { mutableStateOf(sourceSnapshot, referentialEqualityPolicy()) }
    LaunchedEffect(reorderEnabled, dataVersion) {
        orderedSources = sourceSnapshot
    }
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        orderedSources = orderedSources.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
    }
    // bugfix-0908 T5：预构建扁平 RowModel（含域名分组头），单 items(key) 批量提交，
    // 消除原 forEach 万级闭包展开与 content 作用域内 SnapshotStateMap 直读。
    // 构建置于组合上下文（dataVersion 仅在宿主实际变更时递增）
    val rowModels = remember(dataVersion, showSourceHost) {
        buildList {
            sources.forEach { source ->
                if (showSourceHost) {
                    val host = sourceHostHeaders[source.bookSourceUrl]
                    if (host != null) {
                        add(SourceRowModel(key = "host:${source.bookSourceUrl}", headerText = host, source = source))
                    }
                }
                add(SourceRowModel(key = source.bookSourceUrl, headerText = null, source = source))
            }
        }
    }

    @Composable
    fun itemRow(source: BookSourcePart, dragHandle: (@Composable () -> Unit)? = null) {
        val message = debugMessages[source.bookSourceUrl].orEmpty()
        // P1/B1-②：校验消息优先；空位显示"引用书籍 N 本"
        val subtitle = message.ifBlank {
            stringResource(R.string.source_book_count, bookCounts[source.bookSourceUrl] ?: 0)
        }
        BookSourceItemRow(
            title = source.getDisPlayNameGroup(),
            enabled = source.enabled,
            hasExploreUrl = source.hasExploreUrl,
            enabledExplore = source.enabledExplore,
            debugMessage = subtitle,
            debugInProgress = message.isNotBlank() &&
                isChecking &&
                !message.contains(FINAL_DEBUG_MESSAGE_REGEX),
            isSelected = source.bookSourceUrl in selectedUrls,
            isSelectMode = isSelectMode,
            palette = palette,
            onToggleSelect = { onToggleSelect(source) },
            onToggleEnabled = { enabled -> onToggleEnabled(source, enabled) },
            onEdit = { onEdit(source) },
            moreActions = sourceMenuActions(source),
            dragHandle = dragHandle
        )
    }

    // 批D：校验进度横幅（原 Snackbar 承载，校验中显示在列表顶部，可取消）
    // A2.4.5：私有 CheckProgressBanner 提升为公共 InlineTaskBar（同语义复用，消除重复实现）
    // F68 健康度聚合条：计数从既有 debugMessages（校验消息，末尾为 成功/失败）派生，零新增存储。
    // 口径与既有 FINAL_DEBUG_MESSAGE_REGEX 一致；未出消息的源计入「未校验」。
    var healthyCount = 0
    var failedCount = 0
    var uncheckedCount = 0
    sources.forEach { source ->
        val message = debugMessages[source.bookSourceUrl].orEmpty()
        when {
            message.contains(DEBUG_FAILED_MARK) -> failedCount++
            message.contains(DEBUG_SUCCESS_MARK) -> healthyCount++
            else -> uncheckedCount++
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        checkBannerText?.takeIf { it.isNotBlank() }?.let { bannerText ->
            InlineTaskBar(
                state = InlineTaskState.Running,
                text = bannerText,
                onCancel = onCancelCheck
            )
        }
        // 仅在确有校验结果时出现（全未校验时该条无信息量，属噪声）
        if (healthyCount + failedCount > 0) {
            SourceHealthBar(
                healthyCount = healthyCount,
                failedCount = failedCount,
                uncheckedCount = uncheckedCount,
                palette = palette,
                onFilterFailed = onFilterFailed,
                onCheck = onCheck
            )
        }
        AppManagementLazyColumn(
            palette = palette,
            state = lazyListState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
        // 优化 2 无结果态导入引导：原实现列表为空即全白，无法区分"库空"与"搜不到"。
        // 空库 → 添加 + 三种导入；有搜索词 → 清空搜索纠偏。
        if (sources.isEmpty()) {
            item(key = "__book_source_empty__") {
                if (searchQuery.isBlank()) {
                    EmptyStatePlaceholder(
                        icon = Icons.Default.MenuBook,
                        title = stringResource(R.string.book_source_empty_title),
                        subtitle = stringResource(R.string.book_source_empty_subtitle),
                        primaryAction = EmptyStateAction(
                            label = stringResource(R.string.add_book_source),
                            onClick = emptyActions.onAdd
                        ),
                        secondaryActions = listOf(
                            EmptyStateAction(
                                label = stringResource(R.string.import_local),
                                onClick = emptyActions.onImportLocal
                            ),
                            EmptyStateAction(
                                label = stringResource(R.string.import_on_line),
                                onClick = emptyActions.onImportOnline
                            ),
                            EmptyStateAction(
                                label = stringResource(R.string.import_by_qr_code),
                                onClick = emptyActions.onImportQr
                            )
                        ),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    EmptyStatePlaceholder(
                        icon = Icons.Default.SearchOff,
                        title = stringResource(R.string.book_source_no_match_title),
                        subtitle = stringResource(R.string.book_source_no_match_subtitle, searchQuery),
                        primaryAction = EmptyStateAction(
                            label = stringResource(R.string.book_source_clear_search),
                            onClick = emptyActions.onClearSearch
                        ),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        if (reorderEnabled) {
            // 手动排序:扁平列表 + 拖动手柄重排(长按仍为多选,不冲突)。
            items(
                items = orderedSources,
                key = { it.bookSourceUrl },
                contentType = { "bookSource" }
            ) { source ->
                ReorderableItem(reorderState, key = source.bookSourceUrl) {
                    itemRow(source) {
                        Icon(
                            painter = painterResource(R.drawable.ic_drag_handle),
                            contentDescription = stringResource(R.string.sort),
                            tint = palette.settings.secondaryText,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(22.dp)
                                .draggableHandle(
                                    onDragStopped = { onReorder(orderedSources) }
                                )
                        )
                    }
                }
            }
        } else {
            items(
                items = rowModels,
                key = { it.key },
                contentType = { if (it.headerText != null) "bookSourceHost" else "bookSource" }
            ) { row ->
                if (row.headerText != null) {
                    BookSourceHostHeader(
                        hostText = row.headerText,
                        palette = palette
                    )
                }
                itemRow(row.source)
            }
        }
        }
    }
}

/**
 * F68 健康度聚合条：校验后一次性给出「可用 / 失效 / 未校验」分布，
 * 免去用户在万级列表里逐行找红字。点「失效 N」直接复用宿主既有失效筛选链路。
 */
@Composable
private fun SourceHealthBar(
    healthyCount: Int,
    failedCount: Int,
    uncheckedCount: Int,
    palette: AppManagementPalette,
    onFilterFailed: () -> Unit,
    onCheck: () -> Unit
) {
    val settings = palette.settings
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            text = stringResource(R.string.source_health_healthy, healthyCount),
            color = colorResource(R.color.success),
            fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.source_health_failed, failedCount),
            color = if (failedCount > 0) settings.danger else settings.secondaryText,
            fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable(enabled = failedCount > 0, onClick = onFilterFailed)
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.source_health_unchecked, uncheckedCount),
            color = settings.secondaryText,
            fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        LegadoMiuixActionButton(
            text = stringResource(R.string.source_health_check_all),
            palette = palette.miuix,
            onClick = onCheck,
            minWidth = 56.dp,
            minHeight = 30.dp,
            insidePadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun BookSourceHostHeader(
    hostText: String,
    palette: AppManagementPalette
) {
    Text(
        text = hostText,
        color = palette.settings.accent,
        fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun BookSourceItemRow(
    title: String,
    enabled: Boolean,
    hasExploreUrl: Boolean,
    enabledExplore: Boolean,
    debugMessage: String,
    debugInProgress: Boolean,
    isSelected: Boolean,
    isSelectMode: Boolean,
    palette: AppManagementPalette,
    onToggleSelect: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    moreActions: List<AppManagementMenuAction>,
    dragHandle: (@Composable () -> Unit)? = null
) {
    AppManagementListRow(
        title = title,
        palette = palette,
        subtitle = debugMessage,
        selected = isSelected,
        selectionVisible = isSelectMode,
        animatedSelection = true,
        reserveSelectionSlot = isSelectMode,
        onToggleSelection = onToggleSelect,
        switchChecked = enabled,
        onSwitchChange = onToggleEnabled,
        titleMaxLines = 1,
        subtitleMaxLines = 1,
        minHeight = 56.dp,
        drawPanelImage = false,
        onClick = {
            if (isSelectMode) onToggleSelect() else onEdit()
        },
        onLongClick = onToggleSelect,
        onEdit = onEdit,
        moreActions = moreActions,
        moreIndicatorColor = if (hasExploreUrl) {
            if (enabledExplore) palette.settings.accent else palette.settings.danger
        } else {
            null
        },
        leadingContent = dragHandle,
        trailingBeforeSwitch = {
            Box(
                modifier = Modifier.width(30.dp),
                contentAlignment = Alignment.Center
            ) {
                if (debugInProgress) {
                    CircularProgressIndicator(
                        color = palette.settings.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    )
}

private val FINAL_DEBUG_MESSAGE_REGEX = Regex("成功|失败")

/** F68 聚合条计数判据（与 [FINAL_DEBUG_MESSAGE_REGEX] 同口径） */
private const val DEBUG_SUCCESS_MARK = "成功"
private const val DEBUG_FAILED_MARK = "失败"

/** bugfix-0908 T5：扁平行模型（域名分组头或普通源行），单 items(key) 批量提交 */
private data class SourceRowModel(
    val key: String,
    val headerText: String?,
    val source: BookSourcePart
)
