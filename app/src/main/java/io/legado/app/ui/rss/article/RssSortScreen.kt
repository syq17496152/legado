package io.legado.app.ui.rss.article

import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pageview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.ui.rss.article.compose.ListBottomInset
import io.legado.app.ui.rss.article.compose.RssArticleListBridge
import io.legado.app.ui.rss.article.compose.RssArticleListState
import io.legado.app.ui.rss.article.compose.RssArticleListStyle
import io.legado.app.ui.rss.article.compose.toRssArticleListStyle
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.AppEditDialog
import io.legado.app.ui.widget.components.AppShapes
import io.legado.app.ui.widget.components.EditField
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * classic 分类宿主全屏 Compose 壳（design-b3-d4-flagship §5）：
 * GlassTopAppBar（复用，菜单项原样平移）+ SortTabBar（§5.3）+ HorizontalPager（每页按 key
 * 隔离 RssArticlesViewModel，§5.4 注意①）。组件收不可变 state + 显式回调；
 * VM/弹框/Intent 处理留在 RssSortActivity。
 */
@Composable
internal fun RssSortScreen(
    sortViewModel: RssSortViewModel,
    sorts: List<Pair<String, String>>,
    pageMenuTitle: String?,
    pageScrollTopRequest: Pair<Int, Int>,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onPagePicker: () -> Unit,
    onLogin: () -> Unit,
    onRefreshSorts: () -> Unit,
    onSetVariable: () -> Unit,
    onEditSource: () -> Unit,
    onReadRecord: () -> Unit,
    onClearArticles: () -> Unit,
    onCurrentPageChanged: (Int) -> Unit,
    onPageChanged: (page: Int, visible: Boolean) -> Unit,
    onOpenArticle: (RssArticlesViewModel, RssArticle, List<RssArticle>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = sortViewModel.rssSource
    val articleStyle by sortViewModel.articleStyleFlow.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(initialPage = 0) { sorts.size }
    val pagerScope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }
    var searchDialogVisible by remember { mutableStateOf(false) }

    // 标题派生（原 upFragmentsView 语义：单 Tab=搜索键或分类名，多 Tab=源名）
    // 简化说明: 单 Tab 且分类名为空的边缘场景原实现保持旧标题，此处显示空串
    val screenTitle = when {
        sorts.size == 1 && sorts.first().first.isNotEmpty() ->
            sortViewModel.searchKey ?: sorts.first().first
        sorts.size > 1 -> sortViewModel.sourceName ?: ""
        else -> ""
    }

    // 当前页索引上报（Activity 侧登录刷新/翻页菜单定位当前页 VM 用）
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { onCurrentPageChanged(it) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        GlassTopAppBar(
            title = screenTitle,
            navIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onNavClick = onBack,
            actions = {
                if (source?.searchUrl.isNullOrBlank().not()) {
                    IconButton(onClick = { searchDialogVisible = true }) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = stringResource(R.string.action_search),
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(imageVector = Icons.Filled.MoreVert, contentDescription = null)
                    }
                    AppDropdownMenu(
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                        actions = buildSortMenuActions(
                            source = source,
                            pageMenuTitle = pageMenuTitle,
                            onSwitchLayout = { sortViewModel.switchLayout() },
                            onPagePicker = onPagePicker,
                            onLogin = onLogin,
                            onRefreshSorts = onRefreshSorts,
                            onSetVariable = onSetVariable,
                            onEditSource = onEditSource,
                            onReadRecord = onReadRecord,
                            onClearArticles = onClearArticles,
                        ),
                    )
                }
            },
        )
        // searchKey 单 Tab「搜索」页同样走 pager；tabs.size==1 隐藏 SortTabBar（§5.4 注意②）
        if (sorts.size > 1) {
            SortTabBar(
                tabs = sorts.map { it.first },
                selectedIndex = pagerState.currentPage,
                onSelect = { index ->
                    // 对齐原 viewPager.currentItem = position（无平滑滚动）
                    pagerScope.launch { pagerState.scrollToPage(index) }
                },
            )
        }
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            SortPagerPage(
                index = index,
                sort = sorts[index],
                sortViewModel = sortViewModel,
                style = articleStyle.toRssArticleListStyle(),
                pageScrollTopRequest = pageScrollTopRequest,
                pagerState = pagerState,
                onOpenArticle = onOpenArticle,
                onPageChanged = onPageChanged,
            )
        }
    }

    if (searchDialogVisible) {
        AppEditDialog(
            title = stringResource(R.string.action_search),
            fields = listOf(
                EditField(
                    label = stringResource(R.string.action_search),
                    singleLine = true,
                )
            ),
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            onConfirm = { values ->
                searchDialogVisible = false
                val query = values.firstOrNull().orEmpty()
                if (query.isNotBlank()) {
                    onSearch(query)
                }
            },
            onDismiss = { searchDialogVisible = false },
        )
    }
}

/**
 * classic 单分类页（§5.1：每页 = RssArticleListBridge + 页内独立 RssArticlesViewModel(key)）。
 * 文章点击上行（§3.3）：classic 页无 Fragment，走 ReadRss activity 重载（Activity 内实现）。
 */
@Composable
private fun SortPagerPage(
    index: Int,
    sort: Pair<String, String>,
    sortViewModel: RssSortViewModel,
    style: RssArticleListStyle,
    pageScrollTopRequest: Pair<Int, Int>,
    pagerState: PagerState,
    onOpenArticle: (RssArticlesViewModel, RssArticle, List<RssArticle>) -> Unit,
    onPageChanged: (page: Int, visible: Boolean) -> Unit,
) {
    // §5.4 注意①：每页按 key 隔离的 RssArticlesViewModel（Activity 作用域，Tab 变动靠 key 天然隔离）
    val pageVm: RssArticlesViewModel = viewModel(key = "rss_articles_${sort.first}")
    var listState by remember { mutableStateOf<RssArticleListState?>(null) }
    val uiState by pageVm.uiState.collectAsStateWithLifecycle()
    val isCurrent by remember { derivedStateOf { pagerState.currentPage == index } }

    // 配置 + 初始加载（§5.4 注意③：非 preload 源仅当前页加载，一次性触发；
    // 禁止 repeatOnLifecycle——会每次回前台重复网络请求，与原实现不等价）
    LaunchedEffect(sort) {
        pageVm.configure(sort.first, sort.second, sortViewModel.searchKey)
        pageVm.bindOrigin(sortViewModel.url)
        val source = sortViewModel.rssSource ?: return@LaunchedEffect
        if (source.preload) {
            pageVm.loadArticles(source)
        } else {
            snapshotFlow { pagerState.currentPage }
                .filter { it == index }
                .first()
            pageVm.loadArticles(source)
        }
    }

    // 页码上报（§3.3）：仅当前页上报，防 beyondViewport 邻页覆盖（原 offscreen fragment 观察不活跃等价）
    LaunchedEffect(uiState.page, isCurrent) {
        if (isCurrent) {
            val source = sortViewModel.rssSource
            onPageChanged(uiState.page, source != null && !source.ruleNextPage.isNullOrEmpty())
        }
    }

    // 翻页选择后回顶（§3.3：loadArticles(targetPage)+scrollToItem(0)）
    LaunchedEffect(pageScrollTopRequest) {
        if (pageScrollTopRequest.first == index) {
            listState?.requestScrollToTop()
        }
    }

    RssArticleListBridge(
        viewModel = pageVm,
        style = style,
        isRefreshing = uiState.isRefreshing,
        bottomInset = ListBottomInset.NAVIGATION_BARS,
        onHostStateReady = { listState = it },
        onHostStateDisposed = { listState = null },
        onLoadMore = {
            if (!pageVm.isLoading) {
                sortViewModel.rssSource?.let { pageVm.loadMore(it) }
            }
        },
        onRefresh = {
            sortViewModel.rssSource?.let { pageVm.loadArticles(it, 1) }
        },
        onArticleClick = { article ->
            // 文章列表快照取自页面 state holder（原 adapter.getItems 等价，§3.3）
            onOpenArticle(pageVm, article, listState?.articles.orEmpty())
        },
    )
}

/**
 * SortTabBar（design-b3-d4-flagship §5.3 骨架）：多行分类选择器，替代手搓
 * LinearLayout+HorizontalScrollView 三行栈。行数策略对齐原实现：<=10 单行、<=20 两行、
 * >20 三行（横屏减 1）；选中项滚动可见（对齐原 ensureTabVisible）。
 * 间距/描边取 §5.3 冻结视觉规格（无 token，登记 .dp 命中证据）。
 */
@Composable
private fun SortTabBar(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val rows = remember(tabs.size, isLandscape) { computeRowCount(tabs.size, isLandscape) }
    val rowCapacity = (tabs.size + rows - 1) / rows   // W11：先算每行容量局部量，chunked 与 globalIndex 共用
    val rowStates = remember(tabs.size, isLandscape) { List(rows) { LazyListState() } }
    Column(modifier = modifier.fillMaxWidth()) {
        tabs.chunked(rowCapacity).forEachIndexed { rowIndex, rowTabs ->
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                state = rowStates[rowIndex],
            ) {
                itemsIndexed(rowTabs) { indexInRow, tabTitle ->
                    val globalIndex = rowIndex * rowCapacity + indexInRow
                    TabPill(
                        title = tabTitle,
                        selected = globalIndex == selectedIndex,
                        onClick = { onSelect(globalIndex) },
                    )
                }
            }
        }
    }
    LaunchedEffect(selectedIndex, tabs) {
        if (selectedIndex in tabs.indices) {
            val rowIndex = selectedIndex / rowCapacity
            rowStates.getOrNull(rowIndex)?.animateScrollToItem(selectedIndex % rowCapacity)
        }
    }
}

/** 行数策略对齐原 setupMultiLineTabs：<=10 单行 / <=20 两行 / 其余三行，横屏减 1 */
private fun computeRowCount(size: Int, isLandscape: Boolean): Int {
    val rows = when {
        size <= 10 -> 1
        size <= 20 -> 2
        else -> 3
    }
    return if (rows > 1 && isLandscape) rows - 1 else rows
}

@Composable
private fun TabPill(title: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        color = if (selected) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(AppShapes.Capsule)
            .then(
                if (selected) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary, AppShapes.Capsule)
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** 顶栏八项菜单原样平移（design §5.1；页码项由 pageMenuTitle 驱动显隐，§5.2） */
@Composable
private fun buildSortMenuActions(
    source: RssSource?,
    pageMenuTitle: String?,
    onSwitchLayout: () -> Unit,
    onPagePicker: () -> Unit,
    onLogin: () -> Unit,
    onRefreshSorts: () -> Unit,
    onSetVariable: () -> Unit,
    onEditSource: () -> Unit,
    onReadRecord: () -> Unit,
    onClearArticles: () -> Unit,
): List<MenuAction> {
    val actions = mutableListOf<MenuAction>()
    pageMenuTitle?.let { title ->
        actions += MenuAction(Icons.Filled.Pageview, title, onClick = onPagePicker)
    }
    if (source?.loginUrl.isNullOrBlank().not()) {
        actions += MenuAction(Icons.Filled.Login, stringResource(R.string.login), onClick = onLogin)
    }
    actions += MenuAction(
        Icons.Filled.Refresh,
        stringResource(R.string.refresh_sort),
        onClick = onRefreshSorts,
    )
    actions += MenuAction(
        Icons.Filled.Tune,
        stringResource(R.string.set_source_variable),
        onClick = onSetVariable,
    )
    actions += MenuAction(
        Icons.Filled.Edit,
        stringResource(R.string.edit_source),
        onClick = onEditSource,
    )
    actions += MenuAction(
        Icons.Filled.GridView,
        stringResource(R.string.switchLayout),
        onClick = onSwitchLayout,
    )
    actions += MenuAction(
        Icons.Filled.History,
        stringResource(R.string.read_record),
        onClick = onReadRecord,
    )
    actions += MenuAction(
        Icons.Filled.Delete,
        stringResource(R.string.clear),
        onClick = onClearArticles,
    )
    return actions
}
