package io.legado.app.ui.book.info

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import io.legado.app.R
import io.legado.app.help.webView.WebJsExtensions.Companion.getInjectionString
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.utils.openUrl

internal object BookInfoUseWebHost {

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            blockNetworkImage = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            mediaPlaybackRequiresUserGesture = false
            allowContentAccess = true
            allowFileAccess = true
            textZoom = 100
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
        }
    }

    fun attachPopupSupport(
        container: ViewGroup,
        webView: WebView,
        popupWebViewClientFactory: () -> WebViewClient = {
            PopupWebViewClient(container.context)
        },
        configurePopupWebView: (WebView) -> Unit = {},
        onPopupChanged: (Boolean) -> Unit = {}
    ) {
        configure(webView)
        val existing = container.getTag(R.id.book_info_useweb_popup_host) as? PopupChromeClient
        if (existing != null && existing.rootWebView === webView) {
            existing.update(popupWebViewClientFactory, configurePopupWebView, onPopupChanged)
            webView.webChromeClient = existing
            return
        }
        existing?.closePopup()
        val chromeClient = PopupChromeClient(
            rootWebView = webView,
            container = container,
            popupWebViewClientFactory = popupWebViewClientFactory,
            configurePopupWebView = configurePopupWebView,
            onPopupChanged = onPopupChanged
        )
        container.setTag(R.id.book_info_useweb_popup_host, chromeClient)
        webView.webChromeClient = chromeClient
    }

    fun clearPopups(container: ViewGroup) {
        (container.getTag(R.id.book_info_useweb_popup_host) as? PopupChromeClient)?.closePopup()
        container.setTag(R.id.book_info_useweb_popup_host, null)
    }

    private class PopupChromeClient(
        val rootWebView: WebView,
        private val container: ViewGroup,
        private var popupWebViewClientFactory: () -> WebViewClient,
        private var configurePopupWebView: (WebView) -> Unit,
        private var onPopupChanged: (Boolean) -> Unit
    ) : WebChromeClient() {

        private var popupWebView: WebView? = null

        fun update(
            popupWebViewClientFactory: () -> WebViewClient,
            configurePopupWebView: (WebView) -> Unit,
            onPopupChanged: (Boolean) -> Unit
        ) {
            this.popupWebViewClientFactory = popupWebViewClientFactory
            this.configurePopupWebView = configurePopupWebView
            this.onPopupChanged = onPopupChanged
        }

        override fun getDefaultVideoPoster(): Bitmap {
            return super.getDefaultVideoPoster() ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
            closePopup()
            val popup = WebView(view?.context ?: container.context).apply {
                layoutParams = popupLayoutParams()
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                isFocusable = true
                isFocusableInTouchMode = true
                configure(this)
                webViewClient = popupWebViewClientFactory()
            }
            configurePopupWebView(popup)
            popup.webChromeClient = this
            popupWebView = popup
            container.addView(popup, popupLayoutParams())
            popup.requestFocus()
            transport.webView = popup
            resultMsg.sendToTarget()
            onPopupChanged(true)
            return true
        }

        override fun onCloseWindow(window: WebView?) {
            if (window == null || window === popupWebView) {
                closePopup()
                return
            }
            (window.parent as? ViewGroup)?.removeView(window)
            window.stopLoading()
            window.webChromeClient = null
            window.webViewClient = WebViewClient()
            window.destroy()
            onPopupChanged(false)
        }

        fun closePopup() {
            val popup = popupWebView ?: return
            popupWebView = null
            (popup.parent as? ViewGroup)?.removeView(popup)
            popup.stopLoading()
            popup.webChromeClient = null
            popup.webViewClient = WebViewClient()
            popup.destroy()
            onPopupChanged(false)
        }

        private fun popupLayoutParams(): ViewGroup.LayoutParams {
            return if (container is FrameLayout) {
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            } else {
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
    }

    private class PopupWebViewClient(
        private val context: Context
    ) : WebViewClient() {

        private val jsStr = getInjectionString

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            val uri = request?.url ?: return true
            return when (uri.scheme) {
                "http", "https" -> false
                "legado", "yuedu" -> {
                    context.startActivity(Intent(context, OnLineImportActivity::class.java).apply {
                        data = uri
                        if (context !is Activity) {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    })
                    true
                }

                else -> {
                    context.openUrl(uri)
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            runCatching { view?.evaluateJavascript(jsStr, null) }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            runCatching { view?.evaluateJavascript(jsStr, null) }
        }
    }
}