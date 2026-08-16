package io.legado.app.ui.browser

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
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
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.ACache
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
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
import io.legado.app.help.webView.WebViewPool.DATA_HTML
import io.legado.app.model.Download
import splitties.systemservices.powerManager
import java.lang.ref.WeakReference
import java.net.URLDecoder
import androidx.core.graphics.createBitmap
import io.legado.app.help.WebCacheManager
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache

class WebViewActivity : VMBaseActivity<ActivityWebViewBinding, WebViewModel>() {
    companion object {
        // 是否输出日志
        var sessionShowWebLog = false
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
        initComposeTopBar()
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
            if (currentWebView.canGoBack()) {
                val list = currentWebView.copyBackForwardList()
                val size = list.size
                if (size == 1) {
                    finish()
                    return@addCallback
                }
                val currentIndex = list.currentIndex
                val currentItem = list.currentItem
                val currentUrl = currentItem?.originalUrl ?: BLANK_HTML
                val currentTitle = currentItem?.title
                var steps = 1
                for (i in currentIndex - 1 downTo 0) {
                    val item = list.getItemAtIndex(i)
                    val itemUrl = item.originalUrl
                    if (itemUrl == BLANK_HTML) {
                        finish()
                        return@addCallback
                    }
                    if (itemUrl != currentUrl || currentTitle != item.title) {
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
                                actions = buildMenuActions()
                            )
                        }
                    }
                )
            }
        }
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

    private fun buildMenuActions(): List<MenuAction> = buildList {
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
        // 源操作（仅源验证模式可见）
        if (viewModel.sourceOrigin.isNotEmpty()) {
            add(
                MenuAction(
                    icon = Icons.Outlined.Delete,
                    title = getString(R.string.disable_source),
                    onClick = {
                        viewModel.disableSource {
                            finish()
                        }
                    }
                )
            )
            add(
                MenuAction(
                    icon = Icons.Outlined.DeleteForever,
                    title = getString(R.string.delete_source),
                    onClick = {
                        alert(R.string.draw) {
                            setMessage(getString(R.string.sure_del) + "\n" + viewModel.sourceName)
                            noButton()
                            yesButton {
                                viewModel.deleteSource {
                                    finish()
                                }
                            }
                        }
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
                    selector(
                        arrayListOf(
                            SelectItem(getString(R.string.action_save), "save"),
                            SelectItem(getString(R.string.select_folder), "selectFolder")
                        )
                    ) { _, charSequence, _ ->
                        when (charSequence.value) {
                            "save" -> saveImage(webPic)
                            "selectFolder" -> selectSaveFolder()
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
                    } else if (isCloudflareChallenge && viewModel.sourceVerificationEnable) {
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
            handler?.proceed()
        }

    }

}