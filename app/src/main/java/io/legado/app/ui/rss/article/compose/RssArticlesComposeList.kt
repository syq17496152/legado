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
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed as staggeredItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

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
 * 订阅文章列表（Compose 侧样式 0~4）**留白单源**（2026-10-07 用户报障修复）。
 *
 * 为什么必须是**固定 dp** 而非「px 数值再做 px→dp 换算」：CF 6.2 换装 Compose 时间距曾写成
 * `with(LocalDensity.current) { 40.toDp() }`（伪 dp）—— 换算结果随屏幕像素密度**反向**变化：
 * 手机（density ≈ 2.75）`40px → 14.5dp`，低密度设备（模拟器 / 平板，density ≈ 1.0~1.5）
 * `40px → 26.7~40dp` ⇒ 瀑布流外沿留白被放大、卡片明显压窄（用户体感「其他样式宽留白太多」）。
 *
 * 现口径**对齐用户已认可的「自由」布局（`articleStyle = 5`，View 路径）** —— 其
 * `RecyclerView.setPadding(4,4,4,4)`（px）+ 算法 `FreeGridSizeCalculator.SPACING_DP = 4`（dp）
 * 即「紧凑且与屏幕密度无关」。
 *
 * 本对象**不依赖任何 Android API**（纯 `const val Int`），可被 JVM 单测直接断言取值。
 */
object RssArticleListSpacing {

    /** 外沿留白（dp）：内容（卡片 / 行）距屏幕左右边缘的视觉留白 */
    const val OUTER_DP = 4

    /** 条目间距（dp）：相邻条目之间的视觉空隙（横向与纵向**同心**） */
    const val GAP_DP = 4
}

/**
 * 订阅文章列表（CF 6.2：`item_rss_article` ~ `item_rss_article_4` 五样式族换装 Compose）。
 *
 * 换装前的形态：宿主 `RssArticlesFragment` 挂一个 `RecyclerViewAtPager2`，按 `articleStyle`
 * 选 6 个 Adapter（`RssArticlesAdapter` + `_1`~`_5`），每个 Adapter inflate 一个 `item_rss_article*.xml`。
 * 换装后样式 **0~4 走本组件**（线性 / 两列网格 / 瀑布流 / 三列网格），样式 **5（自由布局，
 * `FreeGridSizeCalculator` 尺寸算法冻结区）保留原 View 路径** —— **每条样式只有一条路径**。
 *
 * 逐项等价口径（对照 5 个已退役 XML；**左右留白为 2026-10-07 收窄后的口径**，其余尺寸/字号沿用 XML 事实）：
 * - **样式 0**（`item_rss_article`）：100dp 定高行、纵向 16dp 内边距 + **左右 `OUTER_DP`(4dp)**（原 16dp）；
 *   标题 16sp 粗体 2 行（已读转 `tv_text_summary`）、日期 12sp 斜体、右侧 110×68 圆角 12dp 封面（未加载留白）。
 * - **样式 1**（`item_rss_article_1`）：整宽封面 220dp（上 12dp / **左右 `OUTER_DP`(4dp)** 外边距，原左右各 12dp）+
 *   标题 15sp 粗体 2 行 + 日期 11sp + 末尾 8dp `bg_divider_line` 分隔块。
 * - **样式 2**（`item_rss_article_2`）：两列网格，条目**纵向**内边距 t8/b6；封面 272dp、标题 13sp、日期 11sp。
 * - **样式 4**（`item_rss_article_4`）：三列网格，条目**纵向**内边距 t8/b6；封面 182dp（其余同上）。
 * - **样式 2/4 的横向留白**：外沿 = 容器头尾 `contentPadding = OUTER_DP`，条目间距 =
 *   容器 `horizontalArrangement = spacedBy(GAP_DP)`；条目自身横向内边距归零（原 l4/r4、l2/r2）。
 * - **样式 3**（`item_rss_article_3` + `layout-land` 变体）：瀑布流卡片（12dp 圆角 + `card_bg_water`
 *   底色 + 0.8dp `card_border_water` 描边），封面高度按**真实宽高比**回填；竖屏 2 列 / 横屏 3 列，
 *   横屏下标题 16sp/5 行、日期 14sp（原 XML 是两个变体，此处按配置折叠为同一实现）；
 *   横向与纵向间距同为 `GAP_DP`（原为 px 等价的 40px 横向 / 60px 纵向 —— 随屏幕密度反向放大）。
 * - 线性样式的行间分隔线沿用原 `VerticalDivider`（即 `@drawable/ic_divider` ⇒ `@color/bg_divider_line`）；
 *   Compose 侧以 `HorizontalDivider` 落在条目内，高度 1dp（原 drawable 为 1px 且不占布局高度，
 *   每行多出不到 1dp，属可接受差异）。
 * - 按下态**不加水波**：原条目根 View 无 `selectableItemBackground`（与全文搜索结果条目不同），
 *   `indication = null` 保持「零按下反馈」的既有观感。
 *
 * 触底翻页口径：原实现靠 `recyclerView.addOnScrollListener`——瀑布流在 `isPreload` 时提前 5 条、
 * 其余样式在 `!canScrollVertically(1)`（到底）时取下一页；本组件统一为「末个可视项进入末段阈值」，
 * 阈值由 `RssPagingThresholdResolver.resolve` **单源**给出（瀑布流+预加载=5，其余=1）。
 * **顺带修掉两个真实死角**：①原实现只在滚动事件里判到底，首页条目少于半屏时永远无法触发翻页
 * （用户只能下拉刷新），新实现按 `hasMore` 守卫自动补齐；②提前量为 0 时必须滑到**真正的最后一条**才发请求，
 * 触底后必然出现「网络 RTT + 落库 + Flow 回流 + 重组」的空窗 —— 这是 2026-09-28 用户报障
 * 「非自由布局四样式快速下滑卡顿」的根因之一。
 *
 * 数据/在途/有下一页三态改为 **`State` 入参**（AD-03 落地约束）：`snapshotFlow` 只能观察到 State 支撑的读取，
 * 若数据入口是普通参数则 `items.size` 变化不被捕获 ⇒ 翻页判定永不重算。
 *
 * @param topPaddingPx 顶部覆盖占位（modern-rss 嵌入时由宿主上报，单位 px）
 * @param bottomPadding 底部留白（主壳底栏 / 导航栏，见 `mainBottomBarContentPadding`）
 */
@Composable
fun RssArticlesComposeList(
    itemsState: State<List<RssArticle>>,
    style: Int,
    stateHolder: RssArticleListStateHolder,
    loadMoreView: LoadMoreView,
    topPaddingPx: Int,
    bottomPadding: Dp,
    isLoadingState: State<Boolean>,
    hasMoreState: State<Boolean>,
    isPreload: Boolean,
    scrollTopRequest: Int,
    onItemClick: (RssArticle) -> Unit,
    onLoadMore: () -> Unit,
    onCanScrollBackwardChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = itemsState.value
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val topPadding = with(LocalDensity.current) { topPaddingPx.toDp() }
    // 2026-10-07 用户报障修复（rss-list-padding-tighten）：间距改用**固定 dp 单源**
    // [RssArticleListSpacing]（外沿 OUTER_DP / 间距 GAP_DP），对齐用户已认可的「自由」布局紧凑口径。
    // 历史写法「`N px` 数值再做 px→dp 换算」（`40.toDp()` / `30.toDp()` / `60.toDp()` / `8.toDp()` /
    // `4.toDp()`）会使留白随屏幕像素密度**反向**放大（手机 40px≈14.5dp，低密度模拟器/平板可达
    // 26.7~40dp）⇒ 卡片被压窄（用户体感「其他样式宽留白太多」）。
    val outer = RssArticleListSpacing.OUTER_DP.dp
    val gap = RssArticleListSpacing.GAP_DP.dp
    // 翻页提前量：单源纯函数（瀑布流+预加载=5，其余=1；严禁 0）
    val threshold = RssPagingThresholdResolver.resolve(style, isPreload)

    // 判定脱离组合作用域（AD-03）：`snapshotFlow` 只在判定结果**翻转**时产生一次事件；
    // 流内读取的 `itemsState / hasMoreState / isLoadingState / layoutInfo` 全部由 State 支撑 ⇒ 数据与滚动变化都会重算。
    LaunchedEffect(stateHolder, threshold) {
        snapshotFlow {
            RssPagingDecision.shouldLoadMore(
                itemCount = itemsState.value.size,
                hasMore = hasMoreState.value,
                isLoading = isLoadingState.value,
                lastVisibleIndex = stateHolder.lastVisibleIndex,
                threshold = threshold
            )
        }.distinctUntilChanged().filter { it }.collect { onLoadMore() }
    }

    // 下拉刷新判据同样移出组合作用域：`layoutInfo` 在滚动中每帧被替换，组合期读取会让读取者反复失效
    LaunchedEffect(stateHolder) {
        snapshotFlow { stateHolder.canScrollBackward }
            .distinctUntilChanged()
            .collect { onCanScrollBackwardChanged(it) }
    }

    // 宿主「强制回顶」请求（add-rss-article-refresh-to-top）：**必须在本次组合（已含新数据）应用后**执行。
    // 若在数据写入前直接 `scrollToItem(0)`，该次滚动会先以旧数据被消费并记录旧首条 key，
    // 新数据到达时按 key 把旧首条锚回视口顶 ⇒ 回顶被静默吞掉（2026-10-05 模拟器 L2 实证）。
    LaunchedEffect(scrollTopRequest) {
        if (scrollTopRequest > 0) {
            stateHolder.scrollToItem(0)
        }
    }

    // 稳定 key 预计算（AD-04）：只在**数据变化时**算一次，而非每次组合为每个条目重新拼接字符串
    val keys = remember(items) { items.map(RssArticleKey::of) }
    when {
        stateHolder.staggered != null -> LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(if (landscape) 3 else 2),
            state = stateHolder.staggered!!,
            modifier = modifier,
            // 留白单源（rss-list-padding-tighten）：外沿左右 = OUTER_DP；横向间距与纵向间距同为 GAP_DP
            // （用户 2026-10-07 裁定「瀑布上下间距与左右一致」）。
            contentPadding = PaddingValues(
                start = outer,
                end = outer,
                top = topPadding + gap,
                bottom = bottomPadding + gap
            ),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalItemSpacing = gap
        ) {
            staggeredItemsIndexed(items = items, key = { index, _ -> keys[index] }) { _, item ->
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
            // 留白单源（rss-list-padding-tighten）：外沿左右 = OUTER_DP；条目间距由容器横向排列给出 GAP_DP
            // （条目自身横向内边距已归零，避免「外沿/间距」双重叠加）
            contentPadding = PaddingValues(
                start = outer,
                end = outer,
                top = topPadding,
                bottom = bottomPadding
            ),
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            gridItemsIndexed(items = items, key = { index, _ -> keys[index] }) { _, item ->
                if (style == 2) {
                    RssArticleGridRow(item = item, coverHeight = 272.dp) { onItemClick(item) }
                } else {
                    RssArticleGridRow(item = item, coverHeight = 182.dp) { onItemClick(item) }
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
            itemsIndexed(items = items, key = { index, _ -> keys[index] }) { _, item ->
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
            // 留白单源：左右收窄至 OUTER_DP（原 16dp 四边）；纵向保持 16dp（行高 100dp 与 68dp 封面不变）
            .padding(horizontal = RssArticleListSpacing.OUTER_DP.dp, vertical = 16.dp),
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
    // 留白单源：左右收窄至 OUTER_DP（原 12dp）；纵向 top 沿用原 XML（12dp / 10dp / 分隔块 8dp）
    val outer = RssArticleListSpacing.OUTER_DP.dp
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
                .padding(start = outer, end = outer, top = 12.dp)
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
            modifier = Modifier.padding(start = outer, end = outer, top = 12.dp)
        )
        Text(
            text = item.pubDate.orEmpty(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 11.sp,
            color = colorResource(R.color.primaryText),
            modifier = Modifier.padding(start = outer, end = outer, top = 10.dp)
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
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rssRowClickable(onClick)
            // 横向留白已上移到容器（外沿 OUTER_DP + 横向排列 GAP_DP）⇒ 此处只保留纵向
            .padding(top = 8.dp, bottom = 6.dp)
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

// 注：原 `private const val PRELOAD_THRESHOLD = 5` 已上移至 `RssPaging.kt`
// 的 `RssPagingThresholdResolver`（连同样式 5 View 路径的引用一并为单源），避免阈值散落两处。