package io.legado.app.ui.rss.article.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.RssArticle
import io.legado.app.ui.widget.compose.AppPageSpacing
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull

/**
 * D4 收敛后的唯一文章列表组件（design-b3-d4-flagship §2.1；骨架分型=内容列表，
 * 复用 D4 组件契约，不套 AppManagementScaffold 族）。
 * 契约：组件不持有业务状态；articles 由宿主收集 Room Flow 后传入 [RssArticleListState.articles]。
 * 全部包 LegadoTheme{} 由宿主负责；颜色全部取自 MaterialTheme.colorScheme（ThemeSync §4-8）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RssArticleListScreen(
    state: RssArticleListState,
    style: RssArticleListStyle,
    isRefreshing: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onArticleClick: (RssArticle) -> Unit,
    // 长按为可选扩展（D4 本页无长按语义不传；D5 换源菜单/D7 收藏删除等 B4 复用时注入）
    onItemLongClick: ((RssArticle) -> Unit)? = null,
    bottomInset: ListBottomInset,
    modifier: Modifier = Modifier,
    // slot：空态可替换（D7 收藏页复用时注入差异化文案/入口）；null=默认空态
    emptyContent: (@Composable BoxScope.() -> Unit)? = null,
    // §2.5 错误态：非空时页脚渲染"加载失败，点击重试"（原 LoadMoreView.error 语义；顶栏 toast 仍由 VM 承担）
    error: String? = null,
) {
    val density = LocalDensity.current
    val topOverlayDp = with(density) { state.topOverlaySpacePx.toDp() }
    val navBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPadding: Dp = when (bottomInset) {
        // 对齐 ViewExtensions.applyMainBottomBarPadding 等效值（navigationBarHeight + 90dp 主底部栏桥接）
        ListBottomInset.MAIN_BOTTOM_BAR -> navBottomPadding + MainBottomBarBridgePadding
        // 无底栏宿主：导航栏 inset + 滚动列表尾部留白（AppPageSpacing.ListBottom 语义）
        ListBottomInset.NAVIGATION_BARS -> navBottomPadding + AppPageSpacing.ListBottom
    }
    val contentPadding = PaddingValues(top = topOverlayDp, bottom = bottomPadding)
    val pullToRefreshState = rememberPullToRefreshState()

    // 滚动恢复/回顶 effect 接线点（§2.2 ScrollRestoreEffect）
    ScrollRestoreEffect(state)
    // 加载更多触发（§2.5）：canScrollForward 边界 snapshotFlow 监听；瀑布流保留 -5 预加载阈值
    LoadMoreEffect(state, style, hasMore, onLoadMore)

    // §2.3 注 ①：碰撞守卫在 items 装配前一次性预计算，key lambda 内零副作用（W-3 落死）
    val listKeyGuard = remember(state.articles) { buildListKeyGuard(state.articles) }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = pullToRefreshState,
        modifier = modifier.fillMaxSize(),
        // §4-5：刷新圈 offset 随顶栏覆盖占位下移（对齐原 setProgressViewOffset）
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullToRefreshState,
                isRefreshing = isRefreshing,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topOverlayDp),
            )
        },
    ) {
        when (style) {
            RssArticleListStyle.LIST -> LazyColumn(
                state = state.lazyListState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                articlesItems(
                    state = state,
                    listKeyGuard = listKeyGuard,
                    style = style,
                    onArticleClick = onArticleClick,
                    onItemLongClick = onItemLongClick,
                    onLoadMore = onLoadMore,
                    hasMore = hasMore,
                    error = error,
                )
            }

            RssArticleListStyle.MASONRY -> LazyVerticalStaggeredGrid(
                state = state.staggeredGridState,
                columns = StaggeredGridCells.Adaptive(minSize = MasonryColumnMinSize),
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                articlesStaggeredItems(
                    state = state,
                    listKeyGuard = listKeyGuard,
                    style = style,
                    onArticleClick = onArticleClick,
                    onItemLongClick = onItemLongClick,
                    onLoadMore = onLoadMore,
                    hasMore = hasMore,
                    error = error,
                )
            }

            RssArticleListStyle.GRID_2, RssArticleListStyle.GRID_3 -> LazyVerticalGrid(
                state = state.lazyGridState,
                columns = GridCells.Fixed(if (style == RssArticleListStyle.GRID_2) 2 else 3),
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                articlesGridItems(
                    state = state,
                    listKeyGuard = listKeyGuard,
                    style = style,
                    onArticleClick = onArticleClick,
                    onItemLongClick = onItemLongClick,
                    onLoadMore = onLoadMore,
                    hasMore = hasMore,
                    error = error,
                )
            }
        }
        if (state.articles.isEmpty()) {
            if (isRefreshing) {
                // 骨架屏：预加载场景由宿主以 isRefreshing=false 静默加载（对齐原 isRefreshing = !embeddedInModernRss）
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxSize()
                        .padding(top = topOverlayDp),
                ) {
                    ArticleListSkeleton(style = style)
                }
            } else {
                if (emptyContent != null) emptyContent()
                else DefaultEmptyContent()
            }
        }
    }
}

/**
 * 主底部栏桥接避让常量：对齐 ViewExtensions.applyMainBottomBarPadding 的
 * navigationBarHeight + 90dp 等效值（View 侧无 token，登记为 .dp 命中证据）。
 */
private val MainBottomBarBridgePadding = 90.dp

/** 瀑布流自适应最小列宽（design §2.1，等效原竖 2/横 3；视觉规格无 token，登记 .dp 命中证据） */
private val MasonryColumnMinSize = 150.dp

/**
 * 一次性定位 + 回顶（design-b3-d4-flagship §2.2）：
 * 常驻 effect（key=Unit，组合期只安装一次）：经 snapshotFlow 消费 pending（consumePendingLink
 * 取值即置空，一次性语义），无组合体 back-write 与 `?: return`；key 不含 articles.size
 * （列表异步回填后仍可定位）。
 */
@Composable
private fun ScrollRestoreEffect(state: RssArticleListState) {
    LaunchedEffect(Unit) {
        snapshotFlow { state.consumePendingLink() }
            .filterNotNull()
            .collect { link ->
                val index = state.articles.indexOfFirst { it.link == link }
                if (index >= 0) state.lazyListState.scrollToItem(index)
            }
    }
    val topReq by state.scrollToTopRequest
    LaunchedEffect(topReq) {
        if (topReq > 0) state.lazyListState.scrollToItem(0)
    }
}

/** 加载更多（§2.5）：三容器统一 canScrollForward 边界；瀑布流补 首位 >= 总数-5 预加载阈值 */
@Composable
private fun LoadMoreEffect(
    state: RssArticleListState,
    style: RssArticleListStyle,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(style, hasMore) {
        val boundaryFlow = when (style) {
            RssArticleListStyle.LIST -> snapshotFlow { state.lazyListState.canScrollForward }
            RssArticleListStyle.GRID_2, RssArticleListStyle.GRID_3 ->
                snapshotFlow { state.lazyGridState.canScrollForward }
            RssArticleListStyle.MASONRY -> snapshotFlow {
                val info = state.staggeredGridState.layoutInfo
                val firstVisible = info.visibleItemsInfo.firstOrNull()?.index ?: 0
                !state.staggeredGridState.canScrollForward ||
                    (info.totalItemsCount > 0 && firstVisible >= info.totalItemsCount - 5)
            }
        }
        boundaryFlow
            .distinctUntilChanged()
            .filter { atBoundary -> atBoundary && hasMore }
            .collect { onLoadMore() }
    }
}

private fun LazyListScope.articlesItems(
    state: RssArticleListState,
    listKeyGuard: Map<String, Int>,
    style: RssArticleListStyle,
    onArticleClick: (RssArticle) -> Unit,
    onItemLongClick: ((RssArticle) -> Unit)?,
    onLoadMore: () -> Unit,
    hasMore: Boolean,
    error: String?,
) {
    items(
        items = state.articles,
        key = { article -> article.stableListKey(listKeyGuard) },
        contentType = { "rss_article_${style.name}" }, // 同形态复用
    ) { article ->
        ArticleItem(
            article = article,
            style = style,
            onClick = { onArticleClick(article) },
            onLongClick = onItemLongClick?.let { handler -> { handler(article) } },
        )
    }
    item(key = "footer", contentType = "footer") {
        ListFooter(hasMore = hasMore, onRetry = onLoadMore, error = error)
    }
}

private fun LazyGridScope.articlesGridItems(
    state: RssArticleListState,
    listKeyGuard: Map<String, Int>,
    style: RssArticleListStyle,
    onArticleClick: (RssArticle) -> Unit,
    onItemLongClick: ((RssArticle) -> Unit)?,
    onLoadMore: () -> Unit,
    hasMore: Boolean,
    error: String?,
) {
    gridItems(
        items = state.articles,
        key = { article -> article.stableListKey(listKeyGuard) },
        contentType = { "rss_article_${style.name}" },
    ) { article ->
        ArticleItem(
            article = article,
            style = style,
            onClick = { onArticleClick(article) },
            onLongClick = onItemLongClick?.let { handler -> { handler(article) } },
        )
    }
    item(key = "footer", contentType = "footer") {
        ListFooter(hasMore = hasMore, onRetry = onLoadMore, error = error)
    }
}

private fun LazyStaggeredGridScope.articlesStaggeredItems(
    state: RssArticleListState,
    listKeyGuard: Map<String, Int>,
    style: RssArticleListStyle,
    onArticleClick: (RssArticle) -> Unit,
    onItemLongClick: ((RssArticle) -> Unit)?,
    onLoadMore: () -> Unit,
    hasMore: Boolean,
    error: String?,
) {
    staggeredItems(
        items = state.articles,
        key = { article -> article.stableListKey(listKeyGuard) },
        contentType = { "rss_article_${style.name}" },
    ) { article ->
        ArticleItem(
            article = article,
            style = style,
            onClick = { onArticleClick(article) },
            onLongClick = onItemLongClick?.let { handler -> { handler(article) } },
        )
    }
    item(key = "footer", contentType = "footer") {
        ListFooter(hasMore = hasMore, onRetry = onLoadMore, error = error)
    }
}
