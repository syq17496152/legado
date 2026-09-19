package io.legado.app.ui.rss.source.manage

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.RssSource
import io.legado.app.ui.widget.components.EmptyStateAction
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.compose.AppManagementLazyColumn
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
internal fun RssSourceScreen(
    sources: List<RssSource>,
    selectedUrls: Set<String>,
    isSelectMode: Boolean,
    reorderEnabled: Boolean,
    /** 当前搜索词（F157 空态双语义判据：空库引导 vs 无匹配纠偏） */
    searchQuery: String,
    onReorder: (List<RssSource>) -> Unit,
    onToggleSelect: (RssSource) -> Unit,
    onToggleEnabled: (RssSource, Boolean) -> Unit,
    onEdit: (RssSource) -> Unit,
    onAdd: () -> Unit,
    onImportOnline: () -> Unit,
    onClearSearch: () -> Unit,
    sourceMenuActions: (RssSource) -> List<AppManagementMenuAction>
) {
    val palette = rememberAppManagementPalette()
    val lazyListState = rememberLazyListState()
    val sourceSnapshot = sources.toList()
    val sourcesSignature = sourceSnapshot.joinToString(separator = "\u001F") {
        listOf(
            it.sourceUrl,
            it.sourceName,
            it.sourceGroup.orEmpty(),
            it.sourceComment.orEmpty(),
            it.enabled,
            it.customOrder,
            it.loginUrl.orEmpty(),
            it.type
        ).joinToString(separator = "\u001E")
    }
    var orderedSources by remember { mutableStateOf(sourceSnapshot, referentialEqualityPolicy()) }
    LaunchedEffect(reorderEnabled, sourcesSignature) {
        orderedSources = sourceSnapshot
    }
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        orderedSources = orderedSources.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
    }

    @Composable
    fun itemRow(source: RssSource, dragHandle: (@Composable () -> Unit)? = null) {
        RssSourceItemRow(
            source = source,
            palette = palette,
            isSelected = source.sourceUrl in selectedUrls,
            isSelectMode = isSelectMode,
            onToggleSelect = { onToggleSelect(source) },
            onToggleEnabled = { enabled -> onToggleEnabled(source, enabled) },
            onEdit = { onEdit(source) },
            moreActions = sourceMenuActions(source),
            dragHandle = dragHandle
        )
    }

    AppManagementLazyColumn(
        palette = palette,
        modifier = Modifier.fillMaxSize(),
        state = lazyListState,
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // F157 空态双语义：原实现列表为空时页面全白，用户无法区分"库是空的"与"搜索没匹配"。
        // 空库 → 给可立即脱困的「添加订阅源 / 网络导入」；有搜索词 → 给「清空搜索」纠偏。
        if (sources.isEmpty()) {
            item(key = "__rss_source_empty__") {
                if (searchQuery.isBlank()) {
                    EmptyStatePlaceholder(
                        icon = Icons.Default.RssFeed,
                        title = stringResource(R.string.rss_source_empty_title),
                        subtitle = stringResource(R.string.rss_source_empty_subtitle),
                        primaryAction = EmptyStateAction(
                            label = stringResource(R.string.add),
                            onClick = onAdd
                        ),
                        secondaryActions = listOf(
                            EmptyStateAction(
                                label = stringResource(R.string.import_on_line),
                                onClick = onImportOnline
                            )
                        ),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    EmptyStatePlaceholder(
                        icon = Icons.Default.SearchOff,
                        title = stringResource(R.string.rss_source_no_match_title),
                        subtitle = stringResource(R.string.rss_source_no_match_subtitle, searchQuery),
                        primaryAction = EmptyStateAction(
                            label = stringResource(R.string.rss_source_clear_search),
                            onClick = onClearSearch
                        ),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        if (reorderEnabled) {
            items(
                items = orderedSources,
                key = { it.sourceUrl },
                contentType = { "rssSource" }
            ) { source ->
                ReorderableItem(reorderState, key = source.sourceUrl) {
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
                items = sources,
                key = { it.sourceUrl },
                contentType = { "rssSource" }
            ) { source ->
                itemRow(source)
            }
        }
    }
}

@Composable
private fun RssSourceItemRow(
    source: RssSource,
    palette: AppManagementPalette,
    isSelected: Boolean,
    isSelectMode: Boolean,
    onToggleSelect: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    moreActions: List<AppManagementMenuAction>,
    dragHandle: (@Composable () -> Unit)? = null
) {
    AppManagementListRow(
        title = source.sourceName,
        // F158 健康度元信息外显：meta 行由「仅分组」扩为「分组 · 上次更新 X 前」，
        // 便于一眼识别长期未更新的源（数据源 = 既有 lastUpdateTime，零新增存储）
        subtitle = listOfNotNull(
            source.sourceGroup?.takeIf { it.isNotBlank() },
            relativeUpdateLabel(source.lastUpdateTime)
        ).joinToString(separator = " · "),
        palette = palette,
        selected = isSelected,
        selectionVisible = isSelectMode,
        animatedSelection = true,
        reserveSelectionSlot = isSelectMode,
        onToggleSelection = onToggleSelect,
        switchChecked = source.enabled,
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
        leadingContent = dragHandle
    )
}

/**
 * F158：源条目「上次更新」相对时间（本地化，复用既有 just_now/minutes_ago/hours_ago/days_ago 词条）。
 * `lastUpdateTime <= 0` 表示从未更新过，单列文案而非"1970 年前"这类无意义相对时间。
 */
@Composable
private fun relativeUpdateLabel(lastUpdateTime: Long): String {
    if (lastUpdateTime <= 0L) return stringResource(R.string.rss_source_never_updated)
    val diff = System.currentTimeMillis() - lastUpdateTime
    return when {
        diff < 60_000L -> stringResource(R.string.just_now)
        diff < 3_600_000L -> stringResource(R.string.minutes_ago, (diff / 60_000L).toInt())
        diff < 86_400_000L -> stringResource(R.string.hours_ago, (diff / 3_600_000L).toInt())
        else -> stringResource(R.string.days_ago, (diff / 86_400_000L).toInt())
    }
}
