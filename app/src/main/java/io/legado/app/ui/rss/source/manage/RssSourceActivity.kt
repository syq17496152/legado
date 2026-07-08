package io.legado.app.ui.rss.source.manage

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.SubMenu
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
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
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.adapter.SourceFolderAdapter
import io.legado.app.ui.association.ImportRssSourceDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.ui.widget.recycler.GridSpacingItemDecoration
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.ACache
import io.legado.app.utils.applyTint
import io.legado.app.utils.cnCompare
import io.legado.app.utils.dpToPx
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.launch
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.transaction
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
    SourceFolderAdapter.CallBack,
    SelectActionBar.CallBack,
    RssSourceAdapter.CallBack {

    override val binding by viewBinding(ActivityRssSourceBinding::inflate)
    override val viewModel by viewModels<RssSourceViewModel>()
    private val importRecordKey = "rssSourceRecordKey"
    private val adapter by lazy { RssSourceAdapter(this, this) }
    private val adapterCompact by lazy { RssSourceAdapterCompact(this, this) }
    private val adapterGrid by lazy { RssSourceAdapterGrid(this, this) }
    private val folderAdapter by lazy { SourceFolderAdapter(this, this) }
    private val itemTouchCallback by lazy { ItemTouchCallback(adapter) }
    private val verticalDivider by lazy { VerticalDivider(this) }
    private val gridSpacingDecoration = GridSpacingItemDecoration()
    // source-layout-refactor 隐藏字段方案：子目录状态变量
    private var currentType: Int = -1        // -1=全部, 0-2=具体类型（网页/图片/视频）
    private var currentGroup: String? = null // null=根目录, 非空=在某个分组内
    private val inSubDirectory: Boolean get() = currentType >= 0 || currentGroup != null
    // source-layout-refactor 排序升降序
    private var sortAscending = true
    // source-layout-refactor 视图状态：sourceGroupStyle!=0 时根目录显示文件夹
    private val isFolderViewMode: Boolean
        get() = AppConfig.sourceGroupStyle != 0
    // 当前是否显示文件夹视图（运行时状态）
    private var isShowingFolder: Boolean = false
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private var sourceFlowJob: Job? = null
    private var groups = arrayListOf<String>()
    private var groupMenu: SubMenu? = null
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
        // F-P1-8 初始化运行时状态：跟随用户偏好
        isShowingFolder = isFolderViewMode
        initRecyclerView()
        initSearchView()
        initGroupFlow()
        if (isShowingFolder) {
            upFolderView()
        } else {
            upSourceFlow()
        }
        initSelectActionBar()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.rss_source, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        groupMenu = menu.findItem(R.id.menu_group)?.subMenu
        // source-layout-refactor 同步排序菜单升降序勾选状态
        menu.findItem(R.id.action_sort)?.subMenu?.findItem(R.id.menu_sort_desc)?.isChecked = !sortAscending
        upGroupMenu()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
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
            R.id.menu_group_null -> {
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
                    else -> ""
                }
                searchView.setQuery(keyword, true)
            }

            // source-layout-refactor 按类型筛选菜单（隐藏字段方案，不回填搜索框）
            R.id.menu_type_all, R.id.menu_type_0, R.id.menu_type_1, R.id.menu_type_2 -> {
                item.isChecked = true
                currentType = when (item.itemId) {
                    R.id.menu_type_all -> -1
                    R.id.menu_type_0 -> 0
                    R.id.menu_type_1 -> 1
                    R.id.menu_type_2 -> 2
                    else -> -1
                }
                currentGroup = null
                if (isShowingFolder) {
                    isShowingFolder = false
                    applyListView()
                    invalidateOptionsMenu()
                }
                upSourceFlow(searchView.query?.toString())
            }

            // source-layout-refactor 菜单排序：映射到 sourceSort 配置
            R.id.menu_sort_manual -> {
                item.isChecked = true
                AppConfig.sourceSort = 0
                upSourceFlow(searchView.query?.toString())
            }
            R.id.menu_sort_name -> {
                item.isChecked = true
                AppConfig.sourceSort = 1
                upSourceFlow(searchView.query?.toString())
            }
            R.id.menu_sort_enable -> {
                item.isChecked = true
                AppConfig.sourceSort = 2
                upSourceFlow(searchView.query?.toString())
            }
            R.id.menu_sort_url -> {
                item.isChecked = true
                AppConfig.sourceSort = 5
                upSourceFlow(searchView.query?.toString())
            }
            R.id.menu_sort_time -> {
                item.isChecked = true
                AppConfig.sourceSort = 6
                upSourceFlow(searchView.query?.toString())
            }
            R.id.menu_sort_desc -> {
                sortAscending = !sortAscending
                item.isChecked = !sortAscending
                upSourceFlow(searchView.query?.toString())
            }

            R.id.menu_help -> showHelp("SourceMRssHelp")
            else -> // source-layout-refactor 动态分组菜单：用隐藏字段
                if (item.groupId == R.id.source_group) {
                    currentType = -1
                    currentGroup = item.title.toString()
                    if (isShowingFolder) {
                        isShowingFolder = false
                        applyListView()
                        invalidateOptionsMenu()
                    }
                    upSourceFlow(searchView.query?.toString())
                }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_enable_selection -> viewModel.enableSelection(adapter.selection)
            R.id.menu_disable_selection -> viewModel.disableSelection(adapter.selection)
            R.id.menu_add_group -> selectionAddToGroups()
            R.id.menu_remove_group -> selectionRemoveFromGroups()
            R.id.menu_top_sel -> viewModel.topSource(*adapter.selection.toTypedArray())
            R.id.menu_bottom_sel -> viewModel.bottomSource(*adapter.selection.toTypedArray())
            R.id.menu_export_selection -> viewModel.saveToFile(adapter.selection) { file, name ->
                exportResult.launch {
                    mode = HandleFileContract.EXPORT
                    fileData = HandleFileContract.FileData(
                        name, file, "application/json"
                    )
                }
            }

            R.id.menu_share_source -> viewModel.saveToFile(adapter.selection) { file, name ->
                share(file)
            }

            R.id.menu_check_selected_interval -> adapter.checkSelectedInterval()
        }
        return true
    }

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.recycledViewPool.setMaxRecycledViews(0, 15)
        // When this page is opened, it is in selection mode
        val dragSelectTouchHelper: DragSelectTouchHelper =
            DragSelectTouchHelper(adapter.dragSelectCallback).setSlideArea(16, 50)
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
        itemTouchCallback.isCanDrag = AppConfig.sourceSort == 0 && layout == 0
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

    // source-layout-refactor 配置对话框（新签名：onConfigChanged 回调）
    private fun showFolderConfig() {
        SourceFolderAdapter.showConfigDialog(this) {
            applyConfigChange()
        }
    }

    // source-layout-refactor 配置变更后应用视图
    private fun applyConfigChange() {
        currentType = -1
        currentGroup = null
        when (AppConfig.sourceGroupStyle) {
            0 -> { // 列表平铺
                isShowingFolder = false
                applyListView()
                upSourceFlow(searchView.query?.toString())
            }
            1, 2 -> { // 按类型/按分组：显示文件夹
                isShowingFolder = true
                applyFolderView()
                upFolderView()
            }
        }
        invalidateOptionsMenu()
    }

    // source-layout-refactor 更新文件夹视图数据（根据分组样式：按分组/按类型）
    private fun upFolderView() {
        val folderList = mutableListOf<String>()
        if (AppConfig.sourceGroupStyle == 1) {
            // 按类型分组：显示类型文件夹（订阅源类型 0网页/1图片/2视频）
            folderList.add(getString(R.string.all_groups))
            folderList.add(getString(R.string.type_web))
            folderList.add(getString(R.string.type_image))
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
        binding.titleBar.findViewById<SearchView>(R.id.search_view).let {
            it.applyTint(primaryTextColor)
            it.onActionViewExpanded()
            it.queryHint = getString(R.string.search_rss_source)
            it.clearFocus()
            it.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    return false
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    upSourceFlow(newText)
                    return false
                }
            })
        }
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
                upGroupMenu()
                if (isShowingFolder) {
                    upFolderView()
                }
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
                        viewModel.selectionAddToGroups(adapter.selection, it)
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
                        viewModel.selectionRemoveFromGroups(adapter.selection, it)
                    }
                }
            }
            cancelButton()
        }
    }

    override fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            adapter.selectAll()
        } else {
            adapter.revertSelection()
        }
    }

    override fun revertSelection() {
        adapter.revertSelection()
    }

    override fun onClickSelectBarMainAction() {
        delSourceDialog()
    }

    private fun delSourceDialog() {
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            yesButton { viewModel.del(*adapter.selection.toTypedArray()) }
            noButton()
        }
    }

    private fun upGroupMenu() = groupMenu?.transaction { menu ->
        menu.removeGroup(R.id.source_group)
        groups.forEach {
            menu.add(R.id.source_group, Menu.NONE, Menu.NONE, it)
        }
    }

    private fun upSourceFlow(searchKey: String? = null) {
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
            flow.map { data -> sortSources(data) }
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

    // source-layout-refactor 排序：sourceSort 配置驱动（0=手动/1=名称/2=启用/3=类型/4=分组/5=URL/6=更新时间）
    private fun sortSources(data: List<RssSource>): List<RssSource> {
        val sorted = when (AppConfig.sourceSort) {
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

    override fun onResume() {
        super.onResume()
        adapter.upResumed(true)
    }

    override fun onPause() {
        adapter.upResumed(false)
        super.onPause()
    }

    override fun upCountView() {
        binding.selectActionBar.upCountView(
            adapter.selection.size,
            adapter.itemCount
        )
    }

    // source-layout-refactor 文件夹点击回调：用状态变量切换子目录，不回填搜索框
    // 注意：不修改 sourceGroupStyle（用户偏好），仅修改 isShowingFolder（运行时状态）
    override fun onFolderClick(group: String) {
        when (AppConfig.sourceGroupStyle) {
            1 -> { // 按类型（网页/图片/视频）
                currentType = when (group) {
                    getString(R.string.type_web) -> 0
                    getString(R.string.type_image) -> 1
                    getString(R.string.type_video) -> 2
                    else -> -1
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
            else -> return
        }
        isShowingFolder = false
        applyListView()
        invalidateOptionsMenu()
        upSourceFlow(searchView.query?.toString())
    }

    // source-layout-refactor 返回键：子目录内返回根目录，根目录退出 Activity
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
            upSourceFlow(searchView.query?.toString())
            invalidateOptionsMenu()
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

}