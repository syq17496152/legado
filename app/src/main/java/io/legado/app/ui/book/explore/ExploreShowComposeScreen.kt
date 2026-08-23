package io.legado.app.ui.book.explore

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
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
import io.legado.app.ui.widget.compose.ComposeLazyListFastScroller
import io.legado.app.ui.widget.compose.SearchBookListItem
import io.legado.app.ui.widget.compose.SearchBookPreviewOverlay
import io.legado.app.ui.widget.compose.SearchBookPreviewState
import io.legado.app.utils.stableSearchBookKey

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
                fontSize = 14.sp,
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
                fontSize = 13.sp,
                fontFamily = palette.bodyFontFamily
            )
        }
    }
}
