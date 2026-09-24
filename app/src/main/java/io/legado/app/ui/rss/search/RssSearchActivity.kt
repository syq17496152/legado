package io.legado.app.ui.rss.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.data.entities.SearchRssArticle
import io.legado.app.help.config.AppConfig

import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.search.SearchInputHelpScreen
import io.legado.app.ui.rss.source.manage.RssSourceActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.anima.RefreshProgressBar
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.AppFilterChip
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.InlineTaskBar
import io.legado.app.ui.widget.components.InlineTaskState
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsSearchBar
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
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
 * my-compose-full W2.1：内容区（结果列表/输入帮助区）Compose 化，
 * RssSearchAdapter/RssSearchHistoryAdapter（View 版）随迁移除，
 * 结果列表复用 [RssSearchResultScreen]、输入帮助区直接复用书源搜索的 [SearchInputHelpScreen]
 * （订阅源无书架概念，bookshelfBooks 传空列表）。
 *
 * 设计依据：rss-unified-search design.md §4.2 / §5
 */
class RssSearchActivity :
    VMBaseActivity<ViewBinding, RssSearchViewModel>() {

    // CE 5.2（compose 包）：原 activity_rss_search.xml 已退役，改 composeShell 工厂创建合成壳，
    // Compose 全权接管（顶栏/结果/输入帮助为纯 Compose；进度条与 FAB 无 Compose 等价物 ⇒ AndroidView 原样托管）
    override val binding: ViewBinding by lazy { composeShell(this) }
    override val viewModel by viewModels<RssSearchViewModel>()

    // rss-search-compose 壳层化：Compose 顶栏搜索/菜单状态（替代原 SearchView + Menu）
    private var composeSearchQuery by mutableStateOf("")
    private var menuExpanded by mutableStateOf(false)
    private var composeTypeChecked by mutableStateOf(AppConfig.rssSearchType)
    private var composeGroups by mutableStateOf(listOf<String>())
    private var historyFlowJob: Job? = null
    private var isManualStopSearch = false
    // W2.1 内容区 Compose 化：列表/历史快照状态驱动（替代 RecyclerView Adapter）
    private var searchResults by mutableStateOf(listOf<SearchRssArticle>())
    private var historyKeywords by mutableStateOf(listOf<SearchKeyword>())
    private var hasSearched by mutableStateOf(false)
    private var resultScrollToTopSignal by mutableIntStateOf(0)
    // F199：搜索进行中状态（由 isSearchLiveData 驱动，进行中任务条与结果空态共用同一信号源）
    private var isSearching by mutableStateOf(false)

    // CE 5.2 换装：原 XML 的三个 View 机制改由 Compose 状态驱动（逐一与原 View 操作等价）
    //  · progressLoading  ← 原 refresh_progress_bar 的 visible()/gone() + isAutoLoading
    //  · stopFabVisible   ← 原 fb_start_stop 的 visible()/invisible()
    //  · inputHelpVisible ← 原 ll_input_help 的 VISIBLE/GONE（含 initData 中的 isVisible 读取）
    private var progressLoading by mutableStateOf(false)
    private var stopFabVisible by mutableStateOf(false)
    private var inputHelpVisible by mutableStateOf(false)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeContent()
        initData()
        receiptIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiptIntent(intent)
    }

    /**
     * CE 5.2：Compose 承载全页（顶栏 + 进度条 + 结果列表 + 输入帮助覆盖层 + 停止 FAB）。
     *
     * 与原 XML（`activity_rss_search.xml`）的**逐一对应关系**（三不影响口径）：
     *  · `compose_top_bar` → 顶部 `LegadoTheme { Column { … } }`（内容原样搬入，不再套壳 ComposeView）
     *  · `refresh_progress_bar`（2dp，仅搜索中显示）→ `AndroidView` 托管 `RefreshProgressBar`
     *    （**自绘动画条，无 Compose 等价物**），显隐与动画由 `progressLoading` 驱动
     *  · `content_view`(DynamicFrameLayout) + `compose_results` → `Box(weight(1f))` + `RssSearchResultScreen`
     *    —— 该 `DynamicFrameLayout` 在本页**从未调用其 ViewSwitcher API**（只是容器）⇒ 退化为普通容器，行为等价
     *  · `ll_input_help`(gone + clickable 挡点击) + `compose_input_help` → `inputHelpVisible` 条件组合
     *    + 空白区 `clickable`（保留「挡住下层结果点击」的语义）
     *  · `fb_start_stop`（mini FAB / 16dp 边距 / accent 底 / ic_stop_black_24dp / invisible）
     *    → `AndroidView` 托管同配置 `FloatingActionButton`（边距含导航栏 inset，对齐原 `applyNavigationBarMargin(true)`）
     */
    private fun initComposeContent() {
        binding.root.attachComposeContent {
            Column(modifier = Modifier.fillMaxSize()) {
                // ---- 顶栏区（原 binding.composeTopBar 内容，逐行不变）----
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
                                // 输入变化：停止当前搜索，隐藏 FAB，回到搜索历史，清空结果空态标记
                                viewModel.stop()
                                stopFabVisible = false
                                hasSearched = false
                                upHistory(it.trim())
                                visibleInputHelp(true)
                            },
                            placeholder = getString(R.string.rss_search_key),
                            onSearch = { submitSearch(composeSearchQuery) }
                        )
                        // F200（M4）：类型筛选外显——「全部类型/网页/图片/视频」原藏在「更多」二级菜单，
                        // 切换需 3 步；「搜视频/图集」是订阅域高频意图 ⇒ 上浮为搜索框下一行 chips
                        RssSearchTypeChipsRow(
                            selectedType = composeTypeChecked,
                            onSelect = { updateSearchType(it) }
                        )
                        // F199（M4）：多源搜索是本页耗时最长的操作，进行中给「已得 N 条」正反馈 + 停止出口。
                        // 复用共享 InlineTaskBar（F253：页内长任务条统一载体），Idle 时零高度不占位
                        InlineTaskBar(
                            state = if (isSearching) InlineTaskState.Running else InlineTaskState.Idle,
                            text = stringResource(R.string.rss_search_running, searchResults.size),
                            onCancel = { stopSearch() },
                            actionLabel = getString(R.string.rss_search_stop)
                        )
                    }
                }
                // ---- 进度条（原 refresh_progress_bar：2dp 高，搜索中才参与布局）----
                if (progressLoading) {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        factory = { ctx -> RefreshProgressBar(ctx) },
                        update = { it.isAutoLoading = true }
                    )
                }
                // ---- 内容区（原 content_view > compose_results）----
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    RssSearchResultScreen(
                        articles = searchResults,
                        isLoading = isSearching,
                        hasSearched = hasSearched,
                        scrollToTopSignal = resultScrollToTopSignal,
                        // 修复 4：结果标题/摘要按当前关键词高亮（复用共享 highlightMatches，口径与设置搜索一致）
                        highlightQuery = composeSearchQuery.trim(),
                        onArticleClick = { showArticleInfo(it) }
                    )
                    // 输入帮助覆盖层（搜索历史）：复用书源搜索 Compose 组件，订阅源无书架概念传空列表
                    if (inputHelpVisible) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { /* 只挡点击，不响应（对齐原 ll_input_help 的 clickable 语义） */ }
                        ) {
                            SearchInputHelpScreen(
                                bookshelfBooks = emptyList(),
                                historyKeywords = historyKeywords,
                                onBookClick = { },
                                onHistoryClick = { searchHistory(it) },
                                onHistoryDelete = { deleteHistory(it) },
                                onClearHistory = { alertClearHistory() }
                            )
                        }
                    }
                    // 停止 FAB（原 fb_start_stop）
                    AndroidView(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                            .navigationBarsPadding(),
                        factory = { ctx -> createStopFab(ctx) },
                        update = { fab ->
                            fab.visibility = if (stopFabVisible) View.VISIBLE else View.INVISIBLE
                        }
                    )
                }
            }
        }
    }

    /** 原 `fb_start_stop` 的程序化等价物（mini 尺寸 / accent 底 / 停止图标 / 原点击语义）。 */
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
                if (isSearching) {
                    stopSearch()
                } else {
                    viewModel.search("")
                }
            }
        }

    // rss-search-compose 壳层化：更多菜单数据（分组筛选 + 管理）
    // F200（M4）：类型筛选组已上浮为搜索框下的 chips 行，菜单内不再保留同一状态的第二入口
    private fun buildMenuActions(): List<MenuAction> {
        val searchScopeNames = viewModel.searchScope.displayNames
        return buildList {
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
        }
    }

    // 类型筛选（F200：由搜索框下 chips 行触发）：更新状态并同步 ViewModel
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
            hasSearched = true
            viewModel.saveSearchKey(searchKey)
            viewModel.searchKey = ""
            viewModel.search(searchKey)
        }
        visibleInputHelp(false)
    }

    /**
     * F199（M4）：停止搜索的唯一出口——FAB 与进行中任务条的「停止」共用，
     * 避免两处各写一份状态复位逻辑后漂移（手动停止标记 + 进度条回位）。
     */
    private fun stopSearch() {
        isManualStopSearch = true
        viewModel.stop()
        progressLoading = false
    }

    private fun initData() {
        viewModel.searchScope.stateLiveData.observe(this) {
            if (!inputHelpVisible) {
                composeSearchQuery.trim().let { query ->
                    if (query.isNotEmpty()) {
                        submitSearch(query)
                    }
                }
            }
        }
        viewModel.isSearchLiveData.observe(this) {
            isSearching = it == true
            if (isSearching) {
                startSearch()
            } else {
                searchFinally()
            }
        }
        viewModel.searchRssLiveData.observe(this) {
            // 新搜索会以更短的列表重置（流式追加则只增长），据此判断是否需要回到顶部
            val isFreshSearch = it.size < searchResults.size ||
                (it.isNotEmpty() && searchResults.isNotEmpty() && it.first().deduplicationKey() != searchResults.first().deduplicationKey())
            searchResults = it
            if (isFreshSearch) {
                resultScrollToTopSignal++
            }
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
        // 无 key 分支：原实现 `binding.composeTopBar.requestFocus()` 落在 **XML 的 ComposeView 外壳**上，
        // 而 View 层焦点不会进入 Compose 焦点系统（BasicTextField 需 FocusRequester）⇒ 该调用无可见效果；
        // CE 5.2 换装后无对等物，强行 requestFocus 反而会弹出键盘（用户可见变化）⇒ 保持等价：不自动聚焦。
        if (!key.isNullOrBlank()) {
            composeSearchQuery = key
            submitSearch(key)
        }
    }

    /**
     * 打开关闭输入帮助
     */
    private fun visibleInputHelp(visible: Boolean) {
        inputHelpVisible = visible
        if (visible) {
            upHistory(composeSearchQuery.trim())
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
                historyKeywords = it
            }
        }
    }

    /**
     * 开始搜索
     */
    private fun startSearch() {
        progressLoading = true
        stopFabVisible = true
        // 图标原为 XML 静态 src（ic_stop_black_24dp），运行时无需再 setImageResource
    }

    /**
     * 搜索结束
     */
    private fun searchFinally() {
        progressLoading = false
        // 订阅源搜索无分页概念，搜索结束即隐藏 FAB
        stopFabVisible = false
    }

    override fun observeLiveBus() {
        viewModel.searchFinishLiveData.observe(this) { isEmpty ->
            if (!isEmpty || viewModel.searchScope.isAll()) return@observe
            val displayScope = viewModel.searchScope.display
            showComposeConfirmDialog(
                title = getString(R.string.rss_search_empty_title),
                message = getString(R.string.rss_search_empty_switch, displayScope),
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
    private fun showArticleInfo(article: SearchRssArticle) {
        // 保存搜索结果数据到 Holder，供详情页读取
        RssSearchSourceHolder.searchArticle = article
        RssSearchSourceHolder.articles = article.originArticles
        // 将搜索结果列表转为 List<RssArticle>，供播放页上/下一个切换（废除 AD-07 简化原则）
        RssSearchSourceHolder.rssArticles = searchResults.mapNotNull { it.getDefaultArticle() }
        // 跳转详情页
        startActivity<RssArticleInfoActivity>()
    }

    /**
     * 点击历史关键字，直接发起搜索
     */
    private fun searchHistory(key: String) {
        composeSearchQuery = key
        submitSearch(key)
    }

    /**
     * 删除搜索记录（长按历史标签触发）
     *
     * 修复 2（M4）：原实现长按即直达删库、无任何确认，而「清空全部历史」却有 dangerPositive
     * 二次确认 ⇒ 防护梯度倒挂。现补同口径确认（文案含关键词，明确影响范围）。
     */
    private fun deleteHistory(searchKeyword: SearchKeyword) {
        showComposeConfirmDialog(
            title = getString(R.string.search_history_delete_title),
            message = getString(R.string.search_history_delete_message, searchKeyword.word),
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = { viewModel.deleteHistory(searchKeyword) }
        )
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

/**
 * F200（M4）：搜索结果类型筛选 chips 行（搜索框正下方，与菜单里的分组筛选叠加生效）。
 *
 * 取色/形状全部走共享 [AppFilterChip]（与书源搜索页 `createSourceGroupChip` 同口径）——
 * 同一「范围/筛选 chip」语义不允许多套视觉，见 NORM-FEEDBACK F286 单源原则。
 */
@Composable
private fun RssSearchTypeChipsRow(
    selectedType: Int,
    onSelect: (Int) -> Unit
) {
    val options = listOf(
        -1 to stringResource(R.string.rss_search_type_all),
        0 to stringResource(R.string.rss_article_type_web),
        1 to stringResource(R.string.rss_article_type_image),
        2 to stringResource(R.string.rss_article_type_video)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { (type, label) ->
            AppFilterChip(
                text = label,
                selected = selectedType == type,
                onClick = { onSelect(type) }
            )
        }
    }
}
