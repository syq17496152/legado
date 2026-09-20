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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.constant.AppConst
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.BaseSource
import io.legado.app.databinding.FragmentWebViewLoginBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieStore
import io.legado.app.help.webView.PooledWebView
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.gone
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.snackbar
import io.legado.app.utils.viewbindingdelegate.viewBinding
import androidx.core.net.toUri
import io.legado.app.help.webView.WebViewPool
import java.text.SimpleDateFormat
import java.util.Locale
import splitties.init.appCtx

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

    /** Compose 顶栏（L-C13 S6 改造）：GlassTopAppBar + 完成登录（原 menu_ok 逻辑）+ 证书放行策略 */
    private fun initComposeTopBar(source: BaseSource) {
        binding.composeTopBar.setContent {
            LegadoTheme {
                val palette = rememberAppSettingPalette()
                var menuExpanded by remember { mutableStateOf(false) }
                // 会话内状态源：切换后立即重组菜单勾选态（真实持久化在 AppConfig/PreferKey）
                var passThrough by remember { mutableStateOf(AppConfig.sslCertPassThrough) }
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
                                actions = listOf(
                                    MenuAction(
                                        icon = Icons.Outlined.Lock,
                                        title = getString(R.string.ssl_passthrough),
                                        checked = passThrough,
                                        onClick = { setSslPassThrough(!passThrough) { passThrough = it } }
                                    )
                                )
                            )
                        }
                    }
                )
            }
        }
    }

    /**
     * 证书放行策略切换（默认放行，见 [AppConfig.sslCertPassThrough]）：
     * 变更即落盘并回执当前态——低频安全设置必须让用户看得见「现在是哪种策略」，
     * 否则开关变成猜谜（勾选态在菜单里，菜单关闭后不可见）。
     */
    private fun setSslPassThrough(enable: Boolean, onChanged: (Boolean) -> Unit) {
        appCtx.putPrefBoolean(PreferKey.sslCertPassThrough, enable)
        onChanged(enable)
        binding.root.snackbar(
            if (enable) R.string.ssl_passthrough_on_hint else R.string.ssl_passthrough_off_hint
        )
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
        webView.webViewClient = object : WebViewClient() {
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

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                // 证书放行策略（PreferKey.sslCertPassThrough，**默认放行**）：
                // 开启时维持历史行为——直接放行，登录链路不被过期/自签名证书打断；
                // 关闭时改为逐次知情确认（与内嵌浏览器同文案同出口约定），默认拒绝加载。
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

            /** 证书摘要（与内嵌浏览器同口径）：颁发对象 + 有效期直接写进确认文案，不折叠 */
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
