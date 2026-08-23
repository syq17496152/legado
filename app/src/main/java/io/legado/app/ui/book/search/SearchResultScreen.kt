package io.legado.app.ui.book.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.bookshelf.compose.BookshelfListItemStyle
import io.legado.app.ui.main.bookshelf.compose.rememberBookshelfListRenderConfig
import io.legado.app.ui.widget.compose.ComposeLazyListFastScroller
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.SearchBookListItem
import io.legado.app.ui.widget.compose.SearchBookPreviewOverlay
import io.legado.app.ui.widget.compose.SearchBookPreviewState
import io.legado.app.utils.stableSearchBookKey

/**
 * 搜索结果列表的 Compose 实现，复用与发现页一致的 [BookListCardSurface] 卡片骨架与
 * [rememberBookshelfListRenderConfig] 主题色板，使搜索结果与发现/书架视觉统一、随主题走。
 */
@Composable
fun SearchResultScreen(
    books: List<SearchBook>,
    isLoading: Boolean,
    hasMore: Boolean,
    scrollToTopSignal: Int,
    bookshelfTick: Int,
    isInBookshelf: (SearchBook) -> Boolean,
    lifecycle: Lifecycle,
    onBookClick: (SearchBook) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    LegadoComposeTheme {
        val renderConfig = rememberBookshelfListRenderConfig()
        val rounded = AppConfig.bookshelfListItemStyle == BookshelfListItemStyle.RoundedCard
        val listState = rememberLazyListState()
        var previewState by remember { mutableStateOf<SearchBookPreviewState?>(null) }

        val shouldLoadMore by remember(books, hasMore, isLoading) {
            derivedStateOf {
                if (!hasMore || isLoading || books.isEmpty()) return@derivedStateOf false
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                last >= books.lastIndex - 2
            }
        }
        LaunchedEffect(shouldLoadMore) {
            if (shouldLoadMore) onLoadMore()
        }
        LaunchedEffect(scrollToTopSignal) {
            if (scrollToTopSignal > 0) listState.scrollToItem(0)
        }

        Box(modifier = modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 86.dp),
                verticalArrangement = Arrangement.spacedBy(if (rounded) 4.dp else 2.dp)
            ) {
                itemsIndexed(
                    items = books,
                    key = { _, book -> book.stableSearchBookKey() }
                ) { _, book ->
                    val inBookshelf = remember(book.bookUrl, bookshelfTick) { isInBookshelf(book) }
                    SearchBookListItem(
                        book = book,
                        inBookshelf = inBookshelf,
                        rounded = rounded,
                        renderConfig = renderConfig,
                        lifecycle = lifecycle,
                        showOriginCount = true,
                        onClick = { onBookClick(book) },
                        onPreview = { bounds ->
                            previewState = SearchBookPreviewState(book, bounds)
                        }
                    )
                }
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
}