package io.legado.app.ui.book.source.manage

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.SubMenu
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
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
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.model.CheckSource
import io.legado.app.model.Debug
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.source.debug.BookSourceDebugActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.adapter.SourceFolderAdapter
import io.legado.app.ui.config.CheckSourceConfig
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.ui.widget.recycler.GridSpacingItemDecoration
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.ACache
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.applyTint
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
import io.legado.app.utils.transaction
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
    SelectActionBar.CallBack,
    SearchView.OnQueryTextListener {
    override val binding by viewBinding(ActivityBookSourceBinding::inflate)
    override val viewModel by viewModels<BookSourceViewModel>()
    private val importRecordKey = "bookSourceRecordKey"
    private val adapter by lazy { BookSourceAdapter(this, this, binding.recyclerView) }
    private val adapterCompact by lazy { BookSourceAdapterCompact(this, this) }
    private val adapterGrid by lazy { BookSourceAdapterGrid(this, this) }
    private val folderAdapter by lazy { SourceFolderAdapter(this, this) }
    private val itemTouchCallback by lazy { ItemTouchCallback(adapter) }
    private val verticalDivider by lazy { VerticalDivider(this) }
    private val gridSpacingDecoration = GridSpacingItemDecoration()
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private var sourceFlowJob: Job? = null
    private var checkMessageRefreshJob: Job? = null
    private val groups = linkedSetOf<String>()
    private var groupMenu: SubMenu? = null
    override var sort = BookSourceSort.Default
        private set
    override var sortAscending = true
        private set
    private var snackBar: Snackbar? = null
    private var groupSourcesByDomain = false
    private val hostMap = hashMapOf<String, String>()
    // source-layout-refactor 隐藏字段方案：子目录状态变量
    private var currentType: Int = -1        // -1=全部, 0-4=具体类型
    private var currentGroup: String? = null // null=根目录, 非空=在某个分组内
    private val inSubDirectory: Boolean get() = currentType >= 0 || currentGroup != null
    // source-layout-refactor 视图状态：sourceGroupStyle!=0 时根目录显示文件夹
    private val isFolderViewMode: Boolean
        get() = AppConfig.sourceGroupStyle != 0
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
        // F-P1-8 初始化运行时状态：跟随用户偏好
        isShowingFolder = isFolderViewMode
        initRecyclerView()
        initSearchView()
        if (isShowingFolder) {
            upFolderView()
        } else {
            upBookSource()
        }
        initLiveDataGroup()
        initSelectActionBar()
        resumeCheckSource()
        if (!LocalConfig.bookSourcesHelpVersionIsLast) {
            showHelp("SourceMBookHelp")
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_source, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        groupMenu = menu.findItem(R.id.menu_group).subMenu
        val sortSubMenu = menu.findItem(R.id.action_sort).subMenu!!
        sortSubMenu.findItem(R.id.menu_sort_desc).isChecked = !sortAscending
        sortSubMenu.setGroupCheckable(R.id.menu_group_sort, true, true)
        upGroupMenu()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_folder_config -> showFolderConfig()
            R.id.menu_add_book_source -> startActivity<BookSourceEditActivity>()
            R.id.menu_import_qr -> qrResult.launch()
            R.id.menu_group_manage -> showDialogFragment<GroupManageDialog>()
            R.id.menu_import_local -> importDoc.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }

            R.id.menu_import_onLine -> showImportDialog()

            R.id.menu_sort_desc -> {
                sortAscending = !sortAscending
                item.isChecked = !sortAscending
                upBookSource(searchView.query?.toString())
            }

            // source-layout-refactor 菜单排序：同步重置 bookSourceSort=0，使旧 sort 逻辑生效
            R.id.menu_sort_manual -> {
                item.isChecked = true
                AppConfig.bookSourceSort = 0
                sort = BookSourceSort.Default
                upBookSource(searchView.query?.toString())
            }

            R.id.menu_sort_auto -> {
                item.isChecked = true
                AppConfig.bookSourceSort = 0
                sort = BookSourceSort.Weight
                upBookSource(searchView.query?.toString())
            }

            R.id.menu_sort_name -> {
                item.isChecked = true
                AppConfig.bookSourceSort = 0
                sort = BookSourceSort.Name
                upBookSource(searchView.query?.toString())
            }

            R.id.menu_sort_url -> {
                item.isChecked = true
                AppConfig.bookSourceSort = 0
                sort = BookSourceSort.Url
                upBookSource(searchView.query?.toString())
            }

            R.id.menu_sort_time -> {
                item.isChecked = true
                AppConfig.bookSourceSort = 0
                sort = BookSourceSort.Update
                upBookSource(searchView.query?.toString())
            }

            R.id.menu_sort_respondTime -> {
                item.isChecked = true
                AppConfig.bookSourceSort = 0
                sort = BookSourceSort.Respond
                upBookSource(searchView.query?.toString())
            }

            R.id.menu_sort_enable -> {
                item.isChecked = true
                AppConfig.bookSourceSort = 0
                sort = BookSourceSort.Enable
                upBookSource(searchView.query?.toString())
            }

            // source-layout-refactor 快捷筛选词：重置子目录状态，回根目录筛选
            R.id.menu_enabled_group, R.id.menu_disabled_group, R.id.menu_group_login,
            R.id.menu_group_null, R.id.menu_enabled_explore_group,
            R.id.menu_disabled_explore_group -> {
                currentType = -1
                currentGroup = null
                if (isShowingFolder) {
                    isShowingFolder = false
                    applyListView()
                    invalidateOptionsMenu()
                }
                val keyword = when (item.itemId) {
                    R.id.menu_enabled_group -> getString(R.string.enabled)
                    R.id.menu_disabled_group -> getString(R.string.disabled)
                    R.id.menu_group_login -> getString(R.string.need_login)
                    R.id.menu_group_null -> getString(R.string.no_group)
                    R.id.menu_enabled_explore_group -> getString(R.string.enabled_explore)
                    R.id.menu_disabled_explore_group -> getString(R.string.disabled_explore)
                    else -> ""
                }
                searchView.setQuery(keyword, true)
            }

            R.id.menu_group_sources_by_domain -> {
                item.isChecked = !item.isChecked
                groupSourcesByDomain = item.isChecked
                adapter.showSourceHost = item.isChecked
                upBookSource(searchView.query?.toString())
            }

            // source-layout-refactor 按类型筛选菜单（隐藏字段方案，不回填搜索框）
            R.id.menu_type_all, R.id.menu_type_0, R.id.menu_type_1,
            R.id.menu_type_2, R.id.menu_type_3, R.id.menu_type_4 -> {
                item.isChecked = true
                currentType = when (item.itemId) {
                    R.id.menu_type_all -> -1
                    R.id.menu_type_0 -> 0
                    R.id.menu_type_1 -> 1
                    R.id.menu_type_2 -> 2
                    R.id.menu_type_3 -> 3
                    R.id.menu_type_4 -> 4
                    else -> -1
                }
                currentGroup = null
                if (isShowingFolder) {
                    isShowingFolder = false
                    applyListView()
                    invalidateOptionsMenu()
                }
                upBookSource(searchView.query?.toString())
            }

            R.id.menu_help -> showHelp("SourceMBookHelp")
        }
        // source-layout-refactor 动态分组菜单：用隐藏字段，不回填搜索框
        if (item.groupId == R.id.source_group) {
            currentType = -1
            currentGroup = item.title.toString()
            if (isShowingFolder) {
                isShowingFolder = false
                applyListView()
                invalidateOptionsMenu()
            }
            upBookSource(searchView.query?.toString())
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.recycledViewPool.setMaxRecycledViews(0, 15)
        // When this page is opened, it is in selection mode
        val dragSelectTouchHelper =
            DragSelectTouchHelper(currentSelectionAdapter().dragSelectCallback).setSlideArea(16, 50)
        dragSelectTouchHelper.attachToRecyclerView(binding.recyclerView)
        dragSelectTouchHelper.activeSlideSelect()
        // Note: need judge selection first, so add ItemTouchHelper after it.
        ItemTouchHelper(itemTouchCallback).attachToRecyclerView(binding.recyclerView)
        if (isShowingFolder) {
            applyFolderView()
        } else {
            applyListView()
        }
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

    // source-layout-refactor 应用文件夹视图
    private fun applyFolderView() {
        binding.recyclerView.removeItemDecoration(verticalDivider)
        binding.recyclerView.removeItemDecoration(gridSpacingDecoration)
        val marginDp = AppConfig.sourceMargin
        gridSpacingDecoration.spacing = SourceFolderAdapter.spacingPx(this, marginDp)
        binding.recyclerView.addItemDecoration(gridSpacingDecoration)
        val spanCount = SourceFolderAdapter.calculateSpanCount(this, marginDp)
        binding.recyclerView.layoutManager = GridLayoutManager(this, spanCount)
        binding.recyclerView.adapter = folderAdapter
        itemTouchCallback.isCanDrag = false
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
        SourceFolderAdapter.showConfigDialog(this, isBookSource = true) {
            applyConfigChange()
        }
    }

    // source-layout-refactor 配置变更后应用视图
    private fun applyConfigChange() {
        // 配置变更后重置子目录状态
        currentType = -1
        currentGroup = null
        when (AppConfig.sourceGroupStyle) {
            0 -> { // 列表平铺：直接显示所有源
                isShowingFolder = false
                applyListView()
                upBookSource(searchView.query?.toString())
            }
            1, 2 -> { // 按类型/按分组：显示文件夹
                isShowingFolder = true
                applyFolderView()
                upFolderView()
            }
        }
        invalidateOptionsMenu()
    }

    // F-P1-8 更新文件夹视图数据（根据分组样式：按分组/按类型）
    private fun upFolderView() {
        val folderList = mutableListOf<String>()
        if (AppConfig.sourceGroupStyle == 1) {
            // 按类型分组：显示类型文件夹
            folderList.add(getString(R.string.all_groups))
            folderList.add(getString(R.string.type_text))
            folderList.add(getString(R.string.type_audio))
            folderList.add(getString(R.string.type_image))
            folderList.add(getString(R.string.type_file))
            folderList.add(getString(R.string.type_video))
        } else {
            // 按自定义分组
            folderList.add(getString(R.string.all_groups))
            folderList.add(getString(R.string.no_group))
            folderList.addAll(groups)
        }
        folderAdapter.setItems(folderList, folderAdapter.diffItemCallback)
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.queryHint = getString(R.string.search_book_source)
        searchView.setOnQueryTextListener(this)
    }


    private fun upBookSource(searchKey: String? = null) {
        if (isShowingFolder) return
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
                    data.sortedWith(
                        compareBy<BookSourcePart> { getSourceHost(it.bookSourceUrl) == "#" }
                            .thenBy { getSourceHost(it.bookSourceUrl) }
                            .thenByDescending { it.lastUpdateTime })
                } else {
                    sortSources(data)
                }
            }.flowWithLifecycleAndDatabaseChange(
                lifecycle,
                table = AppDatabase.BOOK_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("书源界面更新书源出错", it)
            }.flowOn(IO).conflate().collect { data ->
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
        if (inSubDirectory) {
            currentType = -1
            currentGroup = null
            if (AppConfig.sourceGroupStyle == 0) {
                applyListView()
            } else {
                isShowingFolder = true
                applyFolderView()
                upFolderView()
            }
            upBookSource(searchView.query?.toString())
            invalidateOptionsMenu()
            return
        }
        super.onBackPressed()
    }


    private fun initLiveDataGroup() {
        lifecycleScope.launch {
            appDb.bookSourceDao.flowGroups().flowOn(IO).conflate().collect {
                groups.clear()
                groups.addAll(it)
                upGroupMenu()
                if (isShowingFolder) {
                    upFolderView()
                }
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
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            yesButton { viewModel.del(currentSelectionAdapter().selection) }
            noButton()
        }
    }

    private fun initSelectActionBar() {
        binding.selectActionBar.setMainActionText(R.string.delete)
        binding.selectActionBar.inflateMenu(R.menu.book_source_sel)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        binding.selectActionBar.setCallBack(this)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_enable_selection -> viewModel.enableSelection(currentSelectionAdapter().selection)
            R.id.menu_disable_selection -> viewModel.disableSelection(currentSelectionAdapter().selection)
            R.id.menu_enable_explore -> viewModel.enableSelectExplore(currentSelectionAdapter().selection)
            R.id.menu_disable_explore -> viewModel.disableSelectExplore(currentSelectionAdapter().selection)
            R.id.menu_check_source -> checkSource()
            R.id.menu_top_sel -> viewModel.topSource(*currentSelectionAdapter().selection.toTypedArray())
            R.id.menu_bottom_sel -> viewModel.bottomSource(*currentSelectionAdapter().selection.toTypedArray())
            R.id.menu_add_group -> selectionAddToGroups()
            R.id.menu_remove_group -> selectionRemoveFromGroups()
            R.id.menu_export_selection -> viewModel.saveToFile(
                currentSelectionAdapter().selection,
                currentItemCount,
                searchView.query?.toString(),
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
                searchView.query?.toString(),
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
    private fun checkSource() {
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
                val selectItems = currentSelectionAdapter().selection
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

    private fun upGroupMenu() = groupMenu?.transaction { menu ->
        menu.removeGroup(R.id.source_group)
        groups.forEach {
            menu.add(R.id.source_group, Menu.NONE, Menu.NONE, it)
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
            snackBar?.dismiss()
            snackBar = null
            currentNotifyItemRangeChanged(
                0,
                currentItemCount,
                bundleOf(Pair("checkSourceMessage", null))
            )
            groups.forEach { group ->
                if (group.contains("失效") && searchView.query.isEmpty()) {
                    searchView.setQuery("失效", true)
                    toastOnUi("发现有失效书源，已为您自动筛选！")
                }
            }
        }
    }

    private fun startCheckMessageRefreshJob(firstItem: Int, lastItem: Int) {
        checkMessageRefreshJob?.cancel()
        checkMessageRefreshJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
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
            NetworkUtils.getSubDomainOrNull(origin) ?: "#"
        }
    }

    // source-layout-refactor 文件夹点击回调：设置状态变量，不触碰搜索框
    override fun onFolderClick(group: String) {
        when (AppConfig.sourceGroupStyle) {
            1 -> { // 按类型
                currentType = when (group) {
                    getString(R.string.type_text) -> 0
                    getString(R.string.type_audio) -> 1
                    getString(R.string.type_image) -> 2
                    getString(R.string.type_file) -> 3
                    getString(R.string.type_video) -> 4
                    else -> -1  // all_groups
                }
                currentGroup = null
            }
            2 -> { // 按分组
                currentType = -1
                currentGroup = when (group) {
                    getString(R.string.all_groups) -> null
                    getString(R.string.no_group) -> null
                    else -> group
                }
            }
            else -> return  // 列表平铺模式无文件夹
        }
        isShowingFolder = false
        applyListView()
        invalidateOptionsMenu()
        upBookSource(searchView.query?.toString())
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        newText?.let {
            upBookSource(it)
        }
        return false
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        return false
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

    override fun finish() {
        if (searchView.query.isNullOrEmpty()) {
            super.finish()
        } else {
            searchView.setQuery("", true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!Debug.isChecking) {
            Debug.debugMessageMap.clear()
        }
    }

}