package io.legado.app.ui.video

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.textclassifier.TextClassifier
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.AdapterView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
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
import io.legado.app.help.gsyVideo.VideoPlayer
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.getInjectionString
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.help.webView.WebViewPool
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.model.VideoPlay
import io.legado.app.service.VideoPlayService
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
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.ui.widget.text.ScrollTextView
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setHtml
import io.legado.app.utils.setMarkdown
import io.legado.app.utils.setTintMutate
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoPlayerActivity : VMBaseActivity<ActivityVideoPlayerBinding, VideoPlayerViewModel>(),
    SettingsDialog.CallBack, RssFavoritesDialog.Callback, VideoSettingsPanel.SettingsPanelCallback {
    override val binding by viewBinding(ActivityVideoPlayerBinding::inflate)
    override val viewModel by viewModels<VideoPlayerViewModel>()
    // P0-1: playerView 从 legacyContainer 获取（legacyContainer 已隐藏但 XML 保留避免编译错误）
    // ViewPager2 模式下使用 currentFragment?.playerView，此字段仅供 Legacy 代码路径引用
    private val playerView: VideoPlayer by lazy { binding.playerView }
    private var starMenuItem: MenuItem? = null

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
    private var menuCustomBtn: MenuItem? = null
    private val bookSourceEditResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upSource {
                    menuCustomBtn?.isVisible = (VideoPlay.source as? BookSource)?.customButton == true
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
        isNew = intent.getBooleanExtra("isNew", true)
        if (isNew) {
            intent.getStringExtra("videoUrl")?.let {
                VideoPlay.videoUrl = it
                VideoPlay.singleUrl = true
            }
            intent.getStringExtra("videoTitle")?.let {
                VideoPlay.videoTitle = it
            }
            val sourceKey = intent.getStringExtra("sourceKey")
            val sourceType = intent.getIntExtra("sourceType", 0)
            val bookUrl = intent.getStringExtra("bookUrl")
            val record = intent.getStringExtra("record")
            VideoPlay.inBookshelf = intent.getBooleanExtra("inBookshelf", true)
            lifecycleScope.launch {
                if (!VideoPlay.initSource(sourceKey, sourceType, bookUrl, record)) {
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
            }
        } else {
            // 非新建恢复：从悬浮窗返回，也用 ViewPager2 模式
            VideoPlay.isResumeFromFloat = true
            switchToViewPagerMode()
            initView()
            upView()
        }
        onBackPressedDispatcher.addCallback(this) {
            if (isFullScreen) {
                toggleFullScreen()
                return@addCallback
            }
            finish()
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

        // 修复：重新绑定 titleBarNew 为 ActionBar
        setSupportActionBar(binding.titleBarNew.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        // B1 修复：直接绑定返回按钮点击事件，绕过 onSupportNavigateUp 的 ActionBar 链路
        // 根因：activity_video_player.xml 有两个 TitleBar（title_bar 在 legacyContainer + title_bar_new 在 viewPagerContainer），
        // TitleBar.kt 的 attachToActivity() 在 onAttachedToWindow() 时自动调用 setSupportActionBar(toolbar)，
        // 两个 TitleBar 都调用 setSupportActionBar 导致 ActionBar 引用混乱，onSupportNavigateUp 可能未被正确触发。
        // setNavigationOnClickListener 直接绑定点击事件更可靠，与 ActionBar 状态无关。
        binding.titleBarNew.setNavigationOnClickListener {
            Log.d("VideoBack", "NavigationOnClickListener triggered, isFullScreen=$isFullScreen")
            onBackPressedDispatcher.onBackPressed()
        }

        // P0-1: 书源/单URL模式禁用滑动（单 Fragment），订阅源模式保持垂直滑动
        val isSinglePage = VideoPlay.book != null || VideoPlay.singleUrl

        // 配置 ViewPager2
        videoPagerAdapter = VideoPagerAdapter(this)
        binding.viewPager.apply {
            orientation = ViewPager2.ORIENTATION_VERTICAL
            offscreenPageLimit = 1
            // 书源/单URL模式禁用滑动
            isUserInputEnabled = !isSinglePage
            adapter = videoPagerAdapter
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    // 旧 Fragment 暂停
                    currentFragment?.deactivatePlayer()
                    // 根据数据源更新索引（文章模式 vs 集数模式）
                    if (!VideoPlay.rssArticles.isNullOrEmpty()) {
                        VideoPlay.rssArticleIndex = position
                    } else {
                        VideoPlay.rssEpisodeIndex = position
                    }
                    // 获取新 Fragment
                    val fragment = getVideoFragment(position)
                    currentFragment = fragment
                    // 激活播放（playerView 可能未就绪，由 onFragmentViewReady 兜底）
                    if (fragment?.playerView != null) {
                        fragment.activatePlayer()
                    }
                    // 更新标题（适配文章模式/集数模式）
                    binding.titleBarNew.title = when {
                        !VideoPlay.rssArticles.isNullOrEmpty() ->
                            VideoPlay.rssArticles?.getOrNull(position)?.title ?: ""
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
            })
        }

        // 文章列表模式：定位到用户点击的文章索引（非0时需设置）
        if (!VideoPlay.rssArticles.isNullOrEmpty() && VideoPlay.rssArticleIndex > 0) {
            binding.viewPager.setCurrentItem(VideoPlay.rssArticleIndex, false)
        }

        // 设置标题
        binding.titleBarNew.title = VideoPlay.videoTitle ?: ""
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
        viewModel.upStarMenuData.observe(this) { upStarMenu() }
        binding.root.setBackgroundColor(backgroundColor)
        // P0-1: 统一 ViewPager2 模式，旧版 UI 初始化全部移除
        // Fragment 自行管理播放器和控件，设置面板由 VideoSettingsPanel 提供
        // Issue-4 修复：旧 titleBar 绑定返回按钮（attachToActivity=false 后需手动设置）
        // 确保旧模式下点击左上角返回箭头能 finish Activity
        binding.titleBar.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.titleBar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
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
                    val episode = VideoPlay.switchRssRoute(index)
                    if (episode != null) {
                        upRssRoutesView()
                        VideoPlay.playRssEpisode(playerView, episode)
                        upRssEpisodesView()
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
        // R3 布局学习：多集时显示上一集/下一集按钮
        if (episodes.size > 1) {
            binding.rssEpisodesContainer.visible()
            binding.btnPrevEpisode.setOnClickListener {
                if (VideoPlay.upRssEpisodeIndex(-1, playerView)) {
                    upRssEpisodesView()
                }
            }
            binding.btnNextEpisode.setOnClickListener {
                if (VideoPlay.upRssEpisodeIndex(1, playerView)) {
                    upRssEpisodesView()
                }
            }
        }
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

    /**
     * R2 调试日志：追加文本到调试面板
     */
    private fun appendDebugLog(text: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        binding.tvDebugLog.append("[$time] $text\n")
    }

    /**
     * R2 调试日志：切换调试面板显示/隐藏
     */
    private fun toggleDebugPanel() {
        if (binding.debugPanel.isShown) {
            binding.debugPanel.gone()
        } else {
            binding.debugPanel.visible()
        }
    }

    /**
     * R3 修复：更新播放地址展示 + 复制按钮
     * startPlay 异步赋值 videoUrl，setupRssVideoPanel 同步执行时可能为空，
     * 需在 VIDEO_SUB_TITLE 事件（每次 player.setUp 后触发）中重复调用兜底。
     */
    private fun updateVideoUrlDisplay() {
        val videoUrl = VideoPlay.videoUrl
        if (!videoUrl.isNullOrEmpty()) {
            binding.tvVideoUrl.text = "播放地址：$videoUrl"
            binding.tvVideoUrl.visible()
            binding.btnCopyUrl.visible()
            binding.tvVideoUrl.setOnClickListener {
                sendToClip(videoUrl)
                toastOnUi("播放地址已复制")
            }
            binding.btnCopyUrl.setOnClickListener {
                sendToClip(videoUrl)
                toastOnUi("播放地址已复制")
            }
        }
    }

    /**
     * R3 布局学习：初始化订阅源功能区按钮（播放地址/快进快退/倍速/调试/上一集下一集/简介）
     */
    private fun setupRssVideoPanel() {
        // 播放地址展示 + 复制按钮（REQ-3.11 多行换行 / REQ-3.12 复制按钮）
        updateVideoUrlDisplay()

        // 快进快退按钮
        binding.btnSkipBack30s.setOnClickListener { skipVideo(-30000) }
        binding.btnSkipBack10s.setOnClickListener { skipVideo(-10000) }
        binding.btnSkipFwd10s.setOnClickListener { skipVideo(10000) }
        binding.btnSkipFwd30s.setOnClickListener { skipVideo(30000) }

        // 倍速Spinner（1x/2x/3x/5x/10x，移除15x避免高倍速问题）
        val speeds = arrayOf("1x", "2x", "3x", "5x", "10x")
        val speedValues = floatArrayOf(1f, 2f, 3f, 5f, 10f)
        // R3 布局优化：用自定义 item 布局（textSize=11sp）替代 simple_spinner_item（默认~14sp），与 Button 文字大小一致
        val speedAdapter = ArrayAdapter(this, R.layout.item_spinner_speed, speeds)
        speedAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerPlaybackRate.adapter = speedAdapter
        binding.spinnerPlaybackRate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                playerView.setSpeed(speedValues[position], true)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 调试按钮
        binding.btnToggleDebug.setOnClickListener { toggleDebugPanel() }

        // 上一集/下一集按钮在 showRssEpisodes 中设置（rssEpisodes 就绪后才显示）

        // 视频简介（从 rssStar/rssRecord 获取 description）
        val description = VideoPlay.rssStar?.toRssArticle()?.description
            ?: VideoPlay.rssRecord?.toRssArticle()?.description
        if (!description.isNullOrEmpty()) {
            binding.tvRssDescription.text = description
            binding.tvRssDescription.visible()
        }
    }

    /**
     * R3 快进快退：跳转到当前位置 ± offset
     */
    private fun skipVideo(offsetMillis: Long) {
        val player = playerView.getCurrentPlayer()
        val currentPosition = VideoPlay.videoManager.currentPosition
        val duration = VideoPlay.videoManager.duration
        var target = currentPosition + offsetMillis
        if (target < 0) target = 0
        if (duration > 0 && target > duration) target = duration
        player.seekTo(target)
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
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                // F1 真全屏修复：用 titleBarNew.gone() 替代 supportActionBar?.hide()
                // 根因：supportActionBar?.hide() 只隐藏 ActionBar 内容显示，但 TitleBar（AppBarLayout）
                // 作为 viewPagerContainer（LinearLayout）子控件仍占据布局空间，
                // 导致 ViewPager2 高度 = 屏幕高度 - TitleBar高度，playerView 无法铺满全屏。
                // gone() 释放布局空间，ViewPager2 可铺满整个屏幕实现真全屏。
                binding.titleBarNew.gone()
                Log.d("VideoFS", "enter fullscreen: titleBarNew gone, isFullScreen=$isFullScreen")
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
                binding.rssVideoPanel.gone()
                playerView.startWindowFullscreen(this, false, false)
            }
        } else {
            if (useViewPagerMode) {
                // R3 阶段4：ViewPager2 模式恢复竖屏
                requestedOrientation = orientation
                // F1 真全屏修复：恢复 TitleBar 显示（与 entering 的 gone() 对应）
                binding.titleBarNew.visible()
                supportActionBar?.show()
                Log.d("VideoFS", "exit fullscreen: titleBarNew visible, isFullScreen=$isFullScreen")
                currentFragment?.onFullScreenChanged(false)
            } else {
                requestedOrientation = orientation
                supportActionBar?.show()
                if (VideoPlay.book != null) {
                    binding.chaptersContainer.visible()
                    binding.data.visible()
                } else {
                    // R3 布局学习：订阅源退出全屏恢复功能区
                    binding.rssVideoPanel.visible()
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

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.video_play, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menuCustomBtn = menu.findItem(R.id.menu_custom_btn)?.also {
            it.isVisible = (VideoPlay.source as? BookSource)?.customButton == true
        }
        starMenuItem = menu.findItem(R.id.menu_rss_star)
        upStarMenu()
        // menu_rss_refresh 只在 RSS 源时显示
        menu.findItem(R.id.menu_rss_refresh)?.isVisible = VideoPlay.source is RssSource
        // rss-unified-search: 仅当搜索结果多源场景（RssSearchSourceHolder.articles.size > 1）显示换源菜单
        menu.findItem(R.id.menu_change_source)?.isVisible =
            (RssSearchSourceHolder.articles?.size ?: 0) > 1
        return super.onPrepareOptionsMenu(menu)
    }

    private fun upStarMenu() {
        val isStarred = VideoPlay.rssStar != null
        if (VideoPlay.rssStar != null) {
            starMenuItem?.isVisible = true
            starMenuItem?.setIcon(R.drawable.ic_star)
            starMenuItem?.setTitle(R.string.in_favorites)
            starMenuItem?.icon?.setTintMutate(primaryTextColor)
        } else if(VideoPlay.rssRecord != null) {
            starMenuItem?.isVisible = true
            starMenuItem?.setIcon(R.drawable.ic_star_border)
            starMenuItem?.setTitle(R.string.out_favorites)
            starMenuItem?.icon?.setTintMutate(primaryTextColor)
        } else {
            starMenuItem?.isVisible = false
        }
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

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_login)?.isVisible = !VideoPlay.source?.loginUrl.isNullOrBlank()
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_custom_btn -> {
                (VideoPlay.source as? BookSource)?.let {source ->
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
            R.id.menu_rss_star -> viewModel.addFavorite {
                VideoPlay.rssStar?.let { showDialogFragment(RssFavoritesDialog(it)) }
            }
            R.id.menu_rss_refresh -> {
                // 简化说明: refresh 通过 recreate 重启 Activity 重新加载 RSS 文章列表
                // 已知上限: 会重置播放进度等运行时状态
                // 升级路径: 后续可抽取 refreshRssArticles() 只重载列表不重启 Activity
                recreate()
            }
            R.id.menu_float_window -> startFloatingWindow()
            R.id.menu_config_settings -> showDialogFragment(SettingsDialog(this))
            R.id.menu_login -> VideoPlay.source?.let {s ->
               when (s) {
                    is BookSource -> {
                        startActivity<SourceLoginActivity> {
                            putExtra("bookType", BookType.video)
                        }
                    }
                    is RssSource -> {
                        startActivity<SourceLoginActivity> {
                            putExtra("type", "rssSource")
                            putExtra("key", s.getKey())
                        }
                    }
                }
            }

            R.id.menu_copy_video_url -> {
                val url = VideoPlay.videoUrl
                if (url.isNullOrBlank()){
                    this.toastOnUi("暂无播放地址")
                    return true
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
            R.id.menu_browser_open -> {
                // 浏览器打开：优先用视频URL，其次用 RSS 文章链接
                val url = VideoPlay.videoUrl
                    ?: VideoPlay.rssArticles?.getOrNull(VideoPlay.rssArticleIndex)?.link
                if (url.isNullOrBlank()) {
                    this.toastOnUi("暂无可用地址")
                } else {
                    openUrl(url)
                }
            }
            R.id.menu_open_other_video_player -> {
                val url = VideoPlay.videoUrl
                if (url.isNullOrBlank()){
                    this.toastOnUi("暂无播放地址")
                    return true
                }
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(url.toUri(), "video/*")
                }
                startActivity(intent)
            }
            R.id.menu_edit_source -> VideoPlay.source?.let {s  ->
                when (s) {
                    is BookSource -> bookSourceEditResult.launch {
                        putExtra("sourceUrl", s.getKey())
                    }
                    is RssSource -> rssSourceEditResult.launch {
                        putExtra("sourceUrl", s.getKey())
                    }
                }
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            // rss-unified-search: 弹出换源对话框
            R.id.menu_change_source -> showDialogFragment(ChangeRssArticleSourceDialog())
        }
        return super.onCompatOptionsItemSelected(item)
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

        observeEventSticky<String>(EventBus.VIDEO_SUB_TITLE) {
            if (useViewPagerMode) {
                binding.titleBarNew.title = it
                // R3 阶段2：同步更新当前 Fragment 的视频标题
                currentFragment?.updateVideoTitle(it)
            } else {
                binding.titleBarNew.title = it
            }
            // R3 修复：startPlay 异步赋值 videoUrl，VIDEO_SUB_TITLE 在每次 player.setUp 后触发，此时 videoUrl 已就绪
            // P0-1: 统一 ViewPager2 模式，updateVideoUrlDisplay 已移除
        }

        observeEvent<ArrayList<Int>>(EventBus.UP_VIDEO_INFO) {
            if (useViewPagerMode) {
                // 文章列表模式：文章数量不变，只需更新当前 Fragment 的集数/线路选择器
                if (!VideoPlay.rssArticles.isNullOrEmpty()) {
                    currentFragment?.updateEpisodeSelector()
                    binding.titleBarNew.title = VideoPlay.videoTitle ?: ""
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
                binding.titleBarNew.title = VideoPlay.rssEpisodes?.getOrNull(VideoPlay.rssEpisodeIndex)?.title
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
            appendDebugLog(it)
            if (!isFullScreen) {
                binding.debugPanel.visible()
            }
            // R3 阶段5：同步更新设置面板的调试日志
            settingsPanel?.appendDebugLog(it)
            // P0-1.4: 显示错误对话框，提供 WebView 降级选项（仅当存在建议时弹窗）
            showVideoPlayErrorDialog(it)
        }

    }

    /**
     * P0-1.4 / 1.7: 显示视频播放错误对话框（F1+F2: 四级降级链+决策日志）
     *
     * 四级降级链：
     * Level 1: ExoPlayer（默认，含 E2 网络错误自动重试）
     * Level 2: ExoPlayer 重试（用户手动，重置 retryCount）
     * Level 3: WebView + HLS.js（降级方案）
     * Level 4: 系统浏览器（最终兜底，适用于 mp4 等直链）
     *
     * 根据 playerType 决定行为：
     * - playerType=2 (WEB_VIEW): 自动降级到 WebView，不弹窗
     * - playerType=1 (EXO_PLAYER): 仅"重试"/"系统浏览器"/"取消"
     * - playerType=0 (AUTO): "WebView播放"/"重试"/"系统浏览器"（可按 Back 取消）
     *
     * F2 决策日志：每个降级转换点记录 AppLog.put（永久日志）
     * 防重复弹窗：若已有对话框显示中则跳过。
     */
    private fun showVideoPlayErrorDialog(errorInfo: String) {
        if (errorDialog?.isShowing == true) return
        // 无播放地址则不弹窗（仅记录日志）
        val url = VideoPlay.videoUrl ?: return
        val title = VideoPlay.videoTitle ?: ""
        // P0-1.7: WEB_VIEW 模式下自动降级，不弹窗
        if (VideoPlay.playerType == 2) {
            // F2 决策日志：Level 1→3 自动降级（WEB_VIEW 强制模式）
            AppLog.put("降级决策: ExoPlayer→WebView(auto, playerType=2), title=$title, urlLen=${url.length}")
            switchCurrentToWebView(url, title)
            return
        }
        // P0-1.7: EXO_PLAYER 强制模式(1) 不提供 WebView 选项；AUTO(0) 根据错误信息判断
        val canUseWebView = errorInfo.contains("WebView") && VideoPlay.playerType != 1
        errorDialog = alert(title = getString(R.string.video_play_error_title), message = errorInfo) {
            if (canUseWebView) {
                // AUTO 模式可降级：positive=WebView, neutral=重试, negative=系统浏览器（Back 可取消）
                positiveButton(getString(R.string.use_webview_play)) {
                    // F2 决策日志：Level 1→3 用户选择 WebView
                    AppLog.put("降级决策: ExoPlayer→WebView(user choice), title=$title, urlLen=${url.length}")
                    switchCurrentToWebView(url, title)
                }
                neutralButton(getString(R.string.retry)) {
                    // F2 决策日志：Level 2 用户手动重试
                    AppLog.put("降级决策: ExoPlayer 重试(user choice), title=$title")
                    retryCurrentPlayback()
                }
                negativeButton(getString(R.string.open_in_browser)) {
                    // F2 决策日志：Level 1→4 用户选择系统浏览器
                    AppLog.put("降级决策: ExoPlayer→系统浏览器(user choice), title=$title, urlLen=${url.length}")
                    openInSystemBrowser(url)
                }
            } else {
                // 不可降级（EXO_PLAYER 强制模式或无 "WebView" 建议的错误）
                positiveButton(getString(R.string.retry)) {
                    // F2 决策日志：Level 2 用户手动重试
                    AppLog.put("降级决策: ExoPlayer 重试(user choice), title=$title")
                    retryCurrentPlayback()
                }
                neutralButton(getString(R.string.open_in_browser)) {
                    // F2 决策日志：Level 1→4 用户选择系统浏览器
                    AppLog.put("降级决策: ExoPlayer→系统浏览器(user choice), title=$title, urlLen=${url.length}")
                    openInSystemBrowser(url)
                }
                negativeButton(getString(R.string.cancel)) { }
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

    /**
     * P0-1.4: 当前 Fragment 切换到 WebView 播放模式
     *
     * 由错误对话框"使用WebView播放"按钮调用。
     * 将当前 Fragment 切换为 WebView 播放（使用 skill V2 hls-video-player 模板）。
     */
    private fun switchCurrentToWebView(url: String, title: String) {
        val headers = VideoPlay.currentPlayHeaders ?: emptyMap()
        currentFragment?.switchToWebViewMode(url, title, headers)
    }

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
            alert(title = getString(R.string.add_to_bookshelf)) {
                setMessage(getString(R.string.check_add_bookshelf, book.name))
                okButton {
                    val book = VideoPlay.book
                    book?.removeType(BookType.notShelf)
                    lifecycleScope.launch(IO) {
                        book?.save()
                        withContext(Main) {
                            VideoPlay.inBookshelf = true
                            setResult(RESULT_OK)
                        }
                    }
                }
                noButton {
                    callBackBookEnd()
                    viewModel.removeFromBookshelf { super.finish() }
                }
            }
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
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // rss-unified-search: 清理换源 Holder，避免内存泄漏与跨文章串数据
        RssSearchSourceHolder.clear()
    }

    private fun destroyWeb() {
        pooledWebView?.let { WebViewPool.release(it) }
        pooledWebView = null
    }
}