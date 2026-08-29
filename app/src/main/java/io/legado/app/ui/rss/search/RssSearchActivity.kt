package io.legado.app.ui.rss.search

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
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.data.entities.SearchRssArticle
import io.legado.app.databinding.ActivityRssSearchBinding
import io.legado.app.help.config.AppConfig

import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.rss.source.manage.RssSourceActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsSearchBar
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyNavigationBarMargin
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 订阅源统一搜索 Activity（rss-unified-search 新增）
 *
 * 参考 [io.legado.app.ui.book.search.SearchActivity] 的设计，差异：
 * - 删除书架搜索区域（tv_book_show / rv_bookshelf_search），订阅源搜索无书架概念（遗漏点 34）
 * - 删除 upAdapterLiveData 观察，订阅源搜索无书架联动需求
 * - 删除 menu_precision_search，订阅源搜索无精准搜索概念
 * - 搜索结果跳转通过 [ReadRss.readRss]（先转 RssArticle 再 toRecord）
 * - 历史记录使用 type=1（订阅源搜索历史），与书源 type=0 隔离
 *
 * 设计依据：rss-unified-search design.md §4.2 / §5
 */
class RssSearchActivity :
    VMBaseActivity<ActivityRssSearchBinding, RssSearchViewModel>(),
    RssSearchAdapter.CallBack,
    RssSearchHistoryAdapter.CallBack {

    override val binding by viewBinding(ActivityRssSearchBinding::inflate)
    override val viewModel by viewModels<RssSearchViewModel>()

    private val adapter by lazy { RssSearchAdapter(this, this) }
    private val historyKeyAdapter by lazy {
        RssSearchHistoryAdapter(this, this).apply {
            setHasStableIds(true)
        }
    }
    // rss-search-compose 壳层化：Compose 顶栏搜索/菜单状态（替代原 SearchView + Menu）
    private var composeSearchQuery by mutableStateOf("")
    private var menuExpanded by mutableStateOf(false)
    private var composeTypeChecked by mutableStateOf(AppConfig.rssSearchType)
    private var composeGroups by mutableStateOf(listOf<String>())
    private var historyFlowJob: Job? = null
    private var isManualStopSearch = false

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.llInputHelp.setBackgroundColor(backgroundColor)
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

    // rss-search-compose 壳层化：顶栏（GlassTopAppBar + 搜索 SettingsSearchBar + 更多菜单 AppDropdownMenu）
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
                            // 输入变化：停止当前搜索，隐藏 FAB，更新搜索历史
                            viewModel.stop()
                            binding.fbStartStop.invisible()
                            upHistory(it.trim())
                            visibleInputHelp(true)
                        },
                        placeholder = getString(R.string.rss_search_key),
                        onSearch = { submitSearch(composeSearchQuery) }
                    )
                }
            }
        }
    }

    // rss-search-compose 壳层化：更多菜单数据（类型筛选 + 分组筛选 + 管理）
    private fun buildMenuActions(): List<MenuAction> {
        val searchScopeNames = viewModel.searchScope.displayNames
        return buildList {
            // 类型筛选分组
            add(MenuAction(Icons.Default.Category, getString(R.string.rss_search_type), header = true) {})
            add(MenuAction(
                Icons.Default.AllInclusive,
                getString(R.string.rss_search_type_all),
                checked = composeTypeChecked == -1,
                onClick = { handleMenuAction(R.id.menu_type_all) }
            ))
            add(MenuAction(
                Icons.Default.Language,
                getString(R.string.rss_article_type_web),
                checked = composeTypeChecked == 0,
                onClick = { handleMenuAction(R.id.menu_type_web) }
            ))
            add(MenuAction(
                Icons.Default.Image,
                getString(R.string.rss_article_type_image),
                checked = composeTypeChecked == 1,
                onClick = { handleMenuAction(R.id.menu_type_image) }
            ))
            add(MenuAction(
                Icons.Default.VideoLibrary,
                getString(R.string.rss_article_type_video),
                checked = composeTypeChecked == 2,
                onClick = { handleMenuAction(R.id.menu_type_video) }
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
                getString(R.string.rss_source_manage),
                onClick = { handleMenuAction(R.id.menu_source_manage) }
            ))
            add(MenuAction(
                Icons.Default.Info,
                getString(R.string.log),
                onClick = { handleMenuAction(R.id.menu_log) }
            ))
        }
    }

    // rss-search-compose 壳层化：菜单动作统一入口（原 onCompatOptionsItemSelected 逻辑迁移）
    private fun handleMenuAction(actionId: Int) {
        when (actionId) {
            R.id.menu_source_manage -> startActivity<RssSourceActivity>()
            R.id.menu_log -> showDialogFragment(AppLogDialog())
            R.id.menu_1 -> {
                viewModel.searchScope.update("")
                reSearchIfNeeded()
            }
            // 类型筛选选项处理
            R.id.menu_type_all -> updateSearchType(-1)
            R.id.menu_type_web -> updateSearchType(0)
            R.id.menu_type_image -> updateSearchType(1)
            R.id.menu_type_video -> updateSearchType(2)
        }
    }

    // 类型筛选：更新状态并同步 ViewModel
    private fun updateSearchType(type: Int) {
        composeTypeChecked = type
        viewModel.updateSearchType(type)
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

    // 分组/类型筛选变化后若已有搜索词则重新搜索
    private fun reSearchIfNeeded() {
        val query = composeSearchQuery.trim()
        if (query.isNotEmpty()) {
            viewModel.search(query)
        }
    }

    // rss-search-compose 壳层化：提交搜索（原 SearchView onQueryTextSubmit 逻辑迁移）
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
        binding.rvHistoryKey.setEdgeEffectColor(primaryColor)
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
        viewModel.searchRssLiveData.observe(this) {
            adapter.setItems(it)
        }
        // 订阅源分组数据（用于菜单显示）
        lifecycleScope.launch {
            appDb.rssSourceDao.flowEnabledGroups().flowOn(IO).collect {
                composeGroups = it
            }
        }
        // 订阅源搜索无分页概念（RssSearchModel 一次搜索所有源），不需要 repeatOnLifecycle resume/pause
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
     * 更新搜索历史（仅订阅源搜索历史 type=1，无书架搜索）
     */
    private fun upHistory(key: String? = null) {
        historyFlowJob?.cancel()
        historyFlowJob = lifecycleScope.launch {
            when {
                key.isNullOrBlank() -> appDb.searchKeywordDao.flowByTime(1)
                else -> appDb.searchKeywordDao.flowSearch(1, key)
            }.catch {
                AppLog.put("订阅源搜索界面获取搜索历史数据失败\n${it.localizedMessage}", it)
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
        // 订阅源搜索无分页概念，搜索结束即隐藏 FAB
        binding.fbStartStop.invisible()
    }

    override fun observeLiveBus() {
        viewModel.searchFinishLiveData.observe(this) { isEmpty ->
            if (!isEmpty || viewModel.searchScope.isAll()) return@observe
            val displayScope = viewModel.searchScope.display
            showComposeConfirmDialog(
                title = "搜索结果为空",
                message = "${displayScope}分组搜索结果为空，是否切换到全部分组？",
                positiveText = getString(R.string.yes),
                negativeText = getString(R.string.no),
                onPositive = {
                    viewModel.searchScope.update("")
                }
            )
        }
    }

    /**
     * 点击搜索结果项，跳转到文章详情页
     *
     * 实现逻辑（rss-unified-search 阶段10 修订，方案 D：独立详情页 Activity）：
     * 1. 将 SearchRssArticle + 多源映射 + 搜索结果列表写入 RssSearchSourceHolder
     * 2. 跳转 RssArticleInfoActivity 显示文章详情（标题/简介/多源列表/阅读按钮）
     * 3. 详情页点击"阅读"按钮或某源项后，由详情页调用 ReadRss.readRss 跳阅读页/播放页
     *
     * 搜索结果列表转 List<RssArticle> 供播放页上/下一个切换文章使用（废除 AD-07 简化原则）
     *
     * 设计依据：rss-unified-search design.md §5（用户反馈"按书源逻辑应有详情页"）
     */
    override fun showArticleInfo(article: SearchRssArticle) {
        // 保存搜索结果数据到 Holder，供详情页读取
        RssSearchSourceHolder.searchArticle = article
        RssSearchSourceHolder.articles = article.originArticles
        // 将搜索结果列表转为 List<RssArticle>，供播放页上/下一个切换（废除 AD-07 简化原则）
        RssSearchSourceHolder.rssArticles = adapter.getItems().mapNotNull { it.getDefaultArticle() }
        // 跳转详情页
        startActivity<RssArticleInfoActivity>()
    }

    /**
     * 点击历史关键字，直接发起搜索
     */
    override fun searchHistory(key: String) {
        composeSearchQuery = key
        submitSearch(key)
    }

    /**
     * 删除搜索记录
     */
    override fun deleteHistory(searchKeyword: SearchKeyword) {
        viewModel.deleteHistory(searchKeyword)
    }

    private fun alertClearHistory() {
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.sure_clear_search_history),
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = { viewModel.clearHistory() }
        )
    }

    // rss-search-compose 壳层化：Compose 顶栏无 searchView 焦点拦截，直接退出
    override fun finish() {
        super.finish()
    }

    companion object {

        fun start(context: Context, key: String?, searchScope: String? = null) {
            context.startActivity<RssSearchActivity> {
                putExtra("key", key)
                putExtra("searchScope", searchScope)
            }
        }

    }
}
