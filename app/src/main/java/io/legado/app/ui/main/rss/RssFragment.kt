package io.legado.app.ui.main.rss

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.commit
import androidx.fragment.app.commitNow
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.SourceGroupCover
import io.legado.app.databinding.FragmentRssBinding
import io.legado.app.databinding.ItemRssBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.sortUrls
import io.legado.app.help.webView.WebViewPool
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.adapter.FolderItem
import io.legado.app.ui.adapter.SourceFolderAdapter
import io.legado.app.ui.adapter.SourceFolderComposeGrid
import io.legado.app.ui.adapter.SourceFolderConfigDialog
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
import io.legado.app.ui.widget.recycler.GridSpacingItemDecoration
import io.legado.app.utils.applyMainBottomBarPadding
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.cnCompare
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.gone
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.observeEvent
import io.legado.app.utils.openUrl
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.transaction
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import splitties.init.appCtx

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
    // 切换逻辑修复：源切换版本号（防 sortUrls 慢返回用过期 currentSorts 覆盖当前源内容）
    private var rssSourceVersion = 0L
    // S1: modern 顶栏布局监听引用（classic 模式需移除，防标题宽度被钳制）
    private var updateSourceNameWidthListener: View.OnLayoutChangeListener? = null
    private val adapter by lazy {
        RssAdapter(requireContext(), this, this, viewLifecycleOwner.lifecycle)
    }
    // folder-compose-refactor: Compose 文件夹目录数据（渲染层 100% Compose，View 版 folderAdapter 已清理）
    private val gridSpacingDecoration = GridSpacingItemDecoration()
    private var folderComposeItems by mutableStateOf(listOf<FolderItem>())
    private var folderComposeCovers by mutableStateOf(mapOf<String, String?>())
    // rss-classic-layout-align：margin/列数以 Compose State 持有，配置变更经 upFolderView 重写即触发重组
    // （原 setContent 闭包静态捕获 AppConfig 值，需"切走再切回"重建 ComposeView 才生效）
    private var folderComposeMargin by mutableStateOf(AppConfig.sourceMargin)
    private var folderComposeSpanCount by mutableStateOf(2)
    // 更多菜单弹窗句柄（ModernActionPopup，生命周期由弹窗自身管理）
    private var moreMenuPopup: ModernActionPopup.Handle? = null
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
    // D1: 标签模式 分组/类型胶囊（复用 top_bar.primaryBar，与书架/发现同套、受主题管理）
    private val groupTagItems = mutableListOf<RoundedTagBarView.Item>()
    // D1: 二级源标签项（tagsBar，点击过滤到该源；首项"全部"=null）
    private val rssTags = mutableListOf<RoundedTagBarView.Item>()
    // D1: 当前二级源标签选中（null=全部）
    private var selectedRssTag: String? = null
    private var groupsFlowJob: Job? = null
    private var rssFlowJob: Job? = null
    // 分组集合（Compose 菜单数据驱动，mutableStateOf 保证分组变化时菜单重组）
    private var groups by mutableStateOf(linkedSetOf<String>())
    // D1: 一级胶囊点击：按 index 映射回 类型(tag=Int)/分组(tag=String)，重设选中并刷新（对齐书架 primaryBar）
    private val tagSelectedListener = { index: Int ->
        run {
            val item = groupTagItems.getOrNull(index) ?: return@run
            @Suppress("UNCHECKED_CAST")
            val tag = item.tag
            if (AppConfig.sourceGroupStyle == 1) {
                currentType = (tag as? Int) ?: -1
                currentGroup = null
            } else {
                currentGroup = tag as? String
                currentType = -1
            }
            selectedRssTag = null
            upTabLayout()
            upRssFlowJob()
        }
    }
    // D1: 二级源标签点击：定位过滤到该源（首项"全部"=null）
    private val tagsSelectedListener = { index: Int ->
        run {
            val item = rssTags.getOrNull(index) ?: return@run
            @Suppress("UNCHECKED_CAST")
            selectedRssTag = item.tag as? String
            upRssFlowJob()
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        applyRssMode()
        // S2: 订阅页模式切换即时生效（对齐 MainActivity NOTIFY_MAIN 消费；onResume 保留为兜底）
        observeEvent<Boolean>(EventBus.NOTIFY_MAIN) {
            if (usingModernRss != AppConfig.modernRssPage) {
                applyRssMode()
            }
        }
        // 跨页同步（rss-classic-layout-align S3）：书架布局弹框改 showBookname 等结构偏好后，
        // 经典订阅文件夹/标签渲染同步刷新（AppConfig 直读，重组即生效；幂等无副作用）
        observeEvent<String>(EventBus.BOOKSHELF_STRUCTURE_CHANGED) {
            if (!usingModernRss) {
                applyView()
            }
        }
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
                            upRssFlowJob()
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
        syncRssModeIfChanged()
        if (pendingRenderCurrentSort && usingModernRss) {
            binding.root.post {
                if (pendingRenderCurrentSort) {
                    renderCurrentSort()
                }
            }
        }
    }

    /**
     * 幂等同步订阅双形态（config-needs-restart-fix 真机兜底）：
     * 供 onResume / MainActivity 页面切换回调多锚点调用
     *
     * 终极兜底（真机多轮修复仍复现）：检测到配置与当前形态不一致时，直接 Activity 重建收敛——
     * 等价"重启生效"的进程内复刻（重建后 onFragmentCreated 必读新配置），不依赖任何
     * 事件/生命周期/渲染竞态链路；订阅模式切换为低频操作，闪屏可接受。
     * 重建后 usingModernRss 以新配置初始化，needSwitch=false 不会循环重建。
     * 模式一致时的残留自愈保留（异模式容器可见即重建当前模式）。
     */
    fun syncRssModeIfChanged() {
        if (usingModernRss != AppConfig.modernRssPage) {
            activity?.recreate()
            return
        }
        if (view == null) return
        if (!usingModernRss) {
            // 经典态自愈：modern 文章/WebView 容器仍可见 = 残留，重建经典
            if (binding.rssFragmentContainer.isVisible || binding.rssWebContainer.isVisible) {
                applyClassicRssMode()
            }
        } else {
            // 新版态自愈：经典网格/文件夹目录仍可见 = 残留，重建新版
            if (binding.recyclerView.isVisible || binding.folderComposeView.isVisible) {
                applyModernRssMode()
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
        // S3/S4/S6: 模式切换统一重置运行时状态（防跨模式残留：分组/类型/源标签/overlay/sortHostViewModel）
        resetRssModeState()
        if (usingModernRss) {
            applyModernRssMode()
        } else {
            applyClassicRssMode()
        }
        activity?.invalidateOptionsMenu()
    }

    // S3/S4/S5/S6: 切换时重置经典/现代运行时状态（初次进入为默认值，无副作用；onResume 同模式不触发）
    private fun resetRssModeState() {
        // P0 根因修复：无条件取消跨模式 collector。classic 路径原仅在 upRssFlowJob 时机取消，
        // 文件夹视图路径完全不取消 → modern observeRssSources collector 存活，
        // 返回 RESUMED 时 flowWithLifecycleAndDatabaseChange 重发，renderRssSourceSelector/selectSource
        // 把 modern 源标签重新覆盖经典顶栏（"新版切经典顶栏残留"根因）
        groupsFlowJob?.cancel()
        groupsFlowJob = null
        rssFlowJob?.cancel()
        rssFlowJob = null
        currentGroup = null
        currentType = -1
        selectedRssTag = null
        rssTopOverlaySpace = 0
        rssTopOverlayEnabled = false
        pendingRenderCurrentSort = false
        // S6: sortHostViewModel 跨模式隔离（切回 modern 前不残留旧源）
        if (sortHostViewModel.url != null) {
            sortHostViewModel.url = null
            sortHostViewModel.sortUrl = null
            sortHostViewModel.rssSource = null
            sortHostViewModel.sourceName = null
            sortHostViewModel.searchKey = null
        }
        // S5: per-mode 释放（adapter 复用，getHeaderCount 幂等兜底防重复挂载）
        classicHeaderReady = false
    }

    // 经典形态（对齐本项目增强：文件夹/标签/排序/内联搜索 + 更多菜单）
    private fun applyClassicRssMode() {
        // S1: 移除 modern 顶栏 layout 监听（classic 下标题宽度不再被钳制 96~190dp）
        updateSourceNameWidthListener?.let { binding.topBar.removeOnLayoutChangeListener(it) }
        updateSourceNameWidthListener = null
        destroyModernRssChildren()
        // video-sniff-403-and-rss-classic-fix R2: 回经典模式必须显式恢复经典布局基线。
        // 根因：updateModernRssTopBarOverlay 在 modern 模式向 recyclerView 写入 topPadding=topBar.height
        // （含展开标签条可达数百 px），切回经典时无重置路径 → 头部标签与列表间出现大块空白，
        // 杀进程重启后 View 树重建 padding 归零才恢复（真机实锤 2026-08-31）。
        // XML 基线：recycler_view/folder_compose_view 均无 padding、clipToPadding 默认 false。
        binding.recyclerView.clipToPadding = false
        binding.recyclerView.setPadding(0, 0, 0, 0)
        // 防御性重置：folderComposeView 当前未被 modern 写 padding，此处归零防同类污染链路
        binding.folderComposeView.setPadding(0, 0, 0, 0)
        binding.folderComposeView.clipToPadding = false
        // 双保险隐藏 modern 容器（rss_fragment_container/rss_web_container 为全屏 z 序最高，
        // applyModernRssMode 置 visible 后 classic 不隐藏会盖住经典列表——真机实锤 2026-08-28）
        binding.rssFragmentContainer.isGone = true
        binding.rssWebContainer.isGone = true
        binding.pbRssLoading.gone()
        binding.recyclerView.isVisible = true
        binding.tvEmptyMsg.isGone = true
        initComposeTopBar()
        initTabLayout()  // D1: 初始化分组胶囊标签
        initFolderComposeView()  // folder-compose-refactor: 初始化 Compose 文件夹目录
        // F-P1-8 初始化运行时状态：跟随用户偏好
        isShowingFolder = isFolderViewMode
        initRecyclerView()
        initGroupData()
        if (isShowingFolder) {
            upFolderView()
        } else {
            upRssFlowJob()
        }
        // 二次收敛（rss-classic-layout-align 守卫补强）：延迟校验 modern 容器/胶囊是否被
        // 日志静默区竞态重新渲染；是则强制再次清空。真机实锤"经典顶栏+modern 内容"混合态。
        binding.root.postDelayed({
            if (view == null || usingModernRss) return@postDelayed
            if (binding.rssFragmentContainer.isVisible || binding.rssWebContainer.isVisible) {
                binding.rssFragmentContainer.isGone = true
                binding.rssWebContainer.isGone = true
                destroyModernRssChildren()
                binding.topBar.setPrimaryItems(emptyList(), 0)
                binding.topBar.showTags(false)
            }
        }, 500)
    }

    // 现代形态（对齐 Archive：源标签 + 分类标签 + 内嵌文章预览 / WebView 单源渲染）
    private fun applyModernRssMode() {
        groupsFlowJob?.cancel()
        rssFlowJob?.cancel()
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
        // commitNow 同步移除（commit 异步存在窗口期，文章列表会残留覆盖经典列表）
        childFragmentManager.findFragmentById(R.id.rss_fragment_container)?.let {
            childFragmentManager.commitNow { remove(it) }
        }
    }

    // ===== 现代形态（新版订阅，对齐 Archive RssFragment L241-297）=====
    // 顶栏：源选择（titleSelect/primaryBar）+ 分类标签（tagsBar）+ 搜索/登录/星标/刷新
    // 内容：内嵌 RssArticlesFragment（ruleArticles 源）或 WebView 单源渲染（无 ruleArticles 源）
    @SuppressLint("SetJavaScriptEnabled")
    private fun initModernRssView() {
        binding.topBar.setMode(MainTopBarView.Mode.RSS)
        // topbar-search-entry-align：modern 初始化路径必须关闭 searchEntry 胶囊（构造默认 open），
        // 否则 selectSource 的 setSearchHint(源名) 会让搜索条显示源名，点击源名被误触为源内搜索跳转
        binding.topBar.setSearchEntryVisible(false)
        binding.topBar.titleText.applyUiTitleTypeface(requireContext())
        binding.topBar.applyStatusBarPadding(withInitialPadding = true)
        binding.topBar.doOnLayout {
            updateModernRssTopBarOverlay()
        }
        updateModernRssTopBarOverlay()
        // S1: 保存引用（classic 模式需移除）
        updateSourceNameWidthListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateRssSourceNameWidth()
        }
        binding.topBar.addOnLayoutChangeListener(updateSourceNameWidthListener)
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
        // S1: 经典形态不执行标题宽度钳制（防止切回 classic 后标题被截断）
        if (!usingModernRss) return
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
        // 守卫（rss-classic-layout-align）：classic 态下任何 modern 渲染调用一律拒绝
        if (!usingModernRss) return
        val changed = selectedRssSource?.sourceUrl != source.sourceUrl
        selectedRssSource = source
        AppConfig.modernRssSourceUrl = source.sourceUrl
        val hasSearch = !source.searchUrl.isNullOrBlank()
        // topbar-search-entry-align：不再重开 searchEntry 胶囊（初始化/空状态已关闭），
        // 形态统一为纯搜索按钮（对齐发现/书架/我的）；titleSelect 源选择入口随胶囊关闭自动回归
        binding.topBar.setTitle(
            if (binding.topBar.isRegularStyle() && hasSearch) getString(R.string.rss) else source.sourceName
        )
        binding.topBar.setSearchHint(source.sourceName)
        binding.topBar.loginButton.isVisible = !source.loginUrl.isNullOrBlank()
        binding.topBar.searchButton.isVisible = hasSearch
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
        // 守卫：classic 态拒绝 modern 渲染
        if (!usingModernRss) return
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
        // 守卫：classic 态拒绝 modern 渲染
        if (!usingModernRss) return
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
        // 守卫：classic 态拒绝 modern 渲染
        if (!usingModernRss) return
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
            upRssFlowJob()
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
        // 守卫：classic 态拒绝 modern 源名胶囊提交
        if (!usingModernRss) return
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

    // fix-rss-search-scope: 按当前浏览上下文计算搜索范围（分组/类型/未分组），全部时 null 保持全局
    // 判定以 currentGroup 优先（与 upRssFlowJob 列表查询分支一致，兜底菜单分组跳转 L955-960 的并存状态）
    private fun buildSearchScope(): String? {
        return kotlin.runCatching {
            when {
                currentGroup == getString(R.string.no_group) -> "@no_group"
                !currentGroup.isNullOrBlank() -> currentGroup
                currentType >= 0 -> "@type:$currentType"
                else -> null
            }
        }.getOrNull()
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

    // 顶栏对齐 Archive MainTopBarView（RSS 模式）：标题 + 全局搜索 + 更多菜单
    // 搜索按钮直接弹 RssSearchActivity 全屏搜索页（对齐发现页 SearchActivity 交互，无就地过滤 overlay）
    // ui-batch-fix-0905 头部收口：搜索留在外面，其余操作（阅读记录/星标/刷新/订阅源管理/布局设置/分组管理）
    // 全部收口到 moreButton 三点菜单；分组信息列举删除（不再展示所有分组跳转）
    private fun initComposeTopBar() {
        binding.topBar.applyStatusBarPadding(withInitialPadding = true)
        binding.topBar.setMode(MainTopBarView.Mode.RSS)
        binding.topBar.setTitle(getString(R.string.rss))
        binding.topBar.setSearchHint(getString(R.string.rss_search_key))
        // 顶栏搜索按钮：弹 RssSearchActivity 全屏搜索页（key=null 空关键词进入）
        // fix-rss-search-scope: 按当前浏览上下文（分组/类型/未分组）限定搜索范围，根目录保持全局
        binding.topBar.searchButton.setOnClickListener {
            RssSearchActivity.start(requireContext(), null, buildSearchScope())
        }
        // 登录为每源入口（列表长按菜单），不在订阅页顶栏显示；搜索已改为弹全屏搜索页，隐藏内置搜索条
        binding.topBar.setSearchEntryVisible(false)
        // ui-batch-fix-0905：星标/刷新等一级按钮收口到三点菜单（setMode(Mode.RSS) 默认隐藏 moreButton，
        // 此处经典路径手动启用；modern 形态走 initModernRssView → setMode 自动隐藏，互不影响）
        binding.topBar.setActionsVisible(star = false, refresh = false, login = false)
        binding.topBar.moreButton.isVisible = true
        binding.topBar.moreButton.setOnClickListener { anchor ->
            val actions = buildList {
                add(ModernActionPopup.Action(getString(R.string.history)) {
                    showDialogFragment<ReadRecordDialog>()
                })
                add(ModernActionPopup.Action(getString(R.string.favorite)) {
                    startActivity<RssFavoritesActivity>()
                })
                add(ModernActionPopup.Action(getString(R.string.refresh)) {
                    upRssFlowJob()
                })
                add(ModernActionPopup.Action(getString(R.string.setting)) {
                    startActivity<RssSourceActivity>()
                })
                add(ModernActionPopup.Action(getString(R.string.source_folder_config)) {
                    showFolderConfig()
                })
                add(ModernActionPopup.Action(getString(R.string.group_manage)) {
                    showDialogFragment<io.legado.app.ui.rss.source.manage.GroupManageDialog>()
                })
            }
            moreMenuPopup = ModernActionPopup.show(anchor, actions, moreMenuPopup)
        }
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
        // S5 (per-mode)：仅经典形态需要 header；classicHeaderReady 随模式释放 + getHeaderCount() 幂等兜底，
        // 防 modern↔classic 反复切换重复堆积（adapter 复用，重复 addHeaderView 不去重）
        if (!classicHeaderReady && adapter.getHeaderCount() == 0) {
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
        // 间距变更后必须触发 decoration 重算，否则视觉恒为初次值（"间距滑条不生效"根因）
        binding.recyclerView.invalidateItemDecorations()
        val spanCount = effectiveSpanCount()
        binding.recyclerView.layoutManager = GridLayoutManager(context, spanCount)
        binding.recyclerView.adapter = adapter
    }

    /** 分组配置弹框拖动边距滑条的实时预览（对齐书架 previewBookshelfMargin） */
    private fun previewRssMargin(margin: Int) {
        val normalized = margin.coerceIn(0, 60)
        if (AppConfig.sourceMargin == normalized) return
        AppConfig.sourceMargin = normalized
        gridSpacingDecoration.spacing = SourceFolderAdapter.spacingPx(requireContext(), normalized)
        binding.recyclerView.invalidateItemDecorations()
        if (isShowingFolder) {
            upFolderView()  // 文件夹视图 margin 由 Compose 重组读取
        }
    }

    // bugfix ④: 订阅源网格列数读取 sourceLayout（Grid2-6 显式生效）；0/1（列表/紧凑，订阅源无此语义）回退屏幕自适应
    private fun effectiveSpanCount(): Int {
        val layout = AppConfig.sourceLayout
        return if (layout in 2..6) layout
        else SourceFolderAdapter.calculateSpanCount(requireContext(), AppConfig.sourceMargin)
    }

    // folder-compose-refactor: 初始化 Compose 文件夹目录（对齐书架文件夹 FolderGroupGridContent 样式；
    // margin 单源驱动——sourceMargin 变更经 onConfigChanged→applyView→upFolderView 重组重读）
    private fun initFolderComposeView() {
        binding.folderComposeView.setContent {
            LegadoTheme {
                SourceFolderComposeGrid(
                    items = folderComposeItems,
                    covers = folderComposeCovers,
                    spanCount = folderComposeSpanCount,
                    margin = folderComposeMargin,
                    onFolderClick = { onFolderClick(it) },
                    onFolderLongClick = { onFolderSelectImage(it) },
                )
            }
        }
    }

    // D1: 初始化分组胶囊标签（复用 top_bar.primaryBar/tagsBar，走主题管理，无需手动配色）。
    // 每次进入经典形态重绑监听：classic/modern 共用顶栏 bar，需用当前模式监听覆盖，防 modern↔classic 切换后监听仍指向旧模式
    private fun initTabLayout() {
        binding.topBar.primaryBar.setOnTagClickListener(tagSelectedListener)
        binding.topBar.tagsBar.setOnTagClickListener(tagsSelectedListener)
    }

    // D1: 填充标签。D2: 按类型时显示类型标签，按分组时显示分组标签（tag 存类型/分组标识）
    private fun upTabLayout() {
        groupTagItems.clear()
        if (AppConfig.sourceGroupStyle == 1) {
            // D2: 按类型分组，标签显示类型名，tag 存类型索引
            groupTagItems.add(RoundedTagBarView.Item(getString(R.string.all_groups), tag = -1))
            groupTagItems.add(RoundedTagBarView.Item(getString(R.string.type_web), tag = 0))
            groupTagItems.add(RoundedTagBarView.Item(getString(R.string.type_image), tag = 1))
            groupTagItems.add(RoundedTagBarView.Item(getString(R.string.type_video), tag = 2))
            val selectedIndex = (currentType + 1).coerceIn(0, 3)
            binding.topBar.setPrimaryItems(groupTagItems, selectedIndex)
        } else {
            // 按分组
            groupTagItems.add(RoundedTagBarView.Item(getString(R.string.all_groups), tag = null))
            val noGroup = getString(R.string.no_group)
            groupTagItems.add(RoundedTagBarView.Item(noGroup, tag = noGroup))
            groups.forEach { group ->
                groupTagItems.add(RoundedTagBarView.Item(group, tag = group))
            }
            val selectedIndex = when (currentGroup) {
                null -> 0
                noGroup -> 1
                else -> 2 + groups.indexOf(currentGroup).coerceAtLeast(0)
            }
            binding.topBar.setPrimaryItems(groupTagItems, selectedIndex.coerceAtMost(groupTagItems.lastIndex))
        }
    }

    // D1: 统一应用视图（根据 isShowingFolder / isTagMode 控制显示）
    private fun applyView() {
        if (isShowingFolder) {
            // 分组模式：显示 Compose 文件夹目录（对齐书架文件夹样式），隐藏列表与顶栏标签
            binding.topBar.showTags(false)
            binding.topBar.setPrimaryItems(emptyList(), 0)
            binding.recyclerView.isGone = true
            binding.folderComposeView.isVisible = true
            upFolderView()
        } else {
            // 列表视图（标签模式 或 列表平铺 或 文件夹点击后）
            binding.folderComposeView.isGone = true
            binding.recyclerView.isVisible = true
            if (isTagMode) {
                upTabLayout()
            } else {
                binding.topBar.showTags(false)
                binding.topBar.setPrimaryItems(emptyList(), 0)
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
                onPreviewMarginChange = ::previewRssMargin,  // 滑条实时预览（rss-classic-layout-align）
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
                    }
                    applyView()
                    if (isShowingFolder) {
                        upFolderView()
                    } else {
                        upRssFlowJob()
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
        // folder-compose-refactor: 同步更新 Compose 文件夹目录数据（View 版 folderAdapter 已清理）
        // rss-classic-layout-align：margin/列数同为 Compose State，此处重写才触发重组
        // （"文件夹间距不立即生效"根因：只写 AppConfig 不写 State，Compose 读到的仍是旧值）
        folderComposeMargin = AppConfig.sourceMargin
        folderComposeSpanCount = effectiveSpanCount()
        folderComposeItems = folderList
        // source-folder-cover: 批量加载分组封面缓存
        viewLifecycleOwner.lifecycleScope.launch {
            kotlin.runCatching {
                appDb.sourceGroupCoverDao.getCoversByKind(SourceGroupCover.KIND_RSS)
            }.getOrDefault(emptyList())
                .associate { it.groupName to it.cover }
                .let { covers ->
                    folderComposeCovers = covers
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
            }.flowOn(IO).collect { list ->
                val sorted = sortSources(list)
                // D1: 二级源标签过滤到单个源（选中标签）/ 全部
                adapter.setItems(
                    if (selectedRssTag.isNullOrBlank()) sorted
                    else sorted.filter { it.sourceName == selectedRssTag }
                )
                renderRssSecondaryTags(sorted)
            }
        }
    }

    // D1: 刷新二级源标签（当前分组的源快捷标签，对齐书架 tagsBar）；showTags(true) 使右侧向下箭头出现，点击箭头展开二级源标签
    // rss-folder-subtag-fix: 二级源标签仅在标签样式(isTagMode)下展示；文件夹样式点进文件夹后的列表视图不显示标签栏与右侧向下箭头
    private fun renderRssSecondaryTags(sources: List<RssSource>) {
        if (!isTagMode) {
            binding.topBar.showTags(false)
            return
        }
        rssTags.clear()
        if (sources.isEmpty()) {
            binding.topBar.showTags(false)
            return
        }
        rssTags.add(RoundedTagBarView.Item(getString(R.string.all_groups), tag = null))
        sources.forEach { src ->
            rssTags.add(RoundedTagBarView.Item(src.sourceName, tag = src.sourceName))
        }
        val selectedIdx = if (selectedRssTag == null) 0
        else sources.indexOfFirst { it.sourceName == selectedRssTag }
            .let { if (it < 0) 0 else it + 1 }
        binding.topBar.showTags(true)
        binding.topBar.tagsBar.submitItems(rssTags, selectedIdx.coerceAtMost(rssTags.lastIndex))
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
    // 注意：不持久化本次切换（保持用户偏好的视图模式），仅修改 isShowingFolder（运行时状态）
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
        upRssFlowJob()  // 直接触发查询
    }

    override fun onFolderSelectImage(folder: FolderItem) {
        // rss-folder-cover-dialog-align：长按直接弹标准封面编辑弹框（对齐书架 GroupEditDialog 交互），
        // 预览/选图（http 直存）/恢复默认/编辑态确定落库均在弹框内完成
        val dialog = RssFolderCoverDialog(folder)
        dialog.onCoverApplied = { key, path ->
            folderComposeCovers = folderComposeCovers + (key to path)
        }
        showDialogFragment(dialog)
    }

    override fun onFolderRestoreCover(folder: FolderItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            kotlin.runCatching {
                appDb.sourceGroupCoverDao.delete(
                    SourceGroupCover.KIND_RSS,
                    folder.groupKey
                )
                folderComposeCovers = folderComposeCovers + (folder.groupKey to null)
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
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.sure_del) + "\n" + rssSource.sourceName,
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = {
                viewModel.del(rssSource)
            }
        )
    }

    override fun disable(rssSource: RssSource) {
        viewModel.disable(rssSource)
    }
}
