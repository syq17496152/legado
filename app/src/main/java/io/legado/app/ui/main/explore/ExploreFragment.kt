package io.legado.app.ui.main.explore

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.isGone
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.databinding.FragmentExploreBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.adapter.SourceFolderAdapter
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsSearchBar
import io.legado.app.ui.widget.recycler.GridSpacingItemDecoration
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 发现界面
 */
class ExploreFragment() : VMBaseFragment<ExploreViewModel>(R.layout.fragment_explore),
    MainFragmentInterface,
    ExploreAdapter.CallBack,
    SourceFolderAdapter.CallBack {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    override val viewModel by viewModels<ExploreViewModel>()
    private val binding by viewBinding(FragmentExploreBinding::bind)
    private val adapter by lazy { ExploreAdapter(requireContext(), this) }
    private val folderAdapter by lazy { SourceFolderAdapter(requireContext(), this) }
    private val gridSpacingDecoration = GridSpacingItemDecoration()
    private val linearLayoutManager by lazy { LinearLayoutManager(context) }
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
    // -1=全部, 0=文本, 1=音频, 2=图片, 3=文件, 4=视频（BookSource.bookSourceType）
    private var currentType: Int = -1
    // D2-补丁2：子目录状态判断（文件夹模式下，只要不在文件夹视图就是子目录）
    // 修复：点击"全部分组"文件夹后 currentType=-1/currentGroup=null 但 isShowingFolder=false，应判定为子目录
    private val inSubDirectory: Boolean
        get() = isFolderViewMode && !isShowingFolder
    // D1: 标签模式 TabLayout
    private val tabLayout: TabLayout by lazy { binding.tabLayout }
    private val diffItemCallBack = ExploreDiffItemCallBack()
    // 分组集合（Compose 菜单数据驱动，mutableStateOf 保证分组变化时菜单重组）
    private var groups by mutableStateOf(linkedSetOf<String>())
    private var exploreFlowJob: Job? = null
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
            upExploreData(composeSearchQuery)
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
            upExploreData()
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
                            upExploreData(composeSearchQuery)
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
                        title = getString(R.string.discovery),
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
                            upExploreData(it)
                        },
                        placeholder = getString(R.string.screen_find)
                    )
                }
            }
        }
    }

    // 更多菜单数据（文件夹配置 + 动态分组列表）
    private fun buildMenuActions(): List<MenuAction> {
        return buildList {
            add(MenuAction(
                Icons.Default.FolderOpen,
                getString(R.string.source_folder_config),
                onClick = { showFolderConfig() }
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
                            upExploreData()
                        }
                    ))
                }
            }
        }
    }

    private fun initRecyclerView() {
        binding.rvFind.setEdgeEffectColor(primaryColor)
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                if (positionStart == 0) {
                    binding.rvFind.scrollToPosition(0)
                }
            }
        })
        applyView()  // D1: 统一应用视图（列表/标签/文件夹）
    }

    // F-P1-8 应用列表视图
    private fun applyListView() {
        binding.rvFind.removeItemDecoration(gridSpacingDecoration)
        binding.rvFind.layoutManager = linearLayoutManager
        binding.rvFind.adapter = adapter
    }

    // F-P1-8 应用文件夹视图
    private fun applyFolderView() {
        binding.rvFind.removeItemDecoration(gridSpacingDecoration)
        val marginDp = AppConfig.sourceMargin
        gridSpacingDecoration.spacing = SourceFolderAdapter.spacingPx(requireContext(), marginDp)
        binding.rvFind.addItemDecoration(gridSpacingDecoration)
        val spanCount = SourceFolderAdapter.calculateSpanCount(requireContext(), marginDp)
        binding.rvFind.layoutManager = GridLayoutManager(context, spanCount)
        binding.rvFind.adapter = folderAdapter
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
            tabLayout.addTab(tabLayout.newTab().setText(R.string.type_text).setTag(0))
            tabLayout.addTab(tabLayout.newTab().setText(R.string.type_audio).setTag(1))
            tabLayout.addTab(tabLayout.newTab().setText(R.string.type_image).setTag(2))
            tabLayout.addTab(tabLayout.newTab().setText(R.string.type_file).setTag(3))
            tabLayout.addTab(tabLayout.newTab().setText(R.string.type_video).setTag(4))
            tabLayout.getTabAt((currentType + 1).coerceIn(0, 5))?.select()
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
            isBookSource = true  // C-01 修复：书源用 bookSourceSort
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
                upExploreData(composeSearchQuery)
            }
            requireActivity().invalidateOptionsMenu()
        }
    }

    // F-P1-8 更新文件夹视图数据。D2: 按类型时显示类型文件夹
    private fun upFolderView() {
        val folderList = mutableListOf<String>()
        if (AppConfig.sourceGroupStyle == 1) {
            // D2: 按类型分组
            folderList.add(getString(R.string.all_groups))
            folderList.add(getString(R.string.type_text))
            folderList.add(getString(R.string.type_audio))
            folderList.add(getString(R.string.type_image))
            folderList.add(getString(R.string.type_file))
            folderList.add(getString(R.string.type_video))
        } else {
            folderList.add(getString(R.string.all_groups))
            folderList.add(getString(R.string.no_group))
            folderList.addAll(groups)
        }
        folderAdapter.setItems(folderList, folderAdapter.diffItemCallback)
    }

    private fun initGroupData() {
        viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookSourceDao.flowExploreGroups()
                .flowWithLifecycleAndDatabaseChange(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.RESUMED,
                    AppDatabase.BOOK_SOURCE_TABLE_NAME
                )
                .conflate()
                .distinctUntilChanged()
                .collect {
                    groups = it.toCollection(linkedSetOf())
                    if (isShowingFolder) {
                        upFolderView()
                    } else if (isTagMode) {
                        upTabLayout()  // D1: 标签模式下刷新 Tab
                    }
                    delay(500)
                }
        }
    }

    private fun upExploreData(searchKey: String? = null) {
        if (isShowingFolder) return
        exploreFlowJob?.cancel()
        exploreFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            // F-01 修复：currentGroup + searchKey 组合查询（6 分支，解耦搜索框回填）
            val noGroup = getString(R.string.no_group)
            when {
                // D2: 按类型 + 有搜索词
                currentType >= 0 && !searchKey.isNullOrBlank() ->
                    appDb.bookSourceDao.flowExploreByTypeSearch(currentType, searchKey)
                // D2: 按类型 + 无搜索词
                currentType >= 0 ->
                    appDb.bookSourceDao.flowExploreByType(currentType)
                // 分支1: 未分组 + 无搜索词
                currentGroup == noGroup && searchKey.isNullOrBlank() ->
                    appDb.bookSourceDao.flowExploreNoGroup()
                // 分支2: 未分组 + 有搜索词
                currentGroup == noGroup && !searchKey.isNullOrBlank() ->
                    appDb.bookSourceDao.flowExploreNoGroupSearch(searchKey)
                // 分支3: 指定分组 + 有搜索词
                currentGroup != null && currentGroup != noGroup && !searchKey.isNullOrBlank() ->
                    appDb.bookSourceDao.flowGroupSearchExact(currentGroup!!, searchKey)
                // 分支4: 指定分组 + 无搜索词
                currentGroup != null && currentGroup != noGroup && searchKey.isNullOrBlank() ->
                    appDb.bookSourceDao.flowGroupExplore(currentGroup!!)
                // 分支5: 无分组 + 有搜索词
                currentGroup == null && !searchKey.isNullOrBlank() ->
                    appDb.bookSourceDao.flowExplore(searchKey)
                // 分支6: 无分组 + 无搜索词（默认全部）
                else -> appDb.bookSourceDao.flowExplore()
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("发现界面更新数据出错", it)
            }.conflate().flowOn(IO).collect {
                binding.tvEmptyMsg.isGone = it.isNotEmpty() || composeSearchQuery.isNotEmpty()
                adapter.setItems(it, diffItemCallBack)
                delay(500)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.upResumed(true)
    }

    override fun onPause() {
        adapter.upResumed(false)
        adapter.onPause()
        super.onPause()
    }

    override val scope: CoroutineScope
        get() = viewLifecycleOwner.lifecycleScope

    // F-P1-8 文件夹点击回调：点击文件夹 → 临时切换到列表视图并按分组筛选
    // 注意：不修改 sourceViewMode（用户偏好），仅修改 isShowingFolder（运行时状态）
    // 这样再次进入或用户点击菜单"文件夹视图"时，仍会显示文件夹视图
    override fun onFolderClick(group: String) {
        isShowingFolder = false
        applyView()  // D1: 统一应用视图（分组模式点击文件夹后进入列表）
        requireActivity().invalidateOptionsMenu()
        // D2: 按类型时设置 currentType，按分组时设置 currentGroup
        if (AppConfig.sourceGroupStyle == 1) {
            currentType = when (group) {
                getString(R.string.type_text) -> 0
                getString(R.string.type_audio) -> 1
                getString(R.string.type_image) -> 2
                getString(R.string.type_file) -> 3
                getString(R.string.type_video) -> 4
                else -> -1  // all_groups
            }
            currentGroup = null
        } else {
            currentType = -1
            currentGroup = when (group) {
                getString(R.string.all_groups) -> null
                getString(R.string.no_group) -> getString(R.string.no_group)
                else -> group
            }
        }
        composeSearchQuery = ""  // 清空搜索词，不触发查询
        upExploreData()  // 直接触发查询
    }

    override fun scrollTo(pos: Int) {
        (binding.rvFind.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 0)
    }

    override fun openExplore(sourceUrl: String, title: String, exploreUrl: String?) {
        if (exploreUrl.isNullOrBlank()) return
        startActivity<ExploreShowActivity> {
            putExtra("exploreName", title)
            putExtra("sourceUrl", sourceUrl)
            putExtra("exploreUrl", exploreUrl)
        }
    }

    override fun editSource(sourceUrl: String) {
        startActivity<BookSourceEditActivity> {
            putExtra("sourceUrl", sourceUrl)
        }
    }

    override fun toTop(source: BookSourcePart) {
        viewModel.topSource(source)
    }

    override fun deleteSource(source: BookSourcePart) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + source.bookSourceName)
            noButton()
            yesButton {
                viewModel.deleteSource(source)
            }
        }
    }

    override fun searchBook(bookSource: BookSourcePart) {
        SearchActivity.start(requireContext(), bookSource)
    }

    fun compressExplore() {
        if (!adapter.compressExplore()) {
            if (AppConfig.isEInkMode) {
                binding.rvFind.scrollToPosition(0)
            } else {
                binding.rvFind.smoothScrollToPosition(0)
            }
        }
    }

}
