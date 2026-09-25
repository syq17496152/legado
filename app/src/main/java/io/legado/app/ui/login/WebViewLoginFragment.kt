package io.legado.app.ui.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.fragment.app.activityViewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.base.attachComposeContent
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.http.CookieStore
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.SilentSslWebViewClient
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.anima.RefreshProgressBar
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.snackbar
import androidx.core.net.toUri
import io.legado.app.help.webView.WebViewPool

// 原 fragment_web_view_login.xml 已退役（CE-b）：布局 id 传 0 表示「无 XML」，改 onCreateView 提供合成壳
class WebViewLoginFragment : BaseFragment(0) {

    private val viewModel by activityViewModels<SourceLoginViewModel>()
    private var pooledWebView: PooledWebView? = null
    private var currentWebView: WebView? = null

    /**
     * CE-b：合成壳与两个 View 内核（**先于组合挂载创建**，见交接文档 §8-㉕）。
     *
     * `onFragmentCreated` 里 `initWebView()` 就要 `webViewContainer.addView(webView)` 与
     * `progressBar.fontColor = accentColor`，故在 `onCreateView` 建好、`AndroidView` 工厂只回传字段。
     */
    private lateinit var shellRoot: FrameLayout
    private lateinit var webViewContainer: FrameLayout
    private lateinit var progressBar: RefreshProgressBar

    /** 原 `progress_bar` 的 `setDurProgress` + `gone(progress == 100)` */
    private var webProgress by mutableIntStateOf(0)

    private var checking = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        webViewContainer = FrameLayout(ctx)
        progressBar = RefreshProgressBar(ctx)
        shellRoot = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        return shellRoot
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.source?.let {
            initComposeContent(it)
            initWebView(it)
        }
    }

    /**
     * CE-b：Compose 承载页面骨架（顶栏 + 网页区 + 进度条）。
     *
     * 与原 XML（`fragment_web_view_login.xml`）的**逐一对应关系**：
     *  · 根 `ConstraintLayout` → `onCreateView` 提供的合成壳 `shellRoot`（`FrameLayout` + 全屏 `LayoutParams`）
     *  · `compose_top_bar`(ComposeView) → 页内直接渲染 `GlassTopAppBar`（内容逐行不变）
     *  · `web_view_container`（`0dp` 上下约束撑满剩余）→ `Box(weight(1f))` 内 `AndroidView` 托管 `FrameLayout`
     *  · `progress_bar`（1dp，网页区顶部悬浮、不占内容布局）→ `AndroidView` 托管 `RefreshProgressBar`
     *    （进度 100 时退出组合，等价原 `gone(progress == 100)`）
     */
    private fun initComposeContent(source: BaseSource) {
        shellRoot.attachComposeContent {
            Column(modifier = Modifier.fillMaxSize()) {
                // ---- 顶栏（原 compose_top_bar，内容逐行不变）----
                LegadoTheme {
                    val palette = rememberAppSettingPalette()
                    GlassTopAppBar(
                        title = getString(R.string.login_source, source.getTag()),
                        navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        onNavClick = { activity?.finish() },
                        actions = {
                            // 修复3（A3-2 操作语义须可理解）：原 ✔ 图标实际行为是 flush Cookie 后重载确认登录，
                            // 图标语义不明（新用户无法理解要再点一次），改文案按钮直说做了什么。
                            Text(
                                text = getString(R.string.finish_login),
                                color = palette.onAccent,
                                fontSize = 13.sp,
                                maxLines = 1,
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(palette.accent)
                                    .clickable { checkHostCookie() }
                                    .padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }
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
                        factory = { webViewContainer }
                    )
                    if (webProgress < 100) {
                        AndroidView(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .fillMaxWidth()
                                .height(1.dp),
                            factory = { progressBar },
                            update = { it.setDurProgress(webProgress) }
                        )
                    }
                }
            }
        }
    }

    /** 完成登录：强制持久化 cookie 并重新加载页面（原 onCompatOptionsItemSelected menu_ok 逻辑） */
    private fun checkHostCookie() {
        if (!checking) {
            checking = true
            shellRoot.snackbar(R.string.check_host_cookie)
            // 强制持久化 WebView 当前 cookie，防止 finish 后丢失
            CookieManager.getInstance().flush()
            viewModel.source?.let {
                loadUrl(it)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(source: BaseSource) {
        val webView = WebViewPool.acquire(requireContext()).let {
            pooledWebView = it
            it.realWebView
        }
        webView.onResume()
        webViewContainer.addView(webView)
        currentWebView = webView
        progressBar.fontColor = accentColor
        webView.settings.apply {
            useWideViewPort = true
            loadWithOverviewMode = true
            viewModel.headerMap[AppConst.UA_NAME]?.let {
                userAgentString = it
            }
        }
        val cookieManager = CookieManager.getInstance()
        webView.webViewClient = object : SilentSslWebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                val cookie = cookieManager.getCookie(url)
                CookieStore.setCookie(source.getKey(), cookie)
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val cookie = cookieManager.getCookie(url)
                CookieStore.setCookie(source.getKey(), cookie)
                if (checking) {
                    activity?.finish()
                }
                super.onPageFinished(view, url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return shouldOverrideUrlLoading(request.url)
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return shouldOverrideUrlLoading(url.toUri())
            }

            private fun shouldOverrideUrlLoading(url: Uri): Boolean {
                when (url.scheme) {
                    "http", "https" -> {
                        return false
                    }

                    else -> {
                        shellRoot.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                            context?.openUrl(url)
                        }
                        return true
                    }
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                // CE-b：原 `setDurProgress + gone(==100)` 合并为状态（`webProgress == 100` 时进度条退出组合）
                webProgress = newProgress
            }

        }
        loadUrl(source)
    }

    private fun loadUrl(source: BaseSource) {
        val loginUrl = source.loginUrl ?: return
        val absoluteUrl = NetworkUtils.getAbsoluteURL(source.getKey(), loginUrl)
        currentWebView?.loadUrl(absoluteUrl, viewModel.headerMap)
    }

    override fun onDestroy() {
        super.onDestroy()
        pooledWebView?.let { WebViewPool.release(it) }
        pooledWebView = null
        currentWebView = null
    }

}
