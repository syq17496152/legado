package io.legado.app.ui.rss.source.manage

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
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
import androidx.lifecycle.lifecycleScope
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsSearchBar
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.ActivityRssSourceBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.CheckRssSource
import io.legado.app.ui.adapter.FolderItem
import io.legado.app.ui.adapter.SourceFolderAdapter
import io.legado.app.ui.association.ImportRssSourceDialog
import io.legado.app.ui.config.CheckRssSourceConfig
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.ui.widget.recycler.GridSpacingItemDecoration
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.ACache
import io.legado.app.utils.cnCompare
import io.legado.app.utils.dpToPx
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.launch
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 订阅源管理
 */
class RssSourceActivity : VMBaseActivity<ActivityRssSourceBinding, RssSourceViewModel>(),
    PopupMenu.OnMenuItemClickListener,
    SelectActionBar.CallBack,
    RssSourceAdapter.CallBack,
    SourceFolderAdapter.CallBack {

    override val binding by viewBinding(ActivityRssSourceBinding::inflate)
    override val viewModel by viewModels<RssSourceViewModel>()
    private val importRecordKey = "rssSourceRecordKey"
    private val adapter by lazy { RssSourceAdapter(this, this) }
    private val adapterCompact by lazy { RssSourceAdapterCompact(this, this) }
    private val adapterGrid by lazy { RssSourceAdapterGrid(this, this) }
    private val itemTouchCallback by lazy { ItemTouchCallback(adapter) }
    private val verticalDivider by lazy { VerticalDivider(this) }
    private val gridSpacingDecoration = GridSpacingItemDecoration()
    // source-layout-refactor 隐藏字段方案：子目录状态变量
    private var currentType: Int = -1        // -1=全部, 0-2=具体类型（网页/图片/视频）
    private var currentGroup: String? = null // null=根目录, 非空=在某个分组内
    private val inSubDirectory: Boolean get() = currentType >= 0 || currentGroup != null
    // source-folder-cover：管理页固定平铺，不再显示文件夹视图（见 spec AD-03）
    private val isFolderViewMode: Boolean
        get() = false
    private var isShowingFolder: Boolean = false
    // source-layout-refactor 排序升降序
    private var sortAscending = true
    // 域名分组（参照 BookSourceActivity.groupSourcesByDomain）
    private var groupSourcesByDomain = false
    private val hostMap = hashMapOf<String, String>()
    private val searchViewQuery: String
        get() = composeSearchQuery
    private var sourceFlowJob: Job? = null
    private var groups = arrayListOf<String>()
    // source-compose 桥接：Compose 顶栏搜索/菜单状态
    private var composeSearchQuery by mutableStateOf("")
    private var menuExpanded by mutableStateOf(false)
    private var composeGroups by mutableStateOf(listOf<String>())
    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        showDialogFragment(ImportRssSourceDialog(it))
    }
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportRssSourceDialog(uri.toString()))
        }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            alert(R.string.export_success) {
                if (uri.toString().isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary())
                }
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = getString(R.string.path)
                    editView.setText(uri.toString())
                }
                customView { alertBinding.root }
                okButton {
                    sendToClip(uri.toString())
                }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        // source-folder-cover：管理页固定平铺，不显示文件夹视图
        isShowingFolder = false
        initRecyclerView()
        initComposeTopBar()
        upSourceFlow()
        initGroupFlow()
        initSelectActionBar()
    }

    // source-compose 壳层化：顶栏（GlassTopAppBar + 搜索 SettingsSearchBar + 更多菜单 AppDropdownMenu）
    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                Column {
                    GlassTopAppBar(
                        title = getString(R.string.rss_source),
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
                            upSourceFlow(it)
                        },
                        placeholder = getString(R.string.search_rss_source),
                        onSearch = { upSourceFlow(composeSearchQuery) }
                    )
                }
            }
        }
    }

    // source-compose 壳层化：更多菜单数据（含分组标题、勾选态、动态分组）
    private fun buildMenuActions(): List<MenuAction> {
        return buildList {
            // 排序分组
            add(MenuAction(Icons.Default.Sort, getString(R.string.sort), header = true) {})
            add(MenuAction(
                Icons.Default.Sort,
                getString(R.string.sort_desc),
                checked = !sortAscending,
                onClick = { handleMenuAction(R.id.menu_sort_desc) }
            ))
            add(MenuAction(
                Icons.Default.ManageSearch,
                getString(R.string.sort_manual),
                checked = AppConfig.rssSort == 0,
                onClick = { handleMenuAction(R.id.menu_sort_manual) }
            ))
            add(MenuAction(
                Icons.Default.SortByAlpha,
                getString(R.string.sort_by_name),
                checked = AppConfig.rssSort == 1,
                onClick = { handleMenuAction(R.id.menu_sort_name) }
            ))
            add(MenuAction(
                Icons.Default.CheckCircle,
                getString(R.string.is_enabled),
                checked = AppConfig.rssSort == 2,
                onClick = { handleMenuAction(R.id.menu_sort_enable) }
            ))
            add(MenuAction(
                Icons.Default.Link,
                getString(R.string.sort_by_url),
                checked = AppConfig.rssSort == 5,
                onClick = { handleMenuAction(R.id.menu_sort_url) }
            ))
            add(MenuAction(
                Icons.Default.Schedule,
                getString(R.string.sort_by_lastUpdateTime),
                checked = AppConfig.rssSort == 6,
                onClick = { handleMenuAction(R.id.menu_sort_time) }
            ))
            // 类型分组
            add(MenuAction(Icons.Default.Category, getString(R.string.source_type), header = true) {})
            add(MenuAction(
                Icons.Default.AllInclusive,
                getString(R.string.all),
                checked = currentType == -1,
                onClick = { handleMenuAction(R.id.menu_type_all) }
            ))
            add(MenuAction(
                Icons.Default.Language,
                getString(R.string.type_web),
                checked = currentType == 0,
                onClick = { handleMenuAction(R.id.menu_type_0) }
            ))
            add(MenuAction(
                Icons.Default.Image,
                getString(R.string.type_image),
                checked = currentType == 1,
                onClick = { handleMenuAction(R.id.menu_type_1) }
            ))
            add(MenuAction(
                Icons.Default.VideoLibrary,
                getString(R.string.type_video),
                checked = currentType == 2,
                onClick = { handleMenuAction(R.id.menu_type_2) }
            ))
            // 分组管理（含动态分组）
            add(MenuAction(Icons.Default.Folder, getString(R.string.menu_action_group), header = true) {})
            add(MenuAction(
                Icons.Default.FolderOpen,
                getString(R.string.group_manage),
                onClick = { handleMenuAction(R.id.menu_group_manage) }
            ))
            add(MenuAction(
                Icons.Default.ToggleOn,
                getString(R.string.enabled),
                onClick = { handleMenuAction(R.id.menu_enabled_group) }
            ))
            add(MenuAction(
                Icons.Default.ToggleOff,
                getString(R.string.disabled),
                onClick = { handleMenuAction(R.id.menu_disabled_group) }
            ))
            add(MenuAction(
                Icons.Default.Login,
                getString(R.string.need_login),
                onClick = { handleMenuAction(R.id.menu_group_login) }
            ))
            add(MenuAction(
                Icons.Default.FolderOff,
                getString(R.string.no_group),
                onClick = { handleMenuAction(R.id.menu_group_null) }
            ))
            composeGroups.forEach { group ->
                add(MenuAction(
                    Icons.Default.Folder,
                    group,
                    onClick = { handleGroupSelect(group) }
                ))
            }
            // 操作分组
            add(MenuAction(Icons.Default.Settings, getString(R.string.source_folder_config), header = true) {})
            add(MenuAction(
                Icons.Default.Folder,
                getString(R.string.source_folder_config),
                onClick = { handleMenuAction(R.id.menu_folder_config) }
            ))
            add(MenuAction(
                Icons.Default.Add,
                getString(R.string.add_rss_source),
                onClick = { handleMenuAction(R.id.menu_add) }
            ))
            add(MenuAction(
                Icons.Default.FileDownload,
                getString(R.string.import_local),
                onClick = { handleMenuAction(R.id.menu_import_local) }
            ))
            add(MenuAction(
                Icons.Default.CloudDownload,
                getString(R.string.import_on_line),
                onClick = { handleMenuAction(R.id.menu_import_onLine) }
            ))
            add(MenuAction(
                Icons.Default.QrCodeScanner,
                getString(R.string.import_by_qr_code),
                onClick = { handleMenuAction(R.id.menu_import_qr) }
            ))
            add(MenuAction(
                Icons.Default.Domain,
                getString(R.string.group_sources_by_domain),
                checked = groupSourcesByDomain,
                onClick = { handleMenuAction(R.id.menu_group_sources_by_domain) }
            ))
            add(MenuAction(
                Icons.Default.AutoAwesome,
                getString(R.string.import_default_rule),
                onClick = { handleMenuAction(R.id.menu_import_default) }
            ))
            add(MenuAction(
                Icons.Default.Help,
                getString(R.string.help),
                onClick = { handleMenuAction(R.id.menu_help) }
            ))
        }
    }

    // source-compose 壳层化：菜单动作统一入口（原 onCompatOptionsItemSelected 逻辑迁移）
    private fun handleMenuAction(actionId: Int) {
        when (actionId) {
            R.id.menu_folder_config -> showFolderConfig()
            R.id.menu_add -> startActivity<RssSourceEditActivity>()
            R.id.menu_import_local -> importDoc.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }

            R.id.menu_import_onLine -> showImportDialog()
            R.id.menu_import_qr -> qrCodeResult.launch()
            R.id.menu_group_manage -> showDialogFragment<GroupManageDialog>()
            R.id.menu_import_default -> viewModel.importDefault()
            // source-layout-refactor 快捷筛选词：重置子目录状态，回根目录筛选
            R.id.menu_enabled_group, R.id.menu_disabled_group, R.id.menu_group_login,
            R.id.menu_group_null -> handleQuickFilter(actionId)
            // source-layout-refactor 按类型筛选菜单（隐藏字段方案，不回填搜索框）
            R.id.menu_type_all, R.id.menu_type_0, R.id.menu_type_1, R.id.menu_type_2 ->
                handleTypeSelect(actionId)

            // source-layout-refactor 菜单排序：映射到 rssSort 配置（C-01 修复：订阅源独立排序）
            R.id.menu_sort_manual -> {
                AppConfig.rssSort = 0
                upSourceFlow(searchViewQuery)
            }
            R.id.menu_sort_name -> {
                AppConfig.rssSort = 1
                upSourceFlow(searchViewQuery)
            }
            R.id.menu_sort_enable -> {
                AppConfig.rssSort = 2
                upSourceFlow(searchViewQuery)
            }
            R.id.menu_sort_url -> {
                AppConfig.rssSort = 5
                upSourceFlow(searchViewQuery)
            }
            R.id.menu_sort_time -> {
                AppConfig.rssSort = 6
                upSourceFlow(searchViewQuery)
            }
            R.id.menu_sort_desc -> {
                sortAscending = !sortAscending
                upSourceFlow(searchViewQuery)
            }

            R.id.menu_group_sources_by_domain -> {
                groupSourcesByDomain = !groupSourcesByDomain
                // Issue-6 ADR-15: 同步 adapter.showSourceHost（参考 BookSourceActivity）
                adapter.showSourceHost = groupSourcesByDomain
                upSourceFlow(searchViewQuery)
            }

            R.id.menu_help -> showHelp("SourceMRssHelp")
        }
    }

    // 快捷筛选词：重置子目录状态，回根目录筛选
    private fun handleQuickFilter(actionId: Int) {
        currentType = -1
        currentGroup = null
        val keyword = when (actionId) {
            R.id.menu_enabled_group -> getString(R.string.enabled)
            R.id.menu_disabled_group -> getString(R.string.disabled)
            R.id.menu_group_login -> getString(R.string.need_login)
            R.id.menu_group_null -> getString(R.string.no_group)
            else -> ""
        }
        composeSearchQuery = keyword
        upSourceFlow(keyword)
    }

    // 按类型筛选（隐藏字段方案，不回填搜索框）
    private fun handleTypeSelect(actionId: Int) {
        currentType = when (actionId) {
            R.id.menu_type_all -> -1
            R.id.menu_type_0 -> 0
            R.id.menu_type_1 -> 1
            R.id.menu_type_2 -> 2
            else -> -1
        }
        currentGroup = null
        upSourceFlow(searchViewQuery)
    }

    // 动态分组筛选
    private fun handleGroupSelect(group: String) {
        currentType = -1
        currentGroup = group
        upSourceFlow(searchViewQuery)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_enable_selection -> viewModel.enableSelection(currentSelectionAdapter().selection)
            R.id.menu_disable_selection -> viewModel.disableSelection(currentSelectionAdapter().selection)
            R.id.menu_add_group -> selectionAddToGroups()
            R.id.menu_remove_group -> selectionRemoveFromGroups()
            R.id.menu_top_sel -> viewModel.topSource(*currentSelectionAdapter().selection.toTypedArray())
            R.id.menu_bottom_sel -> viewModel.bottomSource(*currentSelectionAdapter().selection.toTypedArray())
            R.id.menu_export_selection -> viewModel.saveToFile(currentSelectionAdapter().selection) { file, name ->
                exportResult.launch {
                    mode = HandleFileContract.EXPORT
                    fileData = HandleFileContract.FileData(
                        name, file, "application/json"
                    )
                }
            }

            R.id.menu_share_source -> viewModel.saveToFile(currentSelectionAdapter().selection) { file, name ->
                share(file)
            }

            R.id.menu_check_selected_interval -> currentSelectionAdapter().checkSelectedInterval()
            R.id.menu_check_rss_source -> checkRssSource()
        }
        return true
    }

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.recycledViewPool.setMaxRecycledViews(0, 15)
        // When this page is opened, it is in selection mode
        val dragSelectTouchHelper: DragSelectTouchHelper =
            DragSelectTouchHelper(currentSelectionAdapter().dragSelectCallback).setSlideArea(16, 50)
        dragSelectTouchHelper.attachToRecyclerView(binding.recyclerView)
        dragSelectTouchHelper.activeSlideSelect()
        // Note: need judge selection first, so add ItemTouchHelper after it.
        ItemTouchHelper(itemTouchCallback).attachToRecyclerView(binding.recyclerView)
        // source-folder-cover：管理页固定平铺
        applyListView()
    }

    // source-layout-refactor 应用列表视图（支持 sourceLayout: 0=列表/1=紧凑/2-6=网格）
    private fun applyListView() {
        binding.recyclerView.removeItemDecoration(gridSpacingDecoration)
        binding.recyclerView.removeItemDecoration(verticalDivider)
        val layout = AppConfig.sourceLayout
        when (layout) {
            0 -> { // 列表
                binding.recyclerView.addItemDecoration(verticalDivider)
                binding.recyclerView.layoutManager = LinearLayoutManager(this)
                binding.recyclerView.adapter = adapter
            }
            1 -> { // 紧凑列表
                binding.recyclerView.addItemDecoration(verticalDivider)
                binding.recyclerView.layoutManager = LinearLayoutManager(this)
                binding.recyclerView.adapter = adapterCompact
            }
            else -> { // 网格 2-6 列
                gridSpacingDecoration.spacing = AppConfig.sourceMargin.dpToPx()
                binding.recyclerView.addItemDecoration(gridSpacingDecoration)
                binding.recyclerView.layoutManager = GridLayoutManager(this, layout)
                binding.recyclerView.adapter = adapterGrid
            }
        }
        itemTouchCallback.isCanDrag = AppConfig.rssSort == 0 && layout == 0
    }

    // M-02 修复：获取当前选择适配器（统一 list/compact/grid 的 selection API）
    private fun currentSelectionAdapter(): RssSourceSelection = when (AppConfig.sourceLayout) {
        1 -> adapterCompact
        in 2..6 -> adapterGrid
        else -> adapter
    }

    // M-02 修复：当前适配器 item 数量
    private val currentItemCount: Int
        get() = when (AppConfig.sourceLayout) {
            1 -> adapterCompact.itemCount
            in 2..6 -> adapterGrid.itemCount
            else -> adapter.itemCount
        }

    // M-02 修复：通知当前适配器 resumed/paused（仅 list adapter 有 upResumed）
    // 简化说明：compact/grid 无 upResumed 方法 | 已知上限：compact/grid 模式 resumed 时不刷新 | 升级路径：M-10 提取基类统一 upResumed
    private fun currentUpResumed(resumed: Boolean) {
        if (AppConfig.sourceLayout == 0) adapter.upResumed(resumed)
    }

    // source-layout-refactor 配置对话框（新签名：onConfigChanged 回调）
    private fun showFolderConfig() {
        SourceFolderAdapter.showConfigDialog(this, isBookSource = false, showGroupStyle = false) {
            applyConfigChange()
        }
    }

    // source-folder-cover：管理页固定平铺，配置变更仅影响列表布局/排序/间距
    private fun applyConfigChange() {
        currentType = -1
        currentGroup = null
        isShowingFolder = false
        applyListView()
        upSourceFlow(searchViewQuery)
    }

    private fun initSelectActionBar() {
        binding.selectActionBar.setMainActionText(R.string.delete)
        binding.selectActionBar.inflateMenu(R.menu.rss_source_sel)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        binding.selectActionBar.setCallBack(this)
    }

    private fun initGroupFlow() {
        lifecycleScope.launch {
            appDb.rssSourceDao.flowGroups().flowOn(IO).conflate().collect {
                groups.clear()
                groups.addAll(it)
                composeGroups = groups.toList()
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun selectionAddToGroups() {
        alert(titleResource = R.string.add_group) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.setHint(R.string.group_name)
                editView.setFilterValues(groups.toList())
                editView.dropDownHeight = 180.dpToPx()
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let {
                    if (it.isNotEmpty()) {
                        viewModel.selectionAddToGroups(currentSelectionAdapter().selection, it)
                    }
                }
            }
            cancelButton()
        }
    }

    @SuppressLint("InflateParams")
    private fun selectionRemoveFromGroups() {
        alert(titleResource = R.string.remove_group) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.setHint(R.string.group_name)
                editView.setFilterValues(groups.toList())
                editView.dropDownHeight = 180.dpToPx()
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let {
                    if (it.isNotEmpty()) {
                        viewModel.selectionRemoveFromGroups(currentSelectionAdapter().selection, it)
                    }
                }
            }
            cancelButton()
        }
    }

    override fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            currentSelectionAdapter().selectAll()
        } else {
            currentSelectionAdapter().revertSelection()
        }
    }

    override fun revertSelection() {
        currentSelectionAdapter().revertSelection()
    }

    override fun onClickSelectBarMainAction() {
        delSourceDialog()
    }

    private fun delSourceDialog() {
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            yesButton { viewModel.del(*currentSelectionAdapter().selection.toTypedArray()) }
            noButton()
        }
    }

    private fun upSourceFlow(searchKey: String? = null) {
        // D2 修复：文件夹视图时不查询源数据
        if (isShowingFolder) return
        // source-layout-refactor 历史兼容：清空 type:/group: 前缀回填
        val nameQuery = searchKey?.let {
            when {
                it.startsWith("type:") || it.startsWith("group:") -> ""
                else -> it
            }
        }
        sourceFlowJob?.cancel()
        sourceFlowJob = lifecycleScope.launch {
            val flow = when {
                // 子目录：按类型 + 名称搜索
                currentType >= 0 && !nameQuery.isNullOrEmpty() ->
                    appDb.rssSourceDao.flowByTypeSearch(currentType, nameQuery)
                currentType >= 0 ->
                    appDb.rssSourceDao.flowByType(currentType)
                // 子目录：按分组 + 名称搜索
                currentGroup != null && !nameQuery.isNullOrEmpty() ->
                    appDb.rssSourceDao.flowGroupSearchExact(currentGroup!!, nameQuery)
                currentGroup != null ->
                    appDb.rssSourceDao.flowGroupSearch(currentGroup!!)
                // 根目录：特殊筛选快捷词
                !nameQuery.isNullOrEmpty() && nameQuery == getString(R.string.enabled) ->
                    appDb.rssSourceDao.flowEnabled()
                !nameQuery.isNullOrEmpty() && nameQuery == getString(R.string.disabled) ->
                    appDb.rssSourceDao.flowDisabled()
                !nameQuery.isNullOrEmpty() && nameQuery == getString(R.string.need_login) ->
                    appDb.rssSourceDao.flowLogin()
                !nameQuery.isNullOrEmpty() && nameQuery == getString(R.string.no_group) ->
                    appDb.rssSourceDao.flowNoGroup()
                // 根目录：名称搜索
                !nameQuery.isNullOrEmpty() -> appDb.rssSourceDao.flowSearch(nameQuery)
                // 根目录：全部
                else -> appDb.rssSourceDao.flowAll()
            }
            flow.map { data ->
                hostMap.clear()
                if (groupSourcesByDomain) {
                    data.sortedWith(
                        compareBy<RssSource> { getSourceHost(it.lastHost ?: it.sourceUrl) == "#" }
                            .thenBy { getSourceHost(it.lastHost ?: it.sourceUrl) }
                            .thenByDescending { it.lastUpdateTime })
                } else {
                    sortSources(data)
                }
            }
                .catch {
                    AppLog.put("订阅源管理界面更新数据出错", it)
                }.flowOn(IO).conflate().collect {
                    when (AppConfig.sourceLayout) {
                        1 -> adapterCompact.setItems(it, adapterCompact.diffItemCallback)
                        in 2..6 -> adapterGrid.setItems(it, adapterGrid.diffItemCallback)
                        else -> adapter.setItems(it, adapter.diffItemCallback)
                    }
                    delay(100)
                }
        }
    }

    // source-layout-refactor 排序：rssSort 配置驱动（0=手动/1=名称/2=启用/3=类型/4=分组/5=URL/6=更新时间）
    private fun sortSources(data: List<RssSource>): List<RssSource> {
        val sorted = when (AppConfig.rssSort) {
            1 -> data.sortedWith { o1, o2 -> o1.sourceName.cnCompare(o2.sourceName) }
            2 -> data.sortedByDescending { it.enabled }
            3 -> data.sortedBy { it.type }
            4 -> data.sortedBy { it.sourceGroup ?: "" }
            5 -> data.sortedBy { it.sourceUrl }
            6 -> data.sortedByDescending { it.lastUpdateTime }
            else -> data  // 0=手动，用 customOrder
        }
        return if (sortAscending) sorted else sorted.reversed()
    }

    // 域名分组辅助：提取源的真实host（参照 BookSourceActivity.getSourceHost）
    // Issue-6 ADR-15: 修复异常输入处理（空/纯协议名"http"/"https"/"http:///"/"https:///" 返回 "#"），对齐 BookSourceActivity
    override fun getSourceHost(origin: String): String {
        return hostMap.getOrPut(origin) {
            // 兼容两种输入: 1)完整URL(http://...) 2)纯host(example.com或IP)
            // lastHost字段存储的是host,getSourceHost(it.lastHost ?: it.sourceUrl)调用
            // ADR-15 修复：异常输入（空、纯协议名"http"/"https"、无路径）返回 "#" 不作为分组名
            val trimmed = origin.trim()
            if (trimmed.isEmpty() || trimmed.equals("http", true) || trimmed.equals("https", true)
                || trimmed.startsWith("http:///", true) || trimmed.startsWith("https:///", true)
            ) {
                return@getOrPut "#"
            }
            if (trimmed.startsWith("http", ignoreCase = true)) {
                NetworkUtils.getSubDomainOrNull(trimmed) ?: "#"
            } else {
                // host补http://前缀再提取子域名,支持"www.example.com"→"example.com"归并
                NetworkUtils.getSubDomainOrNull("http://$trimmed") ?: "#"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        currentUpResumed(true)
    }

    override fun onPause() {
        currentUpResumed(false)
        super.onPause()
    }

    override fun upCountView() {
        binding.selectActionBar.upCountView(
            currentSelectionAdapter().selection.size,
            currentItemCount
        )
    }

    // source-folder-cover：管理页固定平铺，文件夹相关回调保留空实现（仅满足 CallBack 接口）
    override fun onFolderClick(folder: FolderItem) {
    }

    override fun onFolderSelectImage(folder: FolderItem) {
    }

    override fun onFolderRestoreCover(folder: FolderItem) {
    }

    // source-folder-cover：管理页固定平铺，返回键直接处理子目录
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (inSubDirectory) {
            currentType = -1
            currentGroup = null
            applyListView()
            upSourceFlow(searchViewQuery)
            return
        }
        super.onBackPressed()
    }

    @SuppressLint("InflateParams")
    private fun showImportDialog() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls: MutableList<String> = aCache
            .getAsString(importRecordKey)
            ?.splitNotBlank(",")
            ?.toMutableList() ?: mutableListOf()
        alert(titleResource = R.string.import_on_line) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url"
                editView.setFilterValues(cacheUrls)
                editView.delCallBack = {
                    cacheUrls.remove(it)
                    aCache.put(importRecordKey, cacheUrls.joinToString(","))
                }
            }
            customView { alertBinding.root }
            okButton {
                val text = alertBinding.editView.text?.toString()
                text?.let {
                    if (it.isAbsUrl() && !cacheUrls.contains(it)) {
                        cacheUrls.add(0, it)
                        aCache.put(importRecordKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(
                        ImportRssSourceDialog(it)
                    )
                }
            }
            cancelButton()
        }
    }

    override fun del(source: RssSource) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + source.sourceName)
            noButton()
            yesButton {
                viewModel.del(source)
            }
        }
    }

    override fun edit(source: RssSource) {
        startActivity<RssSourceEditActivity> {
            putExtra("sourceUrl", source.sourceUrl)
        }
    }

    override fun update(vararg source: RssSource) {
        viewModel.update(*source)
    }

    override fun toTop(source: RssSource) {
        viewModel.topSource(source)
    }

    override fun toBottom(source: RssSource) {
        viewModel.bottomSource(source)
    }

    override fun upOrder() {
        viewModel.upOrder()
    }

    private fun checkRssSource() {
        val dialog = alert(titleResource = R.string.search_book_key) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "search word"
                editView.setText(CheckRssSource.keyword)
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let {
                    if (it.isNotEmpty()) {
                        CheckRssSource.keyword = it
                    }
                }
                val selectItems = currentSelectionAdapter().selection
                CheckRssSource.start(this@RssSourceActivity, selectItems)
            }
            neutralButton(R.string.check_rss_source_config)
            cancelButton()
        }
        // 手动设置监听 避免点击打开校验设置后对话框关闭
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            showDialogFragment<CheckRssSourceConfig>()
        }
    }

}