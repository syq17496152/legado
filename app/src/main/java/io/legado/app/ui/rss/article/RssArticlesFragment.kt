package io.legado.app.ui.rss.article

import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.databinding.FragmentRssArticlesBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.help.image.RssImageRatioStore
import io.legado.app.help.source.autoNextPageEnabled
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.VideoPlay
import io.legado.app.ui.image.ImagePlay
import io.legado.app.ui.rss.article.free.FreeGridSizeCalculator
import io.legado.app.ui.rss.article.free.RssFreeGridLayoutManager
import io.legado.app.ui.rss.read.ReadRss
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.applyMainBottomBarPadding
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.viewbindingdelegate.viewBinding
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

class RssArticlesFragment() : VMBaseFragment<RssArticlesViewModel>(R.layout.fragment_rss_articles),
    BaseRssArticlesAdapter.CallBack {

    constructor(sortName: String, sortUrl: String, searchKey: String?) : this() {
        arguments = Bundle().apply {
            putString("sortName", sortName)
            putString("sortUrl", sortUrl)
            putString("searchKey", searchKey)
        }
    }
    private var isResumed = false

    private val binding by viewBinding(FragmentRssArticlesBinding::bind)
    // modern-rss: 嵌入 RssFragment（新版订阅）时取父 Fragment 作用域 RssSortViewModel，其余（RssSortActivity）取 Activity 作用域
    private val activityViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(parentFragment ?: requireActivity())[RssSortViewModel::class.java]
    }
    override val viewModel by viewModels<RssArticlesViewModel>()
    private val isPreload by lazy { activityViewModel.rssSource?.preload ?: false }
    private val orientation by lazy { resources.configuration.orientation }
    private val adapter: BaseRssArticlesAdapter<*> by lazy {
        when (activityViewModel.articleStyle) {
            1 -> RssArticlesAdapter1(requireContext(), this@RssArticlesFragment)
            2 -> RssArticlesAdapter2(requireContext(), this@RssArticlesFragment)
            4 -> RssArticlesAdapter4(requireContext(), this@RssArticlesFragment)
            3 -> RssArticlesAdapter3(requireContext(), this@RssArticlesFragment)
            5 -> RssArticlesAdapter5(requireContext(), this@RssArticlesFragment)
            else -> RssArticlesAdapter(requireContext(), this@RssArticlesFragment)
        }
    }
    private val loadMoreView: LoadMoreView by lazy {
        LoadMoreView(requireContext())
    }
    private var articlesFlowJob: Job? = null
    override val isGridLayout: Boolean
        get() = activityViewModel.articleStyle == 2 || activityViewModel.articleStyle == 5
    private var fullRefresh = false
    // modern-rss: 顶部覆盖顶栏（MainTopBarView）占位
    private var topOverlaySpace = 0
    private var topOverlayEnabled = false
    private val embeddedInModernRss: Boolean
        get() = parentFragment is io.legado.app.ui.main.rss.RssFragment

    // ---------------------------------------------------------------- 自由布局（articleStyle=5）专用状态
    /** 自由布局为 true；其余分支全部保持不变 */
    private val isFreeLayout: Boolean
        get() = activityViewModel.articleStyle == 5
    /** 自由布局的 LayoutManager 引用，用于尺寸回填后的重排通知 */
    private var freeLayoutManager: RssFreeGridLayoutManager? = null
    /** 首屏尺寸门控是否已执行（仅首次进入时做一次，避免每次刷新都阻塞） */
    private var firstScreenGated = false
    /** 已预取到的 position 上限；用于滚动时判断是否需要再预取一批 */
    private var prefetchHorizon = 0

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.init(arguments)
        initView()
        initData()
    }

    private fun initView() = binding.run {
        refreshLayout.setColorSchemeColors(accentColor)
        recyclerView.setEdgeEffectColor(primaryColor)
        // modern-rss: 嵌入新版订阅页时预留 MainActivity 主底部栏空间
        if (embeddedInModernRss) {
            recyclerView.applyMainBottomBarPadding(withInitialPadding = true)
        } else {
            recyclerView.applyNavigationBarPadding()
        }
        loadMoreView.setOnClickListener {
            if (!loadMoreView.isLoading) {
                scrollToBottom(true)
            }
        }
        val layoutManager = when (activityViewModel.articleStyle) {
            3 -> {
                recyclerView.setPadding(20, 0, 20, 0)
                recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
                    override fun getItemOffsets(
                        outRect: Rect,
                        view: View,
                        parent: RecyclerView,
                        state: RecyclerView.State
                    ) {
                        outRect.set(20,30,20,30)
                    }
                })
                recyclerView.itemAnimator = null
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) { //横屏三列
                    StaggeredGridLayoutManager(3, StaggeredGridLayoutManager.VERTICAL)
                } else {
                    StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
                }
            }
            2 -> {
                recyclerView.setPadding(8, 0, 8, 0)
                GridLayoutManager(requireContext(), 2)
            }
            4 -> {
                recyclerView.setPadding(4, 0, 4, 0)
                GridLayoutManager(requireContext(), 3)
            }
            5 -> {
                // 自由布局（智能相册网格）：行高统一、宽度按原图比例分配、整行精确撑满。
                // 左右各留 4dp 外边距；行内与行间的 4dp 间距由算法内部按 SPACING_DP 扣除，
                // 因此这里**不能**再加 ItemDecoration（会造成间距双重计算）。
                recyclerView.setPadding(4, 4, 4, 4)
                recyclerView.itemAnimator = null
                // 数据源以 provider 形式注入：LayoutManager 不依赖具体 Adapter 实现
                RssFreeGridLayoutManager(
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
                ).also { freeLayoutManager = it }
            }
            else -> {
                recyclerView.addItemDecoration(VerticalDivider(requireContext()))
                LinearLayoutManager(requireContext())
            }
        }
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter
        applyTopOverlaySpace()
        adapter.addFooterView {
            // loadMoreView 是跨 6 种布局 adapter 共用的单实例；若上个布局周期/adapter 仍持有
            // parent（如布局切换瞬间），先摘除再复用，否则 createViewHolder 会因
            // "ViewHolder views must not be attached when created" 抛 IllegalStateException
            (loadMoreView.parent as? ViewGroup)?.removeView(loadMoreView)
            ViewLoadMoreBinding.bind(loadMoreView)
        }
        refreshLayout.setOnRefreshListener {
            loadArticles()
        }
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (!recyclerView.canScrollVertically(1)) {
                    scrollToBottom()
                    return
                }
                if (layoutManager is StaggeredGridLayoutManager) {
                    val visibleItemCount = layoutManager.childCount
                    val totalItemCount = layoutManager.itemCount
                    val firstVisibleItemPositions = layoutManager.findFirstVisibleItemPositions(null)
                    val firstVisibleItemPosition = firstVisibleItemPositions?.minOrNull() ?: 0
                    if (isPreload  && (visibleItemCount + firstVisibleItemPosition) >= (totalItemCount - 5)) {
                        scrollToBottom()
                    }
                }
                // 自由布局：预加载源提前翻页 + 尺寸预取窗口推进（新增分支，不影响上面 0–4 样式）
                if (isFreeLayout && layoutManager is RssFreeGridLayoutManager) {
                    val lastVisible = layoutManager.findLastVisibleItemPosition()
                    if (lastVisible >= 0) {
                        // 预加载源：接近底部时提前取下一页，阈值与瀑布流保持一致（5 条）
                        if (isPreload &&
                            lastVisible >= adapter.getActualItemCount() - PRELOAD_THRESHOLD
                        ) {
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
        binding.recyclerView.clipToPadding = true
        binding.recyclerView.setPadding(
            binding.recyclerView.paddingLeft,
            topOverlaySpace,
            binding.recyclerView.paddingRight,
            binding.recyclerView.paddingBottom
        )
        binding.refreshLayout.setProgressViewOffset(
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
                    // 自由布局首屏门控：先把前若干条图片尺寸预热进缓存，再渲染，
                    // 避免首屏铺好之后因比例回填而整体跳动（仅首次、仅 articleStyle=5）
                    if (isFreeLayout && !firstScreenGated && newList.isNotEmpty()) {
                        firstScreenGated = true
                        prefetchHorizon = PREFETCH_FIRST_SCREEN
                        gateFirstScreen(newList)
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
        adapter.upResumed(isResumed)
        // 阶段8 F11：位置记忆——从播放器返回时滚动到退出时正在看的文章位置
        VideoPlay.lastPlayedArticleLink?.let { link ->
            VideoPlay.lastPlayedArticleLink = null  // 一次性使用，清除标记
            val position = adapter.getItems().indexOfFirst { it.link == link }
            if (position >= 0) {
                binding.recyclerView.scrollToPosition(position)
            }
        }
        // image-gallery-activity: 从图片浏览器返回时滚动到退出时正在看的文章位置
        ImagePlay.lastPlayedArticleLink?.let { link ->
            ImagePlay.lastPlayedArticleLink = null  // 一次性使用，清除标记
            val position = adapter.getItems().indexOfFirst { it.link == link }
            if (position >= 0) {
                binding.recyclerView.scrollToPosition(position)
            }
        }
    }

    override fun onPause() {
        isResumed = false
        adapter.upResumed(isResumed)
        super.onPause()
    }

    private fun loadArticles(fullRefresh: Boolean = false) {
        this.fullRefresh = fullRefresh
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
                    binding.recyclerView.scrollToPosition(0)
                }
            }
    }

    private fun scrollToBottom(forceLoad: Boolean = false) {
        if (viewModel.isLoading) return
        fullRefresh = false
        if ((loadMoreView.hasMore && adapter.getActualItemCount() > 0) || forceLoad) {
            loadMoreView.hasMore()
            activityViewModel.rssSource?.let {
                viewModel.loadMore(it)
            }
        }
    }

    override fun observeLiveBus() {
        viewModel.loadErrorLiveData.observe(viewLifecycleOwner) {
            loadMoreView.error(it)
        }
        viewModel.loadFinallyLiveData.observe(viewLifecycleOwner) { hasMore ->
            binding.refreshLayout.isRefreshing = false
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
        val rssArticles = adapter.getItems()
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
    }
}