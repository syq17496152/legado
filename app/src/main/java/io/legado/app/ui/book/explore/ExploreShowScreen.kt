package io.legado.app.ui.book.explore

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.AppNumberPickerDialog
import io.legado.app.ui.widget.components.AppTextDialog
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.ShelfListSkeleton
import io.legado.app.ui.widget.components.TagChip
import io.legado.app.ui.widget.image.CoverImageView
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 发现列表分页页 Compose 受控组件（L-B14 枝叶页，S2 列表族 + 分页加载范式）。
 *
 * 状态全部由宿主（ExploreShowActivity）传入，事件全部上抛：
 * - 分页：滚动到底自动加载下一页（onLoadMore）、滚动到顶加载上一页（onLoadTop）
 * - 跳页：顶栏更多菜单 -> AppNumberPickerDialog（1-999）
 * - 错误：加载失败自动弹 AppTextDialog 显示详情，底部条点击重试
 * - 点击条目 -> 书籍详情（onItemClick）
 */
data class ExploreShowDisplayItem(
    val name: String,
    val author: String,
    val intro: String,
    val kinds: List<String>,
    val latestChapterTitle: String,
    val isInBookshelf: Boolean,
    val book: SearchBook
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreShowScreen(
    items: List<ExploreShowDisplayItem>,
    title: String,
    currentPage: Int,
    topLoading: Boolean,
    bottomLoading: Boolean,
    hasMore: Boolean,
    canLoadTop: Boolean,
    emptyMessage: String,
    loadMoreError: String?,
    jumpFlag: Int,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onLoadTop: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onItemClick: (Int) -> Unit,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    var moreMenuVisible by remember { mutableStateOf(false) }
    var showPageDialog by remember { mutableStateOf(false) }
    var dismissedError by remember { mutableStateOf<String?>(null) }
    var jumpPending by remember { mutableStateOf(false) }

    // 跳页后待滚动到列表首位（顶部加载条占用位置 0）
    LaunchedEffect(jumpFlag) {
        if (jumpFlag > 0) jumpPending = true
    }
    LaunchedEffect(listState, items, canLoadTop) {
        if (jumpPending && items.isNotEmpty()) {
            listState.scrollToItem(if (canLoadTop) 1 else 0)
            jumpPending = false
        }
    }

    // 滚动到底 -> 加载下一页（加载互斥由宿主负责）
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to
                listState.layoutInfo.totalItemsCount
        }.distinctUntilChanged().collect { (last, total) ->
            if (total > 0 && last != null && last >= total - 1) onLoadMore()
        }
    }

    // 滚动到顶 -> 加载上一页（宿主判断 oldPage > 1 且互斥）
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index }
            .distinctUntilChanged().collect { first ->
                if (first == 0) onLoadTop()
            }
    }

    Column(modifier = modifier.fillMaxSize()) {
        GlassTopAppBar(
            title = title,
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
                                icon = Icons.Default.Refresh,
                                title = stringResource(R.string.menu_page, currentPage),
                                onClick = { showPageDialog = true }
                            )
                        )
                    )
                }
            }
        )

        Box(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            when {
                items.isEmpty() && bottomLoading -> ShelfListSkeleton()
                items.isEmpty() -> EmptyStatePlaceholder(
                    icon = Icons.Default.Search,
                    title = emptyMessage,
                    modifier = Modifier.fillMaxSize()
                )
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    if (canLoadTop) {
                        item(key = "top_loader") {
                            LoadBar(loading = topLoading, onClick = onLoadTop)
                        }
                    }
                    items(items = items, key = { it.book.bookUrl }) { item ->
                        ExploreShowItemRow(item = item) {
                            onItemClick(items.indexOf(item))
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 0.5.dp
                        )
                    }
                    item(key = "bottom_loader") {
                        BottomLoadBar(
                            loading = bottomLoading,
                            hasMore = hasMore,
                            error = loadMoreError,
                            onRetry = onRetryLoadMore
                        )
                    }
                }
            }
        }
    }

    if (showPageDialog) {
        AppNumberPickerDialog(
            title = stringResource(R.string.change_page),
            value = currentPage,
            range = 1..999,
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            onConfirm = {
                showPageDialog = false
                if (it != currentPage) onPageChange(it)
            },
            onDismiss = { showPageDialog = false }
        )
    }

    // 加载失败自动弹错误详情（AppTextDialog），关闭后同条错误不再弹出
    val currentError = loadMoreError?.takeIf { it.isNotBlank() && items.isNotEmpty() }
    if (currentError != null && currentError != dismissedError) {
        AppTextDialog(
            title = stringResource(R.string.error),
            text = currentError,
            confirmText = stringResource(R.string.ok),
            onDismiss = { dismissedError = currentError }
        )
    }
}

@Composable
private fun ExploreShowItemRow(item: ExploreShowDisplayItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box {
            AndroidView(
                factory = { ctx ->
                    CoverImageView(ctx).apply {
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    }
                },
                update = { iv -> iv.load(item.book, AppConfig.loadCoverOnlyWifi) },
                modifier = Modifier.size(width = 80.dp, height = 110.dp)
            )
            if (item.isInBookshelf) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(8.dp)
                        // 书架内状态指示点：成功/已加入 → primary（M3 收敛，原 0xFF4CAF50）
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.author_show, item.author),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.kinds.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.kinds.take(3).forEach { TagChip(text = it) }
                }
            }
            if (item.latestChapterTitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.lasted_show, item.latestChapterTitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (item.intro.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.intro,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 顶部上翻加载条（canLoadTop 时占位显示，可点击强制上翻） */
@Composable
private fun LoadBar(loading: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clickable(enabled = !loading) { onClick() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}

/** 底部加载条：加载中转圈 / 失败重试 / 无更多 */
@Composable
private fun BottomLoadBar(
    loading: Boolean,
    hasMore: Boolean,
    error: String?,
    onRetry: () -> Unit
) {
    val text = when {
        error != null -> stringResource(R.string.error_load_msg, stringResource(R.string.retry))
        !hasMore -> stringResource(R.string.bottom_line)
        else -> stringResource(R.string.loading)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(enabled = !loading && error != null) { onRetry() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
        }
        if (!loading || !hasMore || error != null) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
