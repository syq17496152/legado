package io.legado.app.ui.browser

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.core.view.size
import io.legado.app.R
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.databinding.ActivityWebViewBinding
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.help.http.CookieStore
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.widget.compose.AppUiTokens
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.ACache
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.snackbar
import splitties.init.appCtx
import io.legado.app.utils.startActivity
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import io.legado.app.constant.AppLog
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.basicJs
import io.legado.app.help.webView.WebJsExtensions.Companion.nameBasic
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.http.CookieManager as AppCookieManager
import androidx.core.net.toUri
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebViewPool
import io.legado.app.help.webView.WebViewPool.BLANK_HTML
import io.legado.app.model.Download
import splitties.systemservices.powerManager
import java.lang.ref.WeakReference
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import androidx.core.graphics.createBitmap
import io.legado.app.help.WebCacheManager
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache

class WebViewActivity : VMBaseActivity<ActivityWebViewBinding, WebViewModel>() {
    companion object {
        // 是否输出日志
        var sessionShowWebLog = false

        // F215 一次性引导条的页面本地记忆位（来源类型为源验证，属低频关键路径）
        private const val GUIDE_PREFS = "web_view_guide"
        private const val KEY_VERIFICATION_GUIDE_SHOWN = "source_verification_guide_shown"
        // 引导条「本次已显示」状态的实例态键（配置变更重建时恢复，避免重建后引导条消失）
        private const val KEY_GUIDE_BAR_VISIBLE = "guide_bar_visible"

        // F214 栈顶退出二次确认窗口（毫秒）
        private const val BACK_EXIT_INTERVAL = 2000L
    }

    private lateinit var pooledWebView: PooledWebView
    private lateinit var currentWebView: WebView

    override val binding by viewBinding(ActivityWebViewBinding::inflate)
    override val viewModel by viewModels<WebViewModel>()
    private var customWebViewCallback: WebChromeClient.CustomViewCallback? = null
    private var webPic: String? = null
    private var isCloudflareChallenge = false
    private var isFullScreen = false
    private var isfullscreen = false
    private var wasScreenOff = false
    private var needClearHistory = true
    private var menuExpanded by mutableStateOf(false)
    private var titleState by mutableStateOf("")
    private var subtitleState by mutableStateOf<String?>(null)
    private var webLogChecked by mutableStateOf(sessionShowWebLog)
    // 证书放行策略勾选态（默认放行）：与登录页共用同一全局策略，见 AppConfig.sslCertPassThrough
    private var sslPassThroughChecked by mutableStateOf(AppConfig.sslCertPassThrough)
    // F215：验证模式一次性引导条可见性（显示即记忆）
    private var guideBarVisible by mutableStateOf(false)
    // F215：Cloudflare 挑战期状态提示（挑战页加载完成时置位，非挑战页复位）
    private var challengeChecking by mutableStateOf(false)
    // 验证模式取自 Intent（viewModel 侧字段在 initData 协程内赋值，早于它读取会拿到默认值）
    private var verificationMode = false
    // F214：上次返回键按下时刻（栈顶二次确认窗口）
    private var lastBackPressedTime = 0L
    private val saveImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ACache.get().put(imagePathKey, uri.toString())
            viewModel.saveImage(webPic, uri.toString())
        }
    }

    private fun refresh() {
        currentWebView.reload()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        pooledWebView = WebViewPool.acquire(this)
        currentWebView = pooledWebView.realWebView
        binding.webViewContainer.addView(currentWebView)
        currentWebView.post {
            currentWebView.clearHistory()
        }
        titleState = intent.getStringExtra("title") ?: getString(R.string.loading)
        subtitleState = intent.getStringExtra("sourceName")
        verificationMode = intent.getBooleanExtra("sourceVerificationEnable", false)
        // 配置变更（日夜/密度等）会让本页重建：此时必须**恢复**引导条的显隐，
        // 否则重建后的实例读到刚写入的一次性记忆位 ⇒ 引导条凭空消失（真机实测）
        if (savedInstanceState != null &&
            savedInstanceState.containsKey(KEY_GUIDE_BAR_VISIBLE)
        ) {
            guideBarVisible = savedInstanceState.getBoolean(KEY_GUIDE_BAR_VISIBLE)
        } else {
            initVerificationGuide()
        }
        initComposeTopBar()
        initNoticeBar()
        viewModel.initData(intent) {
            val url = viewModel.baseUrl
            val headerMap = viewModel.headerMap
            initWebView(url, headerMap)
            val html = viewModel.html
            if (html.isNullOrEmpty()) {
                currentWebView.loadUrl(url, headerMap)
            } else {
                if (viewModel.localHtml) {
                    viewModel.source?.let {
                        val webJsExtensions = WebJsExtensions(it, this, currentWebView)
                        currentWebView.addJavascriptInterface(webJsExtensions, nameJava)
                    }
                    currentWebView.addJavascriptInterface(WebCacheManager, nameCache)
                }
                currentWebView.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
            }
        }
        currentWebView.clearHistory()
        onBackPressedDispatcher.addCallback(this) {
            if (binding.customWebView.size > 0) { //网页全屏
                customWebViewCallback?.onCustomViewHidden()
                return@addCallback
            }
            if (isFullScreen) { //按钮全屏
                toggleFullScreen()
                return@addCallback
            }
            // F214 返回可预期化：可回退时**一律一次一页**——`goBack()` 单步后退，替代原实现
            // 「按 URL 或标题变化才计步」的合并启发式（SPA/重定向站点 URL 常不变 ⇒ steps 一路
            // 累加到 steps==size 直接 finish，用户按一次返回整站消失）。
            // 可回退判定用标准 API `canGoBack()`：实测个别 ROM（MEmu+Chrome116）即使
            // copyBackForwardList 里仍有后退项，canGoBack() 也恒 false 且 goBack() 无效——
            // 那种环境下本分支不可达，行为自然落到下面的「栈顶二次确认」，不会卡死。
            if (currentWebView.canGoBack()) {
                val list = currentWebView.copyBackForwardList()
                val target = list.currentIndex - 1
                val targetUrl = if (target >= 0) list.getItemAtIndex(target)?.originalUrl else null
                if (targetUrl == BLANK_HTML) {
                    // 保留原 BLANK_HTML 终止语义：后退落点是复用池的空白预载页 ⇒ 直接关闭，
                    // 不让用户看到一张空白页
                    finish()
                    return@addCallback
                }
                currentWebView.goBack()
                return@addCallback
            }
            // F214 栈顶二次确认：退出不可逆，需要缓冲（2 秒内再按一次才关闭）
            val now = SystemClock.elapsedRealtime()
            if (now - lastBackPressedTime < BACK_EXIT_INTERVAL) {
                finish()
                return@addCallback
            }
            lastBackPressedTime = now
            binding.root.snackbar(getString(R.string.webview_back_again_exit))
        }
    }

    /**
     * F215 验证模式一次性引导条：验证模式下 ✓ 的语义与平时不同（保存验证结果并关闭），
     * 页面本身无任何在场说明 ⇒ 首次进入显示引导条，**显示即记忆**（不重复出现）。
     * 克制度：不做常驻模式徽标（会挤占本就局促的顶栏，且验证一屏内即可完成）。
     */
    private fun initVerificationGuide() {
        if (!verificationMode) return
        val guidePrefs = getSharedPreferences(GUIDE_PREFS, MODE_PRIVATE)
        if (!guidePrefs.getBoolean(KEY_VERIFICATION_GUIDE_SHOWN, false)) {
            guidePrefs.edit().putBoolean(KEY_VERIFICATION_GUIDE_SHOWN, true).apply()
            guideBarVisible = true
        }
    }

    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                GlassTopAppBar(
                    title = titleState,
                    navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                    onNavClick = { finish() },
                    actions = {
                        // 常驻快捷按钮：刷新 / 完成
                        IconButton(onClick = { refresh() }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null)
                        }
                        IconButton(onClick = { onClickOk() }) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                        // 溢出菜单
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = null)
                            }
                            AppDropdownMenu(
                                expanded = menuExpanded,
                                onDismiss = { menuExpanded = false },
                                actions = buildMenuActions(AppUiTokens.danger)
                            )
                        }
                    }
                )
            }
        }
    }

    /**
     * F215 顶栏下引导/状态条（**纯 View 实现**）：文案与可见性都走 View API。
     * 用 Compose 实现时 ComposeView 处于 `gone` 不参与遍历，组合内容与其内的可见性写入在
     * 「初始即应显示」路径上不可靠（真机多轮实测：挑战期条会出现、初始引导条不出现）；
     * View 可见性变化本身即可靠触发父容器重排，网页区随之让位。
     */
    private fun initNoticeBar() {
        val accent = accentColor
        binding.noticeBar.setBackgroundColor(
            ColorUtils.blendColors(this.bottomBackground, accent, 0.12f)
        )
        binding.noticeText.setTextColor(this.primaryTextColor)
        binding.noticeIcon.setColorFilter(accent)
        binding.noticeClose.setColorFilter(this.secondaryTextColor)
        binding.noticeClose.setOnClickListener { onNoticeClose() }
        updateNoticeBar()
    }

    private fun updateNoticeBar() {
        val text = when {
            challengeChecking -> getString(R.string.source_verification_checking)
            guideBarVisible -> getString(R.string.source_verification_guide)
            else -> null
        }
        if (text == null) {
            binding.noticeBar.gone()
            return
        }
        binding.noticeText.text = text
        if (challengeChecking) {
            binding.noticeClose.gone()          // 挑战期条不可关闭（瞬态系统状态，给关闭是假选择）
        } else {
            binding.noticeClose.visible()
        }
        binding.noticeBar.visible()
    }

    private fun onNoticeClose() {
        guideBarVisible = false
        updateNoticeBar()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_GUIDE_BAR_VISIBLE, guideBarVisible)
    }

    private fun onClickOk() {
        if (viewModel.sourceVerificationEnable) {
            viewModel.saveVerificationResult(currentWebView) {
                finish()
            }
        } else {
            finish()
        }
    }

    /** [danger] 为菜单**破坏性项**的语义色（单源 [AppUiTokens.danger]，禁止在此写色值） */
    private fun buildMenuActions(danger: Color): List<MenuAction> = buildList {
        // 浏览器打开 / 复制 URL
        add(
            MenuAction(
                icon = Icons.Outlined.OpenInBrowser,
                title = getString(R.string.open_in_browser),
                onClick = { openUrl(viewModel.baseUrl) }
            )
        )
        add(
            MenuAction(
                icon = Icons.Outlined.ContentCopy,
                title = getString(R.string.copy_url),
                onClick = { sendToClip(viewModel.baseUrl) }
            )
        )
        // 全屏
        add(
            MenuAction(
                icon = Icons.Outlined.Fullscreen,
                title = getString(R.string.full_screen),
                onClick = { toggleFullScreen() }
            )
        )
        // 网页日志（勾选态）
        add(
            MenuAction(
                icon = Icons.Outlined.Web,
                title = getString(R.string.show_web_log),
                checked = webLogChecked,
                onClick = {
                    webLogChecked = !webLogChecked
                    sessionShowWebLog = webLogChecked
                }
            )
        )
        // 证书放行策略（勾选态，**默认放行**）：类爬虫场景源站自签名/过期证书极常见，
        // 默认拦截会把大量源直接判死；此处提供关闭入口，关闭后证书失败改为逐次知情确认。
        add(
            MenuAction(
                icon = Icons.Outlined.Lock,
                title = getString(R.string.ssl_passthrough),
                checked = sslPassThroughChecked,
                onClick = {
                    sslPassThroughChecked = !sslPassThroughChecked
                    appCtx.putPrefBoolean(PreferKey.sslCertPassThrough, sslPassThroughChecked)
                    binding.root.snackbar(
                        if (sslPassThroughChecked) {
                            R.string.ssl_passthrough_on_hint
                        } else {
                            R.string.ssl_passthrough_off_hint
                        }
                    )
                }
            )
        )
        // 源操作（仅源验证模式可见）
        if (viewModel.sourceOrigin.isNotEmpty()) {
            // 修复2/3（合规缺口·原「禁用源」与「删除源」同菜单且无确认直接执行、无危险标识）：
            // 破坏性梯度对齐——两者补 danger tint；禁用源补二次确认（影响面说明 + 红色确认键）
            add(
                MenuAction(
                    icon = Icons.Outlined.Delete,
                    tint = danger,
                    title = getString(R.string.disable_source),
                    onClick = {
                        showComposeConfirmDialog(
                            title = getString(R.string.disable_source),
                            message = getString(
                                R.string.disable_source_confirm,
                                viewModel.sourceName
                            ),
                            positiveText = getString(R.string.yes),
                            negativeText = getString(R.string.no),
                            dangerPositive = true,
                            onPositive = {
                                viewModel.disableSource {
                                    finish()
                                }
                            }
                        )
                    }
                )
            )
            add(
                MenuAction(
                    icon = Icons.Outlined.DeleteForever,
                    tint = danger,
                    title = getString(R.string.delete_source),
                    onClick = {
                        showComposeConfirmDialog(
                            title = getString(R.string.draw),
                            message = getString(R.string.sure_del) + "\n" + viewModel.sourceName,
                            positiveText = getString(R.string.yes),
                            negativeText = getString(R.string.no),
                            dangerPositive = true,
                            onPositive = {
                                viewModel.deleteSource {
                                    finish()
                                }
                            }
                        )
                    }
                )
            )
        }
    }

    //实现starBrowser调起页面全屏
    private fun toggleFullScreen() {
        isFullScreen = !isFullScreen
        toggleSystemBar(!isFullScreen)
        if (isFullScreen) {
            supportActionBar?.hide()
        } else {
            supportActionBar?.show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(url: String, headerMap: HashMap<String, String>) {
        binding.progressBar.fontColor = accentColor
        currentWebView.webChromeClient = CustomWebChromeClient()
        // 添加 JavaScript 接口
        currentWebView.addJavascriptInterface(JSInterface(this), nameBasic)
        currentWebView.webViewClient = CustomWebViewClient()
        currentWebView.settings.apply {
            useWideViewPort = true
            loadWithOverviewMode = true
            headerMap[AppConst.UA_NAME]?.let {
                userAgentString = it
            }
        }
        AppCookieManager.applyToWebView(url)
        currentWebView.setOnLongClickListener {
            val hitTestResult = currentWebView.hitTestResult
            if (hitTestResult.type == WebView.HitTestResult.IMAGE_TYPE ||
                hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                hitTestResult.extra?.let { webPic ->
                    showComposeActionListDialog(
                        title = "",
                        labels = listOf(
                            getString(R.string.action_save),
                            getString(R.string.select_folder)
                        )
                    ) { index ->
                        when (index) {
                            0 -> saveImage(webPic)
                            1 -> selectSaveFolder()
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
        this.webPic = webPic
        val path = ACache.get().getAsString(imagePathKey)
        if (path.isNullOrEmpty()) {
            selectSaveFolder()
        } else {
            viewModel.saveImage(webPic, path)
        }
    }

    private fun selectSaveFolder() {
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(imagePathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        saveImage.launch {
            otherActions = default
        }
    }

    override fun finish() {
        if (viewModel.sourceVerificationEnable) {
            // 验证模式下，确保先保存验证结果再关闭
            // 根因：返回键等路径直接调用 finish() 绕过了 saveVerificationResult，
            // 导致 checkResult 发现无结果时设置空 Pair("", "") → "验证结果为空"
            // 检查是否已有结果，无结果则先执行保存
            if (SourceVerificationHelp.getResult(viewModel.sourceOrigin) == null) {
                viewModel.saveVerificationResult(currentWebView) {
                    SourceVerificationHelp.checkResult(viewModel.sourceOrigin)
                    super.finish()
                }
                return
            }
        }
        SourceVerificationHelp.checkResult(viewModel.sourceOrigin)
        super.finish()
    }

    private fun close() {
        if (!isCloudflareChallenge) {
            if (viewModel.sourceVerificationEnable) {
                viewModel.saveVerificationResult(currentWebView) {
                    finish()
                }
            }
            else {
                finish()
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
        super.onDestroy()
    }

    @Suppress("unused")
    private class JSInterface(activity: WebViewActivity) {
        private val activityRef: WeakReference<WebViewActivity> = WeakReference(activity)
        @JavascriptInterface
        fun lockOrientation(orientation: String) {
            val ctx = activityRef.get()
            if (ctx != null && ctx.isfullscreen  && !ctx.isFinishing && !ctx.isDestroyed) {
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
                    ctx.close()
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
            isfullscreen = true
            binding.llView.invisible()
            binding.customWebView.addView(view)
            customWebViewCallback = callback
            keepScreenOn(true)
            toggleSystemBar(false)
        }

        override fun onHideCustomView() {
            isfullscreen = false
            binding.customWebView.removeAllViews()
            binding.llView.visible()
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            keepScreenOn(false)
            toggleSystemBar(true)
        }

        /* 覆盖window.close() */
        override fun onCloseWindow(window: WebView?) {
            close()
        }

        /* 监听网页日志 */
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            viewModel.source?.let { source ->
                if (sessionShowWebLog) {
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

    inner class CustomWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            request?.let {
                return shouldOverrideUrlLoading(it.url)
            }
            return true
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            url?.let {
                return shouldOverrideUrlLoading(it.toUri())
            }
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (needClearHistory) {
                needClearHistory = false
                currentWebView.clearHistory() //清除历史
            }
            super.onPageStarted(view, url, favicon)
            currentWebView.evaluateJavascript(basicJs, null)
        }
        
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            val cookieManager = CookieManager.getInstance()
            // 异步保存 WebView Cookie，避免 runBlocking(IO) 阻塞主线程
            // CookieStore.setCookie 已有空值保护，不会用 null 覆盖有效 Cookie
            url?.let {
                val webViewCookie = cookieManager.getCookie(it)
                if (!webViewCookie.isNullOrEmpty()) {
                    CookieStore.setCookie(it, webViewCookie)
                }
            }
            view?.title?.let { title ->
                if (title != url && title != view.url && title.isNotBlank()) {
                    titleState = title
                } else {
                    titleState = intent.getStringExtra("title").orEmpty()
                }
                view.evaluateJavascript("!!window._cf_chl_opt") {
                    if (it == "true") {
                        isCloudflareChallenge = true
                        // F215：把「正在过站点安全检查」的 5-15 秒无反馈黑盒变成在场说明
                        // （evaluateJavascript 回调在主线程，可直接写 Compose 状态）
                        challengeChecking = true
                    } else {
                        // 本次加载已无挑战标记 ⇒ 状态条复位（isCloudflareChallenge 语义保持原样，见 close()）
                        challengeChecking = false
                    }
                    updateNoticeBar()
                    if (it != "true" && isCloudflareChallenge && viewModel.sourceVerificationEnable) {
                        viewModel.saveVerificationResult(currentWebView) {
                            finish()
                        }
                    }
                }
            }
        }

        private fun shouldOverrideUrlLoading(url: Uri): Boolean {
            return when (url.scheme) {
                "http", "https" -> false
                "legado", "yuedu" -> {
                    startActivity<OnLineImportActivity> {
                        data = url
                    }
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

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?
        ) {
            // 证书放行策略（AppConfig.sslCertPassThrough，**默认放行**，用户可关）：
            // 本应用主体是类爬虫的书源/订阅源引擎，源站自签名/过期证书极常见，默认拦截会把大量源判死；
            // 故默认放行（历史行为）。策略关闭后才走下面的逐次知情确认（不做站点白名单记忆）。
            // 确认分支的三个出口都要落定 handler（proceed / cancel 只能调一次）：确认→proceed；
            // 取消→cancel；点外关闭/返回→onDismissAction→cancel。
            handler ?: return
            if (AppConfig.sslCertPassThrough) {
                handler.proceed()
                return
            }
            val host = error?.url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                ?: view?.url
                ?: getString(R.string.ssl_error_cert_unknown)
            showComposeConfirmDialog(
                title = getString(R.string.ssl_error_title),
                message = getString(
                    R.string.ssl_error_message,
                    host,
                    sslCertSummary(error)
                ),
                positiveText = getString(R.string.ssl_error_continue),
                negativeText = getString(R.string.cancel),
                dangerPositive = true,
                onPositive = { handler.proceed() },
                onNegative = { handler.cancel() },
                onDismissAction = { handler.cancel() }
            )
        }

        /**
         * 证书摘要（替代原型的「查看证书详情」展开卡）：颁发对象 + 有效期在确认文案内直接给出，
         * 避免为一次性披露新增共享对话框组件；信息不折叠也就不会被用户错过。
         */
        @Suppress("DEPRECATION")
        private fun sslCertSummary(error: SslError?): String {
            val cert = error?.certificate ?: return getString(R.string.ssl_error_cert_unknown)
            val subject = cert.issuedTo?.cName?.takeIf { it.isNotBlank() }
                ?: getString(R.string.ssl_error_cert_unknown)
            val notAfter = cert.validNotAfterDate
                ?.let { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(it) }
                ?: return subject
            return getString(R.string.ssl_error_cert_info, subject, notAfter)
        }

    }

}