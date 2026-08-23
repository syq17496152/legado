package io.legado.app.ui.main.rss

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.commit
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
import io.legado.app.help.source.sortUrls
import io.legado.app.help.webView.WebViewPool
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.adapter.FolderItem
import io.legado.app.ui.adapter.SourceFolderAdapter
import io.legado.app.ui.adapter.SourceFolderConfigDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.rss.article.ReadRecordDialog
import io.legado.app.ui.rss.article.RssArticlesFragment
import io.legado.app.ui.rss.article.RssSortActivity
import io.legado.app.ui.rss.article.RssSortViewModel
import io.legado.app.ui.rss.favorites.RssFavoritesActivity
import io.legado.app.ui.rss.read.ReadRssActivity
import io.legado.app.ui.rss.search.RssSearchActivity
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.rss.source.manage.RssSourceActivity
import io.legado.app.ui.rss.subscription.RuleSubActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.MainTopBarView
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.RoundedTagBarView
import io.legado.app.ui.widget.SourceSelectDialog
import io.legado.app.ui.widget.components.SettingsSearchBar
import io.legado.app.ui.widget.recycler.GridSpacingItemDecoration
import io.legado.app.utils.applyMainBottomBarPadding
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.cnCompare
import io.legado.app.utils.dpToPx
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.gone
import io.legado.app.utils.inputStream
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.openUrl
import io.legado.app.utils.readUri
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.transaction
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
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
    // 现代形态（新版订阅）：内嵌 RssArticlesFragment 共享的分类/文章宿主 ViewModel（对齐 Archive）
    private val sortHostViewModel by viewModels<RssSortViewModel>()
    // 现代形态（新版订阅）运行时状态（对齐 Archive RssFragment L100-111）
    private var usingModernRss = false
    private var rssWebView: WebView? = null
    private var selectedRssSource: RssSource? = null
    private val rssSources = mutableListOf<RssSource>()
    private val currentSorts = mutableListOf<Pair<String, String>>()
    private var selectedTagIndex = 0
    private var currentSearchKey: String? = null
    private var webSourceVersion = 0L
    private var lastRenderedWebSourceUrl: String? = null
    private var rssTopOverlaySpace = 0
    private var rssTopOverlayEnabled = false
    private var pendingRenderCurrentSort = false
    // 切换逻辑修复：经典形态一次性初始化标记（防 modern↔classic 反复切换重复添加 header/注册 tab 监听）
    private var classicHeaderReady = false
    private var classicTabListenerReady = false
    // 切换逻辑修复：源切换版本号（防 sortUrls 慢返回用过期 currentSorts 覆盖当前源内容）
    private var rssSourceVersion = 0L
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
    // 顶栏状态：搜索词
    private var composeSearchQuery by mutableStateOf("")
    // 更多菜单弹窗句柄（ModernActionPopup，生命周期由弹窗自身管理）
    private var rssMenuPopup: ModernActionPopup.Handle? = null
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
        applyRssMode()
        // D2-补丁：返回键处理——子目录内按返回键回文件夹列表/全部
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!usingModernRss && inSubDirectory) {
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

    override fun onResume() {
        super.onResume()
        if (usingModernRss != AppConfig.modernRssPage) {
            applyRssMode()
        }
        if (pendingRenderCurrentSort && usingModernRss) {
            binding.root.post {
                if (pendingRenderCurrentSort) {
                    renderCurrentSort()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        WebViewPool.scheduleDestroyScope(WebViewPool.Scope.RSS)
    }

    override fun onDestroyView() {
        groupsFlowJob?.cancel()
        groupsFlowJob = null
        rssFlowJob?.cancel()
        rssFlowJob = null
        pendingRenderCurrentSort = false
        rssWebView?.let { webView ->
            binding.rssWebContainer.removeView(webView)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        rssWebView = null
        WebViewPool.destroyScope(WebViewPool.Scope.RSS)
        super.onDestroyView()
    }

    // F-4/6.x: 新版/经典订阅双形态分派（读 AppConfig.modernRssPage）
    private fun applyRssMode() {
        usingModernRss = AppConfig.modernRssPage
        if (usingModernRss) {
            applyModernRssMode()
        } else {
            applyClassicRssMode()
        }
        activity?.invalidateOptionsMenu()
    }

    // 经典形态（对齐本项目增强：文件夹/标签/排序/内联搜索 + 更多菜单）
    private fun applyClassicRssMode() {
        destroyModernRssChildren()
        pendingRenderCurrentSort = false
        binding.composeSearchBar.isVisible = true
        binding.tabLayout.isVisible = true
        binding.recyclerView.isVisible = true
        binding.tvEmptyMsg.isGone = true
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
    }

    // 现代形态（对齐 Archive：源标签 + 分类标签 + 内嵌文章预览 / WebView 单源渲染）
    private fun applyModernRssMode() {
        groupsFlowJob?.cancel()
        rssFlowJob?.cancel()
        binding.composeSearchBar.gone()
        binding.tabLayout.gone()
        binding.recyclerView.gone()
        binding.tvEmptyMsg.gone()
        binding.rssFragmentContainer.isGone = true
        binding.rssWebContainer.isGone = true
        binding.pbRssLoading.gone()
        initModernRssView()
        observeRssSources()
    }

    private fun destroyModernRssChildren() {
        rssWebView?.let { webView ->
            binding.rssWebContainer.removeView(webView)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        rssWebView = null
        childFragmentManager.findFragmentById(R.id.rss_fragment_container)?.let {
            childFragmentManager.commit { remove(it) }
        }
    }

    // ===== 现代形态（新版订阅，对齐 Archive RssFragment L241-297）=====
    // 顶栏：源选择（titleSelect/primaryBar）+ 分类标签（tagsBar）+ 搜索/登录/星标/刷新
    // 内容：内嵌 RssArticlesFragment（ruleArticles 源）或 WebView 单源渲染（无 ruleArticles 源）
    @SuppressLint("SetJavaScriptEnabled")
    private fun initModernRssView() {
        binding.topBar.setMode(MainTopBarView.Mode.RSS)
        binding.topBar.titleText.applyUiTitleTypeface(requireContext())
        binding.topBar.applyStatusBarPadding(withInitialPadding = true)
        binding.topBar.doOnLayout {
            updateModernRssTopBarOverlay()
        }
        updateModernRssTopBarOverlay()
        val updateSourceNameWidth = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateRssSourceNameWidth()
        }
        binding.topBar.addOnLayoutChangeListener(updateSourceNameWidth)
        binding.topBar.post(::updateRssSourceNameWidth)
        // 源选择：点击标题 → 弹 SourceSelectDialog
        binding.topBar.titleSelect.setOnClickListener {
            showSourceSelector()
        }
        // 源标签（primaryBar）点击 → 切换源（无法现代渲染的单源走旧打开方式）
        binding.topBar.primaryBar.setOnTagClickListener { index ->
            val source = rssSources.getOrNull(index) ?: return@setOnTagClickListener
            if (source.canRenderInModernPage()) {
                selectSource(source, reload = true)
            } else {
                openRssLegacy(source)
            }
        }
        // 搜索入口
        binding.topBar.searchEntry.setOnClickListener {
            openRssSearch()
        }
        binding.topBar.loginButton.setOnClickListener {
            selectedRssSource?.let(::openRssLogin)
        }
        binding.topBar.starButton.setOnClickListener {
            startActivity<RssFavoritesActivity>()
        }
        binding.topBar.refreshButton.setOnClickListener {
            refreshCurrentRssContent(forceWebRefresh = true)
        }
        binding.topBar.searchButton.setOnClickListener {
            openRssSearch()
        }
        // 分类标签（tagsBar）点击 → 渲染对应分类
        binding.topBar.tagsBar.setOnTagClickListener { index ->
            val targetIndex = validRssSortIndex(index) ?: return@setOnTagClickListener
            if (targetIndex == selectedTagIndex) return@setOnTagClickListener
            selectedTagIndex = targetIndex
            binding.topBar.tagsBar.setSelectedIndex(targetIndex)
            renderCurrentSort()
        }
        binding.topBar.setOnHeightChangedListener {
            updateModernRssTopBarOverlay()
        }
    }

    // 顶栏高度变化 → 内容区顶部 padding 同步（覆盖式顶栏：列表/WebView 需留出顶栏占位）
    private fun updateModernRssTopBarOverlay() {
        if (!usingModernRss || view == null) return
        val topSpace = binding.topBar.height
        val overlay = binding.topBar.isOverlayMode()
        rssTopOverlaySpace = topSpace
        rssTopOverlayEnabled = overlay
        binding.recyclerView.clipToPadding = true
        binding.recyclerView.setPadding(
            binding.recyclerView.paddingLeft,
            topSpace,
            binding.recyclerView.paddingRight,
            binding.recyclerView.paddingBottom
        )
        rssWebView?.let { webView ->
            applyModernRssWebViewTopSpace(webView)
        }
        (childFragmentManager.findFragmentById(R.id.rss_fragment_container) as? RssArticlesFragment)
            ?.setTopOverlaySpace(topSpace, overlay)
        binding.topBar.bringToFront()
    }

    private fun scheduleModernRssTopBarOverlayUpdate() {
        if (!usingModernRss || view == null) return
        binding.topBar.post {
            updateModernRssTopBarOverlay()
        }
    }

    // 源名称最大宽度：减去右侧操作按钮宽度，避免标题挤压
    private fun updateRssSourceNameWidth() {
        val rowWidth = binding.topBar.width
        if (rowWidth <= 0) return
        val actionsWidth = listOf(
            binding.topBar.searchButton,
            binding.topBar.starButton,
            binding.topBar.refreshButton,
            binding.topBar.loginButton
        ).filter { it.isVisible }.sumOf { it.measuredWidth.takeIf { width -> width > 0 } ?: it.layoutParams.width }
        val spacing = 36.dpToPx()
        val maxWidth = (rowWidth - actionsWidth - spacing).coerceIn(96.dpToPx(), 190.dpToPx())
        binding.topBar.titleText.maxWidth = maxWidth
    }

    // 下拉刷新/滚动目标（本项目无 swipeRefreshLayout，仅用于 gotoTop 定位）
    private fun currentRssScrollTarget(): View? {
        return when {
            usingModernRss && binding.rssWebContainer.isVisible -> rssWebView
            usingModernRss && binding.rssFragmentContainer.isVisible ->
                childFragmentManager.findFragmentById(R.id.rss_fragment_container)
                    ?.view
                    ?.findViewById<View>(R.id.recycler_view)
            else -> binding.recyclerView
        }
    }

    // 现代形态源列表观察（对齐 Archive L396-434）
    private fun observeRssSources(searchKey: String? = currentSearchKey) {
        currentSearchKey = searchKey
        rssFlowJob?.cancel()
        rssFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> appDb.rssSourceDao.flowEnabled()
                searchKey.startsWith("group:") ->
                    appDb.rssSourceDao.flowEnabledByGroup(searchKey.substringAfter("group:"))
                else -> appDb.rssSourceDao.flowEnabled(searchKey)
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.RSS_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("订阅页面更新数据出错\n${it.localizedMessage}", it)
            }.flowOn(IO).collect { sources ->
                rssSources.clear()
                rssSources.addAll(sources)
                renderRssSourceSelector()
                val keep = selectedRssSource?.sourceUrl?.let { key ->
                    sources.firstOrNull { it.sourceUrl == key && it.canRenderInModernPage() }
                }
                val remembered = if (keep == null && searchKey.isNullOrEmpty()) {
                    AppConfig.modernRssSourceUrl?.let { key ->
                        sources.firstOrNull { it.sourceUrl == key && it.canRenderInModernPage() }
                    }
                } else {
                    null
                }
                when {
                    keep != null -> selectSource(keep, reload = false)
                    remembered != null -> selectSource(remembered, reload = true)
                    sources.any { it.canRenderInModernPage() } ->
                        selectSource(sources.first { it.canRenderInModernPage() }, reload = true)
                    else -> renderEmptyState()
                }
            }
        }
    }

    // 选中源（对齐 Archive L436-460）
    private fun selectSource(source: RssSource, reload: Boolean) {
        val changed = selectedRssSource?.sourceUrl != source.sourceUrl
        selectedRssSource = source
        AppConfig.modernRssSourceUrl = source.sourceUrl
        val hasSearch = !source.searchUrl.isNullOrBlank()
        binding.topBar.setSearchEntryVisible(hasSearch)
        binding.topBar.setTitle(
            if (binding.topBar.isRegularStyle() && hasSearch) getString(R.string.rss) else source.sourceName
        )
        binding.topBar.setSearchHint(source.sourceName)
        binding.topBar.loginButton.isVisible = !source.loginUrl.isNullOrBlank()
        binding.topBar.searchButton.isVisible = hasSearch && !binding.topBar.isRegularStyle()
        binding.topBar.searchEntry.isEnabled = hasSearch
        binding.topBar.searchEntry.alpha = if (hasSearch) 1f else 0.58f
        binding.topBar.refreshButton.isVisible = source.ruleArticles.isNullOrBlank()
        renderRssSourceSelector()
        binding.topBar.post(::updateRssSourceNameWidth)
        scheduleModernRssTopBarOverlayUpdate()
        if (changed) {
            selectedTagIndex = 0
        }
        if (changed || reload) {
            // 切换逻辑修复：源切换版本号递增，presentSource 校验版本丢弃过期源结果
            rssSourceVersion += 1
            val sourceVersion = rssSourceVersion
            viewLifecycleOwner.lifecycleScope.launch {
                presentSource(source, sourceVersion)
            }
        }
    }

    // 渲染源：ruleArticles 源 → 分类标签 + 内嵌文章列表；无 ruleArticles 源 → WebView 单源（对齐 Archive L462-516）
    // sourceVersion 由 selectSource 传入，用于切换逻辑修复（丢弃过期源渲染结果）
    private suspend fun presentSource(source: RssSource, sourceVersion: Long = rssSourceVersion) {
        if (binding.pbRssLoading.isVisible) {
            binding.pbRssLoading.gone()
        } else {
            binding.pbRssLoading.visible()
        }
        if (!source.canRenderInModernPage()) {
            binding.pbRssLoading.gone()
            renderEmptyState()
            return
        }
        binding.tvEmptyMsg.gone()
        sortHostViewModel.url = source.sourceUrl
        sortHostViewModel.rssSource = source
        sortHostViewModel.sourceName = source.sourceName
        sortHostViewModel.searchKey = null

        if (source.ruleArticles.isNullOrBlank()) {
            currentSorts.clear()
            binding.topBar.showTags(false)
            scheduleModernRssTopBarOverlayUpdate()
            // 切换逻辑修复：期间用户已切换源则丢弃过期 WebView 渲染
            if (sourceVersion != rssSourceVersion || selectedRssSource?.sourceUrl != source.sourceUrl) {
                return
            }
            renderWebSource(source)
            return
        }

        val sorts = kotlin.runCatching { source.sortUrls() }
            .getOrElse {
                AppLog.put("订阅页面加载分类失败\n${it.localizedMessage}", it)
                listOf(Pair("", source.sourceUrl))
            }.ifEmpty {
                listOf(Pair("", source.sourceUrl))
            }
        // 切换逻辑修复：sortUrls 可能含 30s JS 执行，期间用户已切换源则丢弃本次结果（防旧 currentSorts 覆盖当前源）
        if (sourceVersion != rssSourceVersion || selectedRssSource?.sourceUrl != source.sourceUrl) {
            return
        }
        currentSorts.clear()
        currentSorts.addAll(sorts.filter { it.first.isNotBlank() }.ifEmpty { sorts })
        selectedTagIndex = validRssSortIndex(selectedTagIndex) ?: currentSorts.indexOfFirst { it.second.isNotBlank() }
            .takeIf { it >= 0 }
            ?: 0
        val visibleTags = currentSorts.filter { it.first.isNotBlank() }
        if (visibleTags.size > 1 || (currentSorts.size == 1 && currentSorts.first().first.isNotBlank())) {
            binding.topBar.showTags(true)
            binding.topBar.tagsBar.submitItems(
                currentSorts.map {
                    RoundedTagBarView.Item(
                        it.first,
                        if (it.second.isBlank()) 0.55f else 1f
                    )
                },
                selectedTagIndex.coerceIn(0, currentSorts.lastIndex)
            )
        } else {
            binding.topBar.showTags(false)
        }
        scheduleModernRssTopBarOverlayUpdate()
        renderCurrentSort()
    }

    // 渲染当前分类（内嵌 RssArticlesFragment 到 rss_fragment_container，对齐 Archive L518-553）
    private fun renderCurrentSort() {
        val source = selectedRssSource ?: return
        if (currentSorts.isEmpty()) {
            binding.pbRssLoading.gone()
            renderEmptyState()
            return
        }
        if (!canCommitRssChildFragment()) {
            pendingRenderCurrentSort = true
            return
        }
        pendingRenderCurrentSort = false
        selectedTagIndex = selectedTagIndex.coerceIn(0, currentSorts.lastIndex)
        selectedTagIndex = validRssSortIndex(selectedTagIndex) ?: return
        binding.topBar.tagsBar.setSelectedIndex(selectedTagIndex, smooth = false)
        val sort = currentSorts[selectedTagIndex]
        binding.recyclerView.gone()
        binding.rssWebContainer.gone()
        binding.rssFragmentContainer.visible()
        binding.pbRssLoading.gone()
        childFragmentManager.commit {
            replace(
                R.id.rss_fragment_container,
                RssArticlesFragment(sort.first, sort.second, null),
                "rss_articles_${source.sourceUrl}_${selectedTagIndex}"
            )
            runOnCommit {
                (childFragmentManager.findFragmentById(R.id.rss_fragment_container) as? RssArticlesFragment)
                    ?.setTopOverlaySpace(rssTopOverlaySpace, rssTopOverlayEnabled)
                binding.root.post {
                    updateModernRssTopBarOverlay()
                }
            }
        }
    }

    private fun canCommitRssChildFragment(): Boolean {
        return isAdded &&
                view != null &&
                !childFragmentManager.isStateSaved &&
                viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    // WebView 单源渲染（无 ruleArticles 源，对齐 Archive L563-623）
    @SuppressLint("SetJavaScriptEnabled")
    private fun renderWebSource(source: RssSource) {
        if (!source.canRenderInModernPage()) {
            renderEmptyState()
            return
        }
        webSourceVersion += 1
        val currentVersion = webSourceVersion
        binding.recyclerView.gone()
        binding.rssFragmentContainer.gone()
        binding.rssWebContainer.visible()
        val webView = rssWebView ?: WebView(requireContext()).also { created ->
            created.layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            created.overScrollMode = View.OVER_SCROLL_NEVER
            created.settings.javaScriptEnabled = true
            created.settings.domStorageEnabled = true
            created.settings.cacheMode = WebSettings.LOAD_DEFAULT
            created.settings.loadsImagesAutomatically = true
            created.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            created.webViewClient = WebViewClient()
            created.webChromeClient = WebChromeClient()
            binding.rssWebContainer.addView(created)
            rssWebView = created
        }
        applyModernRssWebViewTopSpace(webView)
        webView.settings.javaScriptEnabled = source.enableJs
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        webView.stopLoading()
        if (lastRenderedWebSourceUrl != source.sourceUrl) {
            webView.clearHistory()
            webView.loadUrl("about:blank")
        }
        viewModel.launchRssWithHtml(source, {
            if (currentVersion != webSourceVersion || selectedRssSource?.sourceUrl != source.sourceUrl) {
                return@launchRssWithHtml
            }
            binding.pbRssLoading.gone()
            lastRenderedWebSourceUrl = source.sourceUrl
            webView.loadUrl(source.sourceUrl)
        }) { html ->
            if (currentVersion != webSourceVersion || selectedRssSource?.sourceUrl != source.sourceUrl) {
                return@launchRssWithHtml
            }
            binding.pbRssLoading.gone()
            lastRenderedWebSourceUrl = source.sourceUrl
            webView.loadDataWithBaseURL(
                source.sourceUrl,
                html,
                "text/html",
                "utf-8",
                source.sourceUrl
            )
        }
    }

    // 刷新当前内容（对齐 Archive L625-645，本项目无 swipeRefreshLayout，下拉刷新由 refresh 按钮触发）
    private fun refreshCurrentRssContent(forceWebRefresh: Boolean = false) {
        if (!usingModernRss) {
            upRssFlowJob(composeSearchQuery)
            return
        }
        selectedRssSource?.let { source ->
            if (source.ruleArticles.isNullOrBlank()) {
                if (forceWebRefresh) {
                    lastRenderedWebSourceUrl = null
                }
                renderWebSource(source)
            } else {
                viewLifecycleOwner.lifecycleScope.launch {
                    presentSource(source)
                }
            }
        }
    }

    // 空状态（对齐 Archive L647-664）
    private fun renderEmptyState() {
        selectedRssSource = null
        currentSorts.clear()
        binding.topBar.setTitle(getString(R.string.rss))
        binding.topBar.setSearchHint(getString(R.string.rss_search_hint))
        binding.topBar.setSearchEntryVisible(false)
        renderRssSourceSelector()
        binding.topBar.loginButton.gone()
        binding.topBar.searchButton.gone()
        binding.topBar.refreshButton.gone()
        binding.topBar.showTags(false)
        binding.recyclerView.gone()
        binding.rssFragmentContainer.gone()
        binding.rssWebContainer.gone()
        binding.pbRssLoading.gone()
        binding.tvEmptyMsg.visible()
    }

    // 源选择弹窗（对齐 Archive L683-702）
    private fun showSourceSelector() {
        if (rssSources.isEmpty()) return
        SourceSelectDialog.show(
            context = requireContext(),
            title = getString(R.string.rss),
            items = rssSources,
            selectedKey = selectedRssSource?.sourceUrl,
            displayName = { it.getDisplayNameGroup() },
            searchTexts = {
                listOfNotNull(it.sourceName, it.sourceUrl, it.sourceGroup)
            },
            itemKey = { it.sourceUrl }
        ) {
            if (it.canRenderInModernPage()) {
                selectSource(it, reload = true)
            } else {
                openRssLegacy(it)
            }
        }
    }

    // WebView 顶部覆盖占位（对齐 Archive L704-712）
    private fun applyModernRssWebViewTopSpace(webView: WebView) {
        webView.clipToPadding = true
        webView.setPadding(
            webView.paddingLeft,
            rssTopOverlaySpace,
            webView.paddingRight,
            webView.paddingBottom
        )
    }

    // 分类索引合法性（跳过空 sortUrl 分类，对齐 Archive L714-724）
    private fun validRssSortIndex(index: Int): Int? {
        if (index !in currentSorts.indices) return null
        if (currentSorts[index].second.isNotBlank()) return index
        for (next in index + 1 until currentSorts.size) {
            if (currentSorts[next].second.isNotBlank()) return next
        }
        for (previous in index - 1 downTo 0) {
            if (currentSorts[previous].second.isNotBlank()) return previous
        }
        return null
    }

    // 源标签条（primaryBar）渲染（对齐 Archive L726-731）
    private fun renderRssSourceSelector() {
        binding.topBar.setPrimaryItems(
            rssSources.map { RoundedTagBarView.Item(it.sourceName) },
            rssSources.indexOfFirst { it.sourceUrl == selectedRssSource?.sourceUrl }
        )
    }

    // 源内搜索（本项目 RssSearchActivity 的 searchScope 为分组维度，对齐 Archive 的源内搜索意图）
    private fun openRssSearch() {
        val source = selectedRssSource ?: return
        if (source.searchUrl.isNullOrBlank()) return
        RssSearchActivity.start(requireContext(), null, source.sourceGroup)
    }

    private fun openRssLogin(rssSource: RssSource) {
        startActivity<SourceLoginActivity> {
            putExtra("type", "rssSource")
            putExtra("key", rssSource.sourceUrl)
        }
    }

    // 现代形态仅对非单源源内嵌渲染，单源走旧打开方式（对齐 Archive L782-784）
    private fun RssSource.canRenderInModernPage(): Boolean {
        return !singleUrl
    }

    // 顶栏对齐 Archive MainTopBarView（RSS 模式）：标题 + 全局搜索 + 星标 + 刷新 + 更多菜单
    // 内联搜索过滤保留在 compose_search_bar（按名称过滤 + 提交跳全局搜索）；
    // 本项目 RSS 为订阅源列表，需 更多菜单 容纳 分组配置/阅读记录/分组跳转/设置，故保留 moreButton
    private fun initComposeTopBar() {
        binding.topBar.applyStatusBarPadding(withInitialPadding = true)
        binding.topBar.setMode(MainTopBarView.Mode.RSS)
        binding.topBar.setTitle(getString(R.string.rss))
        binding.topBar.setSearchHint(getString(R.string.rss_search_key))
        // 星标：收藏订阅源
        binding.topBar.starButton.setOnClickListener {
            startActivity<RssFavoritesActivity>()
        }
        // 刷新：重新加载当前列表流
        binding.topBar.refreshButton.setOnClickListener {
            upRssFlowJob(composeSearchQuery)
        }
        // RSS 全局搜索：顶栏搜索按钮 → 全局搜索页
        binding.topBar.searchButton.setOnClickListener {
            RssSearchActivity.start(requireContext(), null)
        }
        // 登录为每源入口（列表长按菜单），不在订阅页顶栏显示；内联搜索承担正文搜索，隐藏内嵌搜索条
        binding.topBar.setSearchEntryVisible(false)
        binding.topBar.setActionsVisible(login = false)
        // 本项目 RSS 为订阅源列表，更多菜单容纳 分组配置/阅读记录/分组跳转/设置（Archive 的 RSS 为单源页故无此按钮）
        binding.topBar.moreButton.isVisible = true
        binding.topBar.moreButton.setOnClickListener {
            showRssMenu(it)
        }
        // 内联搜索过滤（原 SettingsSearchBar，保留订阅源按名称过滤 + 提交跳全局搜索能力）
        binding.composeSearchBar.setContent {
            LegadoTheme {
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

    // 更多菜单数据（文件夹配置 + 阅读记录 + 动态分组 + 设置；星标收藏已并入顶栏星标按钮）
    private fun showRssMenu(anchor: View) {
        val actions = buildList {
            add(ModernActionPopup.Action(getString(R.string.source_folder_config)) {
                showFolderConfig()
            })
            add(ModernActionPopup.Action(getString(R.string.history)) {
                showDialogFragment<ReadRecordDialog>()
            })
            groups.forEach { group ->
                add(ModernActionPopup.Action(group) {
                    currentGroup = group
                    composeSearchQuery = ""
                    upRssFlowJob()
                })
            }
            add(ModernActionPopup.Action(getString(R.string.setting)) {
                startActivity<RssSourceActivity>()
            })
        }
        rssMenuPopup = ModernActionPopup.show(anchor, actions, rssMenuPopup)
    }

    fun gotoTop() {
        // F-4/6.x: 现代形态定位到内嵌文章列表/WebView 顶部，经典形态定位到订阅源网格
        when (val target = currentRssScrollTarget()) {
            is WebView -> target.scrollTo(0, 0)
            is androidx.recyclerview.widget.RecyclerView -> target.scrollToPosition(0)
        }
    }

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        // 切换逻辑修复：仅首次进入经典形态添加 header，防 modern↔classic 反复切换重复堆积
        if (!classicHeaderReady) {
            classicHeaderReady = true
            adapter.addHeaderView {
                ItemRssBinding.inflate(layoutInflater, it, false).apply {
                    tvName.setText(R.string.rule_subscription)
                    ivIcon.setImageResource(R.drawable.image_legado)
                    root.setOnClickListener {
                        startActivity<RuleSubActivity>()
                    }
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
        // 切换逻辑修复：仅首次进入经典形态注册监听，防 modern↔classic 反复切换监听累积
        if (!classicTabListenerReady) {
            classicTabListenerReady = true
            tabLayout.addOnTabSelectedListener(tabSelectedListener)
        }
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

    // source-layout-deep-refactor 文件夹视图配置对话框（Compose 版，对齐书架布局弹框样式）
    private fun showFolderConfig() {
        val oldStyle = AppConfig.sourceGroupStyle
        showDialogFragment(
            SourceFolderConfigDialog.create(
                isBookSource = false,  // C-01 修复：订阅源用 rssSort
                onConfigChanged = {
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
            )
        )
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
        openRssLegacy(rssSource)
    }

    // 旧打开方式：单源 → getSingleUrl 直读/外链；多源 → RssSortActivity 分类列表 或 ReadRssActivity 直读
    private fun openRssLegacy(rssSource: RssSource) {
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
