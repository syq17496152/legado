package io.legado.app.ui.book.search

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.TextView
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
import io.legado.app.help.book.isVideo
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.TopBarSearchStyle
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.themeMutedColorOrDefault
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.SearchBookOpenHelper
import io.legado.app.ui.book.info.BookInfoNavigator
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyNavigationBarMargin
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.gone
import io.legado.app.utils.imeHeight
import io.legado.app.utils.invisible
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

class SearchActivity : VMBaseActivity<ActivityBookSearchBinding, SearchViewModel>(),
    SearchScopeDialog.Callback {

    override val binding by viewBinding(ActivityBookSearchBinding::inflate)
    override val viewModel by viewModels<SearchViewModel>()

    // 搜索结果列表已 Compose 化(SearchResultScreen)，用快照状态驱动，替代原 SearchAdapter。
    private val searchResults = mutableStateListOf<SearchBook>()
    private val bookshelfTick = mutableIntStateOf(0)
    private val resultScrollToTopSignal = mutableIntStateOf(0)
    // 输入帮助区(书架命中 + 搜索历史)已 Compose 化，用快照状态驱动，替代原 BookAdapter/HistoryKeyAdapter。
    private val bookshelfHintBooks = mutableStateListOf<Book>()
    private val historyKeywords = mutableStateListOf<SearchKeyword>()
    private val searchView: SearchView by lazy { binding.searchView }
    private var groups: List<String>? = null
    private var historyFlowJob: Job? = null
    private var booksFlowJob: Job? = null
    private var modernMenuPopup: ModernActionPopup.Handle? = null
    private var isManualStopSearch = false
    private var sourceGroupBarBaseBottomMargin = 0
    private var inputHelpBaseBottomMargin = 0
    private var currentBottomInset = 0
    private var currentImeInset = 0
    private var rootBaseTopPadding = 0
    private val searchEditText: TextView?
        get() = searchView.findViewById(androidx.appcompat.R.id.search_src_text)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initTopBar()
        initRecyclerView()
        initSearchView()
        initOtherView()
        initData()
        receiptIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiptIntent(intent)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_precision_search -> {
                putPrefBoolean(
                    PreferKey.precisionSearch,
                    !getPrefBoolean(PreferKey.precisionSearch)
                )
                searchView.query?.toString()?.trim()?.let {
                    searchView.setQuery(it, true)
                }
            }

            R.id.menu_search_scope -> alertSearchScope()
            R.id.menu_source_manage -> startActivity<BookSourceActivity>()
            R.id.menu_log -> showDialogFragment(AppLogDialog())
            R.id.menu_1 -> viewModel.searchScope.update("")
            else -> {
                if (item.groupId == R.id.menu_group_1) {
                    viewModel.searchScope.remove(item.title.toString())
                } else if (item.groupId == R.id.menu_group_2) {
                    viewModel.searchScope.update(item.title.toString())
                }
            }
        }
        return true
    }

    private fun initTopBar() {
        rootBaseTopPadding = binding.root.paddingTop
        binding.btnMenu.setColorFilter(secondaryTextColor)
        TopBarSearchStyle.apply(binding.searchView)
        updateSourceGroupTags()
        binding.btnMenu.setOnClickListener {
            showSearchMenu(it)
        }
    }

    private fun showSearchMenu(anchor: View) {
        modernMenuPopup = ModernActionPopup.showFromMenu(
            anchor = anchor,
            menuRes = R.menu.book_search,
            previousPopup = modernMenuPopup,
            prepare = {
                prepareSearchMenu(this)
            }
        ) {
            onCompatOptionsItemSelected(it)
        }
    }

    private fun prepareSearchMenu(menu: Menu) {
        menu.findItem(R.id.menu_precision_search)?.isChecked = getPrefBoolean(PreferKey.precisionSearch)
        menu.removeGroup(R.id.menu_group_1)
        menu.removeGroup(R.id.menu_group_2)
        var hasChecked = false
        val searchScopeNames = viewModel.searchScope.displayNames
        if (viewModel.searchScope.isSource()) {
            menu.add(R.id.menu_group_1, Menu.NONE, Menu.NONE, searchScopeNames.first()).apply {
                isChecked = true
                hasChecked = true
            }
        }
        val allSourceMenu =
            menu.add(R.id.menu_group_2, R.id.menu_1, Menu.NONE, getString(R.string.all_source))
                .apply {
                    if (searchScopeNames.isEmpty()) {
                        isChecked = true
                        hasChecked = true
                    }
                }
        groups?.forEach {
            if (searchScopeNames.contains(it)) {
                menu.add(R.id.menu_group_1, Menu.NONE, Menu.NONE, it).apply {
                    isChecked = true
                    hasChecked = true
                }
            } else {
                menu.add(R.id.menu_group_2, Menu.NONE, Menu.NONE, it)
            }
        }
        if (!hasChecked) {
            viewModel.searchScope.update("")
            allSourceMenu.isChecked = true
        }
        menu.setGroupCheckable(R.id.menu_group_1, true, false)
        menu.setGroupCheckable(R.id.menu_group_2, true, true)
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchEditText?.apply {
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(primaryTextColor)
            setHintTextColor(secondaryTextColor)
            setOnFocusChangeListener { _, _ ->
                updateKeyboardGroupBarVisible()
            }
        }
        searchView.findViewById<android.view.View>(androidx.appcompat.R.id.search_plate)
            ?.setBackgroundColor(Color.TRANSPARENT)
        searchView.findViewById<android.view.View>(androidx.appcompat.R.id.search_edit_frame)
            ?.setBackgroundColor(Color.TRANSPARENT)
        searchView.findViewById<android.view.View>(androidx.appcompat.R.id.submit_area)
            ?.setBackgroundColor(Color.TRANSPARENT)
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.search_book_key)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                searchView.clearFocus()
                query.trim().let { searchKey ->
                    isManualStopSearch = false
                    viewModel.saveSearchKey(searchKey)
                    viewModel.searchKey = ""
                    viewModel.search(searchKey)
                }
                visibleInputHelp(false)
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                viewModel.stop()
                binding.fbStartStop.invisible()
                searchEditText?.apply {
                    setTextColor(primaryTextColor)
                    setHintTextColor(secondaryTextColor)
                }
                upHistory(newText.trim())
                return false
            }
        })
        searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
            updateKeyboardGroupBarVisible()
            if (binding.refreshProgressBar.isAutoLoading || (!hasFocus && searchResults.isNotEmpty() && searchView.query.isNotBlank())) {
                visibleInputHelp(false)
            } else {
                visibleInputHelp(true)
            }
        }
        visibleInputHelp(true)
    }

    private fun initRecyclerView() {
        initInputHelpCompose()
        initResultsCompose()
    }

    private fun initResultsCompose() {
        binding.composeResults.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeResults.setContent {
            SearchResultScreen(
                books = searchResults,
                isLoading = viewModel.isSearchLiveData.value == true,
                hasMore = viewModel.hasMore,
                scrollToTopSignal = resultScrollToTopSignal.intValue,
                bookshelfTick = bookshelfTick.intValue,
                isInBookshelf = { isInBookshelf(it) },
                lifecycle = lifecycle,
                onBookClick = { showBookInfo(it) },
                onLoadMore = { scrollToBottom() }
            )
        }
    }

    private fun initInputHelpCompose() {
        binding.composeInputHelp.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeInputHelp.setContent {
            SearchInputHelpScreen(
                bookshelfBooks = bookshelfHintBooks,
                historyKeywords = historyKeywords,
                onBookClick = { showBookInfo(it) },
                onHistoryClick = { searchHistory(it) },
                onHistoryDelete = { deleteHistory(it) },
                onClearHistory = { alertClearHistory() }
            )
        }
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
        sourceGroupBarBaseBottomMargin =
            (binding.hsvSourceGroupBar.layoutParams as? ViewGroup.MarginLayoutParams)
                ?.bottomMargin ?: 0
        inputHelpBaseBottomMargin =
            (binding.llInputHelp.layoutParams as? ViewGroup.MarginLayoutParams)
                ?.bottomMargin ?: 0
        binding.hsvSourceGroupBar.bringToFront()
        binding.root.setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
            val statusInset = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.root.setPadding(
                binding.root.paddingLeft,
                rootBaseTopPadding + statusInset,
                binding.root.paddingRight,
                binding.root.paddingBottom
            )
            val imeInset = windowInsets.imeHeight
            currentImeInset = imeInset
            val bottomInset = if (imeInset > 0) imeInset else windowInsets.navigationBarHeight
            currentBottomInset = bottomInset
            binding.hsvSourceGroupBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = sourceGroupBarBaseBottomMargin + bottomInset
            }
            updateKeyboardGroupBarVisible()
            updateInputHelpBottomMargin()
            windowInsets
        }
    }

    private fun initData() {
        viewModel.searchScope.stateLiveData.observe(this) {
            updateSourceGroupTags()
            updateKeyboardGroupBarVisible()
        }
        viewModel.isSearchLiveData.observe(this) {
            if (it) {
                startSearch()
            } else {
                searchFinally()
            }
        }
        viewModel.searchBookLiveData.observe(this) {
            // 新搜索会以更短的列表重置(流式追加则只增长)，据此判断是否需要回到顶部。
            val isFreshSearch = it.size < searchResults.size ||
                (it.isNotEmpty() && searchResults.isNotEmpty() && it.first().bookUrl != searchResults.first().bookUrl)
            searchResults.clear()
            searchResults.addAll(it)
            if (isFreshSearch) {
                resultScrollToTopSignal.intValue++
            }
        }
        lifecycleScope.launch {
            appDb.bookSourceDao.flowEnabledGroups().collect {
                groups = it
                updateSourceGroupTags()
                updateKeyboardGroupBarVisible()
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
            searchEditText?.requestFocus()
        } else {
            searchView.setQuery(key, true)
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
            upHistory(searchView.query.toString())
            updateSourceGroupTags()
            updateKeyboardGroupBarVisible()
            binding.llInputHelp.visibility = VISIBLE
        } else {
            binding.llInputHelp.visibility = GONE
        }
    }

    private fun updateSourceGroupTags() {
        val currentGroups = groups.orEmpty()
        val container = binding.llSourceGroupTags
        container.removeAllViews()
        if (currentGroups.isEmpty()) {
            binding.hsvSourceGroupBar.gone()
            return
        }
        updateKeyboardGroupBarVisible()
        val selectedNames = if (viewModel.searchScope.isSource()) {
            emptySet()
        } else {
            viewModel.searchScope.toString().splitNotBlank(",").toSet()
        }
        container.addView(createSourceGroupChip(getString(R.string.all_source), selectedNames.isEmpty()) {
            updateSearchScopeFromTag("")
        })
        currentGroups.forEach { group ->
            container.addView(createSourceGroupChip(group, selectedNames.contains(group)) {
                updateSearchScopeFromTag(group)
            })
        }
    }

    private fun updateKeyboardGroupBarVisible() {
        val show = currentImeInset > 0 && groups.orEmpty().isNotEmpty()
        val oldVisible = binding.hsvSourceGroupBar.isVisible
        if (show) {
            binding.hsvSourceGroupBar.bringToFront()
            binding.hsvSourceGroupBar.visible()
        } else {
            binding.hsvSourceGroupBar.gone()
        }
        if (oldVisible != binding.hsvSourceGroupBar.isVisible) {
            updateInputHelpBottomMargin()
        }
    }

    private fun updateInputHelpBottomMargin() {
        val groupBarHeight = binding.hsvSourceGroupBar.height
            .takeIf { it > 0 } ?: 46.dpToPx()
        val inputHelpBottomInset = currentBottomInset +
            if (binding.hsvSourceGroupBar.isVisible) groupBarHeight else 0
        binding.llInputHelp.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = inputHelpBaseBottomMargin + inputHelpBottomInset
        }
    }

    private fun createSourceGroupChip(
        title: String,
        selected: Boolean,
        onClick: () -> Unit
    ): TextView {
        val bgColor = if (selected) {
            ColorUtils.adjustAlpha(accentColor, if (AppConfig.isNightTheme) 0.28f else 0.16f)
        } else {
            themeMutedColorOrDefault()
        }
        val strokeColor = if (selected) {
            accentColor
        } else {
            TopBarSearchStyle.strokeColor(this)
        }
        return TextView(this).apply {
            text = title
            isSelected = selected
            gravity = android.view.Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            minWidth = 52.dpToPx()
            setTextColor(if (selected) accentColor else primaryTextColor)
            textSize = 12f
            typeface = uiTypeface()
            includeFontPadding = false
            setPadding(12.dpToPx(), 0, 12.dpToPx(), 0)
            background = UiCorner.opaqueRoundedStroke(
                bgColor,
                UiCorner.actionRadius(this@SearchActivity),
                1.dpToPx(),
                strokeColor
            )
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                30.dpToPx()
            ).apply {
                setMargins(0, 4.dpToPx(), 8.dpToPx(), 4.dpToPx())
            }
        }
    }

    private fun updateSearchScopeFromTag(group: String) {
        viewModel.searchScope.update(group)
        updateSourceGroupTags()
    }

    /**
     * 更新搜索历史
     */
    private fun upHistory(key: String? = null) {
        booksFlowJob?.cancel()
        booksFlowJob = lifecycleScope.launch {
            if (key.isNullOrBlank()) {
                bookshelfHintBooks.clear()
            } else {
                appDb.bookDao.flowSearchDisplayInfos(key).conflate().collect { displayInfos ->
                    val books = displayInfos.map { it.toBook() }
                    bookshelfHintBooks.clear()
                    bookshelfHintBooks.addAll(books)
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
                historyKeywords.clear()
                historyKeywords.addAll(it)
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
            // 书架状态等变化：触发结果项重算 isInBookshelf。
            bookshelfTick.intValue++
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
                        viewModel.searchKey = ""
                        viewModel.search(searchView.query.toString())
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
    private fun showBookInfo(book: SearchBook) {
        lifecycleScope.launch {
            val isVideo = withContext(IO) {
                SearchBookOpenHelper.isVideoResult(
                    book,
                    viewModel.searchScope.getSingleBookSourcePart()?.bookSourceType
                )
            }
            if (isVideo) {
                SearchBookOpenHelper.open(this@SearchActivity, book, true)
            } else {
                SearchBookOpenHelper.open(this@SearchActivity, book, false)
            }
        }
    }

    /**
     * 是否已经加入书架
     */
    private fun isInBookshelf(book: SearchBook): Boolean {
        return viewModel.isInBookShelf(book)
    }

    /**
     * 显示书籍详情
     */
    private fun showBookInfo(book: Book) {
        if (book.isVideo) {
            startActivityForBook(book)
            return
        }
        BookInfoNavigator.open(this, book)
    }

    /**
     * 点击历史关键字
     */
    private fun searchHistory(key: String) {
        lifecycleScope.launch {
            when {
                searchView.query.toString() == key -> {
                    searchView.setQuery(key, true)
                }

                withContext(IO) { appDb.bookDao.findByName(key).isEmpty() } -> {
                    searchView.setQuery(key, true)
                }

                else -> {
                    searchView.setQuery(key, false)
                }
            }
        }
    }

    /**
     * 删除搜索记录
     */
    private fun deleteHistory(searchKeyword: SearchKeyword) {
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

    override fun finish() {
        if (searchView.hasFocus()) {
            searchView.clearFocus()
            return
        }
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