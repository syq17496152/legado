package io.legado.app.ui.book.cache

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.ui.theme.bodySecondary
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.compose.BookCoverImage
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import androidx.compose.ui.draw.alpha

/**
 * 缓存管理页 Compose 实现（my-compose-full W4.1，替代 CacheManageAdapter View 列表）。
 *
 * 保真原则（红队 R2 源码穿透修正）：原页无拖拽/无多选，排序为对话框比较器方案——本 Screen 不新增拖拽，
 * 排序/过滤在宿主 applyFilters 完成后传入 [items]。任务态经 SnapshotStateMap 定向写入实现
 * 等效 PAYLOAD_TASK_STATE 局部刷新（仅读取对应 key 的 item 重组）。
 * 数据安全边界（L3）：删除/上传/恢复全部经宿主回调链（确认弹框前置），Screen 不直接触达 VM/文件。
 */
enum class CacheItemAction {
    OPEN_CHAPTERS, UPLOAD, DOWNLOAD, SELECT_SYNC, RESTORE_BOOKSHELF, DELETE, STOP_AUDIO, SELECT_SOURCE
}

@Composable
fun CacheManageScreen(
    mode: CacheManageMode,
    items: List<CacheBookItem>,
    summaryText: String,
    loading: Boolean,
    audioTaskStates: Map<String, AudioCacheTaskState>,
    webDavTaskStates: Map<String, WebDavTaskState>,
    containerVisible: Boolean,
    onModeSwitch: (CacheManageMode) -> Unit,
    onSearch: () -> Unit,
    onSortSelect: () -> Unit,
    onContainerSelect: () -> Unit,
    onUploadAll: () -> Unit,
    onDeleteAll: () -> Unit,
    onItemAction: (CacheBookItem, CacheItemAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = rememberAppManagementPalette()
    val hostActivity = LocalContext.current as? androidx.activity.ComponentActivity
    AppManagementScaffold(
        title = stringResource(R.string.cache_manage_title),
        selectedCount = 0,
        totalCount = items.size,
        palette = palette,
        onBack = { hostActivity?.finish() },
        topActions = buildList {
            add(AppManagementAction(text = stringResource(R.string.cache_manage_search_book), iconRes = R.drawable.ic_search, onClick = onSearch))
            add(AppManagementAction(text = stringResource(R.string.cache_manage_sort_title), iconRes = R.drawable.ic_baseline_sort_24, onClick = onSortSelect))
            if (containerVisible) {
                add(AppManagementAction(text = stringResource(R.string.s3_bucket), iconRes = R.drawable.ic_outline_cloud_24, onClick = onContainerSelect))
            }
        }
    ) { _ ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(palette.settings.page)
        ) {
            CacheModeTabs(mode = mode, palette = palette, onModeSwitch = onModeSwitch)
            Text(
                text = summaryText,
                color = palette.settings.secondaryText,
                fontSize = 12.sp,
                fontFamily = palette.settings.bodyFontFamily,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            Box(modifier = Modifier.weight(1f)) {
                if (items.isEmpty() && !loading) {
                    Text(
                        text = stringResource(R.string.cache_manage_empty, stringResource(mode.titleRes)),
                        color = palette.settings.secondaryText,
                        fontSize = MaterialTheme.typography.bodySecondary.fontSize,
                        fontFamily = palette.settings.bodyFontFamily,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(items, key = { it.groupKey }) { item ->
                            CacheManageListItem(
                                item = item,
                                palette = palette,
                                audioTaskStates = audioTaskStates,
                                webDavTaskStates = webDavTaskStates,
                                onAction = onItemAction
                            )
                        }
                    }
                }
            }
            CacheBatchBar(onUploadAll = onUploadAll, onDeleteAll = onDeleteAll, palette = palette)
        }
    }
}

@Composable
private fun CacheModeTabs(
    mode: CacheManageMode,
    palette: AppManagementPalette,
    onModeSwitch: (CacheManageMode) -> Unit
) {
    val tabs = remember {
        listOf(
            CacheManageMode.BOOK to R.string.cache_manage_books,
            CacheManageMode.AUDIO to R.string.cache_manage_audio,
            CacheManageMode.MANGA to R.string.cache_manage_manga
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(palette.settings.row)),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        tabs.forEach { (tabMode, titleRes) ->
            val selected = mode == tabMode
            Text(
                text = stringResource(titleRes),
                color = if (selected) palette.settings.accent else palette.settings.primaryText,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontFamily = palette.settings.bodyFontFamily,
                modifier = Modifier
                    .clickable { onModeSwitch(tabMode) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun CacheBatchBar(
    onUploadAll: () -> Unit,
    onDeleteAll: () -> Unit,
    palette: AppManagementPalette
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(palette.settings.row))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // W4.1：批量按钮用 Chip 风格保持与 item 动作一致（LegadoMiuixActionButton 需 Miuix 色板，管理页色板无转换）
        Text(
            text = stringResource(R.string.cache_manage_upload_all),
            color = palette.settings.accent,
            fontSize = 14.sp,
            fontFamily = palette.settings.bodyFontFamily,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(palette.settings.rowPressed))
                .clickable(onClick = onUploadAll)
                .padding(vertical = 10.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.cache_manage_delete_all),
            color = palette.settings.primaryText,
            fontSize = 14.sp,
            fontFamily = palette.settings.bodyFontFamily,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(palette.settings.rowPressed))
                .clickable(onClick = onDeleteAll)
                .padding(vertical = 10.dp)
        )
    }
}

@Composable
private fun CacheManageListItem(
    item: CacheBookItem,
    palette: AppManagementPalette,
    audioTaskStates: Map<String, AudioCacheTaskState>,
    webDavTaskStates: Map<String, WebDavTaskState>,
    onAction: (CacheBookItem, CacheItemAction) -> Unit
) {
    val context = LocalContext.current
    val audioState = item.taskStateFor(audioTaskStates)
    val webDavState = item.webDavTaskStateFor(webDavTaskStates)
    val isCaching = audioState?.active == true
    val isPaused = audioState?.status == CacheTaskStatus.PAUSED
    val webDavActive = webDavState?.active == true
    val taskLocked = isCaching || isPaused || webDavActive
    val hasCache = item.hasLocalCache()
    val canSync = hasCache || item.hasRemoteCache()
    val canDelete = canSync && !taskLocked

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(palette.settings.panelRadiusPx.let { (it / LocalContext.current.resources.displayMetrics.density).toInt().coerceAtLeast(1) }))
            .background(Color(palette.settings.row))
            .clickable { onAction(item, CacheItemAction.OPEN_CHAPTERS) }
            .padding(10.dp)
    ) {
        Row {
            BookCoverImage(
                book = item.book,
                modifier = Modifier.size(width = 60.dp, height = 82.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.book.name,
                    color = palette.settings.primaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = palette.settings.titleFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 源 chip（多源可点击切换）
                val multiSource = item.sourceVariants.size > 1
                Text(
                    text = if (item.sourceAvailable) item.sourceName
                    else stringResource(R.string.cache_manage_source_deleted_chip, item.sourceName),
                    color = palette.settings.secondaryText,
                    fontSize = 12.sp,
                    fontFamily = palette.settings.bodyFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(palette.settings.rowPressed))
                        .clickable(enabled = multiSource) { onAction(item, CacheItemAction.SELECT_SOURCE) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .let { if (multiSource) it else it.alpha(0.72f) }
                )
                Text(
                    text = item.cacheCountText(context),
                    color = palette.settings.primaryText,
                    fontSize = 12.sp,
                    fontFamily = palette.settings.bodyFontFamily,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = stringResource(item.cacheStateLabelRes()),
                    color = palette.settings.secondaryText,
                    fontSize = 11.sp,
                    fontFamily = palette.settings.bodyFontFamily
                )
            }
        }
        // 任务态行（音频/WebDav 进度消息 + 暂停/继续）
        val taskMessage = when {
            isCaching || isPaused -> audioState?.message
            webDavActive -> webDavState?.message
            else -> webDavState?.takeIf { it.status != WebDavTaskStatus.COMPLETED }?.message
                ?: audioState?.takeIf { it.status != CacheTaskStatus.COMPLETED }?.message
        }
        if (!taskMessage.isNullOrBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    text = taskMessage,
                    color = palette.settings.secondaryText,
                    fontSize = 11.sp,
                    fontFamily = palette.settings.bodyFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isCaching || isPaused) {
                    Text(
                        text = stringResource(if (isPaused) R.string.resume else R.string.pause),
                        color = palette.settings.accent,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { onAction(item, CacheItemAction.STOP_AUDIO) }
                    )
                }
            }
        }
        // 动作按钮行（章节/同步/书架/删除）
        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CacheActionChip(
                text = stringResource(R.string.cache_manage_chapters),
                enabled = hasCache,
                palette = palette,
                accent = true,
                onClick = { onAction(item, CacheItemAction.OPEN_CHAPTERS) }
            )
            val uploadLabel = when {
                hasCache && item.hasRemoteCache() -> stringResource(R.string.cache_manage_sync_action)
                item.hasRemoteCache() && !hasCache -> stringResource(R.string.action_download)
                else -> stringResource(R.string.cache_manage_upload)
            }
            CacheActionChip(
                text = uploadLabel,
                enabled = canSync && !taskLocked,
                palette = palette,
                onClick = {
                    when {
                        hasCache && item.hasRemoteCache() -> onAction(item, CacheItemAction.SELECT_SYNC)
                        item.hasRemoteCache() && !hasCache -> onAction(item, CacheItemAction.DOWNLOAD)
                        else -> onAction(item, CacheItemAction.UPLOAD)
                    }
                }
            )
            if (item.manifest != null && hasCache) {
                CacheActionChip(
                    text = stringResource(if (item.inBookshelf) R.string.cache_manage_use_cache else R.string.cache_manage_add_bookshelf),
                    enabled = true,
                    palette = palette,
                    onClick = { onAction(item, CacheItemAction.RESTORE_BOOKSHELF) }
                )
            }
            CacheActionChip(
                text = stringResource(R.string.delete),
                enabled = canDelete,
                palette = palette,
                onClick = { onAction(item, CacheItemAction.DELETE) }
            )
        }
    }
}

@Composable
private fun CacheActionChip(
    text: String,
    enabled: Boolean,
    palette: AppManagementPalette,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    Text(
        text = text,
        color = when {
            !enabled -> palette.settings.secondaryText.copy(alpha = 0.45f)
            accent -> palette.settings.accent
            else -> palette.settings.primaryText
        },
        fontSize = 12.sp,
        fontFamily = palette.settings.bodyFontFamily,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(palette.settings.rowPressed))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

// —— 状态解析（自 CacheManageAdapter 平移，保持判定语义一致） ——

private fun CacheBookItem.taskStateFor(states: Map<String, AudioCacheTaskState>): AudioCacheTaskState? {
    states[book.bookUrl]?.let { return it }
    sourceVariants.forEach { variant ->
        states[variant.book.bookUrl]?.let { return it }
        variant.taskState?.let { return it }
    }
    return taskState
}

private fun CacheBookItem.webDavTaskStateFor(states: Map<String, WebDavTaskState>): WebDavTaskState? {
    states[cacheKey]?.let { return it }
    sourceVariants.forEach { variant ->
        states[variant.cacheKey]?.let { return it }
    }
    return null
}

private fun CacheBookItem.hasLocalCache(): Boolean = localCachedCount > 0

private fun CacheBookItem.cacheStateLabelRes(): Int {
    val local = hasLocalCache()
    val remote = hasRemoteCache()
    return when {
        local && remote -> R.string.cache_manage_state_both
        local -> R.string.cache_manage_state_local
        remote -> R.string.cache_manage_state_remote
        else -> R.string.cache_manage_state_none
    }
}

private fun CacheBookItem.cacheCountText(context: android.content.Context): String {
    return buildCacheCountText(context, localCachedCount, remoteCachedCount, totalChapterCount, remoteAvailable)
}

fun CacheBookSourceVariant.cacheCountText(context: android.content.Context): String {
    return buildCacheCountText(context, localCachedCount, remoteCachedCount, totalChapterCount, remoteAvailable)
}

fun buildCacheCountText(
    context: android.content.Context,
    localCount: Int,
    remoteCount: Int,
    totalCount: Int,
    remoteAvailable: Boolean
): String {
    val parts = arrayListOf<String>()
    if (localCount > 0) {
        parts += context.getString(R.string.cache_manage_local_cached_count, localCount)
    }
    if (remoteAvailable && remoteCount > 0) {
        parts += context.getString(R.string.cache_manage_remote_cached_count, remoteCount)
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        ?: context.getString(R.string.cache_manage_cached_count, localCount)
}
