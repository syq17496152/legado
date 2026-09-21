package io.legado.app.ui.rss.read

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.content.res.Configuration
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import androidx.lifecycle.lifecycleScope
import kotlin.math.abs
import kotlinx.coroutines.launch
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.size
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.constant.AppLog
import io.legado.app.databinding.ActivityRssReadBinding
import io.legado.app.help.WebCacheManager
import io.legado.app.help.source.SourceCacheManager
import io.legado.app.help.source.SourceContentFilter
import io.legado.app.help.source.SourceWebViewController
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieManager
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.rss.favorites.RssFavoritesDialog
import io.legado.app.ui.rss.search.ChangeRssArticleSourceDialog
import io.legado.app.ui.rss.search.RssSearchSourceHolder
import io.legado.app.utils.ACache
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.isTrue
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.textArray
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import org.apache.commons.text.StringEscapeUtils
import org.jsoup.Jsoup
import splitties.views.bottomPadding
import java.io.ByteArrayInputStream
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.rss.article.ReadRecordDialog
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.utils.StartActivityContract
import kotlinx.coroutines.runBlocking
import androidx.core.net.toUri
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.webView.WebJsExtensions.Companion.basicJs
import io.legado.app.help.webView.WebJsExtensions.Companion.nameBasic
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.SilentSslWebViewClient
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_INJECTION
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_URL
import io.legado.app.help.webView.WebJsExtensions.Companion.nameUrl
import io.legado.app.help.webView.WebViewPool
import io.legado.app.help.webView.WebViewPool.BLANK_HTML
import io.legado.app.help.webView.WebViewPool.DATA_HTML
import io.legado.app.model.Download
import kotlinx.coroutines.Dispatchers.IO
import java.lang.ref.WeakReference
import splitties.systemservices.powerManager
import java.net.URLDecoder
import androidx.core.graphics.createBitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.InlineTaskBar
import io.legado.app.ui.widget.components.InlineTaskState
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog

/**
 * rss阅读界面
 */
class ReadRssActivity : VMBaseActivity<ActivityRssReadBinding, ReadRssViewModel>(),
    RssFavoritesDialog.Callback {

    override val binding by viewBinding(ActivityRssReadBinding::inflate)
    override val viewModel by viewModels<ReadRssViewModel>()

    private lateinit var pooledWebView: PooledWebView
    private lateinit var currentWebView: WebView

    private var isFullscreen = false

    // Compose 顶栏状态（L-D6 S4 改造）：标题/收藏/朗读由 Compose 状态驱动
    private var composeTitle by mutableStateOf("")
    private var menuExpanded by mutableStateOf(false)
    private var starVisible by mutableStateOf(false)
    private var starChecked by mutableStateOf(false)
    private var ttsPlaying by mutableStateOf(false)
    // F146：顶栏收起态 / 完整高度 / 动画句柄（沉浸阅读）
    private var topBarCollapsed = false
    private var topBarFullHeight = 0
    private var topBarAnimator: ValueAnimator? = null
    private var immersiveStartY = 0f
    private var immersiveFired = false
    private var wasScreenOff = false
    private var customWebViewCallback: WebChromeClient.CustomViewCallback? = null
    private var interfaceInjected: String? = null
    private var needClearHistory = true
    private val selectImageDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ACache.get().put(imagePathKey, uri.toString())
            viewModel.saveImage(it.value, uri)
        }
    }
    private val rssJsExtensions by lazy { RssJsExtensions(this, viewModel.rssSource) }

    private val refreshNameList: MutableList<String> by lazy { mutableListOf() }
    private fun refresh() {
        if (viewModel.rssSource?.singleUrl == true) {
            currentWebView.reload()
            return
        }
        currentWebView.title?.let {
            refreshNameList.add(it)
        }
        viewModel.rssArticle?.let {
            start(this@ReadRssActivity,true, it.origin, it.title, it.link)
        } ?: run {
            viewModel.initData(intent)
        }
    }
    private val editSourceResult = registerForActivityResult(
        StartActivityContract(RssSourceEditActivity::class.java)
    ) {
        if (it.resultCode == RESULT_OK) {
            refresh()
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        pooledWebView = WebViewPool.acquire(this)
        currentWebView = pooledWebView.realWebView
        binding.webViewContainer.addView(currentWebView)
        viewModel.upStarMenuData.observe(this) { upStarMenu() }
        viewModel.upTtsMenuData.observe(this) { upTtsMenu(it) }
        viewModel.upTitleData.observe(this) { composeTitle = it }
        initComposeTopBar()
        initView()
        initWebView()
        initLiveData()
        viewModel.initData(intent)
        currentWebView.clearHistory()
        onBackPressedDispatcher.addCallback(this) {
            if (binding.customWebView.size > 0) { //关闭全屏
                customWebViewCallback?.onCustomViewHidden()
                return@addCallback
            }
            if (currentWebView.canGoBack()) {
                val list = currentWebView.copyBackForwardList() //获取历史列表
                val size = list.size
                if (size == 1) {
                    finish()
                    return@addCallback
                }
                val currentIndex = list.currentIndex
                val currentItem = list.currentItem
                val currentUrl = currentItem?.originalUrl ?: BLANK_HTML
                val currentTitle = currentItem?.title
                //从后往前找，找到第一个不同链接的页面，计算需要回退多少步 避免刷新后导致返回不灵
                var steps = 1
                for (i in currentIndex - 1 downTo 0) {
                    val item = list.getItemAtIndex(i)
                    val itemTitle = item.title
                    val index = refreshNameList.indexOf(itemTitle)
                    if (index != -1) {
                        refreshNameList.removeAt(index)
                        steps++
                        continue
                    }
                    val itemUrl = item.originalUrl
                    if (itemUrl == BLANK_HTML) {
                        finish()
                        return@addCallback
                    }
                    if (itemUrl != currentUrl || itemTitle != currentTitle) {
                        break
                    }
                    if (currentUrl == DATA_HTML) {
                        break
                    }
                    steps++
                }
                if (steps == size) {
                    finish()
                    return@addCallback
                }
                currentWebView.goBackOrForward(-steps)
                return@addCallback
            }
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        binding.progressBar.visible()
        binding.progressBar.setDurProgress(30)
        setIntent(intent)
        viewModel.initData(intent)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("SwitchIntDef")
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        when (newConfig.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }

            Configuration.ORIENTATION_PORTRAIT -> {
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                window.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
            }
        }
    }

    /**
     * Compose 顶栏（L-D6 S4 改造）：GlassTopAppBar + 刷新/收藏图标按钮 + MoreVert 下拉菜单
     */
    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                GlassTopAppBar(
                    title = composeTitle,
                    navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                    onNavClick = { finish() },
                    actions = {
                        IconButton(onClick = { refresh() }) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = getString(R.string.refresh)
                            )
                        }
                        if (starVisible) {
                            IconButton(onClick = {
                                viewModel.addFavorite()
                                viewModel.rssArticle?.let {
                                    showDialogFragment(RssFavoritesDialog(it))
                                }
                            }) {
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
                    },
                    secondRow = {
                        // F147：朗读是长任务，原实现的状态信号只有「菜单里图标 Stop 化」——
                        // 菜单不展开就看不见「还在读 / 从哪停」。改用共享 InlineTaskBar 常驻页内。
                        InlineTaskBar(
                            state = if (ttsPlaying) InlineTaskState.Running else InlineTaskState.Idle,
                            text = getString(R.string.rss_read_tts_running),
                            onCancel = { readAloud() },
                            actionLabel = getString(R.string.aloud_stop)
                        )
                    }
                )
            }
        }
    }

    /**
     * F2 落地第 4 处：8 项平铺 → 四组 + 组标题（`MenuAction(header = true)`）。
     *
     * 分组口径按**动作对象**而不是代码顺序：内容（对文章本身）/ 阅读工具（对阅读位置）/ 源（对来源）
     * / 工具（开发者入口）。本页是高频沉浸页，菜单出现即打断阅读，分组把扫视成本从 8 次降到 4+组内。
     */
    private fun buildMenuActions(): List<MenuAction> = buildList {
        fun addGroup(@androidx.annotation.StringRes titleRes: Int) {
            add(MenuAction(title = getString(titleRes), header = true, onClick = {}))
        }

        addGroup(R.string.rss_read_menu_group_content)
        add(
            MenuAction(
                icon = Icons.Filled.Share,
                title = getString(R.string.share),
                onClick = {
                    currentWebView.url?.let { share(it) }
                        ?: viewModel.rssArticle?.let { share(it.link) }
                        ?: toastOnUi(R.string.null_url)
                }
            )
        )
        add(
            MenuAction(
                icon = if (ttsPlaying) Icons.Filled.Stop else Icons.Filled.VolumeUp,
                title = getString(if (ttsPlaying) R.string.aloud_stop else R.string.read_aloud),
                onClick = { readAloud() }
            )
        )
        add(
            MenuAction(
                icon = Icons.Filled.OpenInBrowser,
                title = getString(R.string.open_in_browser),
                onClick = {
                    currentWebView.url?.let { openUrl(it) } ?: toastOnUi(R.string.null_url)
                }
            )
        )

        addGroup(R.string.rss_read_menu_group_reading)
        add(
            MenuAction(
                icon = Icons.Filled.History,
                title = getString(R.string.read_record),
                onClick = {
                    showDialogFragment(ReadRecordDialog(viewModel.rssSource?.sourceUrl))
                }
            )
        )
        // rss-unified-search: 仅当搜索结果多源场景（RssSearchSourceHolder.articles.size > 1）显示换源菜单
        if ((RssSearchSourceHolder.articles?.size ?: 0) > 1) {
            add(
                MenuAction(
                    icon = Icons.Filled.SwapVert,
                    title = getString(R.string.change_source),
                    onClick = { showDialogFragment(ChangeRssArticleSourceDialog()) }
                )
            )
        }

        addGroup(R.string.rss_read_menu_group_source)
        if (!viewModel.rssSource?.loginUrl.isNullOrBlank()) {
            add(
                MenuAction(
                    icon = Icons.Filled.Login,
                    title = getString(R.string.login),
                    onClick = {
                        startActivity<SourceLoginActivity> {
                            putExtra("type", "rssSource")
                            putExtra("key", viewModel.rssSource?.sourceUrl)
                        }
                    }
                )
            )
        }
        add(
            MenuAction(
                icon = Icons.Filled.Edit,
                title = getString(R.string.edit_source),
                onClick = {
                    viewModel.rssSource?.sourceUrl?.let {
                        editSourceResult.launch { putExtra("sourceUrl", it) }
                    }
                }
            )
        )

        addGroup(R.string.source_menu_group_tools)
        add(
            MenuAction(
                icon = Icons.Filled.Info,
                title = getString(R.string.log),
                onClick = { showDialogFragment<AppLogDialog>() }
            )
        )
    }

    override fun updateFavorite(title: String?, group: String?) {
        viewModel.rssArticle?.let {
            if (title != null) {
                it.title = title
            }
            if (group != null) {
                it.group = group
            }
        }
        viewModel.updateFavorite()
    }

    override fun deleteFavorite() {
        viewModel.delFavorite()
    }

    private fun initView() {
        binding.root.setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
            val typeMask = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            val insets = windowInsets.getInsets(typeMask)
            view.bottomPadding = insets.bottom
            windowInsets
        }
    }

    /**
     * F146：沉浸阅读——正文滚动收起/展开顶栏。
     *
     * 做法：**先动画 `translationY`、动画结束再落高度**（收起：0→-h 位移后把高度置 0；展开：先恢复
     * 高度并把位移摆到 -h，再动画回 0）。不逐帧改高度是因为 `compose_top_bar` 里是 ComposeView，
     * 逐帧测量会有掉帧风险；位移与高度在动画终态**视觉等价**（位移量恰等于高度），故落高度无跳变。
     * 布局零改动：`web_view_container` 是 `height=0dp` + 上下约束 ⇒ 顶栏高度变化时容器自动补位，
     * 不会出现底部空隙（这比整体平移 `ll_view` 更稳）。
     */
    private fun setTopBarCollapsed(collapsed: Boolean, animate: Boolean = true) {
        if (topBarCollapsed == collapsed) return
        val bar = binding.composeTopBar
        if (topBarFullHeight <= 0) topBarFullHeight = bar.height
        val h = topBarFullHeight.toFloat()
        if (h <= 0f) return
        // 沉浸联动是「只有真机能观察」的行为，保留低量级（仅状态跃迁）诊断日志便于现场定位
        AppLog.putDebugWithTag(
            TAG_RSS_IMMERSIVE,
            "topBar collapsed=$collapsed h=${topBarFullHeight}px",
            level = AppLog.Level.INFO
        )
        topBarCollapsed = collapsed
        topBarAnimator?.cancel()
        if (collapsed) {
            bar.visibility = View.VISIBLE
            bar.translationY = 0f
            if (!animate) {
                bar.visibility = View.GONE
                return
            }
            topBarAnimator = ValueAnimator.ofFloat(0f, -h).apply {
                duration = TOP_BAR_ANIM_MS
                addUpdateListener { bar.translationY = it.animatedValue as Float }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!topBarCollapsed) return
                        bar.translationY = 0f
                        // 「收起」用 GONE 表达而非高度置 0：ConstraintLayout 下 `height=0` 是
                        // **match_constraint** 语义，会被 Compose 内容重新撑开（真机实测仍为 132px）；
                        // GONE 则让下邻居按约束自然补位到顶（页面下方不留空隙）。
                        bar.visibility = View.GONE
                    }
                })
                start()
            }
        } else {
            bar.visibility = View.VISIBLE
            bar.translationY = -h
            if (!animate) {
                bar.translationY = 0f
                return
            }
            topBarAnimator = ValueAnimator.ofFloat(-h, 0f).apply {
                duration = TOP_BAR_ANIM_MS
                addUpdateListener { bar.translationY = it.animatedValue as Float }
                start()
            }
        }
    }

    /**
     * F146：正文滚动联动顶栏（下滚收起 / 上滚唤回）。
     *
     * 为什么用**触摸方向**而不是滚动监听：①WebView 的文档滚动在内部合成层完成，
     * `View.setOnScrollChangeListener`／`webView.scrollY` 恒不回调（真机实测：正文确实滚了、顶栏不动）
     * ②页面内 JS 钩子（`evaluateJavascript` + JS 桥）在**源关闭 JS**（`enableJs=0`）时静默失效，
     * 而本页正是「源站页面」——不能为了收顶栏去擅自打开 JS（会改变源站页面行为，实测桥接零回调）。
     * 触摸监听只**观察**事件（返回 false，WebView 行为零影响），按拖动位移方向决定收起/展开。
     */
    private fun initImmersiveScroll() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop * 2f
        currentWebView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    immersiveStartY = ev.y
                    immersiveFired = false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> immersiveFired = false

                MotionEvent.ACTION_MOVE -> {
                    // 一次手势**只作一次判定**（用「相对 ACTION_DOWN 的净位移」而非逐事件增量）：
                    // 逐事件判向会被滑动抖动反复触发（真机实测一次滑动顶栏来回切换 628 次）；
                    // 净位移在拖动过程中即可越阈 ⇒ 仍是实时反馈，但同一手势内不再反复。
                    val total = immersiveStartY - ev.y
                    if (!immersiveFired && total > slop) {
                        setTopBarCollapsed(true)
                        immersiveFired = true
                    } else if (!immersiveFired && total < -slop) {
                        setTopBarCollapsed(false)
                        immersiveFired = true
                    }
                }
            }
            false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        binding.progressBar.fontColor = accentColor
        currentWebView.webChromeClient = CustomWebChromeClient()
        //添加屏幕方向控制，网页关闭，openUI
        currentWebView.addJavascriptInterface(JSInterface(this), nameBasic)
        currentWebView.webViewClient = CustomWebViewClient()
        // F146：沉浸滚动（触摸方向观察，不消费事件）
        initImmersiveScroll()
        currentWebView.setOnLongClickListener {
            val hitTestResult = currentWebView.hitTestResult
            if (hitTestResult.type == WebView.HitTestResult.IMAGE_TYPE ||
                hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                hitTestResult.extra?.let { webPic ->
                    showComposeChoiceListDialog(
                        "",
                        listOf(
                            getString(R.string.action_save),
                            getString(R.string.select_folder)
                        )
                    ) { index ->
                        when (index) {
                            0 -> saveImage(webPic)
                            1 -> selectSaveFolder(null)
                        }
                    }
                    return@setOnLongClickListener true
                }
            }
            return@setOnLongClickListener false
        }
        currentWebView.setDownloadListener { url, _, contentDisposition, _, _ ->
            var fileName = URLUtil.guessFileName(url, contentDisposition, null)
            fileName = URLDecoder.decode(fileName, "UTF-8")
            currentWebView.longSnackbar(fileName, getString(R.string.action_download)) {
                Download.start(this, url, fileName)
            }
        }
    }

    private fun saveImage(webPic: String) {
        val path = ACache.get().getAsString(imagePathKey)
        if (path.isNullOrEmpty()) {
            selectSaveFolder(webPic)
        } else {
            viewModel.saveImage(webPic, path.toUri())
        }
    }

    private fun selectSaveFolder(webPic: String?) {
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(imagePathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        selectImageDir.launch {
            otherActions = default
            value = webPic
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initLiveData() {
        viewModel.contentLiveData.observe(this) { content ->
            viewModel.rssArticle?.let {
                upWebviewSettings()
                initJavascriptInterface()
                val rssSource = viewModel.rssSource
                val html = viewModel.clHtml(content, rssSource?.style)
                val url = NetworkUtils.getAbsoluteURL(it.origin, it.link).substringBefore("@js")
                val baseUrl = if (rssSource?.loadWithBaseUrl == false) null else url
                currentWebView.loadDataWithBaseURL(
                    baseUrl, html, "text/html", "utf-8", url
                )
            }
        }
        viewModel.urlLiveData.observe(this) { urlState ->
            upWebviewSettings(urlState.getUserAgent())
            initJavascriptInterface()
            CookieManager.applyToWebView(urlState.url)
            currentWebView.loadUrl(urlState.url, urlState.headerMap)
        }
        viewModel.htmlLiveData.observe(this) { html ->
            viewModel.rssSource?.let {
                upWebviewSettings()
                initJavascriptInterface()
                val baseUrl = if (it.loadWithBaseUrl) it.sourceUrl else null
                currentWebView.loadDataWithBaseURL(
                    baseUrl, html, "text/html", "utf-8", it.sourceUrl
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun upWebviewSettings(userAgent: String? = null) {
        viewModel.rssSource?.let { s ->
            currentWebView.settings.run {
                userAgentString = userAgent ?: viewModel.headerMap[AppConst.UA_NAME] ?: AppConfig.userAgent
                javaScriptEnabled = s.enableJs
                cacheMode = if (SourceCacheManager.isCacheFirst(s)) WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_DEFAULT
            }
        }
    }

    private fun initJavascriptInterface() {
        viewModel.rssSource?.let {
            if (interfaceInjected != it.sourceUrl) {
                interfaceInjected = it.sourceUrl
                if (!viewModel.hasPreloadJs) return
                val webJsExtensions = WebJsExtensions(it, this, currentWebView)
                currentWebView.addJavascriptInterface(webJsExtensions, nameJava)
                currentWebView.addJavascriptInterface(it, nameSource)
                currentWebView.addJavascriptInterface(WebCacheManager, nameCache)
            }
        }
    }

    private fun upStarMenu() {
        starVisible = viewModel.rssArticle != null
        starChecked = viewModel.rssStar != null
    }

    private fun upTtsMenu(isPlaying: Boolean) {
        ttsPlaying = isPlaying
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun readAloud() {
        if (viewModel.tts?.isSpeaking == true) {
            viewModel.tts?.stop()
            upTtsMenu(false)
        } else {
            currentWebView.settings.javaScriptEnabled = true
            currentWebView.evaluateJavascript("document.documentElement.outerHTML") {
                val html = StringEscapeUtils.unescapeJson(it).replace("^\"|\"$".toRegex(), "")
                viewModel.readAloud(
                    Jsoup.parse(html).textArray().joinToString("\n")
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (powerManager.isInteractive) {
            wasScreenOff = false
            currentWebView.onPause()
        } else {
            wasScreenOff = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (!wasScreenOff) {
            currentWebView.onResume()
        }
    }

    override fun onDestroy() {
        WebViewPool.release(pooledWebView)
        // rss-unified-search: 清理换源 Holder，避免内存泄漏与跨文章串数据
        RssSearchSourceHolder.clear()
        super.onDestroy()
    }


    @Suppress("unused")
    private class JSInterface(activity: ReadRssActivity) {
        private val activityRef: WeakReference<ReadRssActivity> = WeakReference(activity)
        @JavascriptInterface
        fun lockOrientation(orientation: String) {
            val ctx = activityRef.get()
            if (ctx != null && ctx.isFullscreen && !ctx.isFinishing && !ctx.isDestroyed) {
                ctx.runOnUiThread {
                    ctx.requestedOrientation = when (orientation) {
                        "portrait", "portrait-primary" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        "portrait-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                        "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE //横屏且受重力控制正反
                        "landscape-primary" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE //正向横屏
                        "landscape-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE //反向横屏
                        "any", "unspecified" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
            }
        }

        @JavascriptInterface
        fun onCloseRequested() {
            val ctx = activityRef.get()
            if (ctx != null && !ctx.isFinishing && !ctx.isDestroyed) {
                ctx.runOnUiThread {
                    ctx.finish()
                }
            }
        }
    }

    inner class CustomWebChromeClient : WebChromeClient() {
        override fun getDefaultVideoPoster(): Bitmap {
            return super.getDefaultVideoPoster() ?: createBitmap(100, 100)
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            binding.progressBar.setDurProgress(newProgress)
            binding.progressBar.gone(newProgress == 100)
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            isFullscreen = true
            binding.llView.invisible()
            binding.customWebView.addView(view)
            customWebViewCallback = callback
            keepScreenOn(true)
            toggleSystemBar(false)
            if (viewModel.rssSource?.enableJs == false) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            }
        }

        override fun onHideCustomView() {
            isFullscreen = false
            binding.customWebView.removeAllViews()
            binding.llView.visible()
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            keepScreenOn(false)
            toggleSystemBar(true)
            // F146：全屏视频退出后顶栏必须复位（否则「看不到工具栏」会被当成页面卡住）
            setTopBarCollapsed(false, animate = false)
        }

        /* 覆盖window.close() */
        override fun onCloseWindow(window: WebView?) {
            finish()
        }

        /* 监听网页日志 */
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            viewModel.rssSource?.let { source ->
                if (source.showWebLog) {
                    val messageLevel = consoleMessage.messageLevel().name
                    val message = consoleMessage.message()
                    AppLog.put("${source.getTag()}${messageLevel}: $message",
                        NoStackTraceException("\n${message}\n- Line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}"))
                    return true
                }
            }
            return false
        }
    }

    inner class CustomWebViewClient : SilentSslWebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView, request: WebResourceRequest
        ): Boolean {
            return shouldOverrideUrlLoading(request.url)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            return shouldOverrideUrlLoading(url.toUri())
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (needClearHistory) {
                needClearHistory = false
                currentWebView.clearHistory() //清除历史
            }
            // F146：新页面从顶部开始 ⇒ 顶栏复位，避免带着上一页的收起态进入新文章
            setTopBarCollapsed(false, animate = false)
            super.onPageStarted(view, url, favicon)
            currentWebView.evaluateJavascript(basicJs, null)
        }

        private var jsInjected = false
        /**
         * 如果有黑名单,黑名单匹配返回空白,
         * 没有黑名单再判断白名单,在白名单中的才通过,
         * 都没有不做处理
         */
        override fun shouldInterceptRequest(
            view: WebView, request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url.toString()
            val source = viewModel.rssSource ?: return super.shouldInterceptRequest(view, request)
            if (request.isForMainFrame) {
                if (viewModel.hasPreloadJs) {
                    jsInjected = false
                    if (url.startsWith("data:text/html;") || request.method == "POST") {
                        return super.shouldInterceptRequest(view, request)
                    }
                    return runBlocking(IO) {
                        getModifiedContentWithJs(url, request) ?: super.shouldInterceptRequest(view, request)
                    }
                }
            } else if (!jsInjected && url == nameUrl) {
                jsInjected = true
                val preloadJs = source.preloadJs ?: ""
                return WebResourceResponse(
                    "text/javascript",
                    "utf-8",
                    ByteArrayInputStream("(() => {$JS_INJECTION\n$preloadJs\n})();".toByteArray())
                )
            }
            // M2 SourceContentFilter 统一 WebView 资源过滤（抽取原有黑名单/白名单逻辑为共享组件）
            if (!SourceContentFilter.filterUrl(url, source)) {
                return createEmptyResource()
            }
            return super.shouldInterceptRequest(view, request)
        }

        private suspend fun getModifiedContentWithJs(url: String, request: WebResourceRequest): WebResourceResponse? {
            try {
                val cookie = webCookieManager.getCookie(url)
                val res = okHttpClient.newCallResponse {
                    url(url)
                    method(request.method, null)
                    if (!cookie.isNullOrEmpty()) {
                        addHeader("Cookie", cookie)
                    }
                    request.requestHeaders?.forEach { (key, value) ->
                        addHeader(key, value)
                    }
                }
                res.headers("Set-Cookie").forEach { setCookie ->
                    webCookieManager.setCookie(url, setCookie)
                }
                val body = res.body
                val contentType = body.contentType()
                val mimeType = contentType?.toString()?.substringBefore(";") ?: "text/html"
                val charset = contentType?.charset() ?: Charsets.UTF_8
                val charsetSre = charset.name()
                val bodyText = body.text().let { originalText ->
                    val headIndex = originalText.indexOf("<head", ignoreCase = true)
                    if (headIndex >= 0) {
                        val closingHeadIndex = originalText.indexOf('>', startIndex = headIndex)
                        if (closingHeadIndex >= 0) {
                            val insertPos = closingHeadIndex + 1
                            StringBuilder(originalText).insert(insertPos, JS_URL).toString()
                        } else {
                            originalText
                        }
                    } else {
                        originalText
                    }
                }
                return WebResourceResponse(
                    mimeType,
                    charsetSre,
                    ByteArrayInputStream(bodyText.toByteArray(charset))
                )
            } catch (_: Exception) {
                return null
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            view.title?.let { title ->
                if (title != url
                    && title != view.url
                    && title.isNotBlank()
                    && url != BLANK_HTML
                    && !url.contains(title)) {
                    composeTitle = title
                } else {
                    composeTitle = viewModel.upTitleData.value ?: ""
                }
            }
            viewModel.rssSource?.let { source ->
                SourceWebViewController.getInjectJs(source)?.let {
                    if (it.isNotBlank()) {
                        view.evaluateJavascript(it, null)
                    }
                }
            }
        }

        private fun createEmptyResource(): WebResourceResponse {
            return WebResourceResponse(
                "text/plain", "utf-8", ByteArrayInputStream("".toByteArray())
            )
        }

        private fun shouldOverrideUrlLoading(url: Uri): Boolean {
            viewModel.rssSource?.let { source ->
                source.shouldOverrideUrlLoading?.takeUnless(String::isNullOrBlank)?.let { js ->
                    val startTime = SystemClock.uptimeMillis()
                    val result = runCatching {
                        runScriptWithContext(lifecycleScope.coroutineContext) {
                            source.evalJS(js) {
                                put("java", rssJsExtensions)
                                put("url", url.toString())
                            }.toString()
                        }
                    }.onFailure {
                        AppLog.put("${source.getTag()}: url跳转拦截js出错", it)
                    }.getOrNull()
                    if (SystemClock.uptimeMillis() - startTime > 99) {
                        AppLog.put("${source.getTag()}: url跳转拦截js执行耗时过长")
                    }
                    if (result.isTrue()) return true
                }
            }
            return handleCommonSchemes(url)
        }

        private fun handleCommonSchemes(url: Uri): Boolean {
            return when (url.scheme) {
                "http", "https" -> false
                "legado", "yuedu" -> {
                    startActivity<OnLineImportActivity> { data = url }
                    true
                }

                else -> {
                    binding.root.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                        openUrl(url)
                    }
                    true
                }
            }
        }
    }

    companion object {
        // F146：顶栏收起动画时长与诊断 tag
        private const val TOP_BAR_ANIM_MS = 180L
        private const val TAG_RSS_IMMERSIVE = "RssReadImmersive"

        fun start(context: Context, singleTop: Boolean, origin: String, title: String? = null, url: String? = null, startHtml: String? = null) {
            context.startActivity<ReadRssActivity> {
                putExtra("origin", origin)
                putExtra("title", title)
                putExtra("openUrl", url)
                putExtra("startHtml", startHtml)
                if (singleTop) {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            }
        }

        /**
         * 知晓rssArticle的打开
         */
        fun start(context: Context, origin: String, title: String?, link: String, sort: String) {
            context.startActivity<ReadRssActivity> {
                putExtra("origin", origin)
                putExtra("title", title)
                putExtra("link", link)
                putExtra("sort", sort)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) //栈顶复用
            }
        }

        private val webCookieManager by lazy { android.webkit.CookieManager.getInstance() }
    }

}
