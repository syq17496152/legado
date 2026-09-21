package io.legado.app.ui.book.searchContent

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ActivitySearchContentBinding
import io.legado.app.help.IntentData
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.AppMenuSheet
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsSearchBar
import io.legado.app.ui.widget.recycler.UpLinearLayoutManager
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyNavigationBarMargin
import io.legado.app.utils.invisible
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.systemservices.inputMethodManager

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
    VMBaseActivity<ActivitySearchContentBinding, SearchContentViewModel>(),
    SearchContentAdapter.Callback {

    override val binding by viewBinding(ActivitySearchContentBinding::inflate)
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

    // search-content-compose 壳层化：菜单动作 ID（原 R.id.menu_xxx，菜单资源已删除）
    private object MenuId {
        const val ENABLE_REPLACE = 1
        const val ENABLE_REGEX = 2
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val bbg = bottomBackground
        val btc = getPrimaryTextColor(ColorUtils.isColorLight(bbg))
        binding.llSearchBaseInfo.setBackgroundColor(bbg)
        binding.llSearchBaseInfo.applyNavigationBarMargin()
        binding.tvCurrentSearchInfo.setTextColor(btc)
        binding.ivSearchContentTop.setColorFilter(btc)
        binding.ivSearchContentBottom.setColorFilter(btc)
        val searchResultList = IntentData.get<List<SearchResult>>("searchResultList")
        val position = intent.getIntExtra("searchResultIndex", 0)
        val noSearchResult = searchResultList == null
        initComposeTopBar()
        initRecyclerView()
        initView()
        val bookUrl = intent.getStringExtra("bookUrl") ?: return
        viewModel.initBook(bookUrl) {
            initSearchResultList(searchResultList, position)
            initBook(noSearchResult)
        }
    }

    // search-content-compose 壳层化：顶栏（GlassTopAppBar 标题 + 搜索 SettingsSearchBar + 更多菜单 AppDropdownMenu）
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
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

    private fun initSearchResultList(list: List<SearchResult>?, position: Int) {
        list ?: return
        viewModel.searchResultList.addAll(list)
        viewModel.searchResultCounts = list.size
        adapter.setItems(list)
        binding.recyclerView.scrollToPosition(position)
        // F77：既有结果也陈述分布（不重搜也能按章直达）
        renderCoverage()
    }

    private fun initRecyclerView() {
        binding.recyclerView.layoutManager = mLayoutManager
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.adapter = adapter
    }

    private fun initView() {
        binding.ivSearchContentTop.setOnClickListener {
            mLayoutManager.scrollToPositionWithOffset(0, 0)
        }
        binding.ivSearchContentBottom.setOnClickListener {
            if (adapter.itemCount > 0) {
                mLayoutManager.scrollToPositionWithOffset(adapter.itemCount - 1, 0)
            }
        }
        binding.tvCurrentSearchInfo.setOnClickListener {
            searchFocusRequester.requestFocus()
            inputMethodManager.showSoftInput(
                binding.composeTopBar, InputMethodManager.SHOW_IMPLICIT
            )
        }
        binding.fbStop.setOnClickListener {
            searchJob?.cancel()
        }
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
        binding.refreshProgressBar.isAutoLoading = true
        binding.fbStop.visible()
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
                        binding.tvCurrentSearchInfo.post {
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
                    binding.tvCurrentSearchInfo.post {
                        adapter.addItem(noSearchResult)
                    }
                }
            }.onFailure {
                AppLog.put("全文搜索出错\n${it.localizedMessage}", it)
            }
            binding.tvCurrentSearchInfo.post {
                binding.fbStop.invisible()
                binding.refreshProgressBar.isAutoLoading = false
                // 结果与覆盖率一并落定：命中数 / 分布章数 / 已搜章数（+被跳过的未缓存章）
                renderCoverage(searchedChapters, skippedChapters)
            }
        }
    }

    /** F76：搜索进行中的确定性进度（已搜 N/M 章 + 实时命中数） */
    private fun renderProgress(searched: Int, total: Int) {
        val text = getString(
            R.string.search_content_progress, searched, total, viewModel.searchResultCounts
        )
        binding.tvCurrentSearchInfo.post {
            binding.tvCurrentSearchInfo.text = text
        }
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
        binding.tvCurrentSearchInfo.post {
            binding.tvCurrentSearchInfo.text = text
        }
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
