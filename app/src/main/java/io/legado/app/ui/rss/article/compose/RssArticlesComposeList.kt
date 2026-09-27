package io.legado.app.ui.rss.article.compose

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.R
import io.legado.app.data.entities.RssArticle
import io.legado.app.ui.widget.recycler.LoadMoreView

/**
 * 订阅文章列表（Compose 侧）的**滚动状态单源**（CF 6.2 RSS 文章五样式族）。
 *
 * 为什么由宿主持有状态而非页内 `remember`：宿主需要在 `onResume`（从播放器/图片页返回）与
 * 页码选择后**命令列表滚动到指定位置**（原实现走 `recyclerView.scrollToPosition`），
 * 状态必须可被宿主直接触达。
 *
 * 每种样式只有一条路径 ⇒ 只创建该样式真正使用的那一个 state（避免「三份状态并存」的双源隐患）：
 * 样式 0/1 = 线性列表、2/4 = 定列网格、3 = 瀑布流。
 */
class RssArticleListStateHolder(style: Int) {

    /**
     * 归一化样式：只有 **2 / 3 / 4** 有专属容器，**其余取值（含越界值）一律按样式 0 处理**。
     *
     * 为什么必须归一：原 View 实现 `when (articleStyle) { … else -> RssArticlesAdapter }` 天然带回退路径
     * （越界值落回样式 0，布局管理器同理走 `else` 分支）；若直接按原样值 new state，越界值会让三个 state
     * **全为 null** ⇒ 列表侧 `linear!!` 直接 NPE（导入的来源 JSON 可携带任意 `articleStyle`）。
     */
    val normalizedStyle: Int = if (style == 2 || style == 3 || style == 4) style else 0

    val linear: LazyListState? = if (normalizedStyle == 0) LazyListState() else null
    val grid: LazyGridState? =
        if (normalizedStyle == 2 || normalizedStyle == 4) LazyGridState() else null
    val staggered: LazyStaggeredGridState? =
        if (normalizedStyle == 3) LazyStaggeredGridState() else null

    init {
        // 不变量：三类容器恰有且仅有一个非空（防「归一化写漏 ⇒ 列表侧 !! 崩」）
        check(listOfNotNull(linear, grid, staggered).size == 1) {
            "RssArticleListStateHolder 容器分派异常：style=$style normalized=$normalizedStyle"
        }
    }

    /** 首个可视项下标（旋转/进程重建后的位置恢复用） */
    val firstVisibleIndex: Int
        get() = when {
            linear != null -> linear.firstVisibleItemIndex
            grid != null -> grid.firstVisibleItemIndex
            staggered != null -> staggered.firstVisibleItemIndex
            else -> 0
        }

    /** 首个可视项偏移（同上） */
    val firstVisibleOffset: Int
        get() = when {
            linear != null -> linear.firstVisibleItemScrollOffset
            grid != null -> grid.firstVisibleItemScrollOffset
            staggered != null -> staggered.firstVisibleItemScrollOffset
            else -> 0
        }

    /** 末个可视项下标（无可视项 = -1） */
    val lastVisibleIndex: Int
        get() = when {
            linear != null -> linear.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            grid != null -> grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            staggered != null -> staggered.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
            else -> -1
        }

    /** 内容是否已上滚（下拉刷新判据；与 View 侧 `canScrollVertically(-1)` 同义） */
    val canScrollBackward: Boolean
        get() = when {
            linear != null -> linear.firstVisibleItemIndex > 0 || linear.firstVisibleItemScrollOffset > 0
            grid != null -> grid.firstVisibleItemIndex > 0 || grid.firstVisibleItemScrollOffset > 0
            staggered != null ->
                staggered.firstVisibleItemIndex > 0 || staggered.firstVisibleItemScrollOffset > 0
            else -> false
        }

    /**
     * 命令式滚动（位置记忆 / 页码跳转回顶 / 旋转恢复共用）。
     *
     * 未组合时调用是**安全**的：`LazyListState.scrollToItem` 内部会 `awaitScrollDependencies()`
     * 等首次测量后再执行。
     */
    suspend fun scrollToItem(index: Int, offset: Int = 0) {
        when {
            linear != null -> linear.scrollToItem(index, offset)
            grid != null -> grid.scrollToItem(index, offset)
            staggered != null -> staggered.scrollToItem(index, offset)
        }
    }
}

/**
 * 订阅文章列表（CF 6.2：`item_rss_article` ~ `item_rss_article_4` 五样式族换装 Compose）。
 *
 * 换装前的形态：宿主 `RssArticlesFragment` 挂一个 `RecyclerViewAtPager2`，按 `articleStyle`
 * 选 6 个 Adapter（`RssArticlesAdapter` + `_1`~`_5`），每个 Adapter inflate 一个 `item_rss_article*.xml`。
 * 换装后样式 **0~4 走本组件**（线性 / 两列网格 / 瀑布流 / 三列网格），样式 **5（自由布局，
 * `FreeGridSizeCalculator` 尺寸算法冻结区）保留原 View 路径** —— **每条样式只有一条路径**。
 *
 * 逐项等价口径（对照 5 个已退役 XML）：
 * - **样式 0**（`item_rss_article`）：100dp 定高行、16dp 内边距；标题 16sp 粗体 2 行（已读转 `tv_text_summary`）、
 *   日期 12sp 斜体、右侧 110×68 圆角 12dp 封面（未加载留白）。
 * - **样式 1**（`item_rss_article_1`）：整宽封面 220dp（左右上各 12dp 外边距）+ 标题 15sp 粗体 2 行 +
 *   日期 11sp + 末尾 8dp `bg_divider_line` 分隔块。
 * - **样式 2**（`item_rss_article_2`）：两列网格，条目内边距 l4/t8/b6/r4；封面 272dp、标题 13sp、日期 11sp。
 * - **样式 4**（`item_rss_article_4`）：三列网格，条目内边距 l2/t8/b6/r2；封面 182dp（其余同上）。
 * - **样式 3**（`item_rss_article_3` + `layout-land` 变体）：瀑布流卡片（12dp 圆角 + `card_bg_water`
 *   底色 + 0.8dp `card_border_water` 描边），封面高度按**真实宽高比**回填；竖屏 2 列 / 横屏 3 列，
 *   横屏下标题 16sp/5 行、日期 14sp（原 XML 是两个变体，此处按配置折叠为同一实现）。
 * - 线性样式的行间分隔线沿用原 `VerticalDivider`（即 `@drawable/ic_divider` ⇒ `@color/bg_divider_line`）；
 *   Compose 侧以 `HorizontalDivider` 落在条目内，高度 1dp（原 drawable 为 1px 且不占布局高度，
 *   每行多出不到 1dp，属可接受差异）。
 * - 按下态**不加水波**：原条目根 View 无 `selectableItemBackground`（与全文搜索结果条目不同），
 *   `indication = null` 保持「零按下反馈」的既有观感。
 *
 * 触底翻页口径：原实现靠 `recyclerView.addOnScrollListener`——瀑布流在 `isPreload` 时提前 5 条、
 * 其余样式在 `!canScrollVertically(1)`（到底）时取下一页；本组件统一为「末个可视项进入末段阈值」
 * （瀑布流预加载 `PRELOAD_THRESHOLD`，其余为 0）。**顺带修掉一个真实死角**：原实现只在滚动事件里判到底，
 * 首页条目少于半屏时永远无法触发翻页（用户只能下拉刷新），新实现按 `hasMore` 守卫自动补齐。
 *
 * @param topPaddingPx 顶部覆盖占位（modern-rss 嵌入时由宿主上报，单位 px）
 * @param bottomPadding 底部留白（主壳底栏 / 导航栏，见 `mainBottomBarContentPadding`）
 */
@Composable
fun RssArticlesComposeList(
    items: List<RssArticle>,
    style: Int,
    stateHolder: RssArticleListStateHolder,
    loadMoreView: LoadMoreView,
    topPaddingPx: Int,
    bottomPadding: Dp,
    isLoading: Boolean,
    hasMore: Boolean,
    isPreload: Boolean,
    onItemClick: (RssArticle) -> Unit,
    onLoadMore: () -> Unit,
    onCanScrollBackwardChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val topPadding = with(LocalDensity.current) { topPaddingPx.toDp() }
    // 2026-09-27 用户报障修复：样式 3 的间距在**旧 View 实现里是像素(px)** ——
    // `RecyclerView.setPadding(20,0,20,0)` + `ItemDecoration(20,30,20,30)` ⇒ 左右间距 40px、
    // 上下间距 60px、阵列外沿左右 40px / 上下 30px。换装 Compose 时若直接写 `40.dp/30.dp/60.dp`，
    // 会按屏幕密度整体放大（本机 ×1.5、普通手机 ×2.75~3）⇒ 卡片变窄、整页松散
    // （用户体感「跟原来没改 Compose 时完全不一样」）。此处按 px→dp **等价换算**还原旧观感。
    val px40 = with(LocalDensity.current) { 40.toDp() }
    val px30 = with(LocalDensity.current) { 30.toDp() }
    val px60 = with(LocalDensity.current) { 60.toDp() }
    val px8 = with(LocalDensity.current) { 8.toDp() }
    val px4 = with(LocalDensity.current) { 4.toDp() }
    val threshold = if (style == 3 && isPreload) PRELOAD_THRESHOLD else 0
    val shouldLoadMore by remember(items, isLoading, hasMore, threshold) {
        derivedStateOf {
            items.isNotEmpty() && hasMore && !isLoading &&
                stateHolder.lastVisibleIndex >= items.lastIndex - threshold
        }
    }
    val canScrollBackward by remember {
        derivedStateOf { stateHolder.canScrollBackward }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }
    LaunchedEffect(canScrollBackward) {
        onCanScrollBackwardChanged(canScrollBackward)
    }

    val key: (RssArticle) -> String = { "${it.origin}|${it.link}|${it.sort}" }
    when {
        stateHolder.staggered != null -> LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(if (landscape) 3 else 2),
            state = stateHolder.staggered!!,
            modifier = modifier,
            // 原 RecyclerView：左右各 20px 内边距 + ItemDecoration 20/30（均为 **px**）⇒ 条目间距
            // 左右 40px、上下 60px，阵列外沿左右 40px / 上下 30px；此处用 px→dp 等价值还原
            contentPadding = PaddingValues(
                start = px40,
                end = px40,
                top = topPadding + px30,
                bottom = bottomPadding + px30
            ),
            horizontalArrangement = Arrangement.spacedBy(px40),
            verticalItemSpacing = px60
        ) {
            staggeredItems(items = items, key = key) { item ->
                RssArticleCardRow(
                    item = item,
                    landscape = landscape,
                    onClick = { onItemClick(item) }
                )
            }
            item(span = StaggeredGridItemSpan.FullLine) {
                LoadMoreFooter(loadMoreView)
            }
        }

        stateHolder.grid != null -> LazyVerticalGrid(
            columns = GridCells.Fixed(if (style == 2) 2 else 3),
            state = stateHolder.grid!!,
            modifier = modifier,
            // 同源问题（2026-09-27 用户报障）：旧实现样式 2/4 的 `setPadding(8,0,8,0)` /
            // `(4,0,4,0)` 同样是 **px**，此前按 dp 搬运 ⇒ 外沿随密度放大
            contentPadding = PaddingValues(
                start = if (style == 2) px8 else px4,
                end = if (style == 2) px8 else px4,
                top = topPadding,
                bottom = bottomPadding
            )
        ) {
            gridItems(items = items, key = key) { item ->
                if (style == 2) {
                    RssArticleGridRow(item = item, coverHeight = 272.dp, horizontalPadding = 4.dp) { onItemClick(item) }
                } else {
                    RssArticleGridRow(item = item, coverHeight = 182.dp, horizontalPadding = 2.dp) { onItemClick(item) }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LoadMoreFooter(loadMoreView)
            }
        }

        else -> LazyColumn(
            state = stateHolder.linear!!,
            modifier = modifier,
            contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding)
        ) {
            itemsIndexed(items = items, key = { _, item -> key(item) }) { _, item ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (style == 1) {
                        RssArticleBigCoverRow(item = item, onClick = { onItemClick(item) })
                    } else {
                        RssArticleListRow(
                            title = item.title,
                            pubDate = item.pubDate,
                            read = item.read,
                            origin = item.origin,
                            link = item.link,
                            onClick = { onItemClick(item) }
                        )
                    }
                    // 原 DividerItemDecoration 在任意相邻两项之间都有分隔线（含末条与页脚之间，末条之后无）
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = colorResource(R.color.bg_divider_line)
                    )
                }
            }
            item { LoadMoreFooter(loadMoreView) }
        }
    }
}

/**
 * 样式 0 行（`item_rss_article`）：收藏页共用（故为 public）。
 *
 * 收藏页差异仅在交互（长按删除）与取数通道（`rssStarDao`），行观感与文章列表完全一致 ⇒ 复用同一实现，
 * 避免「同脚手架不同行」。
 */
@Composable
fun RssArticleListRow(
    title: String?,
    pubDate: String?,
    read: Boolean,
    origin: String?,
    link: String?,
    modifier: Modifier = Modifier,
    queryImage: RssArticleImageQuery = DefaultRssArticleImageQuery,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp)
            .rssRowClickable(onClick, onLongClick)
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title.orEmpty(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = readTitleColor(read)
            )
            Text(
                text = pubDate.orEmpty(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                fontStyle = FontStyle.Italic,
                color = colorResource(R.color.primaryText)
            )
        }
        RssArticleImage(
            origin = origin,
            link = link,
            modifier = Modifier
                .width(110.dp)
                .height(68.dp),
            radiusDp = 12,
            queryImage = queryImage
        )
    }
}

/** 样式 1 行（`item_rss_article_1`）：整宽大封面 + 标题 + 日期 + 8dp 分隔块 */
@Composable
private fun RssArticleBigCoverRow(
    item: RssArticle,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rssRowClickable(onClick)
    ) {
        RssArticleImage(
            origin = item.origin,
            link = item.link,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 12.dp)
                .height(220.dp),
            radiusDp = 12
        )
        Text(
            text = item.title.orEmpty(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = readTitleColor(item.read),
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)
        )
        Text(
            text = item.pubDate.orEmpty(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 11.sp,
            color = colorResource(R.color.primaryText),
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(8.dp)
                .background(colorResource(R.color.bg_divider_line))
        )
    }
}

/** 样式 2/4 行（`item_rss_article_2` / `item_rss_article_4`）：定高封面 + 标题 + 日期（定列网格内） */
@Composable
private fun RssArticleGridRow(
    item: RssArticle,
    coverHeight: Dp,
    horizontalPadding: Dp,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rssRowClickable(onClick)
            .padding(start = horizontalPadding, end = horizontalPadding, top = 8.dp, bottom = 6.dp)
    ) {
        RssArticleImage(
            origin = item.origin,
            link = item.link,
            modifier = Modifier
                .fillMaxWidth()
                .height(coverHeight),
            radiusDp = 12
        )
        Text(
            text = item.title.orEmpty(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp,
            color = readTitleColor(item.read),
            modifier = Modifier.padding(top = 10.dp)
        )
        Text(
            text = item.pubDate.orEmpty(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 11.sp,
            color = colorResource(R.color.primaryText),
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
        )
    }
}

/**
 * 样式 3 行（`item_rss_article_3`）：瀑布流卡片。
 *
 * 封面高度：原实现写 `layoutParams.height = cardWidth × 比例`（比例来自 20 天缓存，首帧解码后回填）；
 * Compose 侧改由 [RssArticleImage.onRatioResolved] 上报比例 + 行内 `aspectRatio` 表达（结果一致）。
 * 比例未知时不占高（原 `WRAP_CONTENT` + 透明占位图，等价）。
 */
@Composable
private fun RssArticleCardRow(
    item: RssArticle,
    landscape: Boolean,
    onClick: () -> Unit
) {
    var ratio by remember(item.link) { mutableStateOf(0f) }
    val horizontalMargin = if (landscape) 8.dp else 6.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colorResource(R.color.card_bg_water))
            .border(
                width = 0.8.dp,
                color = colorResource(R.color.card_border_water),
                shape = RoundedCornerShape(12.dp)
            )
            .rssRowClickable(onClick)
    ) {
        RssArticleImage(
            origin = item.origin,
            link = item.link,
            // 圆角由整卡裁剪承担（原 CardView clipToOutline + cardCornerRadius）
            //
            // ⚠️ 比例未知时**必须给出正方形容器**（`aspectRatio(1f)`），不能「不加高度约束」：
            // 原 View 实现的比例未知态是 `height = WRAP_CONTENT` + `adjustViewBounds = true`，
            // 配合占位图 `transparent_placeholder`（`<size 100dp×100dp>` ⇒ 内在 1:1）⇒ 高度 ≈ 宽度（正方）。
            // 若换成「无约束」，`AndroidView` 在瀑布流的无界高度约束下会塌成极端高度，且 ImageView
            // 拿不到有效尺寸 ⇒ **Glide 请求永不完成、比例永远学不到**（2026-09-26 真机实测：8 张卡片
            // 全部拉成高条且无一条 `onResourceReady`）。
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (ratio > 0f) 1f / ratio else 1f),
            radiusDp = 0,
            onRatioResolved = { ratio = it },
            persistRatio = true
        )
        Text(
            text = item.title.orEmpty(),
            // 原 XML：竖屏 maxLines=9 / 横屏 maxLines=5（实为「不限行」的写法，逐字保留）
            maxLines = if (landscape) 5 else 9,
            overflow = TextOverflow.Ellipsis,
            fontSize = if (landscape) 16.sp else 13.sp,
            fontWeight = FontWeight.Bold,
            color = readTitleColor(item.read),
            modifier = Modifier.padding(start = horizontalMargin, end = horizontalMargin, top = 10.dp)
        )
        Text(
            text = item.pubDate.orEmpty(),
            maxLines = if (landscape) 19 else 39,
            overflow = TextOverflow.Ellipsis,
            fontSize = if (landscape) 14.sp else 11.sp,
            color = colorResource(R.color.primaryText),
            modifier = Modifier.padding(
                start = horizontalMargin,
                end = horizontalMargin,
                top = 8.dp,
                bottom = 12.dp
            )
        )
    }
}

/** 页脚：沿用 View 侧 [LoadMoreView]（三态 + 错误详情弹窗 + 重试），Compose 侧只负责托管与占位 */
@Composable
private fun LoadMoreFooter(loadMoreView: LoadMoreView) {
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { loadMoreView }
    )
}

/** 已读态标题色（原 `getCompatColor(R.color.tv_text_summary)`）/ 未读（`R.color.primaryText`） */
@Composable
private fun readTitleColor(read: Boolean): Color =
    colorResource(if (read) R.color.tv_text_summary else R.color.primaryText)

/** 原条目根 View 无 `selectableItemBackground` ⇒ 保持零按下反馈（`indication = null`） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.rssRowClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
): Modifier = combinedClickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onLongClick = onLongClick,
    onClick = onClick
)

/** 预加载源的提前翻页条数（与原瀑布流分支的阈值 5 一致） */
private const val PRELOAD_THRESHOLD = 5