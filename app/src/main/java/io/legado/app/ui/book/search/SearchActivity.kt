package io.legado.app.ui.book.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View.GONE
import android.view.View.VISIBLE
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
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.databinding.ActivityBookSearchBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsSearchBar
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyNavigationBarMargin
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import kotlin.math.abs

class SearchActivity : VMBaseActivity<ActivityBookSearchBinding, SearchViewModel>(),
    BookAdapter.CallBack,
    HistoryKeyAdapter.CallBack,
    SearchScopeDialog.Callback,
    SearchAdapter.CallBack {

    override val binding by viewBinding(ActivityBookSearchBinding::inflate)
    override val viewModel by viewModels<SearchViewModel>()

    private val adapter by lazy { SearchAdapter(this, this) }
    private val bookAdapter by lazy {
        BookAdapter(this, this).apply {
            setHasStableIds(true)
        }
    }
    private val historyKeyAdapter by lazy {
        HistoryKeyAdapter(this, this).apply {
            setHasStableIds(true)
        }
    }
    // book-search-compose 壳层化：Compose 顶栏搜索/菜单状态（替代原 SearchView + Menu）
    private var composeSearchQuery by mutableStateOf("")
    private var menuExpanded by mutableStateOf(false)
    // 延迟初始化：构造期 getPrefBoolean → context 尚未注入 → NPE（与 ConfigActivity 同源问题），在 onActivityCreated 赋值
    private var composePrecisionChecked by mutableStateOf(false)
    private var composeGroups by mutableStateOf(listOf<String>())
    private var historyFlowJob: Job? = null
    private var booksFlowJob: Job? = null
    private var isManualStopSearch = false

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.llInputHelp.setBackgroundColor(backgroundColor)
        composePrecisionChecked = getPrefBoolean(PreferKey.precisionSearch)
        initRecyclerView()
        initComposeTopBar()
        initOtherView()
        initData()
        receiptIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiptIntent(intent)
    }

    // book-search-compose 壳层化：顶栏（GlassTopAppBar + 搜索 SettingsSearchBar + 更多菜单 AppDropdownMenu）
    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                Column {
                    GlassTopAppBar(
                        title = getString(R.string.search),
                        navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        onNavClick = { finish() },
                        actions = {
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
                        onQueryChange = {
                            composeSearchQuery = it
                            // 输入变化：停止当前搜索，隐藏 FAB，更新搜索历史与书架搜索
                            viewModel.stop()
                            binding.fbStartStop.invisible()
                            upHistory(it.trim())
                            visibleInputHelp(true)
                        },
                        placeholder = getString(R.string.search_book_key),
                        onSearch = { submitSearch(composeSearchQuery) }
                    )
                }
            }
        }
    }

    // 搜索页菜单项 id 常量（menu XML 已随 Compose 化清理，原 R.id.menu_* 改为本地常量）
    private object MenuId {
        const val PRECISION_SEARCH = 1201
        const val SEARCH_SCOPE = 1202
    }

    // book-search-compose 壳层化：更多菜单数据（精准搜索 + 分组筛选 + 管理）
    private fun buildMenuActions(): List<MenuAction> {
        val searchScopeNames = viewModel.searchScope.displayNames
        return buildList {
            // 搜索选项分组
            add(MenuAction(Icons.Default.Tune, getString(R.string.search_options), header = true) {})
            add(MenuAction(
                Icons.Default.Search,
                getString(R.string.precision_search),
                checked = composePrecisionChecked,
                onClick = { handleMenuAction(MenuId.PRECISION_SEARCH) }
            ))
            add(MenuAction(
                Icons.Default.ManageSearch,
                getString(R.string.groups_or_source),
                onClick = { handleMenuAction(MenuId.SEARCH_SCOPE) }
            ))
            // 分组筛选分组
            add(MenuAction(Icons.Default.Folder, getString(R.string.groups_or_source), header = true) {})
            if (!viewModel.searchScope.isAll()) {
                searchScopeNames.forEach { name ->
                    add(MenuAction(
                        Icons.Default.Folder,
                        name,
                        checked = true,
                        onClick = { handleGroupSelect(name, remove = true) }
                    ))
                }
            }
            add(MenuAction(
                Icons.Default.AllInclusive,
                getString(R.string.all_source),
                checked = viewModel.searchScope.isAll(),
                onClick = { handleMenuAction(R.id.menu_1) }
            ))
            composeGroups.forEach { group ->
                if (!searchScopeNames.contains(group)) {
                    add(MenuAction(
                        Icons.Default.Folder,
                        group,
                        onClick = { handleGroupSelect(group, remove = false) }
                    ))
                }
            }
            // 管理分组
            add(MenuAction(Icons.Default.Settings, getString(R.string.more), header = true) {})
            add(MenuAction(
                Icons.Default.ManageSearch,
                getString(R.string.book_source_manage),
                onClick = { handleMenuAction(R.id.menu_source_manage) }
            ))
            add(MenuAction(
                Icons.Default.Info,
                getString(R.string.log),
                onClick = { handleMenuAction(R.id.menu_log) }
            ))
        }
    }

    // book-search-compose 壳层化：菜单动作统一入口（原 onCompatOptionsItemSelected 逻辑迁移）
    private fun handleMenuAction(actionId: Int) {
        when (actionId) {
            MenuId.PRECISION_SEARCH -> togglePrecisionSearch()
            MenuId.SEARCH_SCOPE -> alertSearchScope()
            R.id.menu_source_manage -> startActivity<BookSourceActivity>()
            R.id.menu_log -> showDialogFragment(AppLogDialog())
            R.id.menu_1 -> {
                viewModel.searchScope.update("")
                reSearchIfNeeded()
            }
        }
    }

    // 精准搜索：切换持久化状态并重新搜索
    private fun togglePrecisionSearch() {
        val newValue = !getPrefBoolean(PreferKey.precisionSearch)
        putPrefBoolean(PreferKey.precisionSearch, newValue)
        composePrecisionChecked = newValue
        reSearchIfNeeded()
    }

    // 分组筛选：勾选态切换（remove=true 移除勾选，false 选中分组）
    private fun handleGroupSelect(name: String, remove: Boolean) {
        if (remove) {
            viewModel.searchScope.remove(name)
        } else {
            viewModel.searchScope.update(name)
        }
        reSearchIfNeeded()
    }

    // 分组/精准搜索变化后若已有搜索词则重新搜索
    private fun reSearchIfNeeded() {
        val query = composeSearchQuery.trim()
        if (query.isNotEmpty()) {
            viewModel.searchKey = ""
            viewModel.search(query)
        }
    }

    // book-search-compose 壳层化：提交搜索（原 SearchView onQueryTextSubmit 逻辑迁移）
    private fun submitSearch(query: String) {
        query.trim().let { searchKey ->
            isManualStopSearch = false
            viewModel.saveSearchKey(searchKey)
            viewModel.searchKey = ""
            viewModel.search(searchKey)
        }
        visibleInputHelp(false)
    }

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.rvBookshelfSearch.setEdgeEffectColor(primaryColor)
        binding.rvHistoryKey.setEdgeEffectColor(primaryColor)
        binding.rvBookshelfSearch.layoutManager = FlexboxLayoutManager(this)
        binding.rvBookshelfSearch.adapter = bookAdapter
        binding.rvBookshelfSearch.applyNavigationBarMargin()
        binding.rvHistoryKey.layoutManager = FlexboxLayoutManager(this)
        binding.rvHistoryKey.adapter = historyKeyAdapter
        binding.rvHistoryKey.applyNavigationBarMargin()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.applyNavigationBarPadding()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                if (positionStart == 0) {
                    binding.recyclerView.scrollToPosition(0)
                }
            }

            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
                super.onItemRangeMoved(fromPosition, toPosition, itemCount)
                if (toPosition == 0) {
                    binding.recyclerView.scrollToPosition(0)
                }
            }
        })
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (!recyclerView.canScrollVertically(1)) {
                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    val lastPosition = layoutManager.findLastCompletelyVisibleItemPosition()
                    if (lastPosition == RecyclerView.NO_POSITION) {
                        return
                    }
                    val lastView = layoutManager.findViewByPosition(lastPosition)
                    if (lastView == null) {
                        scrollToBottom()
                        return
                    }
                    val bottom =
                        abs(lastView.bottom - recyclerView.height) - recyclerView.paddingBottom
                    if (bottom <= 1) {
                        scrollToBottom()
                    }
                }
            }
        })
    }

    private fun initOtherView() {
        binding.fbStartStop.backgroundTintList =
            Selector.colorBuild()
                .setDefaultColor(accentColor)
                .setPressedColor(ColorUtils.darkenColor(accentColor))
                .create()
        binding.fbStartStop.setOnClickListener {
            if (viewModel.isSearchLiveData.value == true) {
                isManualStopSearch = true
                viewModel.stop()
                binding.refreshProgressBar.isAutoLoading = false
            } else {
                viewModel.search("")
            }
        }
        binding.fbStartStop.applyNavigationBarMargin(true)
        binding.tvClearHistory.setOnClickListener { alertClearHistory() }
    }

    private fun initData() {
        viewModel.searchScope.stateLiveData.observe(this) {
            if (!binding.llInputHelp.isVisible) {
                composeSearchQuery.trim().let { query ->
                    if (query.isNotEmpty()) {
                        submitSearch(query)
                    }
                }
            }
        }
        viewModel.isSearchLiveData.observe(this) {
            if (it) {
                startSearch()
            } else {
                searchFinally()
            }
        }
        viewModel.searchBookLiveData.observe(this) {
            adapter.setItems(it)
        }
        lifecycleScope.launch {
            appDb.bookSourceDao.flowEnabledGroups().flowOn(IO).collect {
                composeGroups = it
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.resume()
                try {
                    awaitCancellation()
                } finally {
                    viewModel.pause()
                }
            }
        }
    }

    /**
     * 处理传入数据
     */
    private fun receiptIntent(intent: Intent? = null) {
        val searchScope = intent?.getStringExtra("searchScope")
        searchScope?.let {
            viewModel.searchScope.update(searchScope, postValue = false, save = false)
        }
        val key = intent?.getStringExtra("key")
        if (key.isNullOrBlank()) {
            // 无 key：聚焦搜索框（Compose 顶栏搜索框保持焦点状态）
            binding.composeTopBar.requestFocus()
        } else {
            composeSearchQuery = key
            submitSearch(key)
        }
    }

    /**
     * 滚动到底部事件
     */
    private fun scrollToBottom() {
        if (isManualStopSearch) {
            return
        }
        if (viewModel.isSearchLiveData.value == false
            && viewModel.searchKey.isNotEmpty()
            && viewModel.hasMore
        ) {
            viewModel.search("")
        }
    }

    /**
     * 打开关闭输入帮助
     */
    private fun visibleInputHelp(visible: Boolean) {
        if (visible) {
            upHistory(composeSearchQuery.trim())
            binding.llInputHelp.visibility = VISIBLE
        } else {
            binding.llInputHelp.visibility = GONE
        }
    }

    /**
     * 更新搜索历史
     */
    private fun upHistory(key: String? = null) {
        booksFlowJob?.cancel()
        booksFlowJob = lifecycleScope.launch {
            if (key.isNullOrBlank()) {
                binding.tvBookShow.gone()
                binding.rvBookshelfSearch.gone()
            } else {
                appDb.bookDao.flowSearch(key).conflate().collect {
                    if (it.isEmpty()) {
                        binding.tvBookShow.gone()
                        binding.rvBookshelfSearch.gone()
                    } else {
                        binding.tvBookShow.visible()
                        binding.rvBookshelfSearch.visible()
                    }
                    bookAdapter.setItems(it)
                }
            }
        }
        historyFlowJob?.cancel()
        historyFlowJob = lifecycleScope.launch {
            when {
                key.isNullOrBlank() -> appDb.searchKeywordDao.flowByTime(0)
                else -> appDb.searchKeywordDao.flowSearch(0, key)
            }.catch {
                AppLog.put("搜索界面获取搜索历史数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                historyKeyAdapter.setItems(it)
                if (it.isEmpty()) {
                    binding.tvClearHistory.invisible()
                } else {
                    binding.tvClearHistory.visible()
                }
            }
        }
    }

    /**
     * 开始搜索
     */
    private fun startSearch() {
        binding.refreshProgressBar.visible()
        binding.refreshProgressBar.isAutoLoading = true
        binding.fbStartStop.setImageResource(R.drawable.ic_stop_black_24dp)
        binding.fbStartStop.visible()
    }

    /**
     * 搜索结束
     */
    private fun searchFinally() {
        binding.refreshProgressBar.isAutoLoading = false
        binding.refreshProgressBar.gone()
        if (!isManualStopSearch && viewModel.hasMore) {
            binding.fbStartStop.setImageResource(R.drawable.ic_play_24dp)
        } else {
            binding.fbStartStop.invisible()
        }
    }

    override fun observeLiveBus() {
        viewModel.upAdapterLiveData.observe(this) {
            adapter.notifyItemRangeChanged(0, adapter.itemCount, bundleOf(it to null))
        }
        viewModel.searchFinishLiveData.observe(this) { isEmpty ->
            if (!isEmpty || viewModel.searchScope.isAll()) return@observe
            alert("搜索结果为空") {
                val precisionSearch = appCtx.getPrefBoolean(PreferKey.precisionSearch)
                val displayScope = viewModel.searchScope.display
                if (precisionSearch) {
                    setMessage("${displayScope}分组搜索结果为空，是否关闭精准搜索？")
                    yesButton {
                        appCtx.putPrefBoolean(PreferKey.precisionSearch, false)
                        composePrecisionChecked = false
                        viewModel.searchKey = ""
                        viewModel.search(composeSearchQuery.trim())
                    }
                } else {
                    setMessage("${displayScope}分组搜索结果为空，是否切换到全部分组？")
                    yesButton {
                        viewModel.searchScope.update("")
                    }
                }
                noButton()
            }
        }
    }

    /**
     * 显示书籍详情
     */
    override fun showBookInfo(name: String, author: String, bookUrl: String) {
        startActivity<BookInfoActivity> {
            putExtra("name", name)
            putExtra("author", author)
            putExtra("bookUrl", bookUrl)
        }
    }

    /**
     * 是否已经加入书架
     */
    override fun isInBookshelf(book: SearchBook): Boolean {
        return viewModel.isInBookShelf(book)
    }

    /**
     * 显示书籍详情
     */
    override fun showBookInfo(book: Book) {
        showBookInfo(book.name, book.author, book.bookUrl)
    }

    /**
     * 点击历史关键字
     */
    override fun searchHistory(key: String) {
        lifecycleScope.launch {
            when {
                composeSearchQuery == key -> {
                    composeSearchQuery = key
                    submitSearch(key)
                }

                withContext(IO) { appDb.bookDao.findByName(key).isEmpty() } -> {
                    composeSearchQuery = key
                    submitSearch(key)
                }

                else -> {
                    composeSearchQuery = key
                    upHistory(key)
                }
            }
        }
    }

    /**
     * 删除搜索记录
     */
    override fun deleteHistory(searchKeyword: SearchKeyword) {
        viewModel.deleteHistory(searchKeyword)
    }


    override fun onSearchScopeOk(searchScope: SearchScope) {
        viewModel.searchScope.update(searchScope.toString())
    }

    private fun alertSearchScope() {
        showDialogFragment<SearchScopeDialog>()
    }

    private fun alertClearHistory() {
        alert(R.string.draw) {
            setMessage(R.string.sure_clear_search_history)
            yesButton {
                viewModel.clearHistory()
            }
            noButton()
        }
    }

    // book-search-compose 壳层化：Compose 顶栏无 searchView 焦点拦截，直接退出
    override fun finish() {
        super.finish()
    }

    companion object {

        fun start(context: Context, key: String?, searchScope: String? = null) {
            context.startActivity<SearchActivity> {
                putExtra("key", key)
                putExtra("searchScope", searchScope)
            }
        }

        fun start(context: Context, source: BookSource, key: String? = null) {
            context.startActivity<SearchActivity> {
                putExtra("key", key)
                putExtra("searchScope", SearchScope(source).toString())
            }
        }

        fun start(context: Context, source: BookSourcePart, key: String? = null) {
            context.startActivity<SearchActivity> {
                putExtra("key", key)
                putExtra("searchScope", SearchScope(source).toString())
            }
        }

    }
}