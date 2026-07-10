package io.legado.app.ui.video

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.textclassifier.TextClassifier
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.AdapterView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.shuyu.gsyvideoplayer.listener.GSYSampleCallBack
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
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
    SettingsDialog.CallBack,RssFavoritesDialog.Callback {
    override val binding by viewBinding(ActivityVideoPlayerBinding::inflate)
    override val viewModel by viewModels<VideoPlayerViewModel>()
    private val playerView: VideoPlayer by lazy { binding.playerView }
    private var starMenuItem: MenuItem? = null
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
    private var isFullScreen = false
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
        playerView.enlargeImageRes = R.drawable.ic_fullscreen
        isNew = intent.getBooleanExtra("isNew", true)
        if (isNew) {
            intent.getStringExtra("videoUrl")?.let {
                VideoPlay.videoUrl = it
                VideoPlay.singleUrl = true
            }
            intent.getStringExtra("videoTitle")?.let {
                binding.titleBar.title = it
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
                VideoPlay.startPlay(playerView)
                VideoPlay.saveRead()
            }
        } else {
            VideoPlay.clonePlayState(playerView)
            playerView.setSurfaceToPlay()
            playerView.startAfterPrepared()
            binding.titleBar.title = VideoPlay.videoTitle
        }
        setupPlayerView()
        initView()
        upView()
        onBackPressedDispatcher.addCallback(this) {
            if (isFullScreen) {
                toggleFullScreen()
                return@addCallback
            }
            finish()
        }
    }

    private fun initView() {
        viewModel.upStarMenuData.observe(this) { upStarMenu() }
        binding.root.setBackgroundColor(backgroundColor)
        val book = VideoPlay.book
        if (book == null) {
            binding.data.invisible()
            // R3 布局学习：订阅源功能区显示
            binding.rssVideoPanel.visible()
            setupRssVideoPanel()
            // R1 多集选择播放：订阅源多集列表显示
            // startPlay 异步解析 ruleContent，rssEpisodes 可能在 initView 后才就绪
            // 就绪后通过 UP_VIDEO_INFO 事件触发 showRssEpisodes
            val rssRoutes = VideoPlay.rssRoutes
            val rssEpisodes = VideoPlay.rssEpisodes
            if (!rssRoutes.isNullOrEmpty()) {
                // R3 多线路支持：显示线路+集数
                showRssRoutes(rssRoutes)
            } else if (!rssEpisodes.isNullOrEmpty()) {
                binding.chaptersContainer.visible()
                binding.chapters.visible()
                showRssEpisodes(rssEpisodes)
            } else {
                binding.chaptersContainer.invisible()
            }
            return
        }
        showBook(book)
        if (VideoPlay.episodes.isNullOrEmpty()) {
            binding.chapters.gone()
        } else {
            binding.chapters.visible()
            showToc(VideoPlay.episodes!!)
        }
        if (VideoPlay.volumes.isEmpty()) {
            binding.volumes.gone()
        } else {
            binding.volumes.visible()
            showVolumes(VideoPlay.volumes)
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

    private fun toggleFullScreen() {
        isFullScreen = !isFullScreen
        toggleSystemBar(!isFullScreen)
        if (isFullScreen) {
            orientation = requestedOrientation
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
        return super.onPrepareOptionsMenu(menu)
    }

    private fun upStarMenu() {
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
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun startFloatingWindow() {
        VideoPlay.savePlayState(playerView)
        // 启动悬浮窗服务
        val intent = Intent(this, VideoPlayService::class.java).apply {
            putExtra("isNew", false)
        }
        ContextCompat.startForegroundService(this, intent)
        playerView.needDestroy = false
        finish() //如果在播放器复刻前活动被销毁，会导致状态继承异常（这里服务创建很快，没发现异常）
    }

    override fun observeLiveBus() {

        observeEventSticky<String>(EventBus.VIDEO_SUB_TITLE) {
            binding.titleBar.title = it
            // R3 修复：startPlay 异步赋值 videoUrl，VIDEO_SUB_TITLE 在每次 player.setUp 后触发，此时 videoUrl 已就绪
            updateVideoUrlDisplay()
        }

        observeEvent<ArrayList<Int>>(EventBus.UP_VIDEO_INFO) {
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

        observeEvent<String>(EventBus.VIDEO_PLAY_ERROR) {
            appendDebugLog(it)
            if (!isFullScreen) {
                binding.debugPanel.visible()
            }
        }

    }

    override fun finish() {
        val book = VideoPlay.book ?: return super.finish()
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
                val currentPlayer = playerView.getCurrentPlayer()
                if (currentPlayer.currentState == GSYVideoView.CURRENT_STATE_PLAYING) {
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
        destroyWeb()
        super.onDestroy()
        if (initGetter) {
            glideImageGetter.clear()
        }
        VideoPlay.saveRead()
        VideoPlay.stopLoading()
        playerView.getCurrentPlayer().release()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun destroyWeb() {
        pooledWebView?.let { WebViewPool.release(it) }
        pooledWebView = null
    }
}