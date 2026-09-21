package io.legado.app.ui.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.activityViewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.constant.AppConst
import io.legado.app.data.entities.BaseSource
import io.legado.app.databinding.FragmentWebViewLoginBinding
import io.legado.app.help.http.CookieStore
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.SilentSslWebViewClient
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.gone
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.snackbar
import io.legado.app.utils.viewbindingdelegate.viewBinding
import androidx.core.net.toUri
import io.legado.app.help.webView.WebViewPool

class WebViewLoginFragment : BaseFragment(R.layout.fragment_web_view_login) {

    private val binding by viewBinding(FragmentWebViewLoginBinding::bind)
    private val viewModel by activityViewModels<SourceLoginViewModel>()
    private var pooledWebView: PooledWebView? = null
    private var currentWebView: WebView? = null

    private var checking = false

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.source?.let {
            initComposeTopBar(it)
            initWebView(it)
        }
    }

    /** Compose 顶栏（L-C13 S6 改造）：GlassTopAppBar + 完成登录（原 menu_ok 逻辑） */
    private fun initComposeTopBar(source: BaseSource) {
        binding.composeTopBar.setContent {
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
        }
    }

    /** 完成登录：强制持久化 cookie 并重新加载页面（原 onCompatOptionsItemSelected menu_ok 逻辑） */
    private fun checkHostCookie() {
        if (!checking) {
            checking = true
            binding.root.snackbar(R.string.check_host_cookie)
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
        binding.webViewContainer.addView(webView)
        currentWebView = webView
        binding.progressBar.fontColor = accentColor
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
                        binding.root.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
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
                binding.progressBar.setDurProgress(newProgress)
                binding.progressBar.gone(newProgress == 100)
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
