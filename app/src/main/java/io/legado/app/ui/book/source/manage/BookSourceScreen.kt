package io.legado.app.ui.book.source.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.ui.widget.compose.AppManagementLazyColumn
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.material3.MaterialTheme
import io.legado.app.ui.theme.bodyTertiary

@Composable
internal fun BookSourceScreen(
    sources: List<BookSourcePart>,
    selectedUrls: Set<String>,
    isSelectMode: Boolean,
    showSourceHost: Boolean,
    sourceHostHeaders: Map<String, String?>,
    debugMessages: Map<String, String>,
    isChecking: Boolean,
    // 批D：校验进度横幅（原 Snackbar 承载，改 Compose 状态驱动）
    checkBannerText: String? = null,
    onCancelCheck: () -> Unit = {},
    // bugfix-0908 T5：数据版本信号（宿主实际变更时递增），替代原万级 joinToString 指纹
    dataVersion: Int = 0,
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
        BookSourceItemRow(
            title = source.getDisPlayNameGroup(),
            enabled = source.enabled,
            hasExploreUrl = source.hasExploreUrl,
            enabledExplore = source.enabledExplore,
            debugMessage = message,
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
    Column(modifier = Modifier.fillMaxSize()) {
        checkBannerText?.takeIf { it.isNotBlank() }?.let { bannerText ->
            CheckProgressBanner(
                text = bannerText,
                palette = palette,
                onCancel = onCancelCheck
            )
        }
        AppManagementLazyColumn(
            palette = palette,
            state = lazyListState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
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

@Composable
private fun CheckProgressBanner(
    text: String,
    palette: AppManagementPalette,
    onCancel: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
    ) {
        CircularProgressIndicator(
            color = palette.settings.accent,
            strokeWidth = 2.dp,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = text,
            color = palette.settings.secondaryText,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        )
        Text(
            text = stringResource(R.string.cancel),
            color = palette.settings.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable(onClick = onCancel)
                .padding(horizontal = 10.dp, vertical = 8.dp)
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

/** bugfix-0908 T5：扁平行模型（域名分组头或普通源行），单 items(key) 批量提交 */
private data class SourceRowModel(
    val key: String,
    val headerText: String?,
    val source: BookSourcePart
)
