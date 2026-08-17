package io.legado.app.ui.main.rss

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.SourceGroupCover
import io.legado.app.databinding.FragmentRssBinding
import io.legado.app.databinding.ItemRssBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.adapter.FolderItem
import io.legado.app.ui.adapter.SourceFolderAdapter
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.rss.article.ReadRecordDialog
import io.legado.app.ui.rss.article.RssSortActivity
import io.legado.app.ui.rss.favorites.RssFavoritesActivity
import io.legado.app.ui.rss.read.ReadRssActivity
import io.legado.app.ui.rss.search.RssSearchActivity
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.rss.source.manage.RssSourceActivity
import io.legado.app.ui.rss.subscription.RuleSubActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsSearchBar
import io.legado.app.ui.widget.recycler.GridSpacingItemDecoration
import io.legado.app.utils.cnCompare
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.inputStream
import io.legado.app.utils.openUrl
import io.legado.app.utils.readUri
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.io.FileOutputStream

/**
 * 订阅界面
 */
class RssFragment() : VMBaseFragment<RssViewModel>(R.layout.fragment_rss), MainFragmentInterface,
    RssAdapter.CallBack,
    SourceFolderAdapter.CallBack {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val binding by viewBinding(FragmentRssBinding::bind)
    override val viewModel by viewModels<RssViewModel>()
    private val adapter by lazy {
        RssAdapter(requireContext(), this, this, viewLifecycleOwner.lifecycle)
    }
    private val folderAdapter by lazy {
        SourceFolderAdapter(requireContext(), SourceGroupCover.KIND_RSS, this)
    }
    private val gridSpacingDecoration = GridSpacingItemDecoration()
    // source-folder-cover: 待设置封面的文件夹（选图返回后写入）
    private var pendingFolder: FolderItem? = null
    // source-folder-cover: 选择封面图片 → 复制到 covers 目录 + upsert 数据库
    private val selectFolderCover =
        registerForActivityResult(HandleFileContract()) { result ->
            val uri = result.uri ?: return@registerForActivityResult
            val folder = pendingFolder ?: return@registerForActivityResult
            pendingFolder = null
            viewLifecycleOwner.lifecycleScope.launch {
                kotlin.runCatching {
                    var savedPath: String? = null
                    withContext(IO) {
                        readUri(uri) { fileDoc, inputStream ->
                            var file = requireContext().externalFiles
                            val suffix = if (fileDoc.name.contains(".9.png", true)) {
                                ".9.png"
                            } else {
                                "." + fileDoc.name.substringAfterLast(".")
                            }
                            val fileName = uri.inputStream(requireContext()).getOrThrow().use { tmp ->
                                MD5Utils.md5Encode(tmp) + suffix
                            }
                            file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                            FileOutputStream(file).use { outputStream ->
                                inputStream.copyTo(outputStream)
                            }
                            savedPath = file.absolutePath
                        }
                    }
                    // readUri 回调非挂持上下文, 挂起库操作移到此处执行
                    savedPath?.let { path ->
                        appDb.sourceGroupCoverDao.upsert(
                            SourceGroupCover(SourceGroupCover.KIND_RSS, folder.groupKey, path)
                        )
                        folderAdapter.updateCover(folder.groupKey, path)
                    }
                }.onFailure {
                    appCtx.toastOnUi(it.localizedMessage)
                }
            }
        }
    // 顶栏 Compose 状态：搜索词 + 更多菜单展开
    private var composeSearchQuery by mutableStateOf("")
    private var menuExpanded by mutableStateOf(false)
    // D1: 分组模式（sourceGroupStyle!=0 && sourceGroupMode==1）→ 文件夹视图
    private val isFolderViewMode: Boolean
        get() = AppConfig.sourceGroupStyle != 0 && AppConfig.sourceGroupMode == 1
    // D1: 标签模式（sourceGroupStyle!=0 && sourceGroupMode==0）→ TabLayout + 列表
    private val isTagMode: Boolean
        get() = AppConfig.sourceGroupStyle != 0 && AppConfig.sourceGroupMode == 0
    // F-P1-8 当前是否显示文件夹视图（运行时状态）
    // 点击文件夹进入分组列表时设为 false，但不修改 isFolderViewMode
    // 用户主动点击菜单"切换视图模式"时才同步修改 isFolderViewMode
    private var isShowingFolder: Boolean = false
    // F-01 修复：当前选中的分组（解耦搜索框，避免回填 "group:xxx" 污染搜索词）
    // null=全部, getString(R.string.no_group)=未分组, 其他字符串=指定分组名
    private var currentGroup: String? = null
    // D2 修复：当前选中的类型（按类型分组时使用，sourceGroupStyle==1）
    // -1=全部, 0=网页, 1=图片, 2=视频（RssSource.type）
    private var currentType: Int = -1
    // D2-补丁2：子目录状态判断（文件夹模式下，只要不在文件夹视图就是子目录）
    // 修复：点击"全部分组"文件夹后 currentType=-1/currentGroup=null 但 isShowingFolder=false，应判定为子目录
    private val inSubDirectory: Boolean
        get() = isFolderViewMode && !isShowingFolder
    // D1: 标签模式 TabLayout
    private val tabLayout: TabLayout by lazy { binding.tabLayout }
    private var groupsFlowJob: Job? = null
    private var rssFlowJob: Job? = null
    // 分组集合（Compose 菜单数据驱动，mutableStateOf 保证分组变化时菜单重组）
    private var groups by mutableStateOf(linkedSetOf<String>())
    // D1: Tab 选中监听（用 tag 存选中项，避免 position 映射不稳定）
    // D2: 按类型时 tag 存 Int(类型索引)，按分组时 tag 存 String(分组名)
    private val tabSelectedListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab) {
            if (AppConfig.sourceGroupStyle == 1) {
                currentType = (tab.tag as? Int) ?: -1
                currentGroup = null
            } else {
                currentGroup = tab.tag as? String
                currentType = -1
            }
            upRssFlowJob(composeSearchQuery)
        }
        override fun onTabUnselected(tab: TabLayout.Tab) = Unit
        override fun onTabReselected(tab: TabLayout.Tab) = Unit
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initComposeTopBar()
        initTabLayout()  // D1: 初始化 TabLayout
        // F-P1-8 初始化运行时状态：跟随用户偏好
        isShowingFolder = isFolderViewMode
        initRecyclerView()
        initGroupData()
        if (isShowingFolder) {
            upFolderView()
        } else {
            upRssFlowJob()
        }
        // D2-补丁：返回键处理——子目录内按返回键回文件夹列表/全部
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (inSubDirectory) {
                        currentType = -1
                        currentGroup = null
                        if (isFolderViewMode) {
                            isShowingFolder = true
                            applyView()
                            upFolderView()
                        } else {
                            applyView()
                            upRssFlowJob(composeSearchQuery)
                        }
                        requireActivity().invalidateOptionsMenu()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        )
    }

    // 顶栏 Compose 化：GlassTopAppBar 用 colorScheme.surface（跟随昼夜主题），搜索/菜单迁移到 Compose
    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                Column(modifier = Modifier.statusBarsPadding()) {
                    GlassTopAppBar(
                        title = getString(R.string.rss),
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
                            // 保留：按名称过滤订阅源列表
                            upRssFlowJob(it)
                        },
                        placeholder = getString(R.string.rss_search_key),
                        // rss-unified-search: 提交搜索时跳转到 RssSearchActivity
                        onSearch = {
                            val key = composeSearchQuery.trim()
                            if (key.isNotEmpty()) {
                                RssSearchActivity.start(requireContext(), key)
                                composeSearchQuery = ""
                            }
                        }
                    )
                }
            }
        }
    }

    // 更多菜单数据（文件夹配置 + 阅读记录 + 收藏 + 动态分组 + 设置）
    private fun buildMenuActions(): List<MenuAction> {
        return buildList {
            add(MenuAction(
                Icons.Default.FolderOpen,
                getString(R.string.source_folder_config),
                onClick = { showFolderConfig() }
            ))
            add(MenuAction(
                Icons.Default.History,
                getString(R.string.history),
                onClick = { showDialogFragment<ReadRecordDialog>() }
            ))
            add(MenuAction(
                Icons.Default.Star,
                getString(R.string.favorite),
                onClick = { startActivity<RssFavoritesActivity>() }
            ))
            if (groups.isNotEmpty()) {
                add(MenuAction(Icons.Default.Groups, getString(R.string.group), header = true) {})
                groups.forEach { group ->
                    add(MenuAction(
                        Icons.Default.Label,
                        group,
                        onClick = {
                            currentGroup = group
                            composeSearchQuery = ""
                            upRssFlowJob()
                        }
                    ))
                }
            }
            add(MenuAction(
                Icons.Default.Settings,
                getString(R.string.setting),
                onClick = { startActivity<RssSourceActivity>() }
            ))
        }
    }

    override fun onPause() {
        super.onPause()
    }

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        adapter.addHeaderView {
            ItemRssBinding.inflate(layoutInflater, it, false).apply {
                tvName.setText(R.string.rule_subscription)
                ivIcon.setImageResource(R.drawable.image_legado)
                root.setOnClickListener {
                    startActivity<RuleSubActivity>()
                }
            }
        }
        applyView()  // D1: 统一应用视图（列表/标签/文件夹）
    }

    // F-P1-8 应用列表视图（订阅源固定卡片网格展示：用户决策"订阅源默认卡片，无列表展示"，
    // 列数按屏幕宽度 + sourceMargin 间距自适应，不再受 sourceLayout 列表/紧凑模式影响）
    private fun applyListView() {
        binding.recyclerView.removeItemDecoration(gridSpacingDecoration)
        val marginDp = AppConfig.sourceMargin
        gridSpacingDecoration.spacing = SourceFolderAdapter.spacingPx(requireContext(), marginDp)
        binding.recyclerView.addItemDecoration(gridSpacingDecoration)
        val spanCount = SourceFolderAdapter.calculateSpanCount(requireContext(), marginDp)
        binding.recyclerView.layoutManager = GridLayoutManager(context, spanCount)
        binding.recyclerView.adapter = adapter
    }

    // F-P1-8 应用文件夹视图
    private fun applyFolderView() {
        binding.recyclerView.removeItemDecoration(gridSpacingDecoration)
        val marginDp = AppConfig.sourceMargin
        gridSpacingDecoration.spacing = SourceFolderAdapter.spacingPx(requireContext(), marginDp)
        binding.recyclerView.addItemDecoration(gridSpacingDecoration)
        val spanCount = SourceFolderAdapter.calculateSpanCount(requireContext(), marginDp)
        binding.recyclerView.layoutManager = GridLayoutManager(context, spanCount)
        binding.recyclerView.adapter = folderAdapter
    }

    // D1: 初始化 TabLayout
    private fun initTabLayout() {
        tabLayout.setSelectedTabIndicatorColor(requireContext().accentColor)
        tabLayout.tabMode = TabLayout.MODE_SCROLLABLE
        tabLayout.addOnTabSelectedListener(tabSelectedListener)
    }

    // D1: 填充 Tab。D2: 按类型时显示类型 Tab，按分组时显示分组 Tab
    private fun upTabLayout() {
        tabLayout.removeOnTabSelectedListener(tabSelectedListener)
        tabLayout.removeAllTabs()
        if (AppConfig.sourceGroupStyle == 1) {
            // D2: 按类型分组，Tab 显示类型名，tag 存类型索引
            tabLayout.addTab(tabLayout.newTab().setText(R.string.all_groups).setTag(-1))
            tabLayout.addTab(tabLayout.newTab().setText(R.string.type_web).setTag(0))
            tabLayout.addTab(tabLayout.newTab().setText(R.string.type_image).setTag(1))
            tabLayout.addTab(tabLayout.newTab().setText(R.string.type_video).setTag(2))
            tabLayout.getTabAt((currentType + 1).coerceIn(0, 3))?.select()
        } else {
            // 按分组
            tabLayout.addTab(tabLayout.newTab().setText(R.string.all_groups).setTag(null))
            val noGroup = getString(R.string.no_group)
            tabLayout.addTab(tabLayout.newTab().setText(noGroup).setTag(noGroup))
            groups.forEach { group ->
                tabLayout.addTab(tabLayout.newTab().setText(group).setTag(group))
            }
            val selectedIndex = when (currentGroup) {
                null -> 0
                noGroup -> 1
                else -> 2 + groups.indexOf(currentGroup).coerceAtLeast(0)
            }
            tabLayout.getTabAt(selectedIndex.coerceAtMost(tabLayout.tabCount - 1))?.select()
        }
        tabLayout.addOnTabSelectedListener(tabSelectedListener)
    }

    // D1: 统一应用视图（根据 isShowingFolder / isTagMode 控制显示）
    private fun applyView() {
        if (isShowingFolder) {
            // 分组模式：显示文件夹视图
            binding.tabLayout.visibility = View.GONE
            applyFolderView()
        } else {
            // 列表视图（标签模式 或 列表平铺 或 文件夹点击后）
            if (isTagMode) {
                binding.tabLayout.visibility = View.VISIBLE
                upTabLayout()
            } else {
                binding.tabLayout.visibility = View.GONE
            }
            applyListView()
        }
    }

    // source-layout-deep-refactor 文件夹视图配置对话框
    private fun showFolderConfig() {
        val oldStyle = AppConfig.sourceGroupStyle
        SourceFolderAdapter.showConfigDialog(
            context = requireContext(),
            isBookSource = false  // C-01 修复：订阅源用 rssSort
        ) {
            // D1: 配置变更后根据新配置重新应用视图
            // D2: 分组样式变更时重置 currentType 和 currentGroup，避免旧状态残留
            if (AppConfig.sourceGroupStyle != oldStyle) {
                currentGroup = null
                currentType = -1
            }
            val newIsFolder = isFolderViewMode  // sourceGroupStyle!=0 && sourceGroupMode==1
            if (newIsFolder != isShowingFolder) {
                isShowingFolder = newIsFolder
                if (newIsFolder) composeSearchQuery = ""
            }
            applyView()
            if (isShowingFolder) {
                upFolderView()
            } else {
                upRssFlowJob(composeSearchQuery)
            }
            requireActivity().invalidateOptionsMenu()
        }
    }

    // F-P1-8 更新文件夹视图数据。D2: 按类型时显示类型文件夹
    private fun upFolderView() {
        val folderList = mutableListOf<FolderItem>()
        if (AppConfig.sourceGroupStyle == 1) {
            // D2: 按类型分组
            folderList.add(
                FolderItem(
                    SourceGroupCover.KEY_ALL_GROUPS,
                    getString(R.string.all_groups),
                    true
                )
            )
            folderList.add(
                FolderItem(
                    SourceGroupCover.KEY_TYPE_WEB,
                    getString(R.string.type_web),
                    true
                )
            )
            folderList.add(
                FolderItem(
                    SourceGroupCover.KEY_TYPE_IMAGE,
                    getString(R.string.type_image),
                    true
                )
            )
            folderList.add(
                FolderItem(
                    SourceGroupCover.KEY_TYPE_VIDEO,
                    getString(R.string.type_video),
                    true
                )
            )
        } else {
            folderList.add(
                FolderItem(
                    SourceGroupCover.KEY_ALL_GROUPS,
                    getString(R.string.all_groups),
                    true
                )
            )
            folderList.add(
                FolderItem(
                    SourceGroupCover.KEY_NO_GROUP,
                    getString(R.string.no_group),
                    true
                )
            )
            folderList.addAll(
                groups.map { FolderItem(it, it, false) }
            )
        }
        folderAdapter.setItems(folderList, folderAdapter.diffItemCallback)
        // source-folder-cover: 批量加载分组封面缓存
        viewLifecycleOwner.lifecycleScope.launch {
            kotlin.runCatching {
                appDb.sourceGroupCoverDao.getCoversByKind(SourceGroupCover.KIND_RSS)
            }.getOrDefault(emptyList())
                .associate { it.groupName to it.cover }
                .let { covers ->
                    folderAdapter.upCovers(covers)
                }
        }
    }

    private fun initGroupData() {
        groupsFlowJob?.cancel()
        groupsFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.rssSourceDao.flowEnabledGroups().catch {
                AppLog.put("订阅界面获取分组数据失败\n${it.localizedMessage}", it)
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.RSS_SOURCE_TABLE_NAME
            ).conflate().collect {
                groups = it.toCollection(linkedSetOf())
                if (isShowingFolder) {
                    upFolderView()
                } else if (isTagMode) {
                    upTabLayout()  // D1: 标签模式下刷新 Tab
                }
            }
        }
    }

    private fun upRssFlowJob(searchKey: String? = null) {
        if (isShowingFolder) return
        rssFlowJob?.cancel()
        rssFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            // F-01 修复：currentGroup + searchKey 组合查询（6 分支，解耦搜索框回填）
            val noGroup = getString(R.string.no_group)
            when {
                // D2: 按类型 + 有搜索词
                currentType >= 0 && !searchKey.isNullOrBlank() ->
                    appDb.rssSourceDao.flowByTypeSearch(currentType, searchKey)
                // D2: 按类型 + 无搜索词
                currentType >= 0 ->
                    appDb.rssSourceDao.flowByType(currentType)
                // 分支1: 未分组 + 无搜索词
                currentGroup == noGroup && searchKey.isNullOrBlank() ->
                    appDb.rssSourceDao.flowEnabledNoGroup()
                // 分支2: 未分组 + 有搜索词
                currentGroup == noGroup && !searchKey.isNullOrBlank() ->
                    appDb.rssSourceDao.flowNoGroupSearch(searchKey)
                // 分支3: 指定分组 + 有搜索词
                currentGroup != null && currentGroup != noGroup && !searchKey.isNullOrBlank() ->
                    appDb.rssSourceDao.flowGroupSearchExact(currentGroup!!, searchKey)
                // 分支4: 指定分组 + 无搜索词
                currentGroup != null && currentGroup != noGroup && searchKey.isNullOrBlank() ->
                    appDb.rssSourceDao.flowEnabledByGroup(currentGroup!!)
                // 分支5: 无分组 + 有搜索词
                currentGroup == null && !searchKey.isNullOrBlank() ->
                    appDb.rssSourceDao.flowEnabled(searchKey)
                // 分支6: 无分组 + 无搜索词（默认全部）
                else -> appDb.rssSourceDao.flowEnabled()
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.RSS_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("订阅界面更新数据出错", it)
            }.flowOn(IO).collect {
                adapter.setItems(sortSources(it))
            }
        }
    }

    // D-2 修复：rssSort 配置驱动排序（0=手动/1=名称/2=启用/3=类型/4=分组/5=URL/6=更新时间），与管理页 RssSourceActivity 一致
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
        return if (AppConfig.rssSortAscending) sorted else sorted.reversed()
    }

    // F-P1-8 文件夹点击回调：点击文件夹 → 临时切换到列表视图并按分组筛选
    // 注意：不修改 rssViewMode（用户偏好），仅修改 isShowingFolder（运行时状态）
    // 这样再次进入或用户点击菜单"文件夹视图"时，仍会显示文件夹视图
    override fun onFolderClick(folder: FolderItem) {
        isShowingFolder = false
        applyView()  // D1: 统一应用视图（分组模式点击文件夹后进入列表）
        requireActivity().invalidateOptionsMenu()
        // D2: 按类型时设置 currentType，按分组时设置 currentGroup
        if (AppConfig.sourceGroupStyle == 1) {
            currentType = when (folder.groupKey) {
                SourceGroupCover.KEY_TYPE_WEB -> 0
                SourceGroupCover.KEY_TYPE_IMAGE -> 1
                SourceGroupCover.KEY_TYPE_VIDEO -> 2
                else -> -1  // all_groups
            }
            currentGroup = null
        } else {
            currentType = -1
            currentGroup = when (folder.groupKey) {
                SourceGroupCover.KEY_ALL_GROUPS -> null
                SourceGroupCover.KEY_NO_GROUP -> getString(R.string.no_group)
                else -> folder.groupKey
            }
        }
        composeSearchQuery = ""  // 清空搜索词，不触发查询
        upRssFlowJob()  // 直接触发查询
    }

    override fun onFolderSelectImage(folder: FolderItem) {
        pendingFolder = folder
        selectFolderCover.launch {
            mode = HandleFileContract.IMAGE
        }
    }

    override fun onFolderRestoreCover(folder: FolderItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            kotlin.runCatching {
                appDb.sourceGroupCoverDao.delete(
                    SourceGroupCover.KIND_RSS,
                    folder.groupKey
                )
                folderAdapter.updateCover(folder.groupKey, null)
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }

    override fun openRss(rssSource: RssSource) {
        if (rssSource.singleUrl) {
            viewModel.getSingleUrl(rssSource) { url ->
                if (url.startsWith("http", true)) {
                    ReadRssActivity.start(
                        requireContext(),
                        true,
                        rssSource.sourceUrl,
                        rssSource.sourceName,
                        url
                    )
                } else {
                    context?.openUrl(url)
                }
            }
        } else {
            viewModel.launchRssWithHtml(rssSource, {
                startActivity<RssSortActivity> {
                    putExtra("sourceUrl", rssSource.sourceUrl)
                }
            }) { html ->
                ReadRssActivity.start(
                    requireContext(),
                    true,
                    rssSource.sourceUrl,
                    rssSource.sourceName,
                    startHtml = html
                )
            }
        }
    }

    override fun toTop(rssSource: RssSource) {
        viewModel.topSource(rssSource)
    }

    override fun login(rssSource: RssSource) {
        startActivity<SourceLoginActivity> {
            putExtra("type", "rssSource")
            putExtra("key", rssSource.sourceUrl)
        }
    }

    override fun edit(rssSource: RssSource) {
        startActivity<RssSourceEditActivity> {
            putExtra("sourceUrl", rssSource.sourceUrl)
        }
    }

    override fun del(rssSource: RssSource) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + rssSource.sourceName)
            noButton()
            yesButton {
                viewModel.del(rssSource)
            }
        }
    }

    override fun disable(rssSource: RssSource) {
        viewModel.disable(rssSource)
    }
}
