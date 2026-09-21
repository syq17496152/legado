package io.legado.app.ui.book.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.bookshelf.compose.BookshelfListItemStyle
import io.legado.app.ui.main.bookshelf.compose.BookshelfListPalette
import io.legado.app.ui.main.bookshelf.compose.rememberBookshelfListRenderConfig
import io.legado.app.ui.widget.components.AppShapes
import io.legado.app.ui.widget.compose.ComposeLazyListFastScroller
import io.legado.app.ui.widget.compose.SearchBookListItem
import io.legado.app.ui.widget.compose.SearchBookPreviewOverlay
import io.legado.app.ui.widget.compose.SearchBookPreviewState
import io.legado.app.utils.stableSearchBookKey
import androidx.compose.material3.MaterialTheme
import io.legado.app.ui.theme.bodyTertiary

@Composable
fun ExploreShowComposeScreen(
    books: List<SearchBook>,
    isLoading: Boolean,
    isLoadingPrevious: Boolean,
    hasMore: Boolean,
    hasPrevious: Boolean,
    errorMessage: String?,
    previousErrorMessage: String?,
    scrollToTopSignal: Int,
    keepPositionAfterPrependSignal: Int,
    prependedItemCount: Int,
    bookshelfTick: Int,
    isInBookshelf: (SearchBook) -> Boolean,
    lifecycle: Lifecycle,
    onBookClick: (SearchBook) -> Unit,
    onBookMore: (SearchBook) -> Unit,
    /** F48：首次进入时的「长按书籍可预览」一次性提示是否展示 */
    showPreviewHint: Boolean,
    onPreviewHintDismiss: () -> Unit,
    /**
     * F46：外部（列表项 ⋮ 菜单选「预览」）请求打开预览的一次性信号。
     * 消费后立即经 [onPreviewRequestHandled] 复位——预览态本身仍由本屏持有，
     * 菜单侧无需了解 bounds/previewState 的内部结构。
     */
    previewRequest: SearchBook?,
    onPreviewRequestHandled: () -> Unit,
    onLoadMore: () -> Unit,
    onLoadPrevious: () -> Unit,
    modifier: Modifier = Modifier
) {
    val renderConfig = rememberBookshelfListRenderConfig()
    val palette = renderConfig.palette
    val rounded = AppConfig.bookshelfListItemStyle == BookshelfListItemStyle.RoundedCard
    val listState = rememberLazyListState()
    var previewState by remember { mutableStateOf<SearchBookPreviewState?>(null) }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val shouldLoadMore by remember(books, hasMore, isLoading, isLoadingPrevious) {
        derivedStateOf {
            if (!hasMore || isLoading || isLoadingPrevious || books.isEmpty()) {
                return@derivedStateOf false
            }
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= books.lastIndex - 2
        }
    }
    val shouldLoadPrevious by remember(books, hasPrevious, isLoading, isLoadingPrevious) {
        derivedStateOf {
            hasPrevious &&
                !isLoading &&
                !isLoadingPrevious &&
                books.isNotEmpty() &&
                listState.isScrollInProgress &&
                listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 0
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }
    LaunchedEffect(shouldLoadPrevious) {
        if (shouldLoadPrevious) onLoadPrevious()
    }
    LaunchedEffect(scrollToTopSignal) {
        if (scrollToTopSignal > 0) listState.scrollToItem(0)
    }
    LaunchedEffect(keepPositionAfterPrependSignal) {
        if (keepPositionAfterPrependSignal > 0 && prependedItemCount > 0) {
            listState.scrollToItem(prependedItemCount)
        }
    }
    // F46：⋮ 菜单选「预览」⇒ 打开预览浮层（无 bounds ⇒ 浮层按默认位置呈现，与直接长按封面同组件）
    LaunchedEffect(previewRequest) {
        previewRequest?.let {
            previewState = SearchBookPreviewState(it, null)
            onPreviewRequestHandled()
        }
    }
    // F48：长按成功即算「已发现该能力」⇒ 关掉提示（提示的存在前提是用户还不知道能长按）
    LaunchedEffect(previewState) {
        if (previewState != null && showPreviewHint) onPreviewHintDismiss()
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 8.dp,
                top = 8.dp,
                end = 8.dp,
                bottom = bottomInset + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(if (rounded) 4.dp else 2.dp)
        ) {
            // F48：一次性提示。**项常驻 + 内容门控**（F293：item 注册不能带条件，首帧为假会永久缺席）
            item(key = "explore_show_preview_hint", contentType = "status") {
                if (showPreviewHint) {
                    ExploreShowPreviewHintRow(palette = palette, onDismiss = onPreviewHintDismiss)
                }
            }
            if (isLoadingPrevious || previousErrorMessage != null) {
                item(key = "explore_show_previous_status", contentType = "status") {
                    ExploreShowStatusRow(
                        loading = isLoadingPrevious,
                        message = previousErrorMessage?.let { stringResource(R.string.load_error_retry) },
                        palette = palette,
                        onClick = onLoadPrevious
                    )
                }
            }
            itemsIndexed(
                items = books,
                key = { _, book -> book.stableSearchBookKey() },
                contentType = { _, _ -> "explore_show_book" }
            ) { _, book ->
                val inBookshelf = remember(book.bookUrl, bookshelfTick) { isInBookshelf(book) }
                SearchBookListItem(
                    book = book,
                    inBookshelf = inBookshelf,
                    rounded = rounded,
                    renderConfig = renderConfig,
                    lifecycle = lifecycle,
                    onClick = { onBookClick(book) },
                    onMore = { onBookMore(book) },
                    onPreview = { bounds ->
                        previewState = SearchBookPreviewState(book, bounds)
                    }
                )
            }
            if (isLoading || errorMessage != null) {
                item(key = "explore_show_footer_status", contentType = "status") {
                    ExploreShowStatusRow(
                        loading = isLoading,
                        message = errorMessage?.let { stringResource(R.string.load_error_retry) },
                        palette = palette,
                        onClick = onLoadMore
                    )
                }
            }
        }
        if (books.isEmpty() && !isLoading && !isLoadingPrevious && errorMessage == null) {
            Text(
                text = stringResource(R.string.explore_empty),
                color = palette.secondaryText,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                fontFamily = palette.bodyFontFamily,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            )
        }
        ComposeLazyListFastScroller(
            state = listState,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
        SearchBookPreviewOverlay(
            state = previewState,
            renderConfig = renderConfig,
            fragment = null,
            lifecycle = lifecycle,
            onDismissed = { previewState = null },
            onOpen = { book ->
                previewState = null
                onBookClick(book)
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * F48：「长按书籍可预览」一次性提示行。
 *
 * 预览是「先看后决定」的隐性能力（长按手势无视觉入口），可发现性低会整块流失；
 * 只提示一次（关闭或首次成功长按后置位），不做常驻入口（克制：不干扰主点击「打开详情」路径）。
 */
@Composable
private fun ExploreShowPreviewHintRow(
    palette: BookshelfListPalette,
    onDismiss: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(AppShapes.Button)
            .background(palette.accent.copy(alpha = 0.12f))
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.TouchApp,
            contentDescription = null,
            tint = palette.accent,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.explore_show_preview_hint),
            color = palette.secondaryText,
            fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
            fontFamily = palette.bodyFontFamily,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.cancel),
                tint = palette.secondaryText,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ExploreShowStatusRow(
    loading: Boolean,
    message: String?,
    palette: BookshelfListPalette,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (loading || message == null) Modifier else Modifier.clickable(onClick = onClick))
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = palette.accent
            )
        } else if (message != null) {
            Text(
                text = message,
                color = palette.secondaryText,
                fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                fontFamily = palette.bodyFontFamily
            )
        }
    }
}
