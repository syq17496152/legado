package io.legado.app.ui.book.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.base.attachComposeContent
import io.legado.app.base.composeShell
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.help.book.isVideo
import io.legado.app.help.video.VideoPlaylistHolder
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.SearchBookOpenHelper
import io.legado.app.ui.book.info.BookInfoNavigator
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.anima.RefreshProgressBar
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.AppFilterChip
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsSearchBar
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * 书源搜索页（CE 5.2 第 2 页，2026-09-24 换装）。
 *
 * 换装口径（唯一验收 = 三不影响：功能 / 现有整体布局及样式 / 性能）：
 * 原 `activity_book_search.xml`（ConstraintLayout + 原生 `io.legado.app.ui.widget.SearchView` + ModernActionPopup）
 * 已退役，页面改由 [composeShell] + [attachComposeContent] 单源承载。
 *
 * **两处「保持 View」**（无 Compose 等价物，一律 [AndroidView] 原样托管，与首项 `activity_rss_search` 同判据）：
 * `RefreshProgressBar`（自绘动画条）· mini 态 `FloatingActionButton`（含搜索中/继续两种动态图标）。
 *
 * **一处用户可感变化（已登记 updateLog）**：原页顶部的原生 `SearchView` + 独立菜单按钮，
 * 换为全站搜索类页面统一的 `GlassTopAppBar` + `SettingsSearchBar` + `AppDropdownMenu`
 * （与兄弟页 `activity_search_content` / `activity_rss_search` 同构；本源分组 chips 换共享 `AppFilterChip`）。
 */
class SearchActivity : VMBaseActivity<ViewBinding, SearchViewModel>(),
    SearchScopeDialog.Callback {

    // CE 5.2：原 activity_book_search.xml 已退役 ⇒ 改 composeShell 工厂创建合成壳（空 FrameLayout）
    override val binding: ViewBinding by lazy { composeShell(this) }
    override val viewModel by viewModels<SearchViewModel>()

    // 搜索结果列表已 Compose 化(SearchResultScreen)，用快照状态驱动，替代原 SearchAdapter。
    private val searchResults = mutableStateListOf<SearchBook>()
    private val bookshelfTick = mutableIntStateOf(0)
    private val resultScrollToTopSignal = mutableIntStateOf(0)
    // F73：状态条可见性（搜索开始置位、用户改动关键词复位），避免空结果时「搜过了」这一事实无表达
    private var resultSummaryVisible by mutableStateOf(false)
    // F73：搜索进行中快照态。**不能用 `viewModel.isSearchLiveData.value` 直读做 Compose 入参**——
    // LiveData 在组合中直读不建立订阅，结束后不会触发重组 ⇒ 状态条永远停在「搜索中」（真机实测）。
    // 由 observe 回调写入快照态，组合随状态变更重组。
    private var searchRunning by mutableStateOf(false)
    // F74/F75：状态条上的两个开关状态。**初值不能在这里读偏好**——属性初始化在 Activity
    // 构造期执行，早于 `attachBaseContext()`，此时 `ContextWrapper.getPackageName()` 为 null
    // ⇒ getPrefBoolean 直接 NPE 崩进程（真机实测）。故先给安全默认值，在 onActivityCreated 里读盘。
    private var precisionSearchEnabled by mutableStateOf(false)
    private var compactSearchResult by mutableStateOf(false)
    // 输入帮助区(书架命中 + 搜索历史)已 Compose 化，用快照状态驱动，替代原 BookAdapter/HistoryKeyAdapter。
    private val bookshelfHintBooks = mutableStateListOf<Book>()
    private val historyKeywords = mutableStateListOf<SearchKeyword>()

    // ---- CE 5.2：原 XML 的 View 机制一律改由 Compose 状态驱动（逐一与原 View 操作等价）----
    //  · composeSearchQuery  ← search_view（原生 SearchView）的当前文本
    //  · menuExpanded        ← btn_menu + ModernActionPopup 的展开态
    //  · sourceGroups / selectedGroupNames ← hsv_source_group_bar 内程序化 TextView chips 的数据与选中集
    //  · progressLoading     ← refresh_progress_bar 的 visible()/gone() + isAutoLoading
    //  · stopFabVisible / stopFabIconRes ← fb_start_stop 的 visible()/invisible() 与 setImageResource
    //  · inputHelpVisible    ← ll_input_help 的 VISIBLE/GONE
    private var composeSearchQuery by mutableStateOf("")
    private var menuExpanded by mutableStateOf(false)
    private var sourceGroups by mutableStateOf(listOf<String>())
    private var selectedGroupNames by mutableStateOf(emptySet<String>())
    private var progressLoading by mutableStateOf(false)
    private var stopFabVisible by mutableStateOf(false)
    private var stopFabIconRes by mutableIntStateOf(R.drawable.ic_stop_black_24dp)
    private var inputHelpVisible by mutableStateOf(true)
    private val searchFocusRequester = FocusRequester()
    // 原 receiptIntent 中 `searchEditText?.requestFocus()` 的等价物：自增信号驱动组合内聚焦。
    // 直接调用会早于组合挂载 ⇒ FocusRequester 未初始化抛异常，故经 LaunchedEffect 在挂载后触发。
    private var autoFocusSignal by mutableIntStateOf(0)

    private var historyFlowJob: Job? = null
    private var booksFlowJob: Job? = null
    private var isManualStopSearch = false

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        // F74/F75：读盘取两个开关的持久化初值（构造期读会 NPE，见字段注释）
        precisionSearchEnabled = getPrefBoolean(PreferKey.precisionSearch)
        compactSearchResult = getPrefBoolean(PreferKey.searchResultCompact)
        initComposeContent()
        initData()
        // 原 initSearchView() 末行：初始展示输入帮助区（顺带加载搜索历史/书架命中）
        visibleInputHelp(true)
        receiptIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiptIntent(intent)
    }

    /**
     * CE 5.2：Compose 承载全页（顶栏 + 搜索框 + 进度条 + 结果列表 + 分组 chips + 输入帮助覆盖层 + 停止 FAB）。
     *
     * 与原 XML（`activity_book_search.xml`）的**逐一对应关系**：
     *  · `search_view`（原生 SearchView，42dp）+ `btn_menu`（`bg_more_icon_button_clear`）
     *    → `GlassTopAppBar` + `SettingsSearchBar` + `AppDropdownMenu`（全站搜索类页面统一顶栏组件族）
     *  · `refresh_progress_bar`（2dp，仅搜索中显示）→ `AndroidView` 托管 `RefreshProgressBar`
     *  · `content_view`(DynamicFrameLayout) + `compose_results` → `Box(weight(1f))` + `SearchResultScreen`
     *    （本页 `DynamicFrameLayout` 从未调用其 ViewSwitcher API，只是容器 ⇒ 退化为普通容器，行为等价）
     *  · `hsv_source_group_bar` + `ll_source_group_tags`（程序化 TextView chips）
     *    → 悬浮 `Row(horizontalScroll)` + 共享 `AppFilterChip`（其 KDoc 明文本页原 chip 同口径 ⇒ 单源收敛）
     *  · `ll_input_help`(gone + clickable 挡点击) + `compose_input_help` → 条件组合 + 挡点击 overlay
     *  · `fb_start_stop`（mini FAB / 搜索中转「停止」、可继续转「继续」）→ `AndroidView` 托管同配置 FAB
     */
    private fun initComposeContent() {
        binding.root.attachComposeContent {
            val density = LocalDensity.current
            // 原 Insets 手工链路（rootBaseTopPadding / currentImeInset / currentBottomInset）等价：
            //  · 状态栏：由 GlassTopAppBar 内部 windowInsetsPadding(statusBars) 承担（与兄弟页同源）
            //  · 底部：原口径 `if (ime > 0) ime else navigationBar`，供分组条与输入帮助覆盖层抬升
            val imeInset = WindowInsets.ime.getBottom(density)
            val bottomInset =
                if (imeInset > 0) imeInset else WindowInsets.navigationBars.getBottom(density)
            // 原 updateKeyboardGroupBarVisible()：仅输入法弹出且存在分组时，分组条悬浮在内容之上
            val groupBarVisible = imeInset > 0 && sourceGroups.isNotEmpty()
            val focusManager = LocalFocusManager.current
            // 原 finish() 的 `searchView.hasFocus()` 分支等价：输入法展开时返回先收键盘（不退出页面）
            BackHandler(enabled = imeInset > 0) { focusManager.clearFocus() }
            LaunchedEffect(autoFocusSignal) {
                // 原 receiptIntent 的 `searchEditText?.requestFocus()`：进入页面自动聚焦搜索框（弹出输入法）
                if (autoFocusSignal > 0) searchFocusRequester.requestFocus()
            }
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // ---- 顶栏 + 搜索框（原 search_view + btn_menu 两节点）----
                    LegadoTheme {
                        Column {
                            GlassTopAppBar(
                                title = getString(R.string.search),
                                navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                                onNavClick = { finish() },
                                actions = {
                                    Box {
                                        IconButton(onClick = {
                                            // 原 prepareSearchMenu() 的失效分组兜底：只在菜单将展开时执行，
                                            // 不得放进组合（组合期写 searchScope 会造成状态震荡）
                                            healStaleSearchScope()
                                            menuExpanded = true
                                        }) {
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
                                onQueryChange = { applyQuery(it, submit = false) },
                                placeholder = getString(R.string.search_book_key),
                                onSearch = {
                                    // 原 onQueryTextSubmit 首行 `searchView.clearFocus()`：提交即收输入法
                                    focusManager.clearFocus()
                                    submitSearch(composeSearchQuery)
                                },
                                focusRequester = searchFocusRequester,
                                // 原 searchView.setOnQueryTextFocusChangeListener 等价物
                                onFocusChanged = { hasFocus ->
                                    if (progressLoading ||
                                        (!hasFocus && searchResults.isNotEmpty() && composeSearchQuery.isNotBlank())
                                    ) {
                                        visibleInputHelp(false)
                                    } else {
                                        visibleInputHelp(true)
                                    }
                                }
                            )
                        }
                    }
                    // ---- 进度条（原 refresh_progress_bar：2dp 高，仅搜索中参与布局）----
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
                        SearchResultScreen(
                            books = searchResults,
                            isLoading = searchRunning,
                            hasMore = viewModel.hasMore,
                            scrollToTopSignal = resultScrollToTopSignal.intValue,
                            bookshelfTick = bookshelfTick.intValue,
                            isInBookshelf = { isInBookshelf(it) },
                            lifecycle = lifecycle,
                            onBookClick = { showBookInfo(it) },
                            onLoadMore = { scrollToBottom() },
                            // F73/F74/F75：结果状态条（计数 / 精准搜索 / 紧凑密度）
                            showSummary = resultSummaryVisible,
                            precisionSearch = precisionSearchEnabled,
                            compactMode = compactSearchResult,
                            onTogglePrecisionSearch = { togglePrecisionSearch() },
                            onToggleCompactMode = { toggleCompactSearchResult() }
                        )
                        // 输入帮助覆盖层（搜索历史 + 书架命中）：沿用原 ll_input_help 的边距语义
                        // （底部 inset + 分组条可见时再抬升一个分组条高）
                        if (inputHelpVisible) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(
                                        bottom = with(density) { bottomInset.toDp() } +
                                            (if (groupBarVisible) 46.dp else 0.dp)
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { /* 只挡点击，不响应（对齐原 ll_input_help 的 clickable 语义） */ }
                            ) {
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
                        // 停止/继续 FAB（原 fb_start_stop）
                        // 两个状态先读进组合作用域，保证状态变化必然触发 update 重放
                        val fabVisible = stopFabVisible
                        val fabIconRes = stopFabIconRes
                        AndroidView(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                                .navigationBarsPadding(),
                            factory = { ctx -> createStopFab(ctx) },
                            update = { fab ->
                                fab.visibility = if (fabVisible) View.VISIBLE else View.INVISIBLE
                                fab.setImageResource(fabIconRes)
                            }
                        )
                    }
                }
                // ---- 源分组 chips（原 hsv_source_group_bar：底部悬浮、不占内容布局空间）----
                if (groupBarVisible) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            // 顺序不可倒：inset 内边距必须写在 height 之前，才是「把条抬起来」而不是「压扁条」
                            .padding(bottom = with(density) { bottomInset.toDp() })
                            .height(46.dp)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppFilterChip(
                            text = getString(R.string.all_source),
                            selected = selectedGroupNames.isEmpty(),
                            onClick = { viewModel.searchScope.update("") }
                        )
                        sourceGroups.forEach { group ->
                            AppFilterChip(
                                text = group,
                                selected = selectedGroupNames.contains(group),
                                onClick = { viewModel.searchScope.update(group) }
                            )
                        }
                    }
                }
            }
        }
    }

    /** 原 `fb_start_stop` 的程序化等价物（mini 尺寸 / accent 底 / 动态图标 / 原点击语义）。 */
    private fun createStopFab(context: Context): FloatingActionButton =
        FloatingActionButton(context).apply {
            size = FloatingActionButton.SIZE_MINI
            contentDescription = getString(R.string.stop)
            backgroundTintList = Selector.colorBuild()
                .setDefaultColor(accentColor)
                .setPressedColor(ColorUtils.darkenColor(accentColor))
                .create()
            visibility = if (stopFabVisible) View.VISIBLE else View.INVISIBLE
            setImageResource(stopFabIconRes)
            setOnClickListener {
                if (viewModel.isSearchLiveData.value == true) {
                    isManualStopSearch = true
                    viewModel.stop()
                    progressLoading = false
                } else {
                    viewModel.search("")
                }
            }
        }

    /**
     * CE 5.2：原 `onCompatOptionsItemSelected(item: MenuItem)` 的等价物（MenuItem → 动作 id）。
     *
     * 分组项改由 [buildMenuActions] 直接给出选中态与点击回调 ⇒ 原 `menu_group_1` / `menu_group_2`
     * 的 groupId 分支不再需要；此处只保留三个静态项与「全部源」。
     */
    private fun handleMenuAction(actionId: Int) {
        when (actionId) {
            R.id.menu_search_scope -> alertSearchScope()
            R.id.menu_source_manage -> startActivity<BookSourceActivity>()
            R.id.menu_log -> showDialogFragment(AppLogDialog())
            R.id.menu_1 -> viewModel.searchScope.update("")
        }
    }

    /**
     * CE 5.2：原 `prepareSearchMenu(menu)` 的等价物（数据驱动 [MenuAction] 列表）。
     *
     * 对应关系（`R.menu.book_search` 实测只有 3 个静态项，其余为动态分组项）：
     *  · `menu_group_1`（单选=当前已选范围）→ 已选范围项（`checked = true`）
     *  · `menu_group_2` 的 `menu_1`（全部源）→ 全部源项（`checked = 无已选范围`）
     *  · `menu_group_2` 的其余项 → 未选分组项
     *  · 管理分组：`menu_source_manage` / `menu_search_scope` / `menu_log`
     *
     * 精确搜索项**不在此处**：F74 已把它上浮为结果状态条上的常驻 chip（见 `SearchResultSummaryBar`）。
     */
    private fun buildMenuActions(): List<MenuAction> {
        val scopeNames = viewModel.searchScope.displayNames
        return buildList {
            add(
                MenuAction(
                    Icons.Default.Folder,
                    getString(R.string.groups_or_source),
                    header = true
                ) {})
            scopeNames.forEach { name ->
                add(
                    MenuAction(
                        Icons.Default.Folder,
                        name,
                        checked = true,
                        onClick = { handleGroupSelect(name) }
                    )
                )
            }
            add(
                MenuAction(
                    Icons.Default.AllInclusive,
                    getString(R.string.all_source),
                    checked = scopeNames.isEmpty(),
                    onClick = { handleMenuAction(R.id.menu_1) }
                )
            )
            sourceGroups.forEach { group ->
                if (!scopeNames.contains(group)) {
                    add(
                        MenuAction(
                            Icons.Default.Folder,
                            group,
                            onClick = { handleGroupSelect(group) }
                        )
                    )
                }
            }
            add(MenuAction(Icons.Default.Settings, getString(R.string.more), header = true) {})
            add(
                MenuAction(
                    Icons.Default.ManageSearch,
                    getString(R.string.book_source_manage),
                    onClick = { handleMenuAction(R.id.menu_source_manage) }
                )
            )
            add(
                MenuAction(
                    Icons.Default.Tune,
                    getString(R.string.search_scope),
                    onClick = { handleMenuAction(R.id.menu_search_scope) }
                )
            )
            add(
                MenuAction(
                    Icons.Default.Info,
                    getString(R.string.log),
                    onClick = { handleMenuAction(R.id.menu_log) }
                )
            )
        }
    }

    /**
     * 分组项点击（原 `menu_group_1` 单选组=取消 / `menu_group_2` 多选组=追加 的等价物）。
     * 状态回灌经 `searchScope.stateLiveData` 观察者刷新 [updateSourceGroupTags]，无需在此重复刷新。
     */
    private fun handleGroupSelect(name: String) {
        if (viewModel.searchScope.displayNames.contains(name)) {
            viewModel.searchScope.remove(name)
        } else {
            viewModel.searchScope.update(name)
        }
    }

    /**
     * 原 `prepareSearchMenu()` 的 hasChecked 兜底：已选范围与现存分组**全不匹配**
     * （历史遗留的失效分组名）时回落「全部源」。原实现发生在菜单 prepare 回调 ⇒ 此处同样只在
     * 菜单将要展开时执行（组合期写 `searchScope` 会造成状态震荡）。
     */
    private fun healStaleSearchScope() {
        if (viewModel.searchScope.isSource()) return
        val scopeNames = viewModel.searchScope.displayNames
        if (scopeNames.isEmpty()) return
        if (scopeNames.any { sourceGroups.contains(it) }) return
        viewModel.searchScope.update("")
    }

    /**
     * 等价于原 `searchView.setQuery(key, submit)`：**仅在文本变化时**走改词副作用
     * （原 SearchView 只在文本变化时回调 `onQueryTextChange`），随后按需提交搜索。
     */
    private fun applyQuery(key: String, submit: Boolean) {
        if (composeSearchQuery != key) {
            composeSearchQuery = key
            viewModel.stop()
            // F73：关键词被改动 ⇒ 旧计数已失效，收起状态条（提交新搜索时会重新亮起）
            resultSummaryVisible = false
            stopFabVisible = false
            upHistory(key.trim())
        }
        if (submit) submitSearch(key)
    }

    /** 提交搜索（原 `onQueryTextSubmit` 等价物，不含 `clearFocus` 与输入帮助收起之外的分支）。 */
    private fun submitSearch(query: String) {
        query.trim().let { searchKey ->
            isManualStopSearch = false
            viewModel.saveSearchKey(searchKey)
            viewModel.searchKey = ""
            viewModel.search(searchKey)
        }
        visibleInputHelp(false)
    }

    private fun initData() {
        viewModel.searchScope.stateLiveData.observe(this) {
            // 原实现为「重刷 chips 内容 + 依 IME 态重算分组条可见性」；后者现由组合派生
            updateSourceGroupTags()
        }
        viewModel.isSearchLiveData.observe(this) {
            searchRunning = it
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
                sourceGroups = it
                updateSourceGroupTags()
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
            // 原实现 `searchEditText?.requestFocus()`：进入页面自动聚焦搜索框并弹出输入法。
            // Compose 侧无对等直调（组合未挂载时 FocusRequester 未初始化）⇒ 经信号在挂载后聚焦。
            autoFocusSignal++
        } else {
            // 原实现 `searchView.setQuery(key, true)`：onNewIntent 重入必须重新消费 query 并直接发起搜索
            applyQuery(key, submit = true)
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
            upHistory(composeSearchQuery)
            updateSourceGroupTags()
            inputHelpVisible = true
        } else {
            inputHelpVisible = false
        }
    }

    /**
     * CE 5.2：原 `updateSourceGroupTags()` 的等价物。
     * 原实现直接操作 `llSourceGroupTags` 容器（removeAllViews + `createSourceGroupChip` 程序化 chips），
     * 现只刷新选中集状态（分组集合由 `flowEnabledGroups` 流写入 `sourceGroups`），chips 由组合渲染。
     */
    private fun updateSourceGroupTags() {
        selectedGroupNames = if (viewModel.searchScope.isSource()) {
            emptySet()
        } else {
            viewModel.searchScope.toString().splitNotBlank(",").toSet()
        }
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
        // F73：搜索一开始就亮状态条（含最终 0 命中的情况），让「搜过了」这件事始终有落点
        resultSummaryVisible = true
        progressLoading = true
        stopFabIconRes = R.drawable.ic_stop_black_24dp
        stopFabVisible = true
    }

    /**
     * 搜索结束
     */
    private fun searchFinally() {
        progressLoading = false
        if (!isManualStopSearch && viewModel.hasMore) {
            stopFabIconRes = R.drawable.ic_play_24dp
        } else {
            stopFabVisible = false
        }
    }

    override fun observeLiveBus() {
        viewModel.upAdapterLiveData.observe(this) {
            // 书架状态等变化：触发结果项重算 isInBookshelf。
            bookshelfTick.intValue++
        }
        viewModel.searchFinishLiveData.observe(this) { isEmpty ->
            if (!isEmpty || viewModel.searchScope.isAll()) return@observe
            val precisionSearch = appCtx.getPrefBoolean(PreferKey.precisionSearch)
            val displayScope = viewModel.searchScope.display
            showComposeConfirmDialog(
                title = getString(R.string.search_book_empty_title),
                message = if (precisionSearch) {
                    getString(R.string.search_book_empty_precision, displayScope)
                } else {
                    getString(R.string.search_book_empty_switch, displayScope)
                },
                positiveText = getString(R.string.yes),
                negativeText = getString(R.string.no),
                onPositive = {
                    if (precisionSearch) {
                        // 弹窗链路也要同步状态条上的 chip（否则出现「偏好已关、chip 仍高亮」）
                        appCtx.putPrefBoolean(PreferKey.precisionSearch, false)
                        precisionSearchEnabled = false
                        viewModel.searchKey = ""
                        viewModel.search(composeSearchQuery)
                    } else {
                        viewModel.searchScope.update("")
                    }
                }
            )
        }
    }

    /**
     * F74：切精准搜索（原 ⋮ 菜单勾选项上浮为常驻 chip，见 NORM-FEEDBACK F288 单入口原则）。
     *
     * 切换后按当前关键词重搜，让结果集立即反映新口径；关键词为空时只落偏好，不触发空搜索。
     */
    private fun togglePrecisionSearch() {
        val enabled = !precisionSearchEnabled
        precisionSearchEnabled = enabled
        putPrefBoolean(PreferKey.precisionSearch, enabled)
        composeSearchQuery.trim().takeIf { it.isNotEmpty() }?.let {
            isManualStopSearch = false
            applyQuery(it, submit = true)
        }
    }

    /** F75：切结果卡紧凑密度（本页私有偏好，不动全局书架样式） */
    private fun toggleCompactSearchResult() {
        val compact = !compactSearchResult
        compactSearchResult = compact
        putPrefBoolean(PreferKey.searchResultCompact, compact)
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
                // video-playlist-continuity：注入同源搜索结果列表（跨影片续播，followup F1 恢复）
                // 一期收敛同源子序列（跨源追加涉切源上下文，S9 混源 Provider 后续扩展）
                val sameOrigin = searchResults.filter { it.origin == book.origin && SearchBookOpenHelper.isVideoResult(it, viewModel.searchScope.getSingleBookSourcePart()?.bookSourceType) }
                val idx = sameOrigin.indexOfFirst { it.bookUrl == book.bookUrl }
                if (idx >= 0) {
                    VideoPlaylistHolder.set(sameOrigin, idx)
                }
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
     * 点击历史关键字（原 `searchView.setQuery` 的三分支等价物）：
     * 关键词与当前一致、或书架中查不到同名书 ⇒ 回填并直接搜索；否则只回填（展示书架命中）。
     */
    private fun searchHistory(key: String) {
        lifecycleScope.launch {
            when {
                composeSearchQuery == key -> applyQuery(key, submit = true)

                withContext(IO) { appDb.bookDao.findByName(key).isEmpty() } ->
                    applyQuery(key, submit = true)

                else -> applyQuery(key, submit = false)
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
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.sure_clear_search_history),
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = { viewModel.clearHistory() }
        )
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