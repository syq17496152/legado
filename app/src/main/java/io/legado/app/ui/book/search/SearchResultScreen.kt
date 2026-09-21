package io.legado.app.ui.book.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.bookshelf.compose.BookshelfListItemStyle
import io.legado.app.ui.main.bookshelf.compose.rememberBookshelfListRenderConfig
import io.legado.app.ui.widget.components.AppFilterChip
import io.legado.app.ui.widget.compose.ComposeLazyListFastScroller
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.SearchBookListItem
import io.legado.app.ui.widget.compose.SearchBookPreviewOverlay
import io.legado.app.ui.widget.compose.SearchBookPreviewState
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.utils.stableSearchBookKey

/**
 * 搜索结果列表的 Compose 实现，复用与发现页一致的 [BookListCardSurface] 卡片骨架与
 * [rememberBookshelfListRenderConfig] 主题色板，使搜索结果与发现/书架视觉统一、随主题走。
 *
 * F73/F74/F75：列表上方新增一条**常驻状态条**（不随列表滚动）——
 * ①实时命中计数（搜索中给「已命中 N 本」，结束后翻转为「共命中 N 本」），让增量到达的
 *   并发聚合过程可见（原实现只有不确定进度条，用户看到几条就以为搜完了）；
 * ②精准搜索 chip（影响结果集的第一开关，原藏在 ⋮ 菜单里，状态下主路径零暴露）；
 * ③紧凑密度切换（扫书名的批量浏览场景，隐藏简介行换一屏更多候选）。
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
    modifier: Modifier = Modifier,
    /** F73：是否展示状态条（搜过即展示，含 0 命中——空结果同样是「搜过了」这一事实） */
    showSummary: Boolean = false,
    precisionSearch: Boolean = false,
    compactMode: Boolean = false,
    onTogglePrecisionSearch: () -> Unit = {},
    onToggleCompactMode: () -> Unit = {}
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

        Column(modifier = modifier.fillMaxSize()) {
            if (showSummary) {
                SearchResultSummaryBar(
                    bookCount = books.size,
                    aggregatedCount = books.count { it.origins.size > 1 },
                    isLoading = isLoading,
                    precisionSearch = precisionSearch,
                    compactMode = compactMode,
                    onTogglePrecisionSearch = onTogglePrecisionSearch,
                    onToggleCompactMode = onToggleCompactMode
                )
            }
            Box(modifier = Modifier.weight(1f)) {
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
                            compact = compactMode,
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
}

/**
 * F73/F74/F75：搜索结果状态条。
 *
 * 计数口径全部来自现成的 `books`（`size` 与 `origins.size > 1` 的条数），零新数据源；
 * 搜索中/结束只切换文案前缀，避免条自身闪入闪出。
 */
@Composable
private fun SearchResultSummaryBar(
    bookCount: Int,
    aggregatedCount: Int,
    isLoading: Boolean,
    precisionSearch: Boolean,
    compactMode: Boolean,
    onTogglePrecisionSearch: () -> Unit,
    onToggleCompactMode: () -> Unit
) {
    val palette = rememberAppSettingPalette()
    val countText = if (isLoading) {
        stringResource(R.string.search_result_counting, bookCount)
    } else {
        stringResource(R.string.search_result_count_done, bookCount)
    }
    val summaryText = if (aggregatedCount > 0) {
        "$countText · ${stringResource(R.string.search_result_aggregated, aggregatedCount)}"
    } else {
        countText
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = summaryText,
            color = palette.secondaryText,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        AppFilterChip(
            text = stringResource(R.string.precision_search),
            selected = precisionSearch,
            onClick = onTogglePrecisionSearch
        )
        AppFilterChip(
            text = stringResource(R.string.search_result_compact),
            selected = compactMode,
            onClick = onToggleCompactMode
        )
    }
}