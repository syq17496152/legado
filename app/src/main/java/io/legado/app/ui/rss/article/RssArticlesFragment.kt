package io.legado.app.ui.rss.article

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.mainBottomBarContentPadding
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.help.image.RssImageRatioStore
import io.legado.app.help.source.autoNextPageEnabled
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.VideoPlay
import io.legado.app.ui.image.ImagePlay
import io.legado.app.ui.rss.article.compose.RssArticleListStateHolder
import io.legado.app.ui.rss.article.compose.RssArticlesComposeList
import io.legado.app.ui.rss.article.free.FreeGridSizeCalculator
import io.legado.app.ui.rss.article.free.RssFreeGridLayoutManager
import io.legado.app.ui.rss.read.ReadRss
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.applyMainBottomBarPadding
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setEdgeEffectColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// CE-b：原 fragment_rss_articles.xml 已退役 ⇒ 继承共享合成壳基类（与 RssFavoritesFragment 同源，单源装配）
class RssArticlesFragment() : RssArticlesShellFragment<RssArticlesViewModel>(),
    BaseRssArticlesAdapter.CallBack {

    constructor(sortName: String, sortUrl: String, searchKey: String?) : this() {
        arguments = Bundle().apply {
            putString("sortName", sortName)
            putString("sortUrl", sortUrl)
            putString("searchKey", searchKey)
        }
    }
    private var isResumed = false

    // modern-rss: 嵌入 RssFragment（新版订阅）时取父 Fragment 作用域 RssSortViewModel，其余（RssSortActivity）取 Activity 作用域
    private val activityViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(parentFragment ?: requireActivity())[RssSortViewModel::class.java]
    }
    override val viewModel by viewModels<RssArticlesViewModel>()
    private val isPreload by lazy { activityViewModel.rssSource?.preload ?: false }
    private val articleStyle: Int by lazy { activityViewModel.articleStyle ?: 0 }

    /**
     * 列表是否走 Compose（CF 6.2：`item_rss_article` ~ `item_rss_article_4` 五样式族）。
     *
     * **禁止半迁移双源**：`articleStyle == 5`（自由布局，尺寸算法冻结区）保留原 `RecyclerView` +
     * `RssArticlesAdapter5` 路径，其余样式一律走 Compose 列表 —— 每条样式只有一条路径。
     */
    private val useComposeList by lazy { articleStyle != 5 }
    private val listStateHolder by lazy { RssArticleListStateHolder(articleStyle) }

    /** 文章列表数据（Compose 路径的唯一数据源；View 路径（样式 5）仍走 adapter） */
    private val articlesState = mutableStateOf<List<RssArticle>>(emptyList())
    private val articles: List<RssArticle> get() = articlesState.value

    /** 在途加载（原 `viewModel.isLoading` 的 Compose 可见镜像，用于触底翻页节流） */
    private val isLoadingState = mutableStateOf(true)
    /** 是否还有下一页（随 `loadFinallyLiveData` 刷新；`false` ⇒ 页脚「我是有底线的」且不再自动翻页） */
    private val hasMoreState = mutableStateOf(true)
    /** modern-rss 顶部覆盖顶栏占位（px，Compose 列表以此作为 contentPadding.top） */
    private val topOverlaySpaceState = mutableIntStateOf(0)
    /** Compose 列表是否已上滚（下拉刷新判据，由列表反向回填） */
    private var composeCanScrollBackward = false
    /** 旋转/进程重建后的位置恢复目标（-1 = 无待恢复）；**须等数据到达后再滚**，否则空表会被钳到 0 */
    private var pendingScrollIndex = -1
    private var pendingScrollOffset = 0

    // 仅样式 5（自由布局 / View 路径）使用
    private val adapter: RssArticlesAdapter5 by lazy {
        RssArticlesAdapter5(requireContext(), this@RssArticlesFragment)
    }
    private val loadMoreView: LoadMoreView by lazy {
        LoadMoreView(requireContext())
    }
    private var articlesFlowJob: Job? = null
    override val isGridLayout: Boolean
        get() = articleStyle == 2 || articleStyle == 5
    private var fullRefresh = false
    // modern-rss: 顶部覆盖顶栏（MainTopBarView）占位
    private var topOverlaySpace = 0
    private var topOverlayEnabled = false
    private val embeddedInModernRss: Boolean
        get() = parentFragment is io.legado.app.ui.main.rss.RssFragment

    // ---------------------------------------------------------------- 自由布局（articleStyle=5）专用状态
    /** 自由布局为 true；其余分支全部保持不变 */
    private val isFreeLayout: Boolean
        get() = articleStyle == 5
    /** 自由布局的 LayoutManager 引用，用于尺寸回填后的重排通知 */
    private var freeLayoutManager: RssFreeGridLayoutManager? = null
    /** 首屏尺寸门控是否已执行（仅首次进入时做一次，避免每次刷新都阻塞） */
    private var firstScreenGated = false
    /** 已预取到的 position 上限；用于滚动时判断是否需要再预取一批 */
    private var prefetchHorizon = 0

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.init(arguments)
        if (useComposeList) {
            // Compose 列表滚动状态由宿主持有 ⇒ 自行恢复（View 侧 RecyclerView 带 id 会自动保存布局状态）
            pendingScrollIndex = savedInstanceState?.getInt(STATE_SCROLL_INDEX, -1) ?: -1
            pendingScrollOffset = savedInstanceState?.getInt(STATE_SCROLL_OFFSET, 0) ?: 0
        }
        initView()
        initData()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (useComposeList) {
            outState.putInt(STATE_SCROLL_INDEX, listStateHolder.firstVisibleIndex)
            outState.putInt(STATE_SCROLL_OFFSET, listStateHolder.firstVisibleOffset)
        }
    }

    private fun initView() = run {
        refreshLayout.setColorSchemeColors(accentColor)
        loadMoreView.setOnClickListener {
            if (!loadMoreView.isLoading) {
                scrollToBottom(true)
            }
        }
        if (useComposeList) {
            initComposeListView()
        } else {
            initViewListView()
        }
        refreshLayout.setOnRefreshListener {
            loadArticles()
        }
        if (isPreload) {
            refreshLayout.post {
                refreshLayout.isRefreshing = !embeddedInModernRss
                loadArticles()
            }
            return@run
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                refreshLayout.isRefreshing = !embeddedInModernRss
                loadArticles()
                this@launch.cancel()
            }
        } //只刷新可见页面,非预加载时使用
    }

    /**
     * Compose 列表装配（样式 0~4）。
     *
     * 由本 Fragment 独占渲染：数据走 [articles]、触底翻页回调 [scrollToBottom]、位置记忆与页码跳转
     * 由 [listStateHolder] 命令式驱动（原实现走 `recyclerView.scrollToPosition`）。
     */
    private fun initComposeListView() {
        // Compose 列表本身不滚动（ComposeView 是宿主），下拉刷新必须显式给出「内容是否已上滚」判据，
        // 否则列表滚到中间也会被 SwipeRefreshLayout 判为可下拉（与发现页同口径）
        refreshLayout.setOnChildScrollUpCallback { _, _ -> composeCanScrollBackward }
        installComposeList {
            val bottomPadding: Dp = if (embeddedInModernRss) {
                mainBottomBarContentPadding().calculateBottomPadding()
            } else {
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            }
            RssArticlesComposeList(
                items = articles,
                style = articleStyle,
                stateHolder = listStateHolder,
                loadMoreView = loadMoreView,
                topPaddingPx = topOverlaySpaceState.intValue,
                bottomPadding = bottomPadding,
                isLoading = isLoadingState.value,
                hasMore = hasMoreState.value,
                isPreload = isPreload,
                onItemClick = { readRss(it) },
                onLoadMore = { scrollToBottom() },
                onCanScrollBackwardChanged = { composeCanScrollBackward = it },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    /** 样式 5（自由布局）的 View 列表装配：逐项与原实现保持一致，仅此处一条路径 */
    private fun initViewListView() = run {
        installRecyclerList()
        recyclerView.setEdgeEffectColor(primaryColor)
        // modern-rss: 嵌入新版订阅页时预留 MainActivity 主底部栏空间
        if (embeddedInModernRss) {
            recyclerView.applyMainBottomBarPadding(withInitialPadding = true)
        } else {
            recyclerView.applyNavigationBarPadding()
        }
        // 自由布局（智能相册网格）：行高统一、宽度按原图比例分配、整行精确撑满。
        // 左右各留 4dp 外边距；行内与行间的 4dp 间距由算法内部按 SPACING_DP 扣除，
        // 因此这里**不能**再加 ItemDecoration（会造成间距双重计算）。
        recyclerView.setPadding(4, 4, 4, 4)
        recyclerView.itemAnimator = null
        // 数据源以 provider 形式注入：LayoutManager 不依赖具体 Adapter 实现
        val freeLayoutManager = RssFreeGridLayoutManager(
            context = requireContext(),
            ratioProvider = { position ->
                // 按 layout position 取文章 → 查尺寸供给层。未解析时返回源级中位数估算值，
                // 保证任何时刻都有合法比例，布局不会塌陷；真值到达后由 onRatiosUpdated 重排。
                val origin = activityViewModel.url
                val article = adapter.getItemByLayoutPosition(position)
                if (origin.isNullOrEmpty() || article == null) {
                    FreeGridSizeCalculator.DEFAULT_RATIO
                } else {
                    RssImageRatioStore.peek(origin, article.link)
                }
            },
            itemCountProvider = { adapter.itemCount },
            viewTypeProvider = { position -> adapter.getItemViewType(position) }
        )
        this.freeLayoutManager = freeLayoutManager
        recyclerView.layoutManager = freeLayoutManager
        recyclerView.adapter = adapter
        applyTopOverlaySpace()
        adapter.addFooterView {
            // loadMoreView 是共享单实例；若上个布局周期/adapter 仍持有 parent（如布局切换瞬间），
            // 先摘除再复用，否则 createViewHolder 会因
            // "ViewHolder views must not be attached when created" 抛 IllegalStateException
            (loadMoreView.parent as? ViewGroup)?.removeView(loadMoreView)
            ViewLoadMoreBinding.bind(loadMoreView)
        }
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (!recyclerView.canScrollVertically(1)) {
                    scrollToBottom()
                    return
                }
                // 自由布局：预加载源提前翻页 + 尺寸预取窗口推进（新增分支，不影响其他样式）
                val layoutManager = recyclerView.layoutManager
                if (isFreeLayout && layoutManager is RssFreeGridLayoutManager) {
                    val lastVisible = layoutManager.findLastVisibleItemPosition()
                    if (lastVisible >= 0) {
                        // 预加载源：接近底部时提前取下一页，阈值与瀑布流保持一致（5 条）
                        if (isPreload &&
                            lastVisible >= adapter.getActualItemCount() - PRELOAD_THRESHOLD
                        ) {
                            scrollToBottom()
                        } else if (!isPreload && lastVisible >= layoutManager.itemCount - 2) {
                            // 非预加载源：footer 进入可视区即翻页。
                            // 不能沿用底边判定（!canScrollVertically(1)）：自由布局 LM 的
                            // maxScrollOffset 口径比 range-extent 小一个上下 padding，
                            // 真机带导航栏 padding 时到底后仍判"能滚"，永不触发翻页
                            //（铁证 2026-09-11 用户真机：上滑到底不加载下一页；
                            //   模拟器日志 viewportBottom 5299 > contentH 5070 仍 itemCount=21）
                            scrollToBottom()
                        }
                        // 尺寸预取：可见下沿 + 前瞻超过已预取上界时再取一批
                        if (lastVisible + PREFETCH_AHEAD > prefetchHorizon) {
                            prefetchHorizon = lastVisible + PREFETCH_AHEAD
                            launchRatioPrefetch(lastVisible - PREFETCH_AHEAD, PREFETCH_AHEAD * 2)
                        }
                    }
                }
            }
        })
    }

    /** modern-rss: 供 RssFragment（新版订阅）设置顶部覆盖顶栏占位空间 */
    fun setTopOverlaySpace(space: Int, overlay: Boolean) {
        topOverlaySpace = space
        topOverlayEnabled = overlay
        view?.post {
            applyTopOverlaySpace()
        }
    }

    private fun applyTopOverlaySpace() {
        if (view == null || !embeddedInModernRss) return
        if (useComposeList) {
            // Compose 列表：占位改由 contentPadding.top 承担（clipToPadding 语义等价）
            topOverlaySpaceState.intValue = topOverlaySpace
        } else {
            recyclerView.clipToPadding = true
            recyclerView.setPadding(
                recyclerView.paddingLeft,
                topOverlaySpace,
                recyclerView.paddingRight,
                recyclerView.paddingBottom
            )
        }
        refreshLayout.setProgressViewOffset(
            true,
            (topOverlaySpace - 28.dpToPx()).coerceAtLeast(0),
            topOverlaySpace + 56.dpToPx()
        )
    }

    // ---------------------------------------------------------------- 自由布局：图片尺寸预取
    // 只有 articleStyle=5 会调用下面这些方法；其余样式的代码路径完全不受影响。

    /**
     * 滚动触发的尺寸预取（fire-and-forget）。
     *
     * 重复触发是安全的：[RssImageRatioStore] 内部按「已在内存 / 已在途」精确去重，
     * 同一张图不会被重复查库或下载。
     */
    private fun launchRatioPrefetch(from: Int, count: Int) {
        val origin = activityViewModel.url ?: return
        val ctx = context ?: return
        val list = adapter.getItems()
        val start = from.coerceAtLeast(0)
        if (start >= list.size) return
        launchRatioPrefetchInternal(origin, ctx, list, start, count)
    }

    /**
     * 首屏尺寸门控（仅自由布局、仅首次数据到达时执行一次）。
     *
     * 目的：冷启动时三级缓存全空，若立即按估算值布局，图片解码完成后必然重排 —— 用户能直接看到网格跳动。
     * 做法：先预取前 [PREFETCH_FIRST_SCREEN] 条，最多等 [FIRST_SCREEN_TIMEOUT_MS] 毫秒，再渲染列表。
     *
     * **关键**：预取任务挂在 `viewLifecycleOwner.lifecycleScope` 上（独立 Job），
     * gating 只 `join()` 等待、绝不把它包进 `withTimeoutOrNull` —— 后者超时会**取消**预取，
     * 导致剩余比例永远拿不到。超时只是放弃等待，任务继续跑完并通过脏标记机制补正。
     */
    private suspend fun gateFirstScreen(list: List<RssArticle>) {
        val origin = activityViewModel.url ?: return
        val ctx = context ?: return
        // 调试：确认首屏门控确实被触发（仅 articleStyle=5）
        AppLog.put("自由布局[门控] 进入gateFirstScreen size=${list.size} origin=${(origin ?: "").take(2)}***")
        val job = runCatching {
            launchRatioPrefetchInternal(origin, ctx, list, 0, PREFETCH_FIRST_SCREEN)
        }.getOrElse { e ->
            AppLog.put("自由布局[门控] 启动预取失败 err=${e.javaClass.simpleName}", e)
            return
        }
        // job.join() 可能因子协程失败而抛异常，必须吞掉：否则下方 setItems 永远不被调用 → 列表空白
        withTimeoutOrNull(FIRST_SCREEN_TIMEOUT_MS) { runCatching { job.join() } }
        AppLog.put("自由布局[门控] 门控结束（已等最多${FIRST_SCREEN_TIMEOUT_MS}ms），继续渲染")
    }

    /** 启动一批预取，完成后回到主线程通知 LayoutManager 按脏标记规则重排 */
    private fun launchRatioPrefetchInternal(
        origin: String,
        ctx: android.content.Context,
        list: List<RssArticle>,
        from: Int,
        count: Int
    ): Job = viewLifecycleOwner.lifecycleScope.launch(IO) {
        val resolved = RssImageRatioStore.prefetch(ctx, origin, list, from, count)
        if (resolved > 0) {
            withContext(Dispatchers.Main) {
                freeLayoutManager?.onRatiosUpdated(from)
            }
        }
    }

    private fun initData() {
        val rssUrl = activityViewModel.url ?: return
        articlesFlowJob?.cancel()
        articlesFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.rssArticleDao.flowByOriginSort(rssUrl, viewModel.sortName)
                .catch {
                    AppLog.put("订阅文章界面获取数据失败\n${it.localizedMessage}", it)
                }.flowOn(IO).collect { newList ->
                    // 自由布局行组成解锁（AD-10）：用户下拉刷新（fullRefresh）后按最新比例表
                    // 重新定型。换源与可用宽度变化由 LayoutManager 内部自动解锁，此处只管这一条
                    if (isFreeLayout && fullRefresh) {
                        freeLayoutManager?.unlockItemsPerRow()
                    }
                    // 自由布局首屏门控：先把前若干条图片尺寸预热进缓存，再渲染，
                    // 避免首屏铺好之后因比例回填而整体跳动（仅首次、仅 articleStyle=5）
                    if (isFreeLayout && !firstScreenGated && newList.isNotEmpty()) {
                        firstScreenGated = true
                        prefetchHorizon = PREFETCH_FIRST_SCREEN
                        gateFirstScreen(newList)
                    }
                    // 2026-09-27 用户报障修复：`articlesState` 是**单一数据源**（`articles` getter 读它）。
                    // 此前只在 Compose 分支赋值 ⇒ **样式 5（自由布局，View 路径）恒为空列表**，
                    // `readRss` 把空列表交给播放器 ⇒ `VideoPlay.rssArticles` 为空 ⇒
                    // 「上滑下滑切上/下一个视频」与「传统式上一部下一部」同时失效（用户真机报障）。
                    // 统一前置赋值：两条渲染路径共用同一份列表，消除口径漂移。
                    articlesState.value = newList
                    if (useComposeList) {
                        // Compose 列表按 key 做条目复用与首项锚定 ⇒ 不再需要
                        // 「isResumed 全量刷新 vs DiffUtil 差异化更新」这套 View 复用防护
                        //（原注释：RecyclerView 复用机制下切换标签走差异更新会报 ViewHolder 状态混乱）
                        consumePendingScroll()
                        delay(200) // 200毫秒防抖
                        return@collect
                    }
                    if (!isResumed || fullRefresh || newList.isEmpty()) {
                        AppLog.put("RssFree[数据] setItems(newList) size=${newList.size} isResumed=$isResumed fullRefresh=$fullRefresh")
                        adapter.setItems(newList)
                    } else {
                        //用DiffUtil只对差异数据进行更新
                        //注意RecyclerView的复用机制,切换标签时采用差异化更新会报ViewHolder的状态管理混乱
                        AppLog.put("RssFree[数据] setItems(diff) size=${newList.size} isResumed=$isResumed")
                        adapter.setItems(newList, object : DiffUtil.ItemCallback<RssArticle>() {
                            override fun areItemsTheSame(
                                oldItem: RssArticle, newItem: RssArticle
                            ): Boolean {
                                return oldItem.link == newItem.link
                            }

                            override fun areContentsTheSame(
                                oldItem: RssArticle, newItem: RssArticle
                            ): Boolean {
                                return oldItem.title == newItem.title && oldItem.image == newItem.image && oldItem.read == newItem.read
                            }

                            override fun getChangePayload(
                                oldItem: RssArticle, newItem: RssArticle
                            ): Any? {
                                return if (oldItem.read != newItem.read) { "read" }
                                else if (oldItem.title != newItem.title) { "title" }
                                else { null }
                            }
                        }, true)
                    }
                    delay(200) // 200毫秒防抖
                }
        }
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        if (!useComposeList) {
            adapter.upResumed(isResumed)
        }
        // 阶段8 F11：位置记忆——从播放器返回时滚动到退出时正在看的文章位置
        VideoPlay.lastPlayedArticleLink?.let { link ->
            VideoPlay.lastPlayedArticleLink = null  // 一次性使用，清除标记
            scrollToArticleLink(link)
        }
        // image-gallery-activity: 从图片浏览器返回时滚动到退出时正在看的文章位置
        ImagePlay.lastPlayedArticleLink?.let { link ->
            ImagePlay.lastPlayedArticleLink = null  // 一次性使用，清除标记
            scrollToArticleLink(link)
        }
    }

    /** 位置记忆：把「文章 link」翻译成列表下标后命令式滚动（Compose/View 两条路径各自实现） */
    private fun scrollToArticleLink(link: String) {
        val position = articles.indexOfFirst { it.link == link }
        if (position < 0) return
        if (useComposeList) {
            viewLifecycleOwner.lifecycleScope.launch {
                listStateHolder.scrollToItem(position)
            }
        } else {
            recyclerView.scrollToPosition(position)
        }
    }

    /**
     * 消费「旋转/进程重建后的待恢复位置」（Compose 路径）。
     *
     * 为什么必须等数据到达（在 `initData` 的首次非空发射里调用）：空表时 `scrollToItem(N)` 会被钳到 0
     * ⇒ 恢复等于没恢复（本批《交接文档》§8-64 口径）。
     */
    private fun consumePendingScroll() {
        val index = pendingScrollIndex
        pendingScrollIndex = -1
        if (index <= 0) return
        val offset = pendingScrollOffset
        pendingScrollOffset = 0
        viewLifecycleOwner.lifecycleScope.launch {
            listStateHolder.scrollToItem(index, offset)
        }
    }

    override fun onPause() {
        isResumed = false
        if (!useComposeList) {
            adapter.upResumed(isResumed)
        }
        super.onPause()
    }

    private fun loadArticles(fullRefresh: Boolean = false) {
        this.fullRefresh = fullRefresh
        isLoadingState.value = true
        activityViewModel.rssSource?.let {
            viewModel.loadArticles(it)
        }
    }

    /** 供 RssSortActivity 登录后刷新当前列表 */
    fun refreshAfterLogin() {
        loadArticles(fullRefresh = true)
    }

    private fun loadArticles(targetPage: Int) {
        fullRefresh = true
        isLoadingState.value = true
        activityViewModel.rssSource?.let {
            viewModel.loadArticles(it, targetPage)
        }
    }

    private fun getCurrentPage(): Int = viewModel.page

    private fun showPageMenu(): Boolean {
        val source = activityViewModel.rssSource ?: return false
        // 显式下一页规则 或 隐式PAGE模式（URL含{{page}}占位符）均可翻页
        return !source.ruleNextPage.isNullOrEmpty() || source.autoNextPageEnabled(viewModel.sortUrl)
    }

    fun showPagePicker() {
        if (!showPageMenu()) return
        val currentPage = getCurrentPage()
        NumberPickerDialog(requireContext())
            .setTitle(getString(R.string.change_page))
            .setMinValue(1)
            .setMaxValue(999)
            .setValue(currentPage)
            .show { targetPage ->
                if (targetPage != currentPage) {
                    fullRefresh = true
                    loadArticles(targetPage)
                    scrollToTop()
                }
            }
    }

    /** 跳到列表顶部（页码切换后；Compose/View 两条路径各自实现） */
    private fun scrollToTop() {
        if (useComposeList) {
            viewLifecycleOwner.lifecycleScope.launch {
                listStateHolder.scrollToItem(0)
            }
        } else {
            recyclerView.scrollToPosition(0)
        }
    }

    private fun scrollToBottom(forceLoad: Boolean = false) {
        if (viewModel.isLoading) return
        fullRefresh = false
        // F142：页脚重试走 forceLoad 通道。首页加载失败时 nextPageUrl 恒为 null，原实现直接交给
        // loadMore ⇒ 立即判「没有下一页」把页脚退化成「我是有底线的」，重试其实没有重新拉取。
        // 取不到下一页地址时改按当前页重拉（真正的可恢复），并先切加载态给即时反馈。
        if (forceLoad && viewModel.nextPageUrl.isNullOrEmpty()) {
            loadMoreView.hasMore()
            isLoadingState.value = true
            fullRefresh = true
            activityViewModel.rssSource?.let {
                viewModel.loadArticles(it, viewModel.page)
            }
            return
        }
        val itemCount = if (useComposeList) articles.size else adapter.getActualItemCount()
        if ((loadMoreView.hasMore && itemCount > 0) || forceLoad) {
            loadMoreView.hasMore()
            isLoadingState.value = true
            activityViewModel.rssSource?.let {
                viewModel.loadMore(it)
            }
        }
    }

    override fun observeLiveBus() {
        viewModel.loadErrorLiveData.observe(viewLifecycleOwner) {
            isLoadingState.value = false
            loadMoreView.error(it)
        }
        viewModel.loadFinallyLiveData.observe(viewLifecycleOwner) { hasMore ->
            refreshLayout.isRefreshing = false
            isLoadingState.value = false
            hasMoreState.value = hasMore
            if (!hasMore) {
                loadMoreView.noMore()
            }
        }
        viewModel.pageLiveData.observe(viewLifecycleOwner) { page ->
            (requireActivity() as? RssSortActivity)?.updatePageMenu(page, showPageMenu())
        }
    }

    override fun readRss(rssArticle: RssArticle) {
        fullRefresh = false //read会触发数据库更新,此时进行差异化更新
        // 传递文章列表给播放器，支持上下滑动切换文章（video-article-swipe-switch spec）
        val rssArticles = articles
        // 阶段8 F9：传递分页上下文给播放器，支持播放器内分页加载
        ReadRss.readRss(
            this, rssArticle, activityViewModel.rssSource, rssArticles,
            sortName = viewModel.sortName,
            sortUrl = viewModel.sortUrl,
            nextPageUrl = viewModel.nextPageUrl,
            page = viewModel.page
        )
    }

    companion object {
        /** 首屏门控预取的条数（自由布局专用） */
        private const val PREFETCH_FIRST_SCREEN = 24

        /** 首屏门控最长等待毫秒数；超时即以估算比例渲染，剩余由脏标记机制补正 */
        private const val FIRST_SCREEN_TIMEOUT_MS = 800L

        /** 滚动预取的前瞻条数：可见下沿往后这么多条进入预取窗口 */
        private const val PREFETCH_AHEAD = 20

        /** 预加载源「接近底部」的阈值（条），与瀑布流分支的 5 保持一致 */
        private const val PRELOAD_THRESHOLD = 5

        /** Compose 列表位置恢复的实例状态键（旋转/进程重建） */
        private const val STATE_SCROLL_INDEX = "rssArticlesScrollIndex"
        private const val STATE_SCROLL_OFFSET = "rssArticlesScrollOffset"
    }
}