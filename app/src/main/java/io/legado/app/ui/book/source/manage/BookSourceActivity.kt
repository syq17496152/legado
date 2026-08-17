package io.legado.app.ui.book.source.manage

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import com.google.android.material.snackbar.Snackbar
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart

import io.legado.app.databinding.ActivityBookSourceBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.CheckSource
import io.legado.app.model.Debug
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.source.debug.BookSourceDebugActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.adapter.FolderItem
import io.legado.app.ui.adapter.SourceFolderAdapter
import io.legado.app.ui.config.CheckSourceConfig
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.source.recycle.RecycleBinActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.ui.widget.recycler.GridSpacingItemDecoration
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.ACache
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.cnCompare
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.launch
import io.legado.app.utils.observeEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 书源管理界面
 */
class BookSourceActivity : VMBaseActivity<ActivityBookSourceBinding, BookSourceViewModel>(),
    PopupMenu.OnMenuItemClickListener,
    BookSourceAdapter.CallBack,
    SourceFolderAdapter.CallBack,
    SelectActionBar.CallBack {
    override val binding by viewBinding(ActivityBookSourceBinding::inflate)
    override val viewModel by viewModels<BookSourceViewModel>()
    private val importRecordKey = "bookSourceRecordKey"
    private val adapter by lazy { BookSourceAdapter(this, this, binding.recyclerView) }
    private val adapterCompact by lazy { BookSourceAdapterCompact(this, this) }
    private val adapterGrid by lazy { BookSourceAdapterGrid(this, this) }
    private val itemTouchCallback by lazy { ItemTouchCallback(adapter) }
    private val verticalDivider by lazy { VerticalDivider(this) }
    private val gridSpacingDecoration = GridSpacingItemDecoration()
    private var sourceFlowJob: Job? = null
    private var checkMessageRefreshJob: Job? = null
    private val groups = linkedSetOf<String>()
    override var sort = BookSourceSort.Default
        private set
    override var sortAscending = true
        private set
    private var snackBar: Snackbar? = null
    private var groupSourcesByDomain = false
    private val hostMap = hashMapOf<String, String>()
    // source-compose 桥接：Compose 侧状态（双轨过渡，View 顶栏/菜单/批量栏保留）
    private var composeSources by mutableStateOf(listOf<BookSourcePart>(), neverEqualPolicy())
    private var composeGroups by mutableStateOf(listOf<String>())
    private var composeSearchQuery by mutableStateOf("")
    private var composeCurrentType by mutableStateOf(-1)
    private var composeCurrentGroup by mutableStateOf<String?>(null)
    private var composeCheckMessages by mutableStateOf(mapOf<String, String>())
    private var composeIsChecking by mutableStateOf(false)
    // source-compose 桥接：Compose 多选状态（多选在 Compose 侧接管）
    private var composeIsSelecting by mutableStateOf(false)
    private var composeSelectedUrls by mutableStateOf(setOf<String>())
    private val composeSelection: List<BookSourcePart>
        get() = composeSources.filter { composeSelectedUrls.contains(it.bookSourceUrl) }
    // source-layout-refactor 隐藏字段方案：子目录状态变量
    private var currentType: Int = -1        // -1=全部, 0-4=具体类型
    private var currentGroup: String? = null // null=根目录, 非空=在某个分组内
    private val inSubDirectory: Boolean get() = currentType >= 0 || currentGroup != null
    // source-layout-refactor 视图状态：管理页固定平铺（source-folder-cover 决策：不再显示文件夹，见 spec AD-03）
    private val isFolderViewMode: Boolean
        get() = false
    // 当前是否显示文件夹视图（运行时状态）
    private var isShowingFolder: Boolean = false
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
        upBookSource()
        initLiveDataGroup()
        initSelectActionBar()
        resumeCheckSource()
        if (!LocalConfig.bookSourcesHelpVersionIsLast) {
            showHelp("SourceMBookHelp")
        }
        initComposeHost()
    }

    // source-compose 桥接：ComposeView 渲染 BookSourceScreen（showTopBar=true，顶栏/搜索/菜单由 Compose 侧提供）
    private fun initComposeHost() {
        binding.composeHost.setContent {
            LegadoTheme {
                BookSourceScreen(
                    sources = composeSources,
                    groups = composeGroups,
                    currentType = composeCurrentType,
                    currentGroup = composeCurrentGroup,
                    currentLayout = AppConfig.sourceLayout,
                    currentSortKey = AppConfig.bookSourceSort.toString(),
                    sortAscending = sortAscending,
                    groupSourcesByDomain = groupSourcesByDomain,
                    isFolderViewMode = isFolderViewMode,
                    sourceGroupStyle = AppConfig.sourceGroupStyle,
                    isShowingFolder = false,
                    folderItems = emptyList(),
                    searchQuery = composeSearchQuery,
                    isSelecting = composeIsSelecting,
                    selectedCount = composeSelectedUrls.size,
                    selectedUrls = composeSelectedUrls,
                    checkMessages = composeCheckMessages,
                    isChecking = composeIsChecking,
                    showTopBar = true,
                    onBack = { onBackPressed() },
                    onSearchQueryChange = { query ->
                        composeSearchQuery = query
                        upBookSource(query)
                    },
                    onLayoutSelect = { layout ->
                        AppConfig.sourceLayout = layout
                        applyListView()
                        upBookSource()
                    },
                    onSortSelect = { key, ascending ->
                        AppConfig.bookSourceSort = key.toInt()
                        sortAscending = ascending
                        upBookSource()
                    },
                    onTypeSelect = { type ->
                        currentType = type
                        currentGroup = null
                        composeCurrentType = type
                        composeCurrentGroup = null
                        upBookSource()
                    },
                    onGroupSelect = { group ->
                        currentType = -1
                        currentGroup = group
                        composeCurrentType = -1
                        composeCurrentGroup = group
                        upBookSource()
                    },
                    onFolderClick = { },
                    onFolderConfig = { showFolderConfig() },
                    onAddSource = { startActivity<BookSourceEditActivity>() },
                    onImportLocal = {
                        importDoc.launch {
                            mode = HandleFileContract.FILE
                            allowExtensions = arrayOf("txt", "json")
                        }
                    },
                    onImportOnline = { showImportDialog() },
                    onImportQr = { qrResult.launch() },
                    onGroupManage = { showDialogFragment<GroupManageDialog>() },
                    onGroupSourcesByDomain = {
                        groupSourcesByDomain = !groupSourcesByDomain
                        adapter.showSourceHost = groupSourcesByDomain
                        upBookSource()
                    },
                    onRecycleBin = { startActivity<RecycleBinActivity>() },
                    onHelp = { showHelp("SourceMBookHelp") },
                    onItemClick = {
                        if (composeIsSelecting) toggleSelect(it) else edit(it)
                    },
                    onItemLongClick = { enterSelect(it) },
                    onEnableToggle = { source, enable -> optimisticEnable(source, enable) },
                    onEdit = { edit(it) },
                    onDebug = { debug(it) },
                    onCopyUrl = { sendToClip(it.bookSourceUrl) },
                    onMore = { showSourceMenu(it) },
                    onSelectAll = { selectAll ->
                        composeSelectedUrls =
                            if (selectAll) composeSources.map { it.bookSourceUrl }.toSet()
                            else emptySet()
                    },
                    onRevertSelection = { revertComposeSelection() },
                    onDeleteSelection = { onClickSelectBarMainAction() },
                    onBatchAction = { handleBatchAction(it) },
                    onGroupBatchEnable = { groupSources, enabled ->
                        optimisticEnableAll(groupSources, enabled)
                    }
                )
            }
        }
    }

    // M-13 乐观更新：受控 Switch 与分组徽标读 composeSources 渲染，而 upBookSource flow 在 DB
    // 写入后不重发射（与初始提交行为一致，pre-existing），本地先同步状态保证开关不弹回、徽标
    // 实时刷新；DB 仍为真相源，重进或下次 flow 发射时自动校正。
    private fun optimisticEnable(source: BookSourcePart, enable: Boolean) {
        composeSources = composeSources.map {
            if (it.bookSourceUrl == source.bookSourceUrl) it.copy(enabled = enable) else it
        }
        viewModel.enable(enable, listOf(source))
    }

    // M-13 分组批量启用/停用：同样本地乐观更新，分组头徽标即时刷新
    private fun optimisticEnableAll(sources: List<BookSourcePart>, enable: Boolean) {
        if (sources.isEmpty()) return
        val urls = sources.mapTo(hashSetOf()) { it.bookSourceUrl }
        composeSources = composeSources.map {
            if (urls.contains(it.bookSourceUrl)) it.copy(enabled = enable) else it
        }
        viewModel.enable(enable, sources)
    }

    private fun toggleSelect(source: BookSourcePart) {
        composeSelectedUrls = if (composeSelectedUrls.contains(source.bookSourceUrl))
            composeSelectedUrls - source.bookSourceUrl
        else composeSelectedUrls + source.bookSourceUrl
    }

    private fun enterSelect(source: BookSourcePart) {
        composeIsSelecting = true
        composeSelectedUrls = composeSelectedUrls + source.bookSourceUrl
    }

    private fun exitSelecting() {
        composeIsSelecting = false
        composeSelectedUrls = emptySet()
    }

    private fun revertComposeSelection() {
        val selected = composeSelectedUrls
        composeSelectedUrls = composeSources
            .filter { !selected.contains(it.bookSourceUrl) }
            .map { it.bookSourceUrl }
            .toSet()
    }

    private fun handleBatchAction(action: BookSourceBatchAction) {
        if (composeSelection.isEmpty()) return
        when (action) {
            BookSourceBatchAction.ENABLE -> viewModel.enableSelection(composeSelection)
            BookSourceBatchAction.DISABLE -> viewModel.disableSelection(composeSelection)
            BookSourceBatchAction.ENABLE_EXPLORE ->
                viewModel.enableSelectExplore(composeSelection)
            BookSourceBatchAction.DISABLE_EXPLORE ->
                viewModel.disableSelectExplore(composeSelection)
            BookSourceBatchAction.CHECK_SOURCE -> checkSource(composeSelection)
            BookSourceBatchAction.CHECK_INTERVAL -> checkComposeSelectedInterval()
            BookSourceBatchAction.TOP -> viewModel.topSource(*composeSelection.toTypedArray())
            BookSourceBatchAction.BOTTOM -> viewModel.bottomSource(*composeSelection.toTypedArray())
            BookSourceBatchAction.ADD_GROUP -> selectionAddToGroups(composeSelection)
            BookSourceBatchAction.REMOVE_GROUP -> selectionRemoveFromGroups(composeSelection)
            BookSourceBatchAction.EXPORT -> exportSelection(composeSelection)
            BookSourceBatchAction.SHARE -> shareSelection(composeSelection)
        }
    }

    private fun checkComposeSelectedInterval() {
        val positions = composeSelection.mapNotNull { source ->
            composeSources.indexOfFirst { it.bookSourceUrl == source.bookSourceUrl }
                .takeIf { it >= 0 }
        }
        if (positions.isEmpty()) return
        val min = positions.min()
        val max = positions.max()
        composeSelectedUrls = composeSources
            .filterIndexed { index, _ -> index in min..max }
            .map { it.bookSourceUrl }
            .toSet()
    }

    private fun exportSelection(selection: List<BookSourcePart>) {
        viewModel.saveToFile(
            selection,
            selection.size,
            composeSearchQuery,
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

    private fun shareSelection(selection: List<BookSourcePart>) {
        viewModel.saveToFile(
            selection,
            selection.size,
            composeSearchQuery,
            sortAscending,
            sort
        ) { file, name ->
            share(file)
        }
    }

    private fun initRecyclerView() {
        // source-compose 桥接后 recyclerView 已改 android:visibility="gone"（ComposeView 渲染列表），
        // 旧 DragSelectTouchHelper/ItemTouchHelper 滑选接线为纯死接线，已移除。
        // 多选交互降级为：长按进入多选 + 逐项勾选（Compose 侧 onItemLongClick/onItemClick toggleSelect）。
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.recycledViewPool.setMaxRecycledViews(0, 15)
        // source-folder-cover：管理页固定平铺，始终列表视图
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
        itemTouchCallback.isCanDrag =
            AppConfig.bookSourceSort == 0 && sort == BookSourceSort.Default
            && layout == 0 && !groupSourcesByDomain
    }

    // M-01 修复：获取当前选择适配器（统一 list/compact/grid 的 selection API）
    private fun currentSelectionAdapter(): BookSourceSelection = when (AppConfig.sourceLayout) {
        1 -> adapterCompact
        in 2..6 -> adapterGrid
        else -> adapter
    }

    // M-01 修复：当前适配器 item 数量
    private val currentItemCount: Int
        get() = when (AppConfig.sourceLayout) {
            1 -> adapterCompact.itemCount
            in 2..6 -> adapterGrid.itemCount
            else -> adapter.itemCount
        }

    // M-01 修复：当前适配器刷新检查源消息
    // 简化说明：compact/grid 的 convert 未处理 checkSourceMessage payload，收到也不会刷新 | 已知上限：compact/grid 模式检查源消息不实时显示 | 升级路径：M-10 提取基类统一 payload 处理
    private fun currentNotifyItemRangeChanged(start: Int, count: Int, payload: Bundle) {
        when (AppConfig.sourceLayout) {
            1 -> adapterCompact.notifyItemRangeChanged(start, count, payload)
            in 2..6 -> adapterGrid.notifyItemRangeChanged(start, count, payload)
            else -> adapter.notifyItemRangeChanged(start, count, payload)
        }
    }

    // M-01 修复：获取当前适配器全部 items
    private fun currentGetItems(): List<BookSourcePart> = when (AppConfig.sourceLayout) {
        1 -> adapterCompact.getItems()
        in 2..6 -> adapterGrid.getItems()
        else -> adapter.getItems()
    }

    // M-01 修复：通知当前适配器 resumed/paused（仅 list adapter 有 checkSourceMessage 刷新）
    // 简化说明：compact/grid 无 upResumed 方法 | 已知上限：compact/grid 模式 resumed 时不刷新检查源消息 | 升级路径：M-10 提取基类统一 upResumed
    private fun currentUpResumed(resumed: Boolean) {
        if (AppConfig.sourceLayout == 0) adapter.upResumed(resumed)
    }

    // source-layout-refactor 配置对话框（新签名：onConfigChanged 回调）
    private fun showFolderConfig() {
        // source-folder-cover：管理页固定平铺，隐藏分组样式选项（showGroupStyle=false）
        SourceFolderAdapter.showConfigDialog(this, isBookSource = true, showGroupStyle = false) {
            applyConfigChange()
        }
    }

    // source-layout-refactor 配置变更后应用视图
    private fun applyConfigChange() {
        // source-folder-cover：管理页固定平铺，配置变更仅影响列表布局/排序/间距
        currentType = -1
        currentGroup = null
        isShowingFolder = false
        applyListView()
        upBookSource(composeSearchQuery)
    }

    private fun upBookSource(searchKey: String? = null) {
        // V4 分组折叠渲染：文件夹根目录不再只渲染 FolderGrid，需填充全量数据供 Compose 分组。
        // 子目录（currentType/currentGroup 已设）时按子目录 flow 加载，根目录直接 flowAll。
        // source-layout-refactor 历史兼容：清空 type:/group: 前缀回填（防止旧代码遗留）
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
                    appDb.bookSourceDao.flowByTypeSearch(currentType, nameQuery)
                currentType >= 0 ->
                    appDb.bookSourceDao.flowByType(currentType)
                // 子目录：按分组 + 名称搜索
                currentGroup != null && !nameQuery.isNullOrEmpty() ->
                    appDb.bookSourceDao.flowGroupSearchExact(currentGroup!!, nameQuery)
                currentGroup != null ->
                    appDb.bookSourceDao.flowGroupSearch(currentGroup!!)
                // 根目录：特殊筛选快捷词（保留 enabled/disabled/need_login 等）
                !nameQuery.isNullOrEmpty() && nameQuery == getString(R.string.enabled) ->
                    appDb.bookSourceDao.flowEnabled()
                !nameQuery.isNullOrEmpty() && nameQuery == getString(R.string.disabled) ->
                    appDb.bookSourceDao.flowDisabled()
                !nameQuery.isNullOrEmpty() && nameQuery == getString(R.string.need_login) ->
                    appDb.bookSourceDao.flowLogin()
                !nameQuery.isNullOrEmpty() && nameQuery == getString(R.string.no_group) ->
                    appDb.bookSourceDao.flowNoGroup()
                !nameQuery.isNullOrEmpty() && nameQuery == getString(R.string.enabled_explore) ->
                    appDb.bookSourceDao.flowEnabledExplore()
                !nameQuery.isNullOrEmpty() && nameQuery == getString(R.string.disabled_explore) ->
                    appDb.bookSourceDao.flowDisabledExplore()
                // 根目录：名称搜索
                !nameQuery.isNullOrEmpty() -> appDb.bookSourceDao.flowSearch(nameQuery)
                // 根目录：全部
                else -> appDb.bookSourceDao.flowAll()
            }
            flow.map { data ->
                hostMap.clear()
                if (groupSourcesByDomain) {
                    // Issue-5.3/5.4 修复：域名分组排序支持 Weight + sortAscending
                    // 原逻辑：同组内按 lastUpdateTime 倒序，sortAscending 参数未传入 → 反序无效
                    // 新逻辑：同组内按 Weight 降序（默认），支持 sortAscending 反序切换
                    val hostComparator = compareBy<BookSourcePart> { getSourceHost(it.lastHost ?: it.bookSourceUrl) == "#" }
                        .thenBy { getSourceHost(it.lastHost ?: it.bookSourceUrl) }
                    val weighted = if (sort == BookSourceSort.Weight) {
                        // 智能排序：同组内按 Weight 降序（默认）或升序（反序）
                        if (sortAscending) {
                            hostComparator.thenBy { it.weight }
                        } else {
                            hostComparator.thenByDescending { it.weight }
                        }
                    } else {
                        // 其他排序模式：保持原有 lastUpdateTime 倒序逻辑
                        if (sortAscending) {
                            hostComparator.thenBy { it.lastUpdateTime }
                        } else {
                            hostComparator.thenByDescending { it.lastUpdateTime }
                        }
                    }
                    data.sortedWith(weighted)
                } else {
                    sortSources(data)
                }
            }.flowWithLifecycleAndDatabaseChange(
                lifecycle,
                table = AppDatabase.BOOK_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("书源界面更新书源出错", it)
            }.flowOn(IO).conflate().collect { data ->
                composeSources = data
                when (AppConfig.sourceLayout) {
                    1 -> adapterCompact.setItems(data, adapterCompact.diffItemCallback, !Debug.isChecking)
                    in 2..6 -> adapterGrid.setItems(data, adapterGrid.diffItemCallback, !Debug.isChecking)
                    else -> adapter.setItems(data, adapter.diffItemCallback, !Debug.isChecking)
                }
                itemTouchCallback.isCanDrag =
                    AppConfig.bookSourceSort == 0 && sort == BookSourceSort.Default
                    && !groupSourcesByDomain
                delay(500)
            }
        }
    }

    // source-layout-refactor 排序：bookSourceSort 配置驱动（6 选项），bookSourceSort==0 时回退旧 sort 逻辑
    private fun sortSources(data: List<BookSourcePart>): List<BookSourcePart> {
        return if (AppConfig.bookSourceSort != 0) {
            val sorted = when (AppConfig.bookSourceSort) {
                1 -> data.sortedWith { o1, o2 -> o1.bookSourceName.cnCompare(o2.bookSourceName) }
                2 -> data.sortedByDescending { it.enabled }
                3 -> data.sortedBy { it.bookSourceType }
                4 -> data.sortedBy { it.bookSourceGroup ?: "" }
                5 -> data.sortedBy { it.bookSourceUrl }
                6 -> data.sortedByDescending { it.lastUpdateTime }
                else -> data
            }
            if (!sortAscending) sorted.reversed() else sorted
        } else {
            // 旧逻辑：保留 BookSourceSort.Weight/Update/Respond 等菜单排序
            if (sortAscending) {
                when (sort) {
                    BookSourceSort.Weight -> data.sortedBy { it.weight }
                    BookSourceSort.Name -> data.sortedWith { o1, o2 ->
                        o1.bookSourceName.cnCompare(o2.bookSourceName)
                    }
                    BookSourceSort.Url -> data.sortedBy { it.bookSourceUrl }
                    BookSourceSort.Update -> data.sortedByDescending { it.lastUpdateTime }
                    BookSourceSort.Respond -> data.sortedBy { it.respondTime }
                    BookSourceSort.Enable -> data.sortedWith { o1, o2 ->
                        var cmp = -o1.enabled.compareTo(o2.enabled)
                        if (cmp == 0) cmp = o1.bookSourceName.cnCompare(o2.bookSourceName)
                        cmp
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
                        var cmp = o1.enabled.compareTo(o2.enabled)
                        if (cmp == 0) cmp = o1.bookSourceName.cnCompare(o2.bookSourceName)
                        cmp
                    }
                    else -> data.reversed()
                }
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

    // source-layout-refactor 子目录内按返回键：回根目录；根目录：退出
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (composeIsSelecting) {
            exitSelecting()
            return
        }
        if (inSubDirectory) {
            currentType = -1
            currentGroup = null
            composeCurrentType = -1
            composeCurrentGroup = null
            // source-folder-cover：管理页固定平铺，返回根目录始终列表视图
            applyListView()
            upBookSource(composeSearchQuery)
            return
        }
        super.onBackPressed()
    }


    private fun initLiveDataGroup() {
        lifecycleScope.launch {
            appDb.bookSourceDao.flowGroups().flowOn(IO).conflate().collect {
                groups.clear()
                groups.addAll(it)
                composeGroups = groups.toList()
            }
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
        val selection = if (composeIsSelecting) composeSelection
        else currentSelectionAdapter().selection
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            yesButton { viewModel.del(selection) }
            noButton()
        }
    }

    private fun initSelectActionBar() {
        binding.selectActionBar.setMainActionText(R.string.delete)
        binding.selectActionBar.inflateMenu(R.menu.book_source_sel)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        binding.selectActionBar.setCallBack(this)
        // source-compose 桥接：多选批量栏改由 Compose SelectActionBarCompose 接管，隐藏 View 版
        binding.selectActionBar.visibility = View.GONE
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_enable_selection -> viewModel.enableSelection(currentSelectionAdapter().selection)
            R.id.menu_disable_selection -> viewModel.disableSelection(currentSelectionAdapter().selection)
            R.id.menu_enable_explore -> viewModel.enableSelectExplore(currentSelectionAdapter().selection)
            R.id.menu_disable_explore -> viewModel.disableSelectExplore(currentSelectionAdapter().selection)
            R.id.menu_check_source -> checkSource(currentSelectionAdapter().selection)
            R.id.menu_top_sel -> viewModel.topSource(*currentSelectionAdapter().selection.toTypedArray())
            R.id.menu_bottom_sel -> viewModel.bottomSource(*currentSelectionAdapter().selection.toTypedArray())
            R.id.menu_add_group -> selectionAddToGroups(currentSelectionAdapter().selection)
            R.id.menu_remove_group -> selectionRemoveFromGroups(currentSelectionAdapter().selection)
            R.id.menu_export_selection -> viewModel.saveToFile(
                currentSelectionAdapter().selection,
                currentItemCount,
                composeSearchQuery,
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

            R.id.menu_share_source -> viewModel.saveToFile(
                currentSelectionAdapter().selection,
                currentItemCount,
                composeSearchQuery,
                sortAscending,
                sort
            ) { file, name ->
                share(file)
            }

            R.id.menu_check_selected_interval -> currentSelectionAdapter().checkSelectedInterval()
        }
        return true
    }

    @SuppressLint("InflateParams")
    private fun checkSource(selection: List<BookSourcePart>) {
        val dialog = alert(titleResource = R.string.search_book_key) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "search word"
                editView.setText(CheckSource.keyword)
            }
            customView { alertBinding.root }
            okButton {
                keepScreenOn(true)
                alertBinding.editView.text?.toString()?.let {
                    if (it.isNotEmpty()) {
                        CheckSource.keyword = it
                    }
                }
                val selectItems = selection
                CheckSource.start(this@BookSourceActivity, selectItems)
                val adapterItems = currentGetItems()
                val firstItem = adapterItems.indexOf(selectItems.firstOrNull())
                val lastItem = adapterItems.indexOf(selectItems.lastOrNull())
                Debug.isChecking = firstItem >= 0 && lastItem >= 0
                startCheckMessageRefreshJob(firstItem, lastItem)
            }
            neutralButton(R.string.check_source_config)
            cancelButton()
        }
        //手动设置监听 避免点击打开校验设置后对话框关闭
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            showDialogFragment<CheckSourceConfig>()
        }
    }

    private fun resumeCheckSource() {
        if (!Debug.isChecking) {
            return
        }
        keepScreenOn(true)
        CheckSource.resume(this)
        startCheckMessageRefreshJob(0, 0)
    }

    @SuppressLint("InflateParams")
    private fun selectionAddToGroups(selection: List<BookSourcePart>) {
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
                        viewModel.selectionAddToGroups(selection, it)
                    }
                }
            }
            cancelButton()
        }
    }

    @SuppressLint("InflateParams")
    private fun selectionRemoveFromGroups(selection: List<BookSourcePart>) {
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
                        viewModel.selectionRemoveFromGroups(selection, it)
                    }
                }
            }
            cancelButton()
        }
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
                    showDialogFragment(ImportBookSourceDialog(it))
                }
            }
            cancelButton()
        }
    }

    override fun observeLiveBus() {
        observeEvent<String>(EventBus.CHECK_SOURCE) { msg ->
            snackBar?.setText(msg) ?: let {
                snackBar = Snackbar
                    .make(binding.root, msg, Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.cancel) {
                        CheckSource.stop(this)
                        Debug.finishChecking()
                    }.apply { show() }
            }
        }
        observeEvent<Int>(EventBus.CHECK_SOURCE_DONE) {
            keepScreenOn(false)
            composeIsChecking = false
            composeCheckMessages = Debug.debugMessageMap.toMap()
            snackBar?.dismiss()
            snackBar = null
            currentNotifyItemRangeChanged(
                0,
                currentItemCount,
                bundleOf(Pair("checkSourceMessage", null))
            )
            groups.forEach { group ->
                if (group.contains("失效") && composeSearchQuery.isEmpty()) {
                    composeSearchQuery = "失效"
                    upBookSource("失效")
                    toastOnUi("发现有失效书源，已为您自动筛选！")
                }
            }
        }
    }

    private fun startCheckMessageRefreshJob(firstItem: Int, lastItem: Int) {
        checkMessageRefreshJob?.cancel()
        composeIsChecking = true
        checkMessageRefreshJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    composeCheckMessages = Debug.debugMessageMap.toMap()
                    if (lastItem == 0) {
                        currentNotifyItemRangeChanged(
                            0,
                            currentItemCount,
                            bundleOf(Pair("checkSourceMessage", null))
                        )
                    } else {
                        currentNotifyItemRangeChanged(
                            firstItem,
                            lastItem + 1,
                            bundleOf(Pair("checkSourceMessage", null))
                        )
                    }
                    if (!Debug.isChecking) {
                        checkMessageRefreshJob?.cancel()
                    }
                    delay(300L)
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

    override fun upCountView() {
        binding.selectActionBar
            .upCountView(currentSelectionAdapter().selection.size, currentItemCount)
    }

    override fun getSourceHost(origin: String): String {
        return hostMap.getOrPut(origin) {
            // 兼容两种输入: 1)完整URL(http://...) 2)纯host(example.com或IP)
            // lastHost字段存储的是host,getSourceHost(it.lastHost ?: it.bookSourceUrl)调用
            // Issue-5.1 修复：异常输入（空、纯协议名"http"/"https"、无路径）返回 "#" 不作为分组名
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

    // source-folder-cover：管理页固定平铺，文件夹相关回调保留空实现（仅满足 CallBack 接口）
    override fun onFolderClick(folder: FolderItem) {
    }

    override fun onFolderSelectImage(folder: FolderItem) {
    }

    override fun onFolderRestoreCover(folder: FolderItem) {
    }

    override fun del(bookSource: BookSourcePart) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + bookSource.bookSourceName)
            noButton()
            yesButton {
                viewModel.del(listOf(bookSource))
            }
        }
    }

    override fun edit(bookSource: BookSourcePart) {
        startActivity<BookSourceEditActivity> {
            putExtra("sourceUrl", bookSource.bookSourceUrl)
        }
    }

    override fun upOrder(items: List<BookSourcePart>) {
        viewModel.upOrder(items)
    }

    override fun enable(enable: Boolean, bookSource: BookSourcePart) {
        viewModel.enable(enable, listOf(bookSource))
    }

    override fun enableExplore(enable: Boolean, bookSource: BookSourcePart) {
        viewModel.enableExplore(enable, listOf(bookSource))
    }

    override fun toTop(bookSource: BookSourcePart) {
        if (sortAscending) {
            viewModel.topSource(bookSource)
        } else {
            viewModel.bottomSource(bookSource)
        }
    }

    override fun toBottom(bookSource: BookSourcePart) {
        if (sortAscending) {
            viewModel.bottomSource(bookSource)
        } else {
            viewModel.topSource(bookSource)
        }
    }

    override fun searchBook(bookSource: BookSourcePart) {
        SearchActivity.start(this, bookSource)
    }

    override fun debug(bookSource: BookSourcePart) {
        startActivity<BookSourceDebugActivity> {
            putExtra("key", bookSource.bookSourceUrl)
        }
    }

    // source-compose 桥接：Compose 条目长按/更多按钮弹菜单（复用 book_source_item.xml 逻辑）
    private fun showSourceMenu(bookSource: BookSourcePart) {
        val popupMenu = PopupMenu(this, binding.composeHost)
        popupMenu.inflate(R.menu.book_source_item)
        popupMenu.menu.findItem(R.id.menu_top).isVisible =
            sort == BookSourceSort.Default && AppConfig.bookSourceSort == 0
        popupMenu.menu.findItem(R.id.menu_bottom).isVisible =
            sort == BookSourceSort.Default && AppConfig.bookSourceSort == 0
        val qyMenu = popupMenu.menu.findItem(R.id.menu_enable_explore)
        if (!bookSource.hasExploreUrl) {
            qyMenu.isVisible = false
        } else {
            qyMenu.setTitle(
                if (bookSource.enabledExplore) R.string.disable_explore
                else R.string.enable_explore
            )
        }
        popupMenu.menu.findItem(R.id.menu_login).isVisible = bookSource.hasLoginUrl
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_top -> toTop(bookSource)
                R.id.menu_bottom -> toBottom(bookSource)
                R.id.menu_login -> startActivity<SourceLoginActivity> {
                    putExtra("type", "bookSource")
                    putExtra("key", bookSource.bookSourceUrl)
                }
                R.id.menu_search -> searchBook(bookSource)
                R.id.menu_debug_source -> debug(bookSource)
                R.id.menu_del -> del(bookSource)
                R.id.menu_enable_explore ->
                    enableExplore(!bookSource.enabledExplore, bookSource)
            }
            true
        }
        popupMenu.show()
    }

    override fun finish() {
        if (composeSearchQuery.isEmpty()) {
            super.finish()
        } else {
            composeSearchQuery = ""
            upBookSource("")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!Debug.isChecking) {
            Debug.debugMessageMap.clear()
        }
    }

}