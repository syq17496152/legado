package io.legado.app.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.size
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.base.attachComposeContent
import io.legado.app.base.composeShell
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.anima.RefreshProgressBar
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
import io.legado.app.utils.dpToPx
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.snackbar
import io.legado.app.utils.startActivity
import io.legado.app.utils.toggleSystemBar
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import io.legado.app.constant.AppLog
import io.legado.app.help.webView.SilentSslWebViewClient
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
import androidx.core.graphics.createBitmap
import io.legado.app.help.WebCacheManager
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache

class WebViewActivity : VMBaseActivity<ViewBinding, WebViewModel>() {
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

    // CE 5.2（compose 包）：原 activity_web_view.xml 已退役 ⇒ 改 composeShell 工厂创建合成壳，
    // Compose 全权接管页面骨架；**四个 View 内核一律 AndroidView 原样托管**（WebView 容器 /
    // 通知条 / 进度条 / 自定义全屏容器）。
    override val binding: ViewBinding by lazy { composeShell(this) }
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
    // F215：验证模式一次性引导条可见性（显示即记忆）
    private var guideBarVisible by mutableStateOf(false)
    // F215：Cloudflare 挑战期状态提示（挑战页加载完成时置位，非挑战页复位）
    private var challengeChecking by mutableStateOf(false)
    // 验证模式取自 Intent（viewModel 侧字段在 initData 协程内赋值，早于它读取会拿到默认值）
    private var verificationMode = false
    // F214：上次返回键按下时刻（栈顶二次确认窗口）
    private var lastBackPressedTime = 0L

    // ---- CE 5.2：原 XML 的 View 机制改由 Compose 状态 + 程序化 View 驱动（逐一与原节点等价）----
    //  · webProgress        ← progress_bar 的 setDurProgress + gone(progress==100)
    //  · customFullscreen   ← ll_view 的 invisible()/visible()（网页自定义全屏期间收起页面骨架）
    private var webProgress by mutableIntStateOf(0)
    private var customFullscreen by mutableStateOf(false)
    // 程序化创建的原 XML 节点（组合挂载时创建，由这些字段持有页面级引用）
    private var noticeBarView: LinearLayout? = null
    private var noticeTextView: TextView? = null
    private var noticeCloseView: ImageView? = null
    private var customWebViewContainer: FrameLayout? = null
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
        // CE 5.2：原 `binding.webViewContainer.addView(currentWebView)` 改由组合内 AndroidView
        // 的 factory 完成（见 initComposeContent），避免在组合挂载前依赖 binding 子视图
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
        initComposeContent()
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
            if ((customWebViewContainer?.childCount ?: 0) > 0) { //网页全屏
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

    /**
     * CE 5.2：Compose 承载页面骨架（顶栏 + 通知条 + 网页区 + 进度条 + 自定义全屏容器）。
     *
     * 与原 XML（`activity_web_view.xml`）的**逐一对应关系**：
     *  · 根 `FrameLayout` → `composeShell` 合成壳（`binding.root`）
     *  · `ll_view`(ConstraintLayout) → 顶层 `Column`；`customFullscreen` 时收起顶栏与通知条
     *    （等价原 `ll_view.invisible()/visible()`）；**网页区容器绝不条件移除**（否则 WebView 被 detach）
     *  · `compose_top_bar`(ComposeView) → 页内直接渲染 `GlassTopAppBar`（内容逐行不变）
     *  · `notice_bar`（**F215 明文保持纯 View**）→ `AndroidView` 托管程序化 `LinearLayout`；
     *    文案与可见性仍走 **View API**（`update` 内切 View 可见性），F215 结论不作废
     *  · `web_view_container` → `AndroidView` 托管 `FrameLayout`，factory 内 `addView(currentWebView)`
     *  · `progress_bar`（1dp，网页区顶部悬浮、不占内容布局）→ `AndroidView` 托管 `RefreshProgressBar`
     *  · `custom_web_view`（网页自定义全屏 overlay）→ `AndroidView` 托管 `FrameLayout`，恒在场、绘于最上层
     */
    private fun initComposeContent() {
        binding.root.attachComposeContent {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (!customFullscreen) {
                        // ---- 顶栏（原 compose_top_bar，内容逐行不变）----
                        LegadoTheme {
                            GlassTopAppBar(
                                title = titleState,
                                subtitle = subtitleState,
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
                        // ---- 通知条（原 notice_bar）：状态先在组合作用域读取，update 内落到 View 可见性 ----
                        val noticeText = when {
                            challengeChecking -> getString(R.string.source_verification_checking)
                            guideBarVisible -> getString(R.string.source_verification_guide)
                            else -> null
                        }
                        val noticeClosable = !challengeChecking
                        AndroidView(
                            modifier = Modifier.fillMaxWidth(),
                            factory = { ctx -> createNoticeBar(ctx) },
                            update = { applyNoticeBar(noticeText, noticeClosable) }
                        )
                    }
                    // ---- 网页区 + 进度条（原 web_view_container + progress_bar）----
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                FrameLayout(ctx).apply { addView(currentWebView) }
                            }
                        )
                        // 原 `gone(progress == 100)` ⇒ 进度 100 时该 1dp 条不再进入组合（同为不占位）
                        if (webProgress < 100) {
                            AndroidView(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .fillMaxWidth()
                                    .height(1.dp),
                                factory = { ctx ->
                                    RefreshProgressBar(ctx).apply { fontColor = accentColor }
                                },
                                update = { it.setDurProgress(webProgress) }
                            )
                        }
                    }
                }
                // ---- 自定义全屏容器（原 custom_web_view，绘制在最上层）----
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> FrameLayout(ctx).also { customWebViewContainer = it } }
                )
            }
        }
    }

    /**
     * F215：通知条的程序化等价物（**纯 View 实现，结论保留**）。
     *
     * 原 XML 注释已明确：用 Compose 实现时 `ComposeView` 处于 `gone` 不参与遍历，组合内容与其内的
     * 可见性写入在「初始即应显示」路径上不可靠（真机多轮实测）；View 可见性变化本身即可靠触发
     * 父容器重排，网页区随之让位。本页换装后仍沿用该机制 ⇒ 文案/可见性一律走 View API。
     */
    private fun createNoticeBar(context: Context): LinearLayout {
        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(16.dpToPx(), 16.dpToPx())
            contentDescription = null
            setImageResource(R.drawable.ic_help)
        }
        val label = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = 8.dpToPx() }
            textSize = 12f
        }
        val close = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(28.dpToPx(), 28.dpToPx())
            val pad = 7.dpToPx()
            setPadding(pad, pad, pad, pad)
            contentDescription = getString(R.string.close)
            setImageResource(R.drawable.ic_close_x)
            visibility = View.GONE
        }
        val accent = accentColor
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(12.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            setBackgroundColor(ColorUtils.blendColors(this@WebViewActivity.bottomBackground, accent, 0.12f))
            addView(icon)
            addView(label)
            addView(close)
        }
        label.setTextColor(this.primaryTextColor)
        icon.setColorFilter(accent)
        close.setColorFilter(this.secondaryTextColor)
        close.setOnClickListener { onNoticeClose() }
        noticeBarView = bar
        noticeTextView = label
        noticeCloseView = close
        return bar
    }

    /** F215：把「文案 + 是否可关闭」落到 View 可见性（不改为 Compose 条件组合，见 [createNoticeBar]）。 */
    private fun applyNoticeBar(text: String?, closable: Boolean) {
        val bar = noticeBarView ?: return
        val label = noticeTextView ?: return
        val close = noticeCloseView ?: return
        if (text == null) {
            bar.visibility = View.GONE
            return
        }
        label.text = text
        // 挑战期条不可关闭（瞬态系统状态，给关闭是假选择）
        close.visibility = if (closable) View.VISIBLE else View.GONE
        bar.visibility = View.VISIBLE
    }

    private fun onNoticeClose() {
        // 状态变更触发重组 ⇒ 通知条经 AndroidView 的 update 落下可见性
        guideBarVisible = false
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
        // CE 5.2：原 `binding.progressBar.fontColor = accentColor` 改在组合内 AndroidView factory 设置
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
            // CE 5.2：原 ProgressBar 的 setDurProgress + gone(==100) 改由状态驱动（组合内 update 落地）
            webProgress = newProgress
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            isfullscreen = true
            // 原 `binding.llView.invisible()`：收起页面骨架（顶栏/通知条随组合移除，
            // 网页区容器保留挂载 ⇒ WebView 不被 detach；自定义视图为不透明全屏、覆盖其上方）
            customFullscreen = true
            customWebViewContainer?.addView(view)
            customWebViewCallback = callback
            keepScreenOn(true)
            toggleSystemBar(false)
        }

        override fun onHideCustomView() {
            isfullscreen = false
            customWebViewContainer?.removeAllViews()
            customFullscreen = false
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

    inner class CustomWebViewClient : SilentSslWebViewClient() {
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
                    // CE 5.2：挑战状态置位即触发重组，通知条由 AndroidView 的 update 落地
                    // （原显式 `updateNoticeBar()` 调用随之删除）
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
    }

}