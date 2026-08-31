package io.legado.app.ui.video

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.textclassifier.TextClassifier
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.viewpager2.widget.ViewPager2
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.shuyu.gsyvideoplayer.listener.GSYSampleCallBack
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.PlayHistoryStore
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssEpisode
import io.legado.app.data.entities.RssRoute
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.ActivityVideoPlayerBinding
import io.legado.app.help.GlideImageGetter
import io.legado.app.help.TextViewTagHandler
import io.legado.app.help.WebCacheManager
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.exoplayer.FirstFramePreloader
import io.legado.app.help.gsyVideo.VideoPlayer
import io.legado.app.help.player.ErrorMapper
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.getInjectionString
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.help.webView.WebViewPool
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.model.VideoPlay
import io.legado.app.service.VideoPlayService
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.model.SourceCallBack
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.rss.favorites.RssFavoritesDialog
import io.legado.app.ui.rss.search.ChangeRssArticleSourceDialog
import io.legado.app.ui.rss.search.RssSearchSourceHolder
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.video.config.SettingsDialog
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.ui.widget.text.ScrollTextView
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.invisible
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setHtml
import io.legado.app.utils.setMarkdown
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoPlayerActivity : VMBaseActivity<ActivityVideoPlayerBinding, VideoPlayerViewModel>(),
    SettingsDialog.CallBack, RssFavoritesDialog.Callback, VideoSettingsPanel.SettingsPanelCallback {

    companion object {
        const val EXTRA_PREPARE_BOOK_INFO = "prepareBookInfo"
    }

    override val binding by viewBinding(ActivityVideoPlayerBinding::inflate)
    override val viewModel by viewModels<VideoPlayerViewModel>()

    // 主题架构 v2：沉浸播放页不随主题事件重建（避免打断播放），Compose 侧经 ThemeSync 刷新
    override val recreateOnThemeChange: Boolean
        get() = false
    // P0-1: playerView 从 legacyContainer 获取（legacyContainer 已隐藏但 XML 保留避免编译错误）
    // ViewPager2 模式下使用 currentFragment?.playerView，此字段仅供 Legacy 代码路径引用
    private val playerView: VideoPlayer by lazy { binding.playerView }

    // Compose 顶栏（L-D9 S5 改造）状态
    private var composeTitle by mutableStateOf("")
    private var menuExpanded by mutableStateOf(false)
    private var starChecked by mutableStateOf(false)
    private var starVisible by mutableStateOf(false)
    private var showCustomBtn by mutableStateOf(false)
    private var showRefresh by mutableStateOf(false)
    private var showLogin by mutableStateOf(false)
    private var showChangeSource by mutableStateOf(false)

    // R3 抖音风格：ViewPager2 相关
    private var useViewPagerMode = true  // P0-1: 统一 ViewPager2 模式，移除 legacyContainer
    private var videoPagerAdapter: VideoPagerAdapter? = null
    private var currentFragment: VideoFragment? = null
    internal var settingsPanel: VideoSettingsPanel? = null  // 阶段5：当前打开的设置面板引用
    // P0-1.4: 视频播放错误对话框引用（防止重复弹窗）
    private var errorDialog: AlertDialog? = null
    private var initIntroView = false
    private val introTextView by lazy {
        initIntroView = true
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.view_book_intro, binding.tvIntroContainer, false) as ScrollTextView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            view.revealOnFocusHint = false
        }
        view
    }
    private var pooledWebView: PooledWebView? = null
    private val imgAvailableWidth by lazy {
        val textView = introTextView
        textView.width - textView.paddingLeft - textView.paddingRight - 8.dpToPx()
    }
    private var initGetter = false
    private val glideImageGetter by lazy {
        initGetter = true
        GlideImageGetter(
            this,
            introTextView,
            lifecycle,
            imgAvailableWidth,
            VideoPlay.source?.getKey()
        )
    }

    private val textViewTagHandler by lazy {
        TextViewTagHandler(object : TextViewTagHandler.OnButtonClickListener {
            override fun onButtonClick(name: String, click: String) {
                viewModel.onButtonClick(this@VideoPlayerActivity, "info button $name" , click)
            }
        })
    }
    private var isNew = true
    internal var isFullScreen = false
    private var orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    /**
     * singleTask 复用：ViewPager2 页面切换回调引用（onNewIntent 重置时需 unregister，防止重复注册）
     */
    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null

    /** initView 幂等守卫：onNewIntent 重初始化时避免重复注册 ViewModel 观察者 */
    private var isViewInitialized = false

    /**
     * T2.8: initSource 协程 Job 引用（onPause 时取消，解决 Bug-25：onPause 后 initSource 仍运行导致资源泄漏）
     */
    private var initSourceJob: Job? = null

    /**
     * AD-04: 播放历史定时保存 Job（每10s保存一次，onPause取消）
     */
    private var historySaveJob: Job? = null

    /**
     * AD-04: 获取当前文章URL（用于PlayHistory复合主键）
     * 订阅源模式返回当前文章link，单URL模式返回空字符串
     */
    private fun getCurrentArticleUrl(): String {
        return VideoPlay.rssArticles?.getOrNull(VideoPlay.rssArticleIndex)?.link ?: ""
    }

    /**
     * AD-04: 保存当前播放进度到PlayHistoryStore
     * 失败不影响主播放链路（PlayHistoryStore内部runCatching包裹）
     */
    private fun savePlayHistory() {
        // 4.8b（Z9）：键改用 historyKeyUrl（嗅探前原始 URL，源侧 token 轮换后仍可重嗅）；
        // rssSourceId 填充真实订阅源 ID（原恒空串）
        val videoUrl = VideoPlay.historyKeyUrl ?: return
        if (videoUrl.isBlank()) return
        val position = VideoPlay.videoManager.currentPosition
        val duration = VideoPlay.videoManager.duration
        if (position <= 0) return
        PlayHistoryStore.save(
            articleUrl = getCurrentArticleUrl(),
            videoUrl = videoUrl,
            position = position,
            duration = duration,
            rssSourceId = (VideoPlay.source as? RssSource)?.sourceUrl ?: ""
        )
    }

    /**
     * AD-04: 恢复播放进度（initSource成功后调用）
     * 异步加载PlayHistory，若position>10s则延迟2s后seekTo+Toast提示
     * 失败不影响主播放链路
     */
    private fun restorePlayHistory() {
        // 4.8b（Z9）：恢复键与保存键一致（嗅探前原始 URL）
        val videoUrl = VideoPlay.historyKeyUrl ?: return
        if (videoUrl.isBlank()) return
        val articleUrl = getCurrentArticleUrl()
        lifecycleScope.launch {
            val history = PlayHistoryStore.load(articleUrl, videoUrl)
            if (history != null && history.position > 10_000) {
                // 延迟2s给ExoPlayer prepare时间
                delay(2_000)
                withContext(Main) {
                    try {
                        val player = playerView.getCurrentPlayer()
                        player.seekTo(history.position)
                        val minutes = history.position / 60_000
                        val seconds = (history.position % 60_000) / 1_000
                        toastOnUi(String.format(getString(R.string.player_history_resume), minutes, seconds))
                        AppLog.put("PlayHistoryStore: resume to ${history.position}ms")
                    } catch (e: Exception) {
                        AppLog.put("PlayHistoryStore: resume failed, error=${e.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    /**
     * T1.13 方案B: VideoPlay 状态快照（解决 Bug-14 + Bug-24 + Bug-6：8 实例快速切换状态串扰）
     *
     * 思路：onActivityCreated 时保存 VideoPlay 单例的关键状态快照到 Activity 字段，
     * 后续此 Activity 内部优先使用快照字段，避免被其他 Activity 实例修改 VideoPlay 单例后状态串扰。
     *
     * 注：本方案为"状态快照"（方案B），保留 VideoPlay object 单例不变，风险最低。
     * 后续如需彻底解决，可升级为方案A（改为 class，每个 Activity 持有独立实例）。
     */
    private var snapshotVideoUrl: String? = null
    private var snapshotVideoTitle: String? = null
    private var snapshotSingleUrl: Boolean = false
    private var snapshotInBookshelf: Boolean = true
    private val bookSourceEditResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upSource {
                    showCustomBtn = (VideoPlay.source as? BookSource)?.customButton == true
                }
            }
        }
    private val rssSourceEditResult =
        registerForActivityResult(StartActivityContract(RssSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upSource()
            }
        }
    private val tocActivityResult = registerForActivityResult(TocActivityResult()) {
        it?.let {
            if (it[2] as Boolean) {
                VideoPlay.chapterInVolumeIndex = it[0] as Int
                val durChapterPos = it[1] as Int
                VideoPlay.durVolumeIndex = it[3] as Int
                VideoPlay.chapterInVolumeIndex = it[4] as Int
                VideoPlay.upEpisodes()
                VideoPlay.saveRead(durChapterPos)
                if (VideoPlay.episodes.isNullOrEmpty()) {
                    binding.chapters.visibility = View.GONE
                } else {
                    binding.chapters.visibility = View.VISIBLE
                    val adapter = binding.chapters.adapter as? ChapterAdapter
                    adapter?.updateData(VideoPlay.episodes)
                }
                upView()
                VideoPlay.startPlay(playerView)
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initFromIntent(intent)
        initComposeTopBar()
        onBackPressedDispatcher.addCallback(this) {
            if (isFullScreen) {
                toggleFullScreen()
                return@addCallback
            }
            finish()
        }
    }

    /**
     * singleTask 复用：旧播放会话 Teardown + 按新 Intent 重建会话
     *
     * 根因（用户2026-08-26反馈）：VideoPlayerActivity 为 singleTask 启动模式，
     * 已存在实例时新 Intent 走 onNewIntent，若不复用处理，新播放请求被静默忽略，
     * VideoPlay 单例残留上一次播放状态（如下载视频的 videoUrl/singleUrl），
     * 导致"下载管理播放完下载视频后，再从订阅源在线播放"仍播放旧视频，
     * 破坏内置播放器的订阅源嗅探播放功能。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newIntent = intent
        val isNewPlayRequest = intent.getBooleanExtra("isNew", true)
        if (!isNewPlayRequest) {
            // 悬浮窗/PiP 恢复会话（VideoPlayService 传 isNew=false）：继续原视频，禁止重置
            AppLog.put("VideoPlayerActivity onNewIntent: isNew=false 悬浮窗恢复, skip reset")
            return
        }
        AppLog.put("VideoPlayerActivity onNewIntent: singleTask 收到新播放意图, reset old session")
        // 1. 取消进行中的 initSource 协程
        if (initSourceJob?.isActive == true) {
            initSourceJob?.cancel()
            AppLog.put("VideoPlayerActivity onNewIntent: old initSourceJob cancelled")
        }
        // 2. 释放旧 Fragment 播放器 + 清空 ViewPager2（防止旧 Fragment 播放器/监听残留）
        currentFragment?.deactivatePlayer()
        currentFragment?.releasePlayer()
        currentFragment = null
        pageChangeCallback?.let { binding.viewPager.unregisterOnPageChangeCallback(it) }
        pageChangeCallback = null
        binding.viewPager.adapter = null
        videoPagerAdapter = null
        // 显式 FragmentTransaction API（不依赖 fragment-ktx 的顶层扩展）
        supportFragmentManager.beginTransaction().apply {
            supportFragmentManager.fragments.filterIsInstance<VideoFragment>()
                .forEach { remove(it) }
            commitAllowingStateLoss()
        }
        supportFragmentManager.executePendingTransactions()
        // 旧会话若处于全屏态，复位为常规态（新视频从常态开始播放）
        if (isFullScreen) {
            isFullScreen = false
            binding.composeTopBar.visible()
            requestedOrientation = orientation
        }
        // 3. 重置 VideoPlay 单会话状态（订阅源文章列表上下文由 ReadRss 在 startActivity 前写入）
        // 仅当新意图的 record 命中当前 VideoPlay.rssArticles 列表时才保留文章场景上下文；
        // 否则（如历史记录单篇播放：同传 sourceKey+record 却不带文章列表）清空，
        // 防止上一会话残留列表导致 ViewPager2 文章模式数据错配
        val rssRecord = newIntent.getStringExtra("record")
        val rssList = VideoPlay.rssArticles
        val isRssArticleIntent = !rssList.isNullOrEmpty() && !rssRecord.isNullOrBlank() &&
            rssList.any { it.link == rssRecord }
        VideoPlay.resetForNewIntent(preserveRssArticlesContext = isRssArticleIntent)
        // 4. 重新走初始化流程（与首次启动一致）
        initFromIntent(newIntent)
        initComposeTopBar()
    }

    /**
     * 按 Intent 初始化播放会话（onActivityCreated 与 onNewIntent 共用）
     */
    private fun initFromIntent(intent: Intent) {
        isNew = intent.getBooleanExtra("isNew", true)
        if (isNew) {
            intent.getStringExtra("videoUrl")?.let {
                VideoPlay.videoUrl = it
                VideoPlay.singleUrl = true
            } ?: run {
                // 修复（用户2026-08-26 真机日志铁证）：新播放请求未带 videoUrl（订阅源/书源嗅探场景）时，
                // 若上一会话残留单URL状态（如下载管理播放完下载视频后 Activity 销毁退出，VideoPlay 单例
                // 未清理 singleUrl=true + videoUrl=file://），startPlay 会误进 singleUrl 分支播放旧下载视频。
                // 此处清残留，让 startPlay 走 source（RssSource/BookSource）嗅探分支播放新内容。
                if (VideoPlay.singleUrl || VideoPlay.videoUrl != null) {
                    AppLog.put("VideoPlayerActivity initFromIntent: clear stale singleUrl state, singleUrl=${VideoPlay.singleUrl}, videoUrl=${VideoPlay.videoUrl?.take(2)}")
                    VideoPlay.singleUrl = false
                    VideoPlay.videoUrl = null
                }
            }
            intent.getStringExtra("videoTitle")?.let {
                VideoPlay.videoTitle = it
            }
            val sourceKey = intent.getStringExtra("sourceKey")
            val sourceType = intent.getIntExtra("sourceType", 0)
            val bookUrl = intent.getStringExtra("bookUrl")
            val record = intent.getStringExtra("record")
            VideoPlay.inBookshelf = intent.getBooleanExtra("inBookshelf", true)
            // T1.13 方案B: 保存 VideoPlay 状态快照到 Activity 字段（解决 8 实例快速切换状态串扰）
            snapshotVideoUrl = VideoPlay.videoUrl
            snapshotVideoTitle = VideoPlay.videoTitle
            snapshotSingleUrl = VideoPlay.singleUrl
            snapshotInBookshelf = VideoPlay.inBookshelf
            AppLog.put("VideoPlayerActivity state snapshot saved: singleUrl=$snapshotSingleUrl, inBookshelf=$snapshotInBookshelf")
            // T2.8: 保存 initSource 协程 Job 引用，onPause 时取消
            initSourceJob = lifecycleScope.launch {
                if (!VideoPlay.initSource(sourceKey, sourceType, bookUrl, record)) {
                    // V-004-P0-2: initSource 失败记录日志（VideoPlay.initSource 内部已记录详细原因）
                    // 根因：004 日志 18:48-19:16 期间 9 次 Activity 启动但播放器未初始化，原 finish() 无日志
                    AppLog.put("VideoPlayerActivity initSource failed, finish activity, sourceKey=${sourceKey?.take(2)}***")
                    finish()
                    return@launch
                }
                // P0-1: 统一 ViewPager2 模式，所有场景都用 ViewPager2
                // 书源/单URL模式：单 Fragment + 禁用滑动
                // 订阅源模式：多 Fragment + 垂直滑动
                // startPlay 由首个 Fragment 的 activatePlayer() 触发
                switchToViewPagerMode()
                initView()
                upView()
                // AD-04: 恢复播放进度
                restorePlayHistory()
            }
        } else {
            // 非新建恢复：从悬浮窗返回，也用 ViewPager2 模式
            VideoPlay.isResumeFromFloat = true
            // T1.13 方案B: 恢复场景也保存状态快照
            snapshotVideoUrl = VideoPlay.videoUrl
            snapshotVideoTitle = VideoPlay.videoTitle
            snapshotSingleUrl = VideoPlay.singleUrl
            snapshotInBookshelf = VideoPlay.inBookshelf
            switchToViewPagerMode()
            initView()
            upView()
        }
    }

    /**
     * T2.8: onPause 取消 initSource 协程（解决 Bug-25：onPause 后 initSource 仍运行导致资源泄漏）
     *
     * 场景：用户快速切 Activity 时，initSource 协程可能在 onPause 后仍在运行（如等待网络请求），
     * 导致资源泄漏 + 状态污染。onPause 时主动取消。
     */
    override fun onPause() {
        super.onPause()
        if (initSourceJob?.isActive == true) {
            initSourceJob?.cancel()
            AppLog.put("VideoPlayerActivity onPause: initSourceJob cancelled")
        }
        // AD-04: 取消定时保存 + 保存最后一次播放进度
        historySaveJob?.cancel()
        savePlayHistory()
        // A3 修复：onPause 延迟清理预加载缓存（30s 后清理，避免快速切回时缓存失效）
        FirstFramePreloader.delayedClearCache()
    }

    /**
     * A3 修复：onResume 取消延迟清理（保留预加载缓存）
     *
     * 场景：用户快速切回时取消延迟清理，缓存命中首帧秒开。
     */
    override fun onResume() {
        super.onResume()
        FirstFramePreloader.cancelDelayedClear()
        // AD-04: 启动定时保存播放进度（每10s）
        historySaveJob = lifecycleScope.launch {
            while (true) {
                delay(10_000)
                savePlayHistory()
            }
        }
    }

    // 修复：重写 onSupportNavigateUp，Toolbar 返回箭头委托给 onBackPressedDispatcher
    // 之前未重写此方法，点击左上角返回箭头时调用默认 NavUtils.navigateUpFromSameTask，
    // 该方法依赖 AndroidManifest 中 PARENT_ACTIVITY 声明，未声明时返回按钮无响应。
    // 现委托给 onBackPressedDispatcher，与系统返回键行为一致（全屏退出全屏，非全屏 finish）
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    // ==================== R3 抖音风格：ViewPager2 模式管理 ====================

    /**
     * R3 抖音风格：切换到 ViewPager2 沉浸式模式
     *
     * 隐藏旧模式布局，显示 ViewPager2 容器。
     * 订阅源非单URL时在 initSource 后调用。
     * 首个 Fragment 的播放由 onFragmentViewReady → activatePlayer 触发。
     */
    private fun switchToViewPagerMode() {
        useViewPagerMode = true
        binding.legacyContainer.gone()
        binding.viewPagerContainer.visible()

        // L-D9 S5 改造：顶栏已 Compose 化（GlassTopAppBar），不再绑定 ActionBar，
        // 返回按钮由 initComposeTopBar 的 onNavClick 直接委托 onBackPressedDispatcher。
        // 原 B1 双 TitleBar setSupportActionBar 冲突问题随 ActionBar 移除而消失。
        updateSourceDependentMenu()

        // P0-1: 书源/单URL模式禁用滑动（单 Fragment），订阅源模式保持垂直滑动
        // 能力迁移：书源视频多集（episodes 非空且 >1）时放开滑动，支持上下滑动切换上/下集
        val book = VideoPlay.book
        val bookEpisodes = VideoPlay.episodes
        val isSinglePage = VideoPlay.singleUrl ||
            (book != null && (bookEpisodes.isNullOrEmpty() || bookEpisodes.size <= 1))

        // 配置 ViewPager2
        videoPagerAdapter = VideoPagerAdapter(this)
        binding.viewPager.apply {
            orientation = ViewPager2.ORIENTATION_VERTICAL
            offscreenPageLimit = 1
            // 书源/单URL模式禁用滑动
            isUserInputEnabled = !isSinglePage
            adapter = videoPagerAdapter
            pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    // 旧 Fragment 暂停
                    currentFragment?.deactivatePlayer()
                    // 根据数据源更新索引（文章模式 vs 集数模式 vs 书源剧集模式）
                    when {
                        !VideoPlay.rssArticles.isNullOrEmpty() -> VideoPlay.rssArticleIndex = position
                        VideoPlay.book != null && !VideoPlay.episodes.isNullOrEmpty() ->
                            VideoPlay.chapterInVolumeIndex = position
                        else -> VideoPlay.rssEpisodeIndex = position
                    }
                    // 获取新 Fragment
                    val fragment = getVideoFragment(position)
                    currentFragment = fragment
                    // 激活播放（playerView 可能未就绪，由 onFragmentViewReady 兜底）
                    if (fragment?.playerView != null) {
                        fragment.activatePlayer()
                    }
                    // 更新标题（适配文章模式/集数模式/书源剧集模式）
                    composeTitle = when {
                        !VideoPlay.rssArticles.isNullOrEmpty() ->
                            VideoPlay.rssArticles?.getOrNull(position)?.title ?: ""
                        VideoPlay.book != null && !VideoPlay.episodes.isNullOrEmpty() ->
                            VideoPlay.episodes?.getOrNull(position)?.title ?: VideoPlay.videoTitle ?: ""
                        !VideoPlay.rssEpisodes.isNullOrEmpty() ->
                            VideoPlay.rssEpisodes?.getOrNull(position)?.title ?: VideoPlay.videoTitle ?: ""
                        else -> VideoPlay.videoTitle ?: ""
                    }
                    // 阶段8 F9：滑到最后一个文章时触发分页加载
                    val articles = VideoPlay.rssArticles
                    if (!articles.isNullOrEmpty() && position == articles.size - 1) {
                        VideoPlay.loadMoreArticles()
                    }
                }
            }
            registerOnPageChangeCallback(pageChangeCallback!!)
        }

        // 文章列表模式：定位到用户点击的文章索引（非0时需设置）
        if (!VideoPlay.rssArticles.isNullOrEmpty() && VideoPlay.rssArticleIndex > 0) {
            binding.viewPager.setCurrentItem(VideoPlay.rssArticleIndex, false)
        }
        // 书源剧集模式：定位到历史播放的集数索引（非0时需设置）
        if (VideoPlay.book != null &&
            !VideoPlay.episodes.isNullOrEmpty() &&
            VideoPlay.chapterInVolumeIndex > 0
        ) {
            binding.viewPager.setCurrentItem(VideoPlay.chapterInVolumeIndex, false)
        }

        // 设置标题
        composeTitle = VideoPlay.videoTitle ?: ""
    }

    /**
     * R3 抖音风格：Fragment 视图就绪回调
     *
     * VideoFragment.onViewCreated 中调用，确保 playerView 已初始化后再激活播放。
     * 解决 ViewPager2 创建 Fragment 异步时序问题：onPageSelected 可能在 Fragment 视图创建前触发。
     */
    fun onFragmentViewReady(fragment: VideoFragment, position: Int) {
        if (!useViewPagerMode) return
        if (position == binding.viewPager.currentItem && currentFragment == null) {
            // 首次就绪：激活播放
            currentFragment = fragment
            fragment.activatePlayer()
        } else if (position == binding.viewPager.currentItem) {
            // 当前页 Fragment 重建（如回收后恢复）：也需要激活播放
            // Bug修复：原代码只设置 currentFragment 不调用 activatePlayer，
            // 导致 onPageSelected 在 Fragment 视图创建前触发时（playerView=null 跳过 activatePlayer），
            // onFragmentViewReady 兜底也不激活播放，视频永远不播放
            currentFragment = fragment
            fragment.activatePlayer()
        }
        // 非当前页 Fragment 就绪：不做操作，等 onPageSelected 触发
    }

    /**
     * R3 抖音风格：获取指定位置的 VideoFragment
     */
    private fun getVideoFragment(position: Int): VideoFragment? {
        return supportFragmentManager.findFragmentByTag("f$position") as? VideoFragment
    }

    /**
     * R3 抖音风格：线路切换后更新 ViewPager2
     */
    fun onRssRouteChangedForViewPager() {
        videoPagerAdapter?.notifyDataSetChanged()
        binding.viewPager.setCurrentItem(0, false)
    }

    private fun initView() {
        if (isViewInitialized) {
            // onNewIntent 重初始化时已初始化过，避免重复注册 ViewModel 观察者
            return
        }
        isViewInitialized = true
        viewModel.upStarMenuData.observe(this) { upStarMenu() }
        binding.root.setBackgroundColor(backgroundColor)
        // P0-1: 统一 ViewPager2 模式，旧版 UI 初始化全部移除
        // Fragment 自行管理播放器和控件，设置面板由 VideoSettingsPanel 提供
        // 返回按钮由 Compose 顶栏 initComposeTopBar 的 onNavClick 直接委托 onBackPressedDispatcher
    }

    /**
     * Compose 顶栏（L-D9 S5 改造）：GlassTopAppBar + 自定义/刷新/收藏图标按钮 + MoreVert 下拉菜单
     *
     * - 标题：composeTitle 状态驱动（VIDEO_SUB_TITLE / UP_VIDEO_INFO / onPageSelected 更新）
     * - 全屏显隐：toggleFullScreen() 通过 binding.composeTopBar.gone()/visible() 控制
     * - 返回按钮：直接委托 onBackPressedDispatcher（全屏退出全屏，非全屏 finish）
     */
    private fun initComposeTopBar() {
        composeTitle = VideoPlay.videoTitle ?: ""
        updateSourceDependentMenu()
        binding.composeTopBar.setContent {
            LegadoTheme {
                GlassTopAppBar(
                    title = composeTitle,
                    navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                    onNavClick = { onBackPressedDispatcher.onBackPressed() },
                    actions = {
                        if (showCustomBtn) {
                            IconButton(onClick = { clickCustomButton() }) {
                                Icon(
                                    imageVector = Icons.Filled.Tune,
                                    contentDescription = getString(R.string.custom_button)
                                )
                            }
                        }
                        if (showRefresh) {
                            IconButton(onClick = { recreate() }) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = getString(R.string.refresh)
                                )
                            }
                        }
                        if (starVisible) {
                            IconButton(onClick = { onFragmentStarClicked() }) {
                                Icon(
                                    imageVector = if (starChecked) Icons.Filled.Star else Icons.Filled.StarBorder,
                                    contentDescription = getString(R.string.favorite)
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = getString(R.string.more)
                                )
                            }
                            AppDropdownMenu(
                                expanded = menuExpanded,
                                onDismiss = { menuExpanded = false },
                                actions = buildMenuActions()
                            )
                        }
                    }
                )
            }
        }
    }

    /**
     * 源相关菜单项可见性（源初始化完成/编辑返回后刷新）
     */
    private fun updateSourceDependentMenu() {
        showCustomBtn = (VideoPlay.source as? BookSource)?.customButton == true
        showRefresh = VideoPlay.source is RssSource
        showLogin = !VideoPlay.source?.loginUrl.isNullOrBlank()
        showChangeSource = (RssSearchSourceHolder.articles?.size ?: 0) > 1
    }

    /**
     * 下拉菜单数据驱动（迁移自 video_play.xml + onCompatOptionsItemSelected）
     */
    private fun buildMenuActions(): List<MenuAction> {
        val actions = mutableListOf<MenuAction>()
        // 浮窗播放
        actions += MenuAction(
            icon = Icons.Filled.PictureInPicture,
            title = getString(R.string.float_window),
            onClick = { startFloatingWindow() }
        )
        // 配置设置
        actions += MenuAction(
            icon = Icons.Filled.Settings,
            title = getString(R.string.config_settings),
            onClick = { showDialogFragment(SettingsDialog(this)) }
        )
        // 登录（源配置了登录地址才显示）
        if (showLogin) {
            actions += MenuAction(
                icon = Icons.Filled.Login,
                title = getString(R.string.login),
                onClick = { openSourceLogin() }
            )
        }
        // 复制播放地址
        actions += MenuAction(
            icon = Icons.Filled.ContentCopy,
            title = getString(R.string.copy_play_url),
            onClick = { copyVideoUrl() }
        )
        // 浏览器打开
        actions += MenuAction(
            icon = Icons.Filled.OpenInBrowser,
            title = getString(R.string.open_in_browser),
            onClick = { openVideoInBrowser() }
        )
        // 其他播放器打开
        actions += MenuAction(
            icon = Icons.Filled.Movie,
            title = getString(R.string.open_other_video_player),
            onClick = { openInOtherPlayer() }
        )
        // 换源（搜索结果多源场景显示）
        if (showChangeSource) {
            actions += MenuAction(
                icon = Icons.Filled.SwapVert,
                title = getString(R.string.change_source),
                onClick = { showDialogFragment(ChangeRssArticleSourceDialog()) }
            )
        }
        // 编辑书源
        actions += MenuAction(
            icon = Icons.Filled.Edit,
            title = getString(R.string.edit_book_source),
            onClick = { editSource() }
        )
        // 日志
        actions += MenuAction(
            icon = Icons.Filled.Info,
            title = getString(R.string.log),
            onClick = { showDialogFragment<AppLogDialog>() }
        )
        return actions
    }

    // ==================== 下拉菜单动作实现（迁移自 onCompatOptionsItemSelected） ====================

    private fun clickCustomButton() {
        (VideoPlay.source as? BookSource)?.let { source ->
            VideoPlay.book?.let { book ->
                SourceCallBack.callBackBtn(
                    this,
                    SourceCallBack.CLICK_CUSTOM_BUTTON,
                    source,
                    book,
                    VideoPlay.chapter,
                    BookType.video
                )
            }
        }
    }

    private fun openSourceLogin() {
        VideoPlay.source?.let { s ->
            when (s) {
                is BookSource -> startActivity<SourceLoginActivity> {
                    putExtra("bookType", BookType.video)
                }
                is RssSource -> startActivity<SourceLoginActivity> {
                    putExtra("type", "rssSource")
                    putExtra("key", s.getKey())
                }
            }
        }
    }

    private fun copyVideoUrl() {
        val url = VideoPlay.videoUrl
        if (url.isNullOrBlank()) {
            this.toastOnUi(getString(R.string.video_no_play_url))
            return
        }
        VideoPlay.book?.let {
            SourceCallBack.callBackBtn(
                this,
                SourceCallBack.CLICK_COPY_PLAY_URL,
                VideoPlay.source as? BookSource,
                it,
                VideoPlay.chapter,
                BookType.video,
                url
            ) {
                sendToClip(url)
            }
        }
    }

    private fun openVideoInBrowser() {
        // 浏览器打开：优先用视频URL，其次用 RSS 文章链接
        val url = VideoPlay.videoUrl
            ?: VideoPlay.rssArticles?.getOrNull(VideoPlay.rssArticleIndex)?.link
        if (url.isNullOrBlank()) {
            this.toastOnUi(getString(R.string.video_no_available_url))
        } else {
            openUrl(url)
        }
    }

    private fun openInOtherPlayer() {
        val url = VideoPlay.videoUrl
        if (url.isNullOrBlank()) {
            this.toastOnUi(getString(R.string.video_no_play_url))
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(url.toUri(), "video/*")
        }
        startActivity(intent)
    }

    private fun editSource() {
        VideoPlay.source?.let { s ->
            when (s) {
                is BookSource -> bookSourceEditResult.launch {
                    putExtra("sourceUrl", s.getKey())
                }
                is RssSource -> rssSourceEditResult.launch {
                    putExtra("sourceUrl", s.getKey())
                }
            }
        }
    }

    private fun showBook(book: Book) {
        binding.run {
            showCover(book)
            tvName.text = book.name
            book.getRealAuthor().takeIf { it.isNotEmpty() }?.let {
                tvAuthor.text = it
            } ?: tvAuthor.gone()
            showBookIntro(book)
        }
    }

    inner class CustomWebViewClient : WebViewClient() {
        private val jsStr = getInjectionString
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            request?.let {
                val uri = it.url
                return when (uri.scheme) {
                    "http", "https" -> false
                    "legado", "yuedu" -> {
                        startActivity<OnLineImportActivity> {
                            data = uri
                        }
                        true
                    }

                    else -> {
                        binding.root.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                            openUrl(uri)
                        }
                        true
                    }
                }
            }
            return true
        }
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            view?.evaluateJavascript(jsStr, null)
        }
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            view?.post {
                binding.tvIntroContainer.requestLayout()
            }
        }
    }

    private fun showBookIntro(book: Book) {
        val intro = book.getDisplayIntro()
        if (intro?.startsWith("<useweb>") == true) {
            val lastIndex = intro.lastIndexOf("<")
            if (lastIndex < 8) {
                introTextView.text = intro
                return
            }
            val html = intro.substring(8, lastIndex)
            val pooledWebView = this.pooledWebView ?: let{
                val pooledWebView = WebViewPool.acquire(this)
                val webView = pooledWebView.realWebView
                webView.onResume()
                webView.webViewClient = CustomWebViewClient()
                webView.addJavascriptInterface(WebCacheManager, nameCache)
                VideoPlay.source?.let {
                    webView.addJavascriptInterface(it, nameSource)
                    val webJsExtensions = WebJsExtensions(it, null, webView)
                    webView.addJavascriptInterface(webJsExtensions, nameJava)
                }
                pooledWebView
            }
            val webView = pooledWebView.realWebView
            if (initIntroView || this.pooledWebView == null) {
                initIntroView = false
                this.pooledWebView = pooledWebView
                binding.tvIntroContainer.removeAllViews()
                binding.tvIntroContainer.addView(webView)
            }
            val bookUrl = VideoPlay.book?.bookUrl
                ?.takeIf { it.startsWith("http", true) }
                ?.substringBefore(",")
            webView.loadDataWithBaseURL(bookUrl, html, "text/html", "utf-8", bookUrl)
            return
        }
        if (!initIntroView || pooledWebView != null) {
            destroyWeb()
            binding.tvIntroContainer.removeAllViews()
            binding.tvIntroContainer.addView(introTextView)
        }
        if (intro.isNullOrBlank()) {
            return
        }
        val tvIntro = introTextView
        if (intro.startsWith("<usehtml>")) {
            val lastIndex = intro.lastIndexOf("<")
            if (lastIndex < 9) {
                tvIntro.text = intro
                return
            }
            val html = intro.substring(9, lastIndex)
            tvIntro.setHtml(
                html,
                glideImageGetter,
                textViewTagHandler,
                imgOnLongClickListener = {
                    showDialogFragment(PhotoDialog(it, VideoPlay.source?.getKey()))
                },
                imgOnClickListener = {
                    viewModel.onButtonClick(this@VideoPlayerActivity, "info image" , it)
                }
            )
        } else if (intro.startsWith("<md>")) {
            val lastIndex = intro.lastIndexOf("<")
            if (lastIndex < 4) {
                tvIntro.text = intro
                return
            }
            val mark = intro.substring(4, lastIndex)
            lifecycleScope.launch {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    tvIntro.setTextClassifier(TextClassifier.NO_OP)
                }
                val context = this@VideoPlayerActivity
                val markwon: Markwon
                val markdown = withContext(IO) {
                    markwon = Markwon.builder(context)
                        .usePlugin(
                            GlideImagesPlugin.create(
                                Glide.with(context)
                                    .applyDefaultRequestOptions(
                                        RequestOptions()
                                            .override(imgAvailableWidth)
                                            .encodeQuality(88)
                                    )
                            )
                        )
                        .usePlugin(HtmlPlugin.create())
                        .usePlugin(TablePlugin.create(context))
                        .build()
                    markwon.toMarkdown(mark)
                }
                tvIntro.setMarkdown(
                    markwon,
                    markdown,
                    imgOnLongClickListener = { source ->
                        showDialogFragment(PhotoDialog(source, VideoPlay.source?.getKey()))
                    }
                )
            }
        } else {
            tvIntro.text = intro
        }
    }

    private fun showCover(book: Book) {
        binding.ivCover.load(book, false)
    }

    private fun showToc(toc: List<BookChapter>) {
        binding.ivChapter.setOnClickListener {
            VideoPlay.book?.bookUrl?.let {
                tocActivityResult.launch(it)
            }
        }
        val recyclerView = binding.chapters
        val layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerView.layoutManager = layoutManager
        val adapter = ChapterAdapter(toc,VideoPlay.chapterInVolumeIndex, false) { chapter, index ->
            if (index != VideoPlay.chapterInVolumeIndex) {
                VideoPlay.chapterInVolumeIndex = index
                VideoPlay.saveRead(0)
                upEpisodesView()
                VideoPlay.startPlay(playerView)
            }
        }
        recyclerView.adapter = adapter
        scrollToDurChapter(recyclerView, VideoPlay.chapterInVolumeIndex)
    }

    /**
     * R3 多线路支持：显示线路选择器
     *
     * 复用 binding.volumes（RecyclerView）显示线路列表，与书源卷选择器 UI 一致。
     * 线路数>1时显示线路选择器；线路数==1时隐藏线路选择器只显示集数。
     */
    private fun showRssRoutes(routes: List<RssRoute>) {
        binding.chaptersContainer.visible()
        if (routes.size > 1) {
            // 多线路：显示线路选择器
            binding.volumes.visible()
            val layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            binding.volumes.layoutManager = layoutManager
            val routeNames = routes.map { it.name }
            val adapter = RssRouteAdapter(routeNames, VideoPlay.rssRouteIndex) { _, index ->
                if (index != VideoPlay.rssRouteIndex) {
                    if (VideoPlay.isNewRoutesMode()) {
                        // 新模式：异步按需采集（switchToRoute 内部处理播放+UI更新）
                        VideoPlay.switchToRoute(index, playerView)
                        upRssRoutesView()
                    } else {
                        // 旧模式：内存切换
                        val episode = VideoPlay.switchRssRoute(index)
                        if (episode != null) {
                            upRssRoutesView()
                            VideoPlay.playRssEpisode(playerView, episode)
                            upRssEpisodesView()
                        }
                    }
                }
            }
            binding.volumes.adapter = adapter
            scrollToDurChapter(binding.volumes, VideoPlay.rssRouteIndex)
        } else {
            // 单线路：隐藏线路选择器
            binding.volumes.gone()
        }
        // 显示当前线路的集数列表
        val episodes = routes.getOrNull(VideoPlay.rssRouteIndex)?.episodes
        if (!episodes.isNullOrEmpty()) {
            binding.chapters.visible()
            showRssEpisodes(episodes)
        } else {
            binding.chapters.gone()
        }
    }

    /**
     * R3 多线路支持：更新线路选择器选中位置
     */
    private fun upRssRoutesView() {
        val routes = VideoPlay.rssRoutes
        if (!routes.isNullOrEmpty() && routes.size > 1) {
            val adapter = binding.volumes.adapter as? RssRouteAdapter
            adapter?.updateSelectedPosition(VideoPlay.rssRouteIndex)
            scrollToDurChapter(binding.volumes, VideoPlay.rssRouteIndex)
            // 更新集数列表
            val episodes = routes.getOrNull(VideoPlay.rssRouteIndex)?.episodes
            if (!episodes.isNullOrEmpty()) {
                showRssEpisodes(episodes)
            }
        }
    }

    /**
     * R1 多集选择播放：显示订阅源多集列表
     *
     * 复用 binding.chapters（RecyclerView），与书源集数列表 UI 一致
     */
    private fun showRssEpisodes(episodes: List<RssEpisode>) {
        val recyclerView = binding.chapters
        val layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerView.layoutManager = layoutManager
        val adapter = RssEpisodeAdapter(episodes, VideoPlay.rssEpisodeIndex) { episode, index ->
            if (index != VideoPlay.rssEpisodeIndex) {
                VideoPlay.rssEpisodeIndex = index
                VideoPlay.playRssEpisode(playerView, episode)
                upRssEpisodesView()
            }
        }
        recyclerView.adapter = adapter
        scrollToDurChapter(recyclerView, VideoPlay.rssEpisodeIndex)
    }

    /**
     * R1 多集选择播放：更新订阅源多集列表选中位置
     */
    private fun upRssEpisodesView() {
        if (!VideoPlay.rssEpisodes.isNullOrEmpty()) {
            val adapter = binding.chapters.adapter as? RssEpisodeAdapter
            adapter?.updateSelectedPosition(VideoPlay.rssEpisodeIndex)
            scrollToDurChapter(binding.chapters, VideoPlay.rssEpisodeIndex)
        }
    }

    private fun showVolumes(volumes: List<BookChapter>) {
        val recyclerView = binding.volumes
        val layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerView.layoutManager = layoutManager
        val adapter = ChapterAdapter(volumes,VideoPlay.durVolumeIndex, true) { chapter, index ->
            if (index != VideoPlay.durVolumeIndex) {
                VideoPlay.durVolumeIndex = index
                VideoPlay.chapterInVolumeIndex = 0
                VideoPlay.upEpisodes()
                if (VideoPlay.episodes.isNullOrEmpty()) {
                    binding.chapters.visibility = View.GONE
                } else {
                    binding.chapters.visibility = View.VISIBLE
                    val adapter = binding.chapters.adapter as? ChapterAdapter
                    adapter?.updateData(VideoPlay.episodes)
                }
                VideoPlay.saveRead(0)
                upVolumesView()
                VideoPlay.startPlay(playerView)
            }
        }
        recyclerView.adapter = adapter
        scrollToDurChapter(recyclerView, VideoPlay.durVolumeIndex)
    }

    private fun scrollToDurChapter(recyclerView: RecyclerView, index: Int) {
        recyclerView.postDelayed({
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
            layoutManager?.run {
                val smoothScroller = object : LinearSmoothScroller(this@VideoPlayerActivity) {
                    override fun getHorizontalSnapPreference(): Int {
                        return SNAP_TO_START // 滚动到最左边
                    }
                }
                smoothScroller.targetPosition = index
                this.startSmoothScroll(smoothScroller)
            }
            val adapter = recyclerView.adapter as? ChapterAdapter
            adapter?.updateSelectedPosition(index)
        }, 200)
    }

    private fun upView() {
        upEpisodesView()
        upVolumesView()
    }

    private fun upEpisodesView() {
        if (!VideoPlay.episodes.isNullOrEmpty()) {
            scrollToDurChapter(binding.chapters, VideoPlay.chapterInVolumeIndex)
        }
    }

    private fun upVolumesView() {
        if (!VideoPlay.volumes.isEmpty()) {
            scrollToDurChapter(binding.volumes, VideoPlay.durVolumeIndex)
        }
    }

    internal fun toggleFullScreen() {
        isFullScreen = !isFullScreen
        toggleSystemBar(!isFullScreen)
        if (isFullScreen) {
            orientation = requestedOrientation
            if (useViewPagerMode) {
                // R3 阶段4：ViewPager2 模式直接旋转 Activity，不使用 GSY startWindowFullscreen
                // 用户需求：根据视频方向选择全屏方向（竖屏视频→竖屏全屏，横屏视频→横屏全屏）
                requestedOrientation = if (VideoPlay.isPortraitVideo) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
                // F1 真全屏修复：用 composeTopBar.gone() 替代 supportActionBar?.hide()
                // 根因：supportActionBar?.hide() 只隐藏 ActionBar 内容显示，但顶栏仍占据布局空间，
                // 导致 ViewPager2 高度 = 屏幕高度 - 顶栏高度，playerView 无法铺满全屏。
                // gone() 释放布局空间，ViewPager2 可铺满整个屏幕实现真全屏。
                binding.composeTopBar.gone()
                currentFragment?.onFullScreenChanged(true)
            } else {
                requestedOrientation = if (VideoPlay.isPortraitVideo) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT //竖屏
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE //横屏
                }
                supportActionBar?.hide()
                binding.chaptersContainer.gone()
                binding.data.gone()
                playerView.startWindowFullscreen(this, false, false)
            }
        } else {
            if (useViewPagerMode) {
                // R3 阶段4：ViewPager2 模式恢复竖屏
                requestedOrientation = orientation
                // F1 真全屏修复：恢复顶栏显示（与 entering 的 gone() 对应）
                binding.composeTopBar.visible()
                supportActionBar?.show()
                currentFragment?.onFullScreenChanged(false)
            } else {
                requestedOrientation = orientation
                supportActionBar?.show()
                if (VideoPlay.book != null) {
                    binding.chaptersContainer.visible()
                    binding.data.visible()
                } else {
                    if (!VideoPlay.rssRoutes.isNullOrEmpty() || !VideoPlay.rssEpisodes.isNullOrEmpty()) {
                        // R3 多线路 / R1 多集：退出全屏恢复线路+集数列表
                        binding.chaptersContainer.visible()
                        val routes = VideoPlay.rssRoutes
                        if (!routes.isNullOrEmpty() && routes.size > 1) {
                            binding.volumes.visible()
                        }
                    }
                }
                playerView.postDelayed({
                    playerView.backFromFull(this)
                }, if (VideoPlay.isPortraitVideo) 300 else 0)
                upView()
            }
        }
    }


    @Suppress("DEPRECATION")
    @SuppressLint("SwitchIntDef")
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // video-player-theme-unify：配置变化兜底刷新播放器主题高亮
        applyVideoThemeColors()
        if (isFullScreen) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
            // R3 阶段4：ViewPager2 模式下不自动触发全屏
            // 全屏由用户主动点击全屏按钮或双指拉伸触发，避免设备旋转后反复进入/退出全屏
            if (useViewPagerMode) return
            when (newConfig.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> {
                    if (!VideoPlay.isPortraitVideo) {
                        toggleFullScreen()
                    }
                }
                Configuration.ORIENTATION_PORTRAIT -> {
                    if (VideoPlay.isPortraitVideo) {
                        toggleFullScreen()
                    }
                }
            }
        }
    }

    /**
     * video-player-theme-unify：刷新所有播放器实例的 View 侧主题高亮。
     * 小屏（Fragment 持有）+ 大屏（lazy playerView）+ 全屏实例全量刷新。
     */
    private fun applyVideoThemeColors() {
        playerView.applyThemeColors()
        currentFragment?.playerView?.applyThemeColors()
        playerView.getFullWindowPlayer()?.applyThemeColors()
    }

    private fun setupPlayerView() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val layoutParams = playerView.layoutParams
        layoutParams.width = screenWidth
        val videoWidth = playerView.currentVideoWidth
        val videoHeight = playerView.currentVideoHeight
        val height = if (videoWidth > 0 && videoHeight > 0) (screenWidth * videoHeight / videoWidth) else (screenWidth * 9 / 16) //默认16:9
        //高度不超过一半屏幕
        layoutParams.height = if (height < screenHeight / 2) height else screenHeight / 2
        playerView.layoutParams = layoutParams
        playerView.isNeedOrientationUtils = false //关闭自带的屏幕方向控制
        playerView.fullscreenButton.setOnClickListener { toggleFullScreen() }
        playerView.setBackFromFullScreenListener { toggleFullScreen() }
        playerView.setVideoAllCallBack(object : GSYSampleCallBack() {
            @SuppressLint("SourceLockedOrientationActivity")
            override fun onPrepared(url: String?, vararg objects: Any?) {
                super.onPrepared(url, *objects)
                playerView.post {
                    val player = playerView.getCurrentPlayer()
                    if (VideoPlay.lockCurScreen &&  !player.getLockCurScreen()) {
                        player.lockTouchLogic()
                    }
                    //根据实际视频比例再次调整
                    val videoWidth = playerView.currentVideoWidth
                    val videoHeight = playerView.currentVideoHeight
                    if (videoWidth > 0 && videoHeight > 0) {
                        val layoutParams = playerView.layoutParams
                        val parentWidth = playerView.width
                        val aspectRatio = videoHeight.toFloat() / videoWidth.toFloat()
                        val isPortraitVideo = if (aspectRatio > 1.2) true else false
                        VideoPlay.isPortraitVideo = isPortraitVideo
                        if (isFullScreen && isPortraitVideo) {
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT //提前进入了全屏，并且默认横屏了，纠正回来
                            return@post
                        }
                        if (VideoPlay.startFull && VideoPlay.autoPlay && !isFullScreen) {
                            toggleFullScreen()
                            return@post
                        }
                        val height = (parentWidth * aspectRatio).toInt()
                        val displayMetrics = resources.displayMetrics
                        val screenHeight = displayMetrics.heightPixels
                        //高度不超过一半屏幕
                        layoutParams.height = if (height < screenHeight / 2) height else screenHeight / 2
                        playerView.layoutParams = layoutParams
                    }
                }
            }
        })
    }

    /**
     * L-D9 S5 改造：菜单已迁移至 Compose AppDropdownMenu，原 ActionBar 菜单体系（video_play.xml）
     * 及 onCompatCreateOptionsMenu/onPrepareOptionsMenu/onMenuOpened/onCompatOptionsItemSelected
     * 均已移除。收藏按钮状态改为 Compose 状态驱动（starVisible/starChecked）。
     */
    private fun upStarMenu() {
        val isStarred = VideoPlay.rssStar != null
        starVisible = VideoPlay.rssStar != null || VideoPlay.rssRecord != null
        starChecked = isStarred
        // R3 阶段2：同步更新当前 Fragment 的收藏按钮状态
        currentFragment?.updateStarState(isStarred)
    }

    /**
     * R3 阶段2：Fragment 收藏按钮点击委托
     *
     * 已收藏 → 弹出 RssFavoritesDialog 编辑
     * 未收藏 → 调用 viewModel.addFavorite 收藏
     */
    fun onFragmentStarClicked() {
        if (VideoPlay.rssStar != null) {
            VideoPlay.rssStar?.let { showDialogFragment(RssFavoritesDialog(it)) }
        } else {
            viewModel.addFavorite {
                VideoPlay.rssStar?.let { showDialogFragment(RssFavoritesDialog(it)) }
            }
        }
    }

    private fun startFloatingWindow() {
        val activePlayer = if (useViewPagerMode) {
            currentFragment?.playerView ?: return
        } else {
            playerView
        }
        // 悬浮窗Bug修复：在启动服务之前先检查 overlay 权限
        // 之前在 VideoPlayService.onStartCommand() 中检查权限，
        // 发现没权限时 stopSelf() 导致服务立即销毁，悬浮窗压根没展示
        if (!Settings.canDrawOverlays(this)) {
            PermissionsCompat.Builder()
                .addPermissions(Permissions.SYSTEM_ALERT_WINDOW)
                .rationale(R.string.float_permission_rationale)
                .onGranted {
                    // 授权成功后重新启动悬浮窗
                    startFloatingWindow()
                }
                .request()
            return
        }
        VideoPlay.savePlayState(activePlayer)
        // 启动悬浮窗服务
        val intent = Intent(this, VideoPlayService::class.java).apply {
            putExtra("isNew", false)
        }
        ContextCompat.startForegroundService(this, intent)
        activePlayer.needDestroy = false
        finish()
    }

    // ==================== R3 阶段5：VideoSettingsPanel 回调实现 ====================

    override fun onRouteChanged(episode: RssEpisode) {
        if (useViewPagerMode) {
            // ViewPager2 模式：重新播放新集
            val pv = currentFragment?.playerView ?: return
            VideoPlay.playRssEpisode(pv, episode)
            if (VideoPlay.rssArticles.isNullOrEmpty()) {
                // 集数模式：更新 adapter + 重置到第一页（旧逻辑）
                onRssRouteChangedForViewPager()
            } else {
                // 文章模式：线路切换不影响 ViewPager2（文章数量不变），只更新当前 Fragment 的集数选择器
                currentFragment?.updateEpisodeSelector()
            }
        } else {
            // Legacy 模式：走旧逻辑
            upRssRoutesView()
            VideoPlay.playRssEpisode(playerView, episode)
            upRssEpisodesView()
        }
    }

    override fun onFloatWindow() {
        startFloatingWindow()
    }

    override fun onEditSource() {
        VideoPlay.source?.let { s ->
            when (s) {
                is BookSource -> bookSourceEditResult.launch {
                    putExtra("sourceUrl", s.getKey())
                }
                is RssSource -> rssSourceEditResult.launch {
                    putExtra("sourceUrl", s.getKey())
                }
            }
        }
    }

    override fun onLog() {
        showDialogFragment<AppLogDialog>()
    }

    override fun observeLiveBus() {

        // video-player-theme-unify：主题切换不重建（recreateOnThemeChange=false），
        // 主动刷新播放器 View 侧主题高亮（进度条/缓冲/缩略图取 accentColor）
        observeEvent<String>(EventBus.RECREATE) {
            applyVideoThemeColors()
        }

        observeEventSticky<String>(EventBus.VIDEO_SUB_TITLE) {
            if (useViewPagerMode) {
                composeTitle = it
                // R3 阶段2：同步更新当前 Fragment 的视频标题
                currentFragment?.updateVideoTitle(it)
            } else {
                composeTitle = it
            }
            // R3 修复：startPlay 异步赋值 videoUrl，VIDEO_SUB_TITLE 在每次 player.setUp 后触发，此时 videoUrl 已就绪
            // P0-1: 统一 ViewPager2 模式，updateVideoUrlDisplay 已移除
        }

        observeEvent<ArrayList<Int>>(EventBus.UP_VIDEO_INFO) {
            if (useViewPagerMode) {
                // 文章列表模式：文章数量不变，只需更新当前 Fragment 的集数/线路选择器
                if (!VideoPlay.rssArticles.isNullOrEmpty()) {
                    currentFragment?.updateEpisodeSelector()
                    composeTitle = VideoPlay.videoTitle ?: ""
                    return@observeEvent
                }
                // 集数列表模式：增量更新 adapter 数量（避免 notifyDataSetChanged 重建首个 Fragment）
                val newCount = if (VideoPlay.book != null) 1
                    else (VideoPlay.rssEpisodes?.size ?: 1)
                val oldCount = videoPagerAdapter?.itemCount ?: 0
                if (newCount > oldCount) {
                    videoPagerAdapter?.notifyItemRangeInserted(oldCount, newCount - oldCount)
                } else if (newCount < oldCount) {
                    videoPagerAdapter?.notifyItemRangeRemoved(newCount, oldCount - newCount)
                }
                composeTitle = VideoPlay.rssEpisodes?.getOrNull(VideoPlay.rssEpisodeIndex)?.title
                    ?: VideoPlay.videoTitle ?: ""
                // 集数模式下也更新选择器（集数列表可能变化）
                currentFragment?.updateEpisodeSelector()
                return@observeEvent
            }
            // Legacy 模式：现有逻辑不变
            it.forEach { value ->
                when (value) {
                    1 -> {
                        // R3 多线路支持：优先处理订阅源多线路列表
                        val rssRoutes = VideoPlay.rssRoutes
                        if (!rssRoutes.isNullOrEmpty()) {
                            if (binding.volumes.adapter !is RssRouteAdapter && rssRoutes.size > 1) {
                                showRssRoutes(rssRoutes)
                            } else {
                                upRssRoutesView()
                            }
                        }
                        // R1 多集选择播放：处理订阅源多集列表
                        val rssEpisodes = VideoPlay.rssEpisodes
                        if (rssEpisodes != null && rssEpisodes.isNotEmpty()) {
                            if (binding.chapters.adapter !is RssEpisodeAdapter) {
                                binding.chaptersContainer.visible()
                                binding.chapters.visible()
                                showRssEpisodes(rssEpisodes)
                            } else {
                                upRssEpisodesView()
                            }
                        } else {
                            upEpisodesView()
                        }
                    }
                }
            }
        }

        // 阶段8 F9：分页加载完成通知，刷新 adapter
        observeEvent<Int>(EventBus.ARTICLES_LOADED) { addedCount ->
            val oldCount = videoPagerAdapter?.itemCount ?: 0
            videoPagerAdapter?.notifyItemRangeInserted(oldCount, addedCount)
        }

        observeEvent<String>(EventBus.VIDEO_PLAY_ERROR) {
            // R3 阶段5：同步更新设置面板的调试日志
            settingsPanel?.appendDebugLog(it)
            // video-sniff-403-and-rss-classic-fix Phase 2 (3.4)：WebView 降级 observe 已删除，
            // 统一走错误对话框"重试/系统浏览器"承接
            showVideoPlayErrorDialog(it)
        }

    }

    /**
     * P0-1.4 / Phase 2 (3.4+3.7) 收敛: 显示视频播放错误对话框（F2 决策日志保留）
     *
     * video-sniff-403-and-rss-classic-fix Phase 2：WebView 播放器已删除，
     * 失败承接收敛为三通道——①tryNextFallback 降级链+BUFFERING 超时自愈（独立于 UI）；
     * ②本对话框"重试"（retryExoPlayback）；③"系统浏览器"（最终兜底）。
     *
     * playerType 设置语义同步收敛：仅剩 自动(0)/内置播放器(1)，原 WebView(2) 存量值
     * 由 VideoPlay.playerType 一次性迁移落 1（F-06/R-P2-2）。
     *
     * F2 决策日志：每个决策转换点记录 AppLog.put（永久日志）
     * 防重复弹窗：若已有对话框显示中则跳过。
     */
    private fun showVideoPlayErrorDialog(errorInfo: String) {
        if (errorDialog?.isShowing == true) return
        // 无播放地址则不弹窗（仅记录日志）
        val url = VideoPlay.videoUrl ?: return
        val title = VideoPlay.videoTitle ?: ""
        // AD-03: 接入 ErrorMapper 获取用户友好错误提示
        val userError = ErrorMapper.map(errorInfo)
        errorDialog = alert(title = getString(userError.titleResId), message = getString(userError.messageResId)) {
            positiveButton(getString(R.string.retry)) {
                // F2 决策日志：用户手动重试
                AppLog.put("降级决策: ExoPlayer 重试(user choice), title=$title")
                retryCurrentPlayback()
            }
            negativeButton(getString(R.string.open_in_browser)) {
                // F2 决策日志：用户选择系统浏览器（系统浏览器天然具备完整 Cookie/JS 环境，
                // 能力等价覆盖原 WebView 播放页且无维护成本）
                AppLog.put("降级决策: ExoPlayer→系统浏览器(user choice), title=$title, urlLen=${url.length}")
                openInSystemBrowser(url)
            }
        }
    }

    /**
     * F1 Level 4: 用系统浏览器打开视频 URL（最终兜底方案）
     *
     * 适用场景：ExoPlayer 和 WebView 都无法播放时，交给系统浏览器处理。
     * 注意：系统浏览器不支持自定义 Headers，仅适用于无需防盗链的直链（mp4 等）。
     */
    private fun openInSystemBrowser(url: String) {
        try {
            // P1-B 修复：本地文件不调用系统浏览器（系统浏览器无法播放 .mpd/.m3u8 清单，且 file:// 触发 FileUriExposedException）
            if (url.startsWith("file://")) {
                AppLog.put("系统浏览器不支持本地清单文件: urlLen=${url.length}")
                return
            }
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            AppLog.put("系统浏览器打开失败: urlLen=${url.length}", e)
        }
    }

    /**
     * P0-1.4: 重试当前播放（ExoPlayer）
     *
     * 由错误对话框"重试"按钮调用，重新触发当前 Fragment 的 ExoPlayer 播放。
     */
    private fun retryCurrentPlayback() {
        currentFragment?.retryExoPlayback()
    }

    // video-sniff-403-and-rss-classic-fix Phase 2 (3.4)：switchCurrentToWebView 已删除
    // （WebView 播放器移除，失败承接见 showVideoPlayErrorDialog 三通道注释）

    override fun finish() {
        val book = VideoPlay.book ?: run {
            // 订阅源模式：保存位置记忆 + 清理缓存和文章列表防止内存泄漏
            // 阶段8 F11：保存退出时正在看的文章 link，供 RssArticlesFragment.onResume 滚动定位
            VideoPlay.lastPlayedArticleLink = VideoPlay.rssArticles?.getOrNull(VideoPlay.rssArticleIndex)?.link
            // 阶段8 F10：清理预缓冲缓存
            VideoPlay.clearPreloadCache()
            // 阶段8 F9：清理分页加载上下文
            VideoPlay.rssSortName = null
            VideoPlay.rssSortUrl = null
            VideoPlay.rssNextPageUrl = null
            VideoPlay.rssArticlePage = 1
            VideoPlay.rssArticlesHasMore = true
            VideoPlay.isLoadingMoreArticles = false
            VideoPlay.rssArticles = null
            VideoPlay.rssArticleIndex = 0
            return super.finish()
        }
        if (VideoPlay.inBookshelf) {
            callBackBookEnd()
            return super.finish()
        }
        if (!AppConfig.showAddToShelfAlert) {
            callBackBookEnd()
            viewModel.removeFromBookshelf { super.finish() }
        } else {
            showComposeConfirmDialog(
                title = getString(R.string.add_to_bookshelf),
                message = getString(R.string.check_add_bookshelf, book.name),
                positiveText = getString(android.R.string.ok),
                negativeText = getString(android.R.string.no),
                onPositive = {
                    val book = VideoPlay.book
                    book?.removeType(BookType.notShelf)
                    lifecycleScope.launch(IO) {
                        book?.save()
                        withContext(Main) {
                            VideoPlay.inBookshelf = true
                            setResult(RESULT_OK)
                        }
                    }
                },
                onNegative = {
                    callBackBookEnd()
                    viewModel.removeFromBookshelf { super.finish() }
                }
            )
        }
    }

    private fun callBackBookEnd() {
        SourceCallBack.callBackBook(SourceCallBack.END_READ, VideoPlay.source as BookSource?, VideoPlay.book, VideoPlay.chapter)
    }

    override fun updateFavorite(title: String?, group: String?) {
        viewModel.updateFavorite(title, group)
    }

    override fun deleteFavorite() {
        viewModel.delFavorite()
    }

    override fun onStart() {
        super.onStart()
        if (initGetter) {
            glideImageGetter.start()
        }
    }

    @SuppressLint("InlinedApi")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 画中画：仅 Android 8+ 且正在播放时进入
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val currentPlayer = if (useViewPagerMode) {
                    currentFragment?.playerView?.getCurrentPlayer()
                } else {
                    playerView.getCurrentPlayer()
                }
                if (currentPlayer?.currentState == GSYVideoView.CURRENT_STATE_PLAYING) {
                    val params = android.app.PictureInPictureParams.Builder()
                        .setAspectRatio(android.util.Rational(16, 9))
                        .build()
                    enterPictureInPictureMode(params)
                }
            } catch (e: Exception) {
                io.legado.app.constant.AppLog.put("VideoPlayerActivity: enterPiP", e)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // T2.12: Activity 切到后台时暂停视频播放（解决 Bug-26：onStop 后视频仍播放导致资源泄漏+音频泄漏）
        // 注：deactivatePlayer 内部会判断 isActivated，仅在活跃时暂停
        currentFragment?.deactivatePlayer()
        if (initGetter) {
            glideImageGetter.stop()
        }
    }

    override fun onDestroy() {
        // app-stability-round2 P2-2: 先取消抓取协程（含嗅探 WebView），再释放播放器资源
        // 根因：原顺序 destroyWeb 先执行、stopLoading 后置，嗅探协程取消时序混乱，且 runCatching 误捕获 CancellationException
        // 修复：stopLoading 提前到最前，协程取消后 BackstageWebView.invokeOnCancellation 主动销毁嗅探 WebView
        VideoPlay.stopLoading()
        destroyWeb()
        super.onDestroy()
        // P0-1.4: 清理错误对话框，防止窗口泄漏
        errorDialog?.dismiss()
        errorDialog = null
        if (initGetter) {
            glideImageGetter.clear()
        }
        VideoPlay.saveRead()
        // R3: ViewPager2 模式下旧 playerView 未使用，Fragment 自行管理释放
        if (!useViewPagerMode) {
            playerView.getCurrentPlayer().release()
        }
        // T5.1: Activity 销毁时清空播放器实例池（池生命周期=Activity 生命周期，
        // 避免 App 后台时池内实例占用解码器/缓冲区资源）
        io.legado.app.help.exoplayer.PlayerInstancePool.clear()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // rss-unified-search: 清理换源 Holder，避免内存泄漏与跨文章串数据
        RssSearchSourceHolder.clear()
    }

    private fun destroyWeb() {
        pooledWebView?.let { WebViewPool.release(it) }
        pooledWebView = null
    }
}