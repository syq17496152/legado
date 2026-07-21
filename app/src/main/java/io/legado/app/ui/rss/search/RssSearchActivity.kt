package io.legado.app.ui.rss.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View.GONE
import android.view.View.VISIBLE
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
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
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.data.entities.SearchRssArticle
import io.legado.app.databinding.ActivityRssSearchBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.rss.read.ReadRss
import io.legado.app.ui.rss.source.manage.RssSourceActivity
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyNavigationBarMargin
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.transaction
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlin.math.abs

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
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private var menu: Menu? = null
    private var groups: List<String>? = null
    private var historyFlowJob: Job? = null
    private var isManualStopSearch = false

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.llInputHelp.setBackgroundColor(backgroundColor)
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

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.rss_search, menu)
        this.menu = menu
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.transaction {
            menu.removeGroup(R.id.menu_group_1)
            menu.removeGroup(R.id.menu_group_2)
            menu.removeGroup(R.id.menu_group_3)

            // 阶段11.4 问题3 优化：类型筛选（menu_group_3）放在分组筛选上面
            // 选项：全部类型(-1) / 网页(0) / 图片(1) / 视频(2)，单选
            // 与分组筛选（menu_group_1/2）独立，可同时生效（先按分组限定源范围，再按类型过滤结果）
            // "全部类型"改名避免与"全部书源"视觉重合（用户反馈"留一个全部就行了"）
            val currentType = viewModel.searchTypeLiveData.value ?: -1
            menu.add(R.id.menu_group_3, R.id.menu_type_all, Menu.NONE, getString(R.string.rss_search_type_all))
                .apply { isChecked = currentType == -1 }
            menu.add(R.id.menu_group_3, R.id.menu_type_web, Menu.NONE, getString(R.string.rss_article_type_web))
                .apply { isChecked = currentType == 0 }
            menu.add(R.id.menu_group_3, R.id.menu_type_image, Menu.NONE, getString(R.string.rss_article_type_image))
                .apply { isChecked = currentType == 1 }
            menu.add(R.id.menu_group_3, R.id.menu_type_video, Menu.NONE, getString(R.string.rss_article_type_video))
                .apply { isChecked = currentType == 2 }
            menu.setGroupCheckable(R.id.menu_group_3, true, true)

            // 分组筛选（menu_group_1/2）放在类型筛选下面
            var hasChecked = false
            val searchScopeNames = viewModel.searchScope.displayNames
            if (!viewModel.searchScope.isAll()) {
                searchScopeNames.forEach { name ->
                    menu.add(R.id.menu_group_1, Menu.NONE, Menu.NONE, name).apply {
                        isChecked = true
                        hasChecked = true
                    }
                }
            }
            val allSourceMenu =
                menu.add(R.id.menu_group_2, R.id.menu_1, Menu.NONE, getString(R.string.all_source))
                    .apply {
                        if (viewModel.searchScope.isAll()) {
                            isChecked = true
                            hasChecked = true
                        }
                    }
            groups?.forEach {
                if (searchScopeNames.contains(it)) {
                    // 已在 group_1 显示，跳过
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
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_source_manage -> startActivity<RssSourceActivity>()
            R.id.menu_search_scope -> {
                // 打开菜单（onMenuOpened 已处理分组展示），此处提示用户选择分组
                // 也可考虑弹出一个独立的对话框，简化为打开菜单
            }
            R.id.menu_search_type -> {
                // 阶段11.4 问题3：类型筛选入口，实际选项在 onMenuOpened 的 menu_group_3 中显示
            }
            R.id.menu_log -> showDialogFragment(AppLogDialog())
            R.id.menu_1 -> viewModel.searchScope.update("")
            // 阶段11.4 问题3：类型筛选选项处理
            R.id.menu_type_all -> viewModel.updateSearchType(-1)
            R.id.menu_type_web -> viewModel.updateSearchType(0)
            R.id.menu_type_image -> viewModel.updateSearchType(1)
            R.id.menu_type_video -> viewModel.updateSearchType(2)
            else -> {
                if (item.groupId == R.id.menu_group_1) {
                    viewModel.searchScope.remove(item.title.toString())
                } else if (item.groupId == R.id.menu_group_2) {
                    viewModel.searchScope.update(item.title.toString())
                }
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.rss_search_key)
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
                upHistory(newText.trim())
                return false
            }
        })
        searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
            if (binding.refreshProgressBar.isAutoLoading || (!hasFocus && adapter.isNotEmpty() && searchView.query.isNotBlank())) {
                visibleInputHelp(false)
            } else {
                visibleInputHelp(true)
            }
        }
        visibleInputHelp(true)
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
                searchView.query?.toString()?.trim()?.let {
                    searchView.setQuery(it, true)
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
                groups = it
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
            // 直接对 SearchView 内部的 EditText 请求焦点（参考 SearchActivity）
            searchView.findViewById<android.widget.TextView>(androidx.appcompat.R.id.search_src_text)
                .requestFocus()
        } else {
            searchView.setQuery(key, true)
        }
    }

    /**
     * 打开关闭输入帮助
     */
    private fun visibleInputHelp(visible: Boolean) {
        if (visible) {
            upHistory(searchView.query.toString())
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
            alert("搜索结果为空") {
                val displayScope = viewModel.searchScope.display
                setMessage("${displayScope}分组搜索结果为空，是否切换到全部分组？")
                yesButton {
                    viewModel.searchScope.update("")
                }
                noButton()
            }
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
        searchView.setQuery(key, true)
    }

    /**
     * 删除搜索记录
     */
    override fun deleteHistory(searchKeyword: SearchKeyword) {
        viewModel.deleteHistory(searchKeyword)
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
            context.startActivity<RssSearchActivity> {
                putExtra("key", key)
                putExtra("searchScope", searchScope)
            }
        }

    }
}
