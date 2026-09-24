package io.legado.app.ui.book.searchContent

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.widget.AppCompatImageView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.base.attachComposeContent
import io.legado.app.base.composeShell
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.IntentData
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.anima.RefreshProgressBar
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.AppMenuSheet
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsSearchBar
import io.legado.app.ui.widget.recycler.UpLinearLayoutManager
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.ui.widget.recycler.scroller.FastScrollRecyclerView
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 命中分布条目（F77）：一章的命中聚合，供「按章直达」列表消费。
 *
 * [firstIndex] 是本章第一条命中在结果列表中的下标（列表按章节顺序追加 ⇒ 与 adapter 下标一致）。
 */
private data class ChapterHit(
    val chapterIndex: Int,
    val chapterTitle: String,
    val count: Int,
    val firstIndex: Int
)


class SearchContentActivity :
    VMBaseActivity<ViewBinding, SearchContentViewModel>(),
    SearchContentAdapter.Callback {

    // CE 5.2（compose 包）：原 activity_search_content.xml 已退役 ⇒ 改 composeShell 工厂创建合成壳，
    // Compose 全权接管页面骨架；**四个 View 内核一律 AndroidView 原样托管**
    // （结果列表 / 进度条 / 底部信息条 / 停止 FAB）。
    override val binding: ViewBinding by lazy { composeShell(this) }
    override val viewModel by viewModels<SearchContentViewModel>()
    private val adapter by lazy { SearchContentAdapter(this, this) }
    private val mLayoutManager by lazy { UpLinearLayoutManager(this) }
    private var durChapterIndex = 0
    private var searchJob: Job? = null
    private var initJob: Job? = null
    // search-content-compose 壳层化：Compose 顶栏状态（搜索词/菜单）
    private var composeSearchQuery by mutableStateOf("")
    private var menuExpanded by mutableStateOf(false)
    private val searchFocusRequester = FocusRequester()
    // F77：命中分布（≥2 章时顶栏出现「分布」入口 → 按章直达）
    private var hitDistribution by mutableStateOf<List<ChapterHit>>(emptyList())
    private var distSheetExpanded by mutableStateOf(false)

    // CE 5.2：原 XML 的 View 机制改由 Compose 状态 + 程序化 View 驱动（逐一与原节点等价）
    //  · progressLoading    ← `refresh_progress_bar` 的 isAutoLoading（原实现从不改可见性 ⇒ 条恒挂载）
    //  · stopFabVisible     ← `fb_stop` 的 visible()/invisible()
    //  · focusRequestSignal ← 原「点信息条 → 焦点回搜索框 + 弹输入法」改为对 Compose 搜索框发聚焦请求
    private var progressLoading by mutableStateOf(false)
    private var stopFabVisible by mutableStateOf(false)
    private var focusRequestSignal by mutableIntStateOf(0)
    // 程序化创建的原 XML 节点（组合挂载时创建，由这些字段持有页面级引用）
    private var resultListView: FastScrollRecyclerView? = null
    private var infoTextView: TextView? = null
    /** 底部信息条文案：组合挂载前先落字段，挂载时回填（原 TextView 的 text 只会更晚被写入，等价）。 */
    private var infoText: String = ""
    /** 原 `initSearchResultList` 的 `scrollToPosition` 在列表尚未挂载时的待回放定位。 */
    private var pendingScrollPosition = -1

    // search-content-compose 壳层化：菜单动作 ID（原 R.id.menu_xxx，菜单资源已删除）
    private object MenuId {
        const val ENABLE_REPLACE = 1
        const val ENABLE_REGEX = 2
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeContent()
        val searchResultList = IntentData.get<List<SearchResult>>("searchResultList")
        val position = intent.getIntExtra("searchResultIndex", 0)
        val noSearchResult = searchResultList == null
        val bookUrl = intent.getStringExtra("bookUrl") ?: return
        viewModel.initBook(bookUrl) {
            initSearchResultList(searchResultList, position)
            initBook(noSearchResult)
        }
    }

    /**
     * CE 5.2：Compose 承载全页（顶栏 + 进度条 + 结果列表 + 底部信息条 + 停止 FAB）。
     *
     * 与原 XML（`activity_search_content.xml`）的**逐一对应关系**（三不影响口径）：
     *  · `compose_top_bar` → 顶部 `LegadoTheme { Column { … } }`（内容原样搬入，不再套壳 ComposeView）
     *  · `refresh_progress_bar`（2dp，无 visibility 属性 ⇒ 恒在布局中）→ `AndroidView` 托管
     *    `RefreshProgressBar`（**自绘动画条，无 Compose 等价物**）；原实现只切 `isAutoLoading`
     *    ⇒ 这里**保持恒挂载**、仅由 `progressLoading` 驱动动画（不改成条件组合，否则会少 2dp 高度）
     *  · `recyclerView`（FastScrollRecyclerView + UpLinearLayoutManager + VerticalDivider）
     *    → `AndroidView` 原样托管（View 版 adapter 仍在）
     *  · `ll_search_base_info`（48dp 信息条：TextView + 上/下箭头）→ `AndroidView` 程序化构造
     *    （保留 middle 省略 / 长按提示 / 波纹底 / 箭头 36dp 几何 / 导航栏边距）
     *  · `fb_stop`（mini FAB / 16dp 边距 / invisible）→ `AndroidView` 托管同配置 FAB；
     *    其底边距对齐原 `constraintBottom_toTopOf=ll_search_base_info`（位于信息条**之上**）
     *    ⇒ 无需再补导航栏 inset
     */
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    private fun initComposeContent() {
        binding.root.attachComposeContent {
            LegadoTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    // ---- 顶栏区（原 binding.composeTopBar 内容，逐行不变）----
                    Column {
                        GlassTopAppBar(
                            title = getString(R.string.search_content),
                            navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                            onNavClick = { finish() },
                            actions = {
                                // F77：命中跨 ≥2 章时才给「按章直达」入口（单章命中时列表本身就一眼看完，加了是仪式感）
                                if (hitDistribution.size >= 2) {
                                    IconButton(onClick = { distSheetExpanded = true }) {
                                        Icon(
                                            Icons.Default.PieChart,
                                            contentDescription = stringResource(R.string.search_content_distribution_label)
                                        )
                                    }
                                }
                                Box {
                                    IconButton(onClick = { menuExpanded = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = null)
                                    }
                                    AppDropdownMenu(
                                        expanded = menuExpanded,
                                        onDismiss = { menuExpanded = false },
                                        actions = buildMenuActions()
                                    )
                                }
                            }
                        )
                        SettingsSearchBar(
                            query = composeSearchQuery,
                            onQueryChange = { composeSearchQuery = it },
                            placeholder = getString(R.string.search),
                            onSearch = { startContentSearch(composeSearchQuery.trim()) },
                            focusRequester = searchFocusRequester
                        )
                        if (distSheetExpanded) {
                            AppMenuSheet(
                                title = getString(
                                    R.string.search_content_distribution,
                                    hitDistribution.size
                                ),
                                actions = hitDistribution.map { hit ->
                                    MenuAction(
                                        Icons.Default.Article,
                                        getString(
                                            R.string.search_content_distribution_item,
                                            hit.chapterTitle,
                                            hit.count
                                        )
                                    ) { scrollToChapter(hit) }
                                },
                                onDismiss = { distSheetExpanded = false }
                            )
                        }
                    }
                    // 点信息条 → 聚焦搜索框（原实现挂在已退役的 ComposeView 壳上：View 层焦点进不了
                    // Compose 焦点系统 ⇒ 改为对 Compose 搜索框发聚焦请求，语义等价且真正生效）
                    LaunchedEffect(focusRequestSignal) {
                        if (focusRequestSignal > 0) {
                            searchFocusRequester.requestFocus()
                        }
                    }
                    // ---- 进度条（原 refresh_progress_bar：2dp，恒在布局中，只切动画态）----
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        factory = { ctx -> RefreshProgressBar(ctx) },
                        update = { it.isAutoLoading = progressLoading }
                    )
                    // ---- 结果列表 + 停止 FAB（原 recycler_view / fb_stop）----
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx -> createResultList(ctx) }
                        )
                        AndroidView(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp),
                            factory = { ctx -> createStopFab(ctx) },
                            update = { fab ->
                                fab.visibility =
                                    if (stopFabVisible) View.VISIBLE else View.INVISIBLE
                            }
                        )
                    }
                    // ---- 底部信息条（原 ll_search_base_info：48dp + 导航栏边距）----
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding(),
                        factory = { ctx -> createSearchInfoBar(ctx) }
                    )
                }
            }
        }
    }

    /** F77：跳到某章的第一条命中（列表按章节顺序追加 ⇒ 下标与 adapter 一致） */
    private fun scrollToChapter(hit: ChapterHit) {
        distSheetExpanded = false
        if (hit.firstIndex in 0 until adapter.itemCount) {
            mLayoutManager.scrollToPositionWithOffset(hit.firstIndex, 0)
        }
    }

    // search-content-compose 壳层化：更多菜单数据（替换/正则 两个勾选项）
    private fun buildMenuActions(): List<MenuAction> {
        return buildList {
            add(MenuAction(
                Icons.Default.Settings,
                getString(R.string.replace),
                header = true
            ) {})
            add(MenuAction(
                Icons.Default.FindReplace,
                getString(R.string.replace),
                checked = SearchContentViewModel.replaceEnabled,
                onClick = { handleMenuAction(MenuId.ENABLE_REPLACE) }
            ))
            add(MenuAction(
                Icons.Default.Rule,
                getString(R.string.regex),
                checked = SearchContentViewModel.regexReplace,
                onClick = { handleMenuAction(MenuId.ENABLE_REGEX) }
            ))
        }
    }

    // search-content-compose 壳层化：菜单动作统一入口（原 onCompatOptionsItemSelected 逻辑迁移）
    private fun handleMenuAction(actionId: Int) {
        when (actionId) {
            MenuId.ENABLE_REPLACE -> {
                SearchContentViewModel.replaceEnabled = !SearchContentViewModel.replaceEnabled
            }
            MenuId.ENABLE_REGEX -> {
                SearchContentViewModel.regexReplace = !SearchContentViewModel.regexReplace
            }
        }
    }

    /**
     * 原 `recycler_view` 的程序化等价物（FastScrollRecyclerView + 上推布局 + 分割线 + View 版 adapter）。
     *
     * 组合挂载晚于 `onActivityCreated`（ComposeView 在附着窗口后才组合）⇒ `initSearchResultList`
     * 可能早于本工厂执行，此时定位请求先入 [pendingScrollPosition]，在此回放。
     */
    private fun createResultList(context: Context): FastScrollRecyclerView =
        FastScrollRecyclerView(context).apply {
            // 原 XML `android:id="@+id/recycler_view"`；程序化构造必须显式赋 id——
            // FastScroller.setLayoutParams 以 `require(recyclerViewId != View.NO_ID)` 定位宿主
            // （无 id 时挂载即抛 IllegalArgumentException，真机实测）
            id = R.id.recycler_view
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutManager = mLayoutManager
            addItemDecoration(VerticalDivider(context))
            adapter = this@SearchContentActivity.adapter
            if (pendingScrollPosition >= 0) {
                scrollToPosition(pendingScrollPosition)
                pendingScrollPosition = -1
            }
            resultListView = this
        }

    /** 原 `fb_stop` 的程序化等价物（mini 尺寸 / accent 底 / 停止图标 / 原点击语义）。 */
    private fun createStopFab(context: Context): FloatingActionButton =
        FloatingActionButton(context).apply {
            size = FloatingActionButton.SIZE_MINI
            contentDescription = getString(R.string.stop)
            setImageResource(R.drawable.ic_stop_black_24dp)
            backgroundTintList = Selector.colorBuild()
                .setDefaultColor(accentColor)
                .setPressedColor(ColorUtils.darkenColor(accentColor))
                .create()
            visibility = if (stopFabVisible) View.VISIBLE else View.INVISIBLE
            setOnClickListener {
                searchJob?.cancel()
            }
        }

    /**
     * 原 `ll_search_base_info` 的程序化等价物（F76/F77 的信息陈述条）。
     *
     * 取色沿用原 `onActivityCreated` 口径（K1：面 token 归属 = `bottomBackground` 面色；
     * 文字/箭头 = `getPrimaryTextColor(isColorLight(bottomBackground))`，与同语义既有实现双栈一致）。
     */
    private fun createSearchInfoBar(context: Context): LinearLayout {
        val bbg = bottomBackground
        val btc = getPrimaryTextColor(ColorUtils.isColorLight(bbg))
        val borderlessBg = borderlessItemBackgroundRes()
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 48.dpToPx()
            )
            setBackgroundColor(bbg)
            elevation = 3.dpToPx().toFloat()
            setPadding(10.dpToPx(), 0, 10.dpToPx(), 0)
        }
        val info = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setBackgroundResource(borderlessBg)
            ellipsize = TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dpToPx(), 0, 10.dpToPx(), 0)
            setSingleLine()
            setTextColor(btc)
            textSize = 12f
            text = infoText
            setOnClickListener { focusRequestSignal++ }
        }
        val spacer = Space(context).apply {
            layoutParams = LinearLayout.LayoutParams(20.dpToPx(), 1.dpToPx())
        }
        val toTop = createNavArrow(
            context, R.drawable.ic_arrow_drop_up, getString(R.string.go_to_top),
            btc, borderlessBg
        ) {
            mLayoutManager.scrollToPositionWithOffset(0, 0)
        }
        val toBottom = createNavArrow(
            context, R.drawable.ic_arrow_drop_down, getString(R.string.go_to_bottom),
            btc, borderlessBg
        ) {
            if (adapter.itemCount > 0) {
                mLayoutManager.scrollToPositionWithOffset(adapter.itemCount - 1, 0)
            }
        }
        bar.addView(info)
        bar.addView(spacer)
        bar.addView(toTop)
        bar.addView(toBottom)
        infoTextView = info
        return bar
    }

    /** 原信息条右侧上/下箭头（36dp、无边界波纹底、图标 tint = 主文字色、长按提示）。 */
    private fun createNavArrow(
        context: Context,
        iconRes: Int,
        label: String,
        tint: Int,
        borderlessBg: Int,
        onClick: () -> Unit
    ): AppCompatImageView = AppCompatImageView(context).apply {
        layoutParams = LinearLayout.LayoutParams(36.dpToPx(), ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundResource(borderlessBg)
        contentDescription = label
        setImageResource(iconRes)
        // 原 XML 用 android:tooltipText（API 26+ 才生效）；直接调属性会在低版本抛 NoSuchMethod
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            tooltipText = label
        }
        setColorFilter(tint)
        setOnClickListener { onClick() }
    }

    /** `?android:attr/selectableItemBackgroundBorderless` 的样式资源 id（原 XML 三处点击节点的波纹底）。 */
    private fun borderlessItemBackgroundRes(): Int {
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
        return outValue.resourceId
    }

    /** 底部信息条文案落点（原 `tv_current_search_info` 的 `post { text = … }` 等价物）。 */
    private fun setInfoText(text: String) {
        infoText = text
        val tv = infoTextView
        if (tv == null) {
            runOnUiThread { infoTextView?.text = text }
        } else {
            tv.post { tv.text = text }
        }
    }

    private fun initSearchResultList(list: List<SearchResult>?, position: Int) {
        list ?: return
        viewModel.searchResultList.addAll(list)
        viewModel.searchResultCounts = list.size
        adapter.setItems(list)
        val listView = resultListView
        if (listView == null) {
            // 组合尚未挂载：先记定位，待 createResultList 回放（原实现列表恒在场，无此分支）
            pendingScrollPosition = position
        } else {
            listView.scrollToPosition(position)
        }
        // F77：既有结果也陈述分布（不重搜也能按章直达）
        renderCoverage()
    }

    private fun initBook(submit: Boolean = true) {
        // 初始/恢复态：先陈述既有结果的命中与分布（若随后提交新搜索，会被进度文案接替）
        renderCoverage()
        viewModel.book?.let {
            initCacheFileNames(it)
            durChapterIndex = it.durChapterIndex
            intent.getStringExtra("searchWord")?.let { searchWord ->
                composeSearchQuery = searchWord
                if (submit) startContentSearch(searchWord.trim())
            }
        }
    }

    private fun initCacheFileNames(book: Book) {
        initJob = lifecycleScope.launch {
            withContext(IO) {
                viewModel.cacheChapterNames.addAll(BookHelp.getChapterFiles(book))
            }
            adapter.notifyItemRangeChanged(0, adapter.itemCount, true)
        }
    }

    override fun observeLiveBus() {
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            viewModel.book?.bookUrl?.let { bookUrl ->
                if (book.bookUrl == bookUrl) {
                    viewModel.cacheChapterNames.add(chapter.getFileName())
                    adapter.notifyItemChanged(chapter.index, true)
                }
            }
        }
    }

    fun startContentSearch(query: String) {
        // 按章节搜索内容
        if (query.isBlank()) return
        searchJob?.cancel()
        adapter.clearItems()
        viewModel.searchResultList.clear()
        viewModel.searchResultCounts = 0
        viewModel.lastQuery = query
        hitDistribution = emptyList()
        distSheetExpanded = false
        progressLoading = true
        stopFabVisible = true
        searchJob = lifecycleScope.launch(IO) {
            initJob?.join()
            // F76：把「搜了哪些章、跳过了多少章」变成可陈述的事实——本页只搜**已缓存**章节，
            // 不告知覆盖率时「没搜到」会被误读为「书里没有」（错误的负结论，危害远大于搜得慢）
            val chapters = appDb.bookChapterDao.getChapterList(viewModel.bookUrl)
            val totalChapters = chapters.size
            var searchedChapters = 0
            var skippedChapters = 0
            var lastPostedChapters = 0
            kotlin.runCatching {
                chapters.forEach { bookChapter ->
                    ensureActive()
                    if (!isLocalBook
                        && !viewModel.cacheChapterNames.contains(bookChapter.getFileName())
                    ) {
                        skippedChapters++
                        return@forEach
                    }
                    val searchResults = viewModel.searchChapter(query, bookChapter)
                    searchedChapters++
                    ensureActive()
                    if (searchResults.isNotEmpty()) {
                        viewModel.searchResultList.addAll(searchResults)
                        // 原实现借 `tv_current_search_info.post{}` 回主线程（列表/文案均须主线程）
                        runOnUiThread {
                            adapter.addItems(searchResults)
                        }
                    }
                    // 进度节流：首章即刻出态（让用户马上看到「在搜了」），其后每 10 章刷一次
                    if (searchedChapters == 1
                        || searchedChapters - lastPostedChapters >= PROGRESS_STEP_CHAPTERS
                    ) {
                        lastPostedChapters = searchedChapters
                        renderProgress(searchedChapters, totalChapters)
                    }
                }
                if (viewModel.searchResultCounts == 0) {
                    val noSearchResult =
                        SearchResult(resultText = getString(R.string.search_content_empty))
                    runOnUiThread {
                        adapter.addItem(noSearchResult)
                    }
                }
            }.onFailure {
                AppLog.put("全文搜索出错\n${it.localizedMessage}", it)
            }
            runOnUiThread {
                stopFabVisible = false
                progressLoading = false
                // 结果与覆盖率一并落定：命中数 / 分布章数 / 已搜章数（+被跳过的未缓存章）
                renderCoverage(searchedChapters, skippedChapters)
            }
        }
    }

    /** F76：搜索进行中的确定性进度（已搜 N/M 章 + 实时命中数） */
    private fun renderProgress(searched: Int, total: Int) {
        setInfoText(
            getString(
                R.string.search_content_progress, searched, total, viewModel.searchResultCounts
            )
        )
    }

    /**
     * F76/F77：搜索落定后的覆盖率摘要 + 命中分布重算。
     *
     * [searched] / [skipped] 为本次搜索的实况；从阅读页带回既有结果（未重新搜索）时传 -1，
     * 此时只陈述「命中 + 分布」而不编造覆盖率。
     */
    private fun renderCoverage(searched: Int = -1, skipped: Int = -1) {
        updateDistribution()
        val hitCount = viewModel.searchResultList.size
        val chapterCount = hitDistribution.size
        val text = if (searched >= 0) {
            val base = getString(
                R.string.search_content_coverage_scanned, hitCount, chapterCount, searched
            )
            // 有跳过才提「边界 + 出路」，全覆盖时不提（不给用户制造无谓焦虑）
            if (skipped > 0) base + getString(R.string.search_content_coverage_uncached, skipped)
            else base
        } else {
            getString(R.string.search_content_coverage, hitCount, chapterCount)
        }
        setInfoText(text)
    }

    /**
     * F77：按章聚合命中（章节升序 = 阅读顺序；同章内下标连续 ⇒ 取首条即滚动锚点）。
     *
     * 纯展示层聚合，零数据层改动；`searchResultList` 按章追加 ⇒ 分组后天然有序，不再排序。
     */
    private fun updateDistribution() {
        hitDistribution = viewModel.searchResultList
            .withIndex()
            .groupBy { it.value.chapterIndex }
            .map { (chapterIndex, items) ->
                ChapterHit(
                    chapterIndex = chapterIndex,
                    chapterTitle = items.first().value.chapterTitle,
                    count = items.size,
                    firstIndex = items.first().index
                )
            }
    }

    private val isLocalBook: Boolean
        get() = viewModel.book?.isLocal == true

    override fun openSearchResult(searchResult: SearchResult, index: Int) {
        searchJob?.cancel()
        postEvent(EventBus.SEARCH_RESULT, viewModel.searchResultList as List<SearchResult>)
        val searchData = Intent()
        val key = System.currentTimeMillis()
        IntentData.put("searchResult$key", searchResult)
        IntentData.put("searchResultList$key", viewModel.searchResultList)
        searchData.putExtra("key", key)
        searchData.putExtra("index", index)
        setResult(RESULT_OK, searchData)
        finish()
    }

    override fun durChapterIndex(): Int {
        return durChapterIndex
    }

    private companion object {
        /** F76：进度刷新步长（每搜过这么多章刷新一次底部信息栏） */
        const val PROGRESS_STEP_CHAPTERS = 10
    }

}