package io.legado.app.ui.rss.source.manage

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
    onReorder: (List<RssSource>) -> Unit,
    onToggleSelect: (RssSource) -> Unit,
    onToggleEnabled: (RssSource, Boolean) -> Unit,
    onEdit: (RssSource) -> Unit,
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
        subtitle = source.sourceGroup,
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
