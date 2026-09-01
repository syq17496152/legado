package io.legado.app.ui.rss.article.compose

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.entities.RssArticle
import io.legado.app.ui.rss.article.RssArticlesViewModel

/**
 * §3.4 兼容层共享桥（modern Fragment 壳 / classic HorizontalPager 页共用，design-b3-d4-flagship §3.4）：
 * 以 rememberSaveable(saver) 创建三容器滚动态（§4 边界 9 进程恢复）→ 组装 RssArticleListState
 * → 收集 VM StateFlow/Room Flow 回填 → 渲染 [RssArticleListScreen]。
 * 组件不含模式分支：classic/modern 差异（bottomInset/顶栏占位/刷新圈语义/style 来源）由宿主传参。
 */
@Composable
internal fun RssArticleListBridge(
    viewModel: RssArticlesViewModel,
    style: RssArticleListStyle,
    isRefreshing: Boolean,
    bottomInset: ListBottomInset,
    modifier: Modifier = Modifier,
    topOverlaySpacePx: Int = 0,
    onLoadMore: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onArticleClick: (RssArticle) -> Unit = {},
    onHostStateReady: (RssArticleListState) -> Unit = {},
    onHostStateDisposed: () -> Unit = {},
) {
    val lazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val lazyGridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    val staggeredGridState =
        rememberSaveable(saver = LazyStaggeredGridState.Saver) { LazyStaggeredGridState() }
    val state = remember(lazyListState, lazyGridState, staggeredGridState) {
        RssArticleListState(
            lazyListState = lazyListState,
            lazyGridState = lazyGridState,
            staggeredGridState = staggeredGridState,
            initialPendingLink = null,
            scrollToTopRequest = mutableStateOf(0),
        )
    }
    SideEffect {
        onHostStateReady(state)
        state.topOverlaySpacePx = topOverlaySpacePx
    }
    DisposableEffect(Unit) {
        onDispose { onHostStateDisposed() }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val articles by remember(viewModel.articlesFlow) {
        viewModel.articlesFlow
    }.collectAsStateWithLifecycle(initialValue = state.articles)
    LaunchedEffect(articles) {
        state.articles = articles
    }

    RssArticleListScreen(
        state = state,
        style = style,
        isRefreshing = isRefreshing,
        hasMore = uiState.hasMore,
        onLoadMore = onLoadMore,
        onRefresh = onRefresh,
        onArticleClick = onArticleClick,
        bottomInset = bottomInset,
        modifier = modifier,
        error = uiState.error,
    )
}
