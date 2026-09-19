package io.legado.app.ui.book.source.manage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.dao.SourceBookCount
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.databinding.ActivityBookSourceBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.CheckSource
import io.legado.app.model.Debug
import io.legado.app.model.ImportCheck
import io.legado.app.model.QualityCheckSession
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.association.showShibbolethDialog
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.source.debug.BookSourceDebugActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.config.CheckSourceConfig
import io.legado.app.ui.config.ImportCheckConfigDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixActionRow
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.replaceByIndex
import io.legado.app.ui.widget.compose.replaceFirst
import io.legado.app.ui.widget.compose.replaceMatching
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeSuggestionTextInputDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.ACache
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.ShibbolethCodec
import io.legado.app.utils.cnCompare
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.launch
import io.legado.app.utils.observeEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 书源管理界面
 */
class BookSourceActivity : VMBaseActivity<ActivityBookSourceBinding, BookSourceViewModel>() {
    override val binding by viewBinding(ActivityBookSourceBinding::inflate)
    override val viewModel by viewModels<BookSourceViewModel>()
    private val importRecordKey = "bookSourceRecordKey"
    private var sourceFlowJob: Job? = null
    private var checkMessageRefreshJob: Job? = null
    private val groups = linkedSetOf<String>()
    private var sort = BookSourceSort.Default
        private set
    private var sortAscending = true
        private set
    // 批D：校验进度横幅（原 Snackbar 改 Compose 状态驱动）
    private val checkBannerState = mutableStateOf<String?>(null)
    // bugfix-0908 T5：数据版本信号（实际变更时递增），替代原 BookSourceScreen 内 1 万条 joinToString 巨串指纹
    internal var sourceDataVersion by mutableStateOf(0)
    private var groupSourcesByDomain = false
    private val finalMessageRegex = Regex("成功|失败")
    private val sourcesState = mutableStateListOf<BookSourcePart>()
    private val selectedUrls = mutableStateOf<Set<String>>(emptySet())
    private val isSelectMode = mutableStateOf(false)
    private val searchQueryState = mutableStateOf("")
    private val showSourceHostState = mutableStateOf(false)
    private val sourceHostHeaders = mutableStateMapOf<String, String?>()
    private val debugMessagesState = mutableStateMapOf<String, String>()
    // P1/B1-②：书源 → 引用书籍数（不参与排序，仅用于列表副标题展示）
    private val bookCountsState = mutableStateMapOf<String, Int>()
    private val isCheckingState = mutableStateOf(Debug.isChecking)
    private val qrResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        showDialogFragment(ImportBookSourceDialog(it))
    }
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportBookSourceDialog(uri.toString()))
        }
    }
    private val exportDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            val url = uri.toString()
            showComposeTextInputDialog(
                title = getString(R.string.export_success),
                hint = getString(R.string.path),
                initialValue = url,
                message = if (url.isAbsUrl()) {
                    DirectLinkUpload.getSummary()
                } else {
                    null
                },
                readOnly = true,
                neutralText = getString(R.string.shibboleth)
                    .takeIf { ShibbolethCodec.canEncodeUrl(url) },
                onPositive = {
                    sendToClip(url)
                },
                onNeutral = { showShibbolethDialog(url, ShibbolethCodec.BOOK_SOURCE) }
            )
        }
    }
    // ui-theme-governance-polish P6：管理族宿主接入背景透明度（1.5 封闭清单成员）
    override fun manageBackgroundAlphaEnabled(): Boolean = true

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeContent()
        upBookSource()
        initLiveDataGroup()
        initBookCounts()
        resumeCheckSource()
        if (!LocalConfig.bookSourcesHelpVersionIsLast) {
            showHelp("SourceMBookHelp")
        }
    }

    private fun initComposeContent() {
        binding.titleBar.visibility = View.GONE
        val container = binding.recyclerView.parent as? ViewGroup ?: return
        val index = container.indexOfChild(binding.recyclerView)
        container.removeView(binding.recyclerView)
        // F2/4.3：旧 View SelectActionBar 已从布局摘除（XML 死声明同步删除），多选操作收口顶栏
        val cv = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setContent {
                AppManagementScaffold(
                    title = getString(R.string.book_source_manage),
                    selectedCount = selectedUrls.value.size,
                    totalCount = sourcesState.size,
                    searchQuery = searchQueryState.value,
                    searchHint = getString(R.string.search_book_source),
                    onSearchChange = ::updateSearchQuery,
                    topActions = listOf(
                        AppManagementAction(
                            text = getString(R.string.sort),
                            iconRes = R.drawable.ic_sort,
                            onClick = ::showSortMenu
                        ),
                        AppManagementAction(
                            text = getString(R.string.menu_action_group),
                            iconRes = R.drawable.ic_groups,
                            onClick = ::showFilterMenu
                        ),
                        AppManagementAction(
                            text = getString(R.string.add_book_source),
                            iconRes = R.drawable.ic_add,
                            onClick = { startActivity<BookSourceEditActivity>() }
                        ),
                        AppManagementAction(
                            text = getString(R.string.more_menu),
                            iconRes = R.drawable.ic_more_vert,
                            menuActions = ::pageMenuActions
                        )
                    ),
                    bottomActions = listOf(
                        AppManagementAction(
                            text = getString(R.string.enable_selection),
                            onClick = ::enableSelected
                        ),
                        AppManagementAction(
                            text = getString(R.string.disable_selection),
                            onClick = ::disableSelected
                        ),
                        AppManagementAction(
                            text = getString(R.string.enable_explore),
                            onClick = ::enableExploreSelected
                        ),
                        AppManagementAction(
                            text = getString(R.string.disable_explore),
                            onClick = ::disableExploreSelected
                        ),
                        AppManagementAction(
                            text = getString(R.string.add_group),
                            onClick = ::selectionAddToGroups
                        ),
                        AppManagementAction(
                            text = getString(R.string.remove_group),
                            onClick = ::selectionRemoveFromGroups
                        ),
                        AppManagementAction(
                            text = getString(R.string.selection_to_top),
                            onClick = ::topSelected
                        ),
                        AppManagementAction(
                            text = getString(R.string.selection_to_bottom),
                            onClick = ::bottomSelected
                        ),
                        AppManagementAction(
                            text = getString(R.string.check_select_source),
                            onClick = { checkSource() }
                        ),
                        AppManagementAction(
                            text = getString(R.string.export_selection),
                            onClick = ::exportSelected
                        ),
                        AppManagementAction(
                            text = getString(R.string.share_selected_source),
                            onClick = ::shareSelected
                        ),
                        AppManagementAction(
                            text = getString(R.string.check_selected_interval),
                            onClick = ::checkSelectedInterval
                        ),
                        AppManagementAction(
                            text = getString(R.string.delete),
                            danger = true,
                            onClick = ::onClickSelectBarMainAction
                        )
                    ),
                    onBack = { finish() },
                    onSelectAll = { selectAll(true) },
                    onInvertSelection = { revertSelection() }
                ) {
                    BookSourceScreen(
                        sources = sourcesState,
                        selectedUrls = selectedUrls.value,
                        isSelectMode = isSelectMode.value,
                        showSourceHost = showSourceHostState.value,
                        sourceHostHeaders = sourceHostHeaders,
                        debugMessages = debugMessagesState,
                        bookCounts = bookCountsState,
                        isChecking = isCheckingState.value,
                        checkBannerText = checkBannerState.value,
                        onCancelCheck = ::cancelSourceCheck,
                        dataVersion = sourceDataVersion,
                        searchQuery = searchQueryState.value,
                        emptyActions = BookSourceEmptyActions(
                            onAdd = { startActivity<BookSourceEditActivity>() },
                            onImportLocal = {
                                importDoc.launch {
                                    mode = HandleFileContract.FILE
                                    allowExtensions = arrayOf("txt", "json")
                                }
                            },
                            onImportOnline = ::showImportDialog,
                            onImportQr = { qrResult.launch() },
                            onClearSearch = { updateSearchQuery("") }
                        ),
                        // 与 CHECK_SOURCE_DONE 的自动筛选同口径（同字面量，保证命中同一分组）
                        onFilterFailed = { updateSearchQuery(FAILED_SOURCE_GROUP_KEY) },
                        onCheck = { checkSource(sourcesState.toList()) },
                        reorderEnabled = sort == BookSourceSort.Default &&
                            searchQueryState.value.isBlank() &&
                            !groupSourcesByDomain,
                        onReorder = { reordered ->
                            val ordered = if (sortAscending) reordered else reordered.asReversed()
                            viewModel.upOrder(
                                ordered.mapIndexed { index, source ->
                                    source.copy(customOrder = index)
                                }
                            )
                        },
                        onToggleSelect = ::toggleSourceSelection,
                        onToggleEnabled = ::toggleSourceEnabled,
                        onEdit = ::edit,
                        sourceMenuActions = ::sourceMenuActions
                    )
                }
            }
        }
        container.addView(cv, index)
    }

    // H17（2026-08-28）：系统 options menu 链删除（onCompatCreateOptionsMenu/onPrepareOptionsMenu/
    // onCompatOptionsItemSelected）——TitleBar 已 GONE，本页全 Compose（AppManagementScaffold），
    // 排序/分组/导入/帮助等入口由 showSortMenu/showFilterMenu/pageMenuActions 覆盖，inflate book_source
    // 为不可达死代码（菜单统一收敛）。

    private fun showSortMenu() {
        val labels = listOf(
            getString(R.string.sort_desc),
            getString(R.string.sort_manual),
            getString(R.string.sort_auto),
            getString(R.string.sort_by_name),
            getString(R.string.sort_by_url),
            getString(R.string.sort_by_lastUpdateTime),
            getString(R.string.sort_by_respondTime),
            getString(R.string.is_enabled)
        )
        showComposeActionListDialog(
            title = getString(R.string.sort),
            labels = labels
        ) { index ->
            when (index) {
                0 -> {
                    sortAscending = !sortAscending
                    refreshBookSources()
                }
                1 -> setSort(BookSourceSort.Default)
                2 -> setSort(BookSourceSort.Weight)
                3 -> setSort(BookSourceSort.Name)
                4 -> setSort(BookSourceSort.Url)
                5 -> setSort(BookSourceSort.Update)
                6 -> setSort(BookSourceSort.Respond)
                7 -> setSort(BookSourceSort.Enable)
            }
        }
    }

    private fun showFilterMenu() {
        val fixedLabels = listOf(
            getString(R.string.group_manage),
            getString(R.string.enabled),
            getString(R.string.disabled),
            getString(R.string.need_login),
            getString(R.string.no_group),
            getString(R.string.enabled_explore),
            getString(R.string.disabled_explore)
        )
        if (groups.size > GROUP_ACTION_LIST_LIMIT) {
            val labels = fixedLabels + getString(R.string.group_select)
            showComposeActionListDialog(
                title = getString(R.string.menu_action_group),
                labels = labels
            ) { index ->
                when (index) {
                    0 -> showDialogFragment<GroupManageDialog>()
                    1 -> updateSearchQuery(getString(R.string.enabled))
                    2 -> updateSearchQuery(getString(R.string.disabled))
                    3 -> updateSearchQuery(getString(R.string.need_login))
                    4 -> updateSearchQuery(getString(R.string.no_group))
                    5 -> updateSearchQuery(getString(R.string.enabled_explore))
                    6 -> updateSearchQuery(getString(R.string.disabled_explore))
                    7 -> showSourceGroupFilterDialog()
                }
            }
            return
        }
        val labels = fixedLabels + groups
        showComposeActionListDialog(
            title = getString(R.string.menu_action_group),
            labels = labels
        ) { index ->
            when (index) {
                0 -> showDialogFragment<GroupManageDialog>()
                1 -> updateSearchQuery(getString(R.string.enabled))
                2 -> updateSearchQuery(getString(R.string.disabled))
                3 -> updateSearchQuery(getString(R.string.need_login))
                4 -> updateSearchQuery(getString(R.string.no_group))
                5 -> updateSearchQuery(getString(R.string.enabled_explore))
                6 -> updateSearchQuery(getString(R.string.disabled_explore))
                else -> updateSearchQuery("group:${labels[index]}")
            }
        }
    }

    private fun showSourceGroupFilterDialog() {
        showDialogFragment(
            SourceGroupFilterDialog.create(groups.toList()) { group ->
                updateSearchQuery("group:$group")
            }
        )
    }

    private fun pageMenuActions(): List<AppManagementMenuAction> {
        return listOf(
            AppManagementMenuAction(getString(R.string.import_local)) {
                importDoc.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("txt", "json")
                }
            },
            AppManagementMenuAction(getString(R.string.import_on_line)) {
                showImportDialog()
            },
            AppManagementMenuAction(getString(R.string.import_by_qr_code)) {
                qrResult.launch()
            },
            AppManagementMenuAction(getString(R.string.import_check_config)) {
                showDialogFragment(ImportCheckConfigDialog())
            },
            AppManagementMenuAction(getString(R.string.quality_report_title)) {
                showQualityReportScopeDialog()
            },
            AppManagementMenuAction(getString(R.string.group_sources_by_domain)) {
                setGroupSourcesByDomain(!groupSourcesByDomain)
            },
            AppManagementMenuAction(getString(R.string.help)) {
                showHelp("SourceMBookHelp")
            }
        )
    }

    /**
     * 质量体检范围选择（spec：选中源/当前分组/全部；体检只读不写库）
     */
    private fun showQualityReportScopeDialog() {
        val options = arrayOf(
            getString(R.string.quality_report_scope_all),
            getString(R.string.quality_report_scope_group),
            getString(R.string.quality_report_scope_selected)
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.quality_report_title)
            .setItems(options) { _, which ->
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val sources = when (which) {
                        1 -> {
                            val query = searchQueryState.value
                            val group = if (query.startsWith("group:")) query.removePrefix("group:") else ""
                            if (group.isBlank()) {
                                appDb.bookSourceDao.search("")
                            } else {
                                appDb.bookSourceDao.groupSearch(group)
                            }
                        }
                        2 -> selectedUrls.value.mapNotNull { appDb.bookSourceDao.getBookSource(it) }
                        else -> appDb.bookSourceDao.search("")
                    }
                    if (sources.isEmpty()) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            toastOnUi(getString(R.string.empty))
                        }
                        return@launch
                    }
                    QualityCheckSession.startBookCheck(this@BookSourceActivity, sources, ImportCheck.toProbeOptions())
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        // 体检首启一句话分工说明（P7：体检=只读评分+批量清理；校验=标注分组+权重）
                        if (!getPrefBoolean(PreferKey.qualityReportFirstHint)) {
                            putPrefBoolean(PreferKey.qualityReportFirstHint, true)
                            androidx.appcompat.app.AlertDialog.Builder(this@BookSourceActivity)
                                .setTitle(R.string.quality_report_title)
                                .setMessage(R.string.quality_report_first_hint)
                                .setPositiveButton(R.string.ok, null)
                                .show()
                        }
                        startActivity<SourceQualityReportActivity> {
                            putExtra("type", QualityCheckSession.TAG_BOOK)
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setSort(next: BookSourceSort) {
        sort = next
        refreshBookSources()
    }

    private fun setGroupSourcesByDomain(enabled: Boolean) {
        groupSourcesByDomain = enabled
        showSourceHostState.value = enabled
        // bugfix-0908 T5：host 头随 flow map 步（IO）重算回填，无需在此同步重建
        refreshBookSources()
    }

    private fun refreshBookSources() {
        upBookSource(searchQueryState.value)
    }

    private fun upBookSource(searchKey: String? = null) {
        sourceFlowJob?.cancel()
        sourceFlowJob = lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> {
                    appDb.bookSourceDao.flowAll()
                }

                searchKey == getString(R.string.enabled) -> {
                    appDb.bookSourceDao.flowEnabled()
                }

                searchKey == getString(R.string.disabled) -> {
                    appDb.bookSourceDao.flowDisabled()
                }

                searchKey == getString(R.string.need_login) -> {
                    appDb.bookSourceDao.flowLogin()
                }

                searchKey == getString(R.string.no_group) -> {
                    appDb.bookSourceDao.flowNoGroup()
                }

                searchKey == getString(R.string.enabled_explore) -> {
                    appDb.bookSourceDao.flowEnabledExplore()
                }

                searchKey == getString(R.string.disabled_explore) -> {
                    appDb.bookSourceDao.flowDisabledExplore()
                }

                searchKey.startsWith("group:") -> {
                    val key = searchKey.substringAfter("group:")
                    appDb.bookSourceDao.flowGroupSearch(key)
                }

                else -> {
                    appDb.bookSourceDao.flowSearch(searchKey)
                }
            }.map { data ->
                // bugfix-0908 T5：host 解析收敛到本 map 步（IO 线程）一次性预计算局部表，
                // 消除原比较器内重复查询与 hostMap 跨线程写（1 万条卡死/崩溃根因之一）
                val localHosts = if (groupSourcesByDomain) {
                    data.associate { it.bookSourceUrl to getSourceHostName(it.bookSourceUrl) }
                } else {
                    emptyMap()
                }
                val sorted = if (groupSourcesByDomain) {
                    data.sortedWith(
                        compareBy<BookSourcePart> { localHosts[it.bookSourceUrl] == "#" }
                            .thenBy { localHosts[it.bookSourceUrl].orEmpty() }
                            .thenByDescending { it.lastUpdateTime })
                } else if (sortAscending) {
                    when (sort) {
                        BookSourceSort.Weight -> data.sortedBy { it.weight }
                        BookSourceSort.Name -> data.sortedWith { o1, o2 ->
                            o1.bookSourceName.cnCompare(o2.bookSourceName)
                        }

                        BookSourceSort.Url -> data.sortedBy { it.bookSourceUrl }
                        BookSourceSort.Update -> data.sortedByDescending { it.lastUpdateTime }
                        BookSourceSort.Respond -> data.sortedBy { it.respondTime }
                        BookSourceSort.Enable -> data.sortedWith { o1, o2 ->
                            var sort = -o1.enabled.compareTo(o2.enabled)
                            if (sort == 0) {
                                sort = o1.bookSourceName.cnCompare(o2.bookSourceName)
                            }
                            sort
                        }

                        else -> data
                    }
                } else {
                    when (sort) {
                        BookSourceSort.Weight -> data.sortedByDescending { it.weight }
                        BookSourceSort.Name -> data.sortedWith { o1, o2 ->
                            o2.bookSourceName.cnCompare(o1.bookSourceName)
                        }

                        BookSourceSort.Url -> data.sortedByDescending { it.bookSourceUrl }
                        BookSourceSort.Update -> data.sortedBy { it.lastUpdateTime }
                        BookSourceSort.Respond -> data.sortedByDescending { it.respondTime }
                        BookSourceSort.Enable -> data.sortedWith { o1, o2 ->
                            var sort = o1.enabled.compareTo(o2.enabled)
                            if (sort == 0) {
                                sort = o1.bookSourceName.cnCompare(o2.bookSourceName)
                            }
                            sort
                        }

                        else -> data.reversed()
                    }
                }
                sorted to localHosts
            }.flowWithLifecycleAndDatabaseChange(
                lifecycle,
                table = AppDatabase.BOOK_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("书源界面更新书源出错", it)
            }.flowOn(IO).conflate().collect { (data, localHosts) ->
                val listChanged = sourcesState.replaceByIndex(data, ::sameBookSourcePartContent)
                val currentUrls = data.mapTo(mutableSetOf()) { it.bookSourceUrl }
                selectedUrls.value = selectedUrls.value.filter { it in currentUrls }.toSet()
                isSelectMode.value = selectedUrls.value.isNotEmpty()
                val headersChanged = updateSourceHostHeaders(data, localHosts)
                refreshDebugMessages()
                // bugfix-0908 T5：仅实际变更时递增重组版本（identical 重发射不重置拖拽中间态）
                if (listChanged || headersChanged) {
                    sourceDataVersion++
                }
                delay(500)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // P1/B1-②：回到本页时主动刷新一次引用计数（覆盖"离开期间书籍被增删/换源"的场景；
        // 不把 books 表纳入观察，避免阅读进度写入（每次翻页）触发聚合重算）
        refreshBookCounts()
    }

    /**
     * P1/B1-②：书源引用书籍数 —— 观察 `book_sources` 变化（书源增删改）+ 页面进入时主动拉一次。
     * 计数仅回填 [bookCountsState]（不参与排序，避免整表重排）。
     */
    private fun initBookCounts() {
        lifecycleScope.launch {
            appDb.bookDao.flowBookCountByOrigin()
                .flowWithLifecycleAndDatabaseChange(
                    lifecycle,
                    table = AppDatabase.BOOK_SOURCE_TABLE_NAME
                )
                .debounce(800)
                .catch {
                    AppLog.put("书源引用计数更新出错", it)
                }
                .flowOn(IO)
                .collect { list ->
                    applyBookCounts(list)
                }
        }
        refreshBookCounts()
    }

    private fun refreshBookCounts() {
        lifecycleScope.launch(IO) {
            kotlin.runCatching {
                appDb.bookDao.getBookCountByOrigin()
            }.onSuccess { list ->
                applyBookCounts(list)
            }.onFailure {
                AppLog.put("书源引用计数刷新出错", it)
            }
        }
    }

    private fun applyBookCounts(list: List<SourceBookCount>) {
        val map = list.associate { it.bookSourceUrl to it.bookCount }
        if (map == bookCountsState.toMap()) return
        bookCountsState.clear()
        bookCountsState.putAll(map)
    }

    override fun onPause() {
        super.onPause()
    }


    private fun initLiveDataGroup() {
        lifecycleScope.launch {
            appDb.bookSourceDao.flowGroups()
                .flowWithLifecycleAndDatabaseChange(
                    lifecycle,
                    table = AppDatabase.BOOK_SOURCE_TABLE_NAME
                )
                .conflate()
                .distinctUntilChanged()
                .collect {
                    groups.clear()
                    groups.addAll(it)
                    delay(500)
                }
        }
    }

    fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            selectedUrls.value = sourcesState.map { it.bookSourceUrl }.toSet()
        } else {
            selectedUrls.value = emptySet()
        }
        isSelectMode.value = selectedUrls.value.isNotEmpty()
    }

    fun revertSelection() {
        val allUrls = sourcesState.map { it.bookSourceUrl }.toSet()
        selectedUrls.value = allUrls - selectedUrls.value
        isSelectMode.value = selectedUrls.value.isNotEmpty()
    }

    fun onClickSelectBarMainAction() {
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.sure_del),
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = {
                viewModel.del(getSelectedSources())
                selectedUrls.value = emptySet()
                isSelectMode.value = false
            }
        )
    }

    private fun enableSelected() {
        val selection = getSelectedSources()
        updateSelectedEnabled(true)
        viewModel.enableSelection(selection)
    }

    private fun disableSelected() {
        val selection = getSelectedSources()
        updateSelectedEnabled(false)
        viewModel.disableSelection(selection)
    }

    private fun enableExploreSelected() {
        val selection = getSelectedSources()
        updateSelectedExplore(true)
        viewModel.enableSelectExplore(selection)
    }

    private fun disableExploreSelected() {
        val selection = getSelectedSources()
        updateSelectedExplore(false)
        viewModel.disableSelectExplore(selection)
    }

    private fun topSelected() {
        viewModel.topSource(*getSelectedSources().toTypedArray())
    }

    private fun bottomSelected() {
        viewModel.bottomSource(*getSelectedSources().toTypedArray())
    }

    private fun exportSelected() {
        viewModel.saveToFile(
            getSelectedSources(),
            sourcesState.size,
            searchQueryState.value,
            sortAscending,
            sort
        ) { file, name ->
            exportDir.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData(
                    name,
                    file,
                    "application/json"
                )
            }
        }
    }

    private fun shareSelected() {
        viewModel.saveToFile(
            getSelectedSources(),
            sourcesState.size,
            searchQueryState.value,
            sortAscending,
            sort
        ) { file, _ ->
            share(file)
        }
    }

    private fun checkSource(items: List<BookSourcePart> = getSelectedSources()) {
        showComposeTextInputDialog(
            title = getString(R.string.search_book_key),
            hint = "search word",
            initialValue = CheckSource.keyword,
            neutralText = getString(R.string.check_source_config),
            onPositive = { text ->
                keepScreenOn(true)
                if (text.isNotEmpty()) {
                    CheckSource.keyword = text
                }
                val selectItems = items
                CheckSource.start(this@BookSourceActivity, selectItems)
                val currentItems = sourcesState
                val firstItem = currentItems.indexOf(selectItems.firstOrNull())
                val lastItem = currentItems.indexOf(selectItems.lastOrNull())
                Debug.isChecking = firstItem >= 0 && lastItem >= 0
                updateCheckingState(Debug.isChecking)
                refreshDebugMessages(force = true)
                startCheckMessageRefreshJob(firstItem, lastItem)
            },
            onNeutral = {
                showDialogFragment<CheckSourceConfig>()
            }
        )
        //手动设置监听 避免点击打开校验设置后对话框关闭
    }

    private fun resumeCheckSource() {
        if (!Debug.isChecking) {
            return
        }
        keepScreenOn(true)
        CheckSource.resume(this)
        updateCheckingState(Debug.isChecking)
        refreshDebugMessages(force = true)
        startCheckMessageRefreshJob(0, 0)
    }

    private fun selectionAddToGroups() {
        showComposeSuggestionTextInputDialog(
            title = getString(R.string.add_group),
            hint = getString(R.string.group_name),
            suggestions = groups.toList(),
            onPositive = { text ->
                if (text.isNotEmpty()) {
                    viewModel.selectionAddToGroups(getSelectedSources(), text)
                }
            }
        )
    }

    private fun selectionRemoveFromGroups() {
        showComposeSuggestionTextInputDialog(
            title = getString(R.string.remove_group),
            hint = getString(R.string.group_name),
            suggestions = groups.toList(),
            onPositive = { text ->
                if (text.isNotEmpty()) {
                    viewModel.selectionRemoveFromGroups(getSelectedSources(), text)
                }
            }
        )
    }

    private fun showImportDialog() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls: MutableList<String> = aCache
            .getAsString(importRecordKey)
            ?.splitNotBlank(",")
            ?.toMutableList() ?: mutableListOf()
        showComposeSuggestionTextInputDialog(
            title = getString(R.string.import_on_line),
            hint = "url",
            suggestions = cacheUrls,
            deletable = true,
            onPositive = { text ->
                if (text.isAbsUrl() && !cacheUrls.contains(text)) {
                    cacheUrls.add(0, text)
                    aCache.put(importRecordKey, cacheUrls.joinToString(","))
                }
                showDialogFragment(ImportBookSourceDialog(text))
            },
            onSuggestionDeleted = { url ->
                cacheUrls.remove(url)
                aCache.put(importRecordKey, cacheUrls.joinToString(","))
            }
        )
    }

    override fun observeLiveBus() {
        observeEvent<String>(EventBus.CHECK_SOURCE) { msg ->
            // 批D：原 Snackbar 改 Compose 横幅状态（取消动作见 cancelSourceCheck）
            checkBannerState.value = msg
        }
        observeEvent<Int>(EventBus.CHECK_SOURCE_DONE) {
            keepScreenOn(false)
            checkBannerState.value = null
            updateCheckingState(false)
            refreshDebugMessages(force = true)
            groups.forEach { group ->
                if (group.contains(FAILED_SOURCE_GROUP_KEY) && searchQueryState.value.isEmpty()) {
                    updateSearchQuery(FAILED_SOURCE_GROUP_KEY)
                    toastOnUi("发现有失效书源，已为您自动筛选！")
                }
            }
        }
    }

    // 批D：校验横幅取消动作（承接原 Snackbar action 逻辑）
    private fun cancelSourceCheck() {
        checkBannerState.value = null
        CheckSource.stop(this)
        Debug.finishChecking()
        updateCheckingState(false)
        refreshDebugMessages(force = true)
    }

    private fun startCheckMessageRefreshJob(firstItem: Int, lastItem: Int) {
        checkMessageRefreshJob?.cancel()
        checkMessageRefreshJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    refreshDebugMessages()
                    if (!Debug.isChecking) {
                        updateCheckingState(false)
                        refreshDebugMessages(force = true)
                        checkMessageRefreshJob?.cancel()
                    }
                    delay(500L)
                }
            }
        }
    }

    /**
     * 保持亮屏
     */
    private fun keepScreenOn(on: Boolean) {
        val isScreenOn =
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (on == isScreenOn) return
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun getSelectedSources(): List<BookSourcePart> {
        val urls = selectedUrls.value
        return sourcesState.filter { it.bookSourceUrl in urls }
    }

    private fun toggleSourceSelection(source: BookSourcePart) {
        val current = selectedUrls.value.toMutableSet()
        if (source.bookSourceUrl in current) {
            current.remove(source.bookSourceUrl)
        } else {
            current.add(source.bookSourceUrl)
        }
        selectedUrls.value = current
        isSelectMode.value = current.isNotEmpty()
    }

    /**
     * 本地变更统一入口：改完 [sourcesState] 立即递增渲染版本信号。
     * BookSourceScreen 的行模型以 (sourceDataVersion, showSourceHost) 为缓存键；本地改动若不递增版本号，
     * 行渲染会继续读旧快照（开关 checked／发现指示点／菜单文字全部定格，而 DB 实际已更新）。
     */
    private fun mutateSourcesLocally(block: () -> Unit) {
        block()
        sourceDataVersion++
    }

    private fun toggleSourceEnabled(source: BookSourcePart, enabled: Boolean) {
        val updated = source.copy(enabled = enabled)
        mutateSourcesLocally {
            sourcesState.replaceFirst(
                predicate = { it.bookSourceUrl == source.bookSourceUrl },
                transform = { updated }
            )
        }
        viewModel.enable(enabled, listOf(updated))
    }

    private fun updateSelectedEnabled(enabled: Boolean) {
        val urls = selectedUrls.value
        if (urls.isEmpty()) return
        mutateSourcesLocally {
            sourcesState.replaceMatching({ it.bookSourceUrl in urls }) { it.copy(enabled = enabled) }
        }
    }

    private fun updateSelectedExplore(enabled: Boolean) {
        val urls = selectedUrls.value
        if (urls.isEmpty()) return
        mutateSourcesLocally {
            sourcesState.replaceMatching({ it.bookSourceUrl in urls }) {
                it.copy(enabledExplore = enabled)
            }
        }
    }

    private fun updateSearchQuery(query: String) {
        if (searchQueryState.value == query) {
            return
        }
        searchQueryState.value = query
        upBookSource(query)
    }

    private fun checkSelectedInterval() {
        val urls = selectedUrls.value
        val positions = sourcesState.mapIndexedNotNull { index, source ->
            if (source.bookSourceUrl in urls) index else null
        }
        if (positions.isEmpty()) return
        val newSelected = urls.toMutableSet()
        for (index in positions.min()..positions.max()) {
            newSelected.add(sourcesState[index].bookSourceUrl)
        }
        selectedUrls.value = newSelected
        isSelectMode.value = newSelected.isNotEmpty()
    }

    private fun buildSourceHostHeaders(
        sources: List<BookSourcePart>,
        localHosts: Map<String, String>
    ): Map<String, String?> {
        if (!groupSourcesByDomain) return emptyMap()
        val headers = linkedMapOf<String, String?>()
        var lastHost: String? = null
        sources.forEach { source ->
            val host = localHosts[source.bookSourceUrl]
            headers[source.bookSourceUrl] = if (host != lastHost) host else null
            lastHost = host
        }
        return headers
    }

    private fun updateSourceHostHeaders(
        sources: List<BookSourcePart>,
        localHosts: Map<String, String>
    ): Boolean {
        val next = buildSourceHostHeaders(sources, localHosts)
        var changed = false
        sourceHostHeaders.keys
            .filter { it !in next }
            .toList()
            .forEach { sourceHostHeaders.remove(it); changed = true }
        next.forEach { (url, header) ->
            if (sourceHostHeaders[url] != header) {
                sourceHostHeaders[url] = header
                changed = true
            }
        }
        return changed
    }

    private fun refreshDebugMessages(force: Boolean = false) {
        val next = buildDebugMessagesSnapshot()
        debugMessagesState.keys
            .filter { it !in next }
            .toList()
            .forEach { debugMessagesState.remove(it) }
        next.forEach { (url, message) ->
            if (force || debugMessagesState[url] != message) {
                debugMessagesState[url] = message
            }
        }
    }

    private fun updateCheckingState(checking: Boolean) {
        if (isCheckingState.value != checking) {
            isCheckingState.value = checking
        }
    }

    private fun buildDebugMessagesSnapshot(): Map<String, String> {
        return sourcesState.mapNotNull { source ->
            val initial = Debug.debugMessageMap[source.bookSourceUrl].orEmpty()
            if (initial.isBlank()) {
                null
            } else {
                if (!Debug.isChecking && !initial.contains(finalMessageRegex)) {
                    Debug.updateFinalMessage(source.bookSourceUrl, "校验失败")
                }
                val latest = Debug.debugMessageMap[source.bookSourceUrl].orEmpty()
                source.bookSourceUrl to latest
            }
        }.toMap()
    }

    // bugfix-0908 T5：hostMap/getSourceHost 删除（原 IO 比较器写 + 主线程读的跨线程竞争源），
    // 改 getSourceHostName 纯查询（IO map 步内局部表构建用）
    private fun getSourceHostName(origin: String): String {
        return NetworkUtils.getSubDomainOrNull(origin) ?: "#"
    }

    private fun sourceMenuActions(bookSource: BookSourcePart): List<AppManagementMenuAction> {
        return buildList {
            if (sort == BookSourceSort.Default) {
                add(AppManagementMenuAction(getString(R.string.to_top)) { toTop(bookSource) })
                add(AppManagementMenuAction(getString(R.string.to_bottom)) { toBottom(bookSource) })
            }
            if (bookSource.hasLoginUrl) {
                add(
                    AppManagementMenuAction(getString(R.string.login)) {
                        startActivity<SourceLoginActivity> {
                            putExtra("type", "bookSource")
                            putExtra("key", bookSource.bookSourceUrl)
                        }
                    }
                )
            }
            add(AppManagementMenuAction(getString(R.string.search)) { searchBook(bookSource) })
            add(AppManagementMenuAction(getString(R.string.debug)) { debug(bookSource) })
            add(
                AppManagementMenuAction(
                    text = getString(R.string.delete),
                    danger = true,
                    onClick = { del(bookSource) }
                )
            )
            if (bookSource.hasExploreUrl) {
                add(
                    AppManagementMenuAction(
                        if (bookSource.enabledExplore) {
                            getString(R.string.disable_explore)
                        } else {
                            getString(R.string.enable_explore)
                        }
                    ) {
                        enableExplore(!bookSource.enabledExplore, bookSource)
                    }
                )
            }
        }
    }

    private fun del(bookSource: BookSourcePart) {
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.sure_del) + "\n" + bookSource.bookSourceName,
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = { viewModel.del(listOf(bookSource)) }
        )
    }

    private fun edit(bookSource: BookSourcePart) {
        startActivity<BookSourceEditActivity> {
            putExtra("sourceUrl", bookSource.bookSourceUrl)
        }
    }

    private fun upOrder(items: List<BookSourcePart>) {
        viewModel.upOrder(items)
    }

    private fun enable(enable: Boolean, bookSource: BookSourcePart) {
        viewModel.enable(enable, listOf(bookSource))
    }

    private fun enableExplore(enable: Boolean, bookSource: BookSourcePart) {
        val updated = bookSource.copy(enabledExplore = enable)
        mutateSourcesLocally {
            sourcesState.replaceFirst(
                predicate = { it.bookSourceUrl == bookSource.bookSourceUrl },
                transform = { updated }
            )
        }
        viewModel.enableExplore(enable, listOf(updated))
    }

    private fun sameBookSourcePartContent(old: BookSourcePart, new: BookSourcePart): Boolean {
        return old.bookSourceUrl == new.bookSourceUrl &&
            old.bookSourceName == new.bookSourceName &&
            old.bookSourceGroup == new.bookSourceGroup &&
            old.customOrder == new.customOrder &&
            old.enabled == new.enabled &&
            old.enabledExplore == new.enabledExplore &&
            old.hasLoginUrl == new.hasLoginUrl &&
            old.lastUpdateTime == new.lastUpdateTime &&
            old.respondTime == new.respondTime &&
            old.weight == new.weight &&
            old.hasExploreUrl == new.hasExploreUrl &&
            old.eventListener == new.eventListener &&
            old.bookSourceType == new.bookSourceType
    }

    private fun toTop(bookSource: BookSourcePart) {
        if (sortAscending) {
            viewModel.topSource(bookSource)
        } else {
            viewModel.bottomSource(bookSource)
        }
    }

    private fun toBottom(bookSource: BookSourcePart) {
        if (sortAscending) {
            viewModel.bottomSource(bookSource)
        } else {
            viewModel.topSource(bookSource)
        }
    }

    private fun searchBook(bookSource: BookSourcePart) {
        SearchActivity.start(this, bookSource)
    }

    private fun debug(bookSource: BookSourcePart) {
        startActivity<BookSourceDebugActivity> {
            putExtra("key", bookSource.bookSourceUrl)
        }
    }

    override fun finish() {
        if (searchQueryState.value.isEmpty()) {
            super.finish()
        } else {
            updateSearchQuery("")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!Debug.isChecking) {
            Debug.debugMessageMap.clear()
        }
    }

}

private const val GROUP_ACTION_LIST_LIMIT = 48

private class SourceGroupFilterDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private var groups: List<String> = emptyList()
    private var onGroupSelected: ((String) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val allGroups = remember { groups }
                var query by rememberSaveable { mutableStateOf("") }
                val style = rememberAppDialogStyle()
                val filteredGroups by remember(allGroups, query) {
                    derivedStateOf {
                        val key = query.trim()
                        if (key.isBlank()) {
                            allGroups
                        } else {
                            allGroups.filter { it.contains(key, ignoreCase = true) }
                        }
                    }
                }
                AppDialogFrame(
                    title = stringResource(R.string.group_select),
                    scrollContent = false,
                    content = {
                        CompositionLocalProvider(
                            LocalTextStyle provides LocalTextStyle.current.copy(
                                fontFamily = style.bodyFontFamily
                            )
                        ) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(stringResource(R.string.search)) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = style.primaryText,
                                    unfocusedTextColor = style.primaryText,
                                    focusedContainerColor = style.fieldSurface,
                                    unfocusedContainerColor = style.fieldSurface,
                                    cursorColor = style.accent,
                                    focusedBorderColor = style.accent.copy(alpha = 0.55f),
                                    unfocusedBorderColor = style.stroke,
                                    focusedLabelColor = style.accent,
                                    unfocusedLabelColor = style.secondaryText
                                ),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = style.primaryText,
                                    fontFamily = style.bodyFontFamily
                                )
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "${filteredGroups.size}/${allGroups.size}",
                                color = style.secondaryText,
                                fontFamily = style.bodyFontFamily,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            val palette = style.toMiuixPalette()
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (filteredGroups.isEmpty()) {
                                    item("empty") {
                                        Text(
                                            text = stringResource(R.string.empty),
                                            color = style.secondaryText,
                                            fontFamily = style.bodyFontFamily
                                        )
                                    }
                                } else {
                                    items(filteredGroups, key = { it }) { group ->
                                        LegadoMiuixActionRow(
                                            text = group,
                                            palette = palette,
                                            onClick = {
                                                dismissAllowingStateLoss()
                                                onGroupSelected?.invoke(group)
                                            },
                                            cornerRadius = style.actionRadius
                                        )
                                    }
                                }
                            }
                        }
                    },
                    actions = {
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.cancel),
                            palette = style.toMiuixPalette(),
                            onClick = { dismissAllowingStateLoss() },
                            cornerRadius = style.actionRadius
                        )
                    }
                )
            }
        }
    }

    companion object {
        fun create(
            groups: List<String>,
            onGroupSelected: (String) -> Unit
        ): SourceGroupFilterDialog {
            return SourceGroupFilterDialog().apply {
                this.groups = groups
                this.onGroupSelected = onGroupSelected
            }
        }
    }
}

/**
 * F68 聚合条「失效」筛选键：与 `CheckSource` 校验后写入的失效分组名同字面量，
 * 也即 `CHECK_SOURCE_DONE` 自动筛选沿用的既有口径（保证两条路径命中同一分组）。
 */
private const val FAILED_SOURCE_GROUP_KEY = "失效"
