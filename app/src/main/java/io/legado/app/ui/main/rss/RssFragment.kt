package io.legado.app.ui.main.rss

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.SubMenu
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.FragmentRssBinding
import io.legado.app.databinding.ItemRssBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.adapter.SourceFolderAdapter
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.rss.article.ReadRecordDialog
import io.legado.app.ui.rss.article.RssSortActivity
import io.legado.app.ui.rss.favorites.RssFavoritesActivity
import io.legado.app.ui.rss.read.ReadRssActivity
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.rss.source.manage.RssSourceActivity
import io.legado.app.ui.rss.subscription.RuleSubActivity
import io.legado.app.ui.widget.recycler.GridSpacingItemDecoration
import io.legado.app.utils.applyTint
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.openUrl
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.transaction
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import io.legado.app.ui.login.SourceLoginActivity

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
    private val folderAdapter by lazy { SourceFolderAdapter(requireContext(), this) }
    private val gridSpacingDecoration = GridSpacingItemDecoration()
    // F-P1-8 用户视图偏好（持久化）：0=列表视图, 1=文件夹视图
    private val isFolderViewMode: Boolean
        get() = AppConfig.sourceGroupStyle != 0  // 按类型/按分组 → 文件夹视图
    // F-P1-8 当前是否显示文件夹视图（运行时状态）
    // 点击文件夹进入分组列表时设为 false，但不修改 isFolderViewMode
    // 用户主动点击菜单"切换视图模式"时才同步修改 isFolderViewMode
    private var isShowingFolder: Boolean = false
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private var groupsFlowJob: Job? = null
    private var rssFlowJob: Job? = null
    private val groups = linkedSetOf<String>()
    private var groupsMenu: SubMenu? = null

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        initSearchView()
        // F-P1-8 初始化运行时状态：跟随用户偏好
        isShowingFolder = isFolderViewMode
        initRecyclerView()
        initGroupData()
        if (isShowingFolder) {
            upFolderView()
        } else {
            upRssFlowJob()
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.main_rss, menu)
        groupsMenu = menu.findItem(R.id.menu_group)?.subMenu
        upGroupsMenu()
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        super.onCompatOptionsItemSelected(item)
        when (item.itemId) {
            R.id.menu_folder_config -> showFolderConfig()
            R.id.menu_read_record -> showDialogFragment<ReadRecordDialog>()
            R.id.menu_rss_config -> startActivity<RssSourceActivity>()
            R.id.menu_rss_star -> startActivity<RssFavoritesActivity>()
            else -> if (item.groupId == R.id.menu_group_text) {
                searchView.setQuery("group:${item.title}", true)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        searchView.clearFocus()
    }

    private fun upGroupsMenu() = groupsMenu?.transaction { subMenu ->
        subMenu.removeGroup(R.id.menu_group_text)
        groups.forEach {
            subMenu.add(R.id.menu_group_text, Menu.NONE, Menu.NONE, it)
        }
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.rss)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                upRssFlowJob(newText)
                return false
            }
        })
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
        if (isShowingFolder) {
            applyFolderView()
        } else {
            applyListView()
        }
    }

    // F-P1-8 应用列表视图
    private fun applyListView() {
        binding.recyclerView.removeItemDecoration(gridSpacingDecoration)
        binding.recyclerView.layoutManager = GridLayoutManager(context, 4)
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

    // source-layout-deep-refactor 文件夹视图配置对话框
    private fun showFolderConfig() {
        SourceFolderAdapter.showConfigDialog(
            context = requireContext()
        ) {
            // 配置变更后根据新分组样式重新应用视图
            val wantFolder = AppConfig.sourceGroupStyle != 0
            if (wantFolder != isShowingFolder) {
                isShowingFolder = wantFolder
                if (wantFolder) searchView.setQuery("", false)
                if (isShowingFolder) {
                    applyFolderView()
                    upFolderView()
                } else {
                    applyListView()
                    upRssFlowJob(searchView.query?.toString())
                }
            } else if (isShowingFolder) {
                applyFolderView()
                upFolderView()
            } else {
                applyListView()
                upRssFlowJob(searchView.query?.toString())
            }
            requireActivity().invalidateOptionsMenu()
        }
    }

    // F-P1-8 更新文件夹视图数据
    private fun upFolderView() {
        val folderList = mutableListOf<String>()
        folderList.add(getString(R.string.all_groups))
        folderList.add(getString(R.string.no_group))
        folderList.addAll(groups)
        folderAdapter.setItems(folderList, folderAdapter.diffItemCallback)
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
                groups.clear()
                groups.addAll(it)
                upGroupsMenu()
                if (isShowingFolder) {
                    upFolderView()
                }
            }
        }
    }

    private fun upRssFlowJob(searchKey: String? = null) {
        if (isShowingFolder) return
        rssFlowJob?.cancel()
        rssFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> appDb.rssSourceDao.flowEnabled()
                searchKey == getString(R.string.no_group) -> {
                    appDb.rssSourceDao.flowEnabledNoGroup()
                }
                searchKey.startsWith("group:") -> {
                    val key = searchKey.substringAfter("group:")
                    appDb.rssSourceDao.flowEnabledByGroup(key)
                }

                else -> appDb.rssSourceDao.flowEnabled(searchKey)
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.RSS_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("订阅界面更新数据出错", it)
            }.flowOn(IO).collect {
                adapter.setItems(it)
            }
        }
    }

    // F-P1-8 文件夹点击回调：点击文件夹 → 临时切换到列表视图并按分组筛选
    // 注意：不修改 rssViewMode（用户偏好），仅修改 isShowingFolder（运行时状态）
    // 这样再次进入或用户点击菜单"文件夹视图"时，仍会显示文件夹视图
    override fun onFolderClick(group: String) {
        isShowingFolder = false
        applyListView()
        requireActivity().invalidateOptionsMenu()
        when (group) {
            getString(R.string.all_groups) -> searchView.setQuery("", true)
            getString(R.string.no_group) -> searchView.setQuery(getString(R.string.no_group), true)
            else -> searchView.setQuery("group:$group", true)
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