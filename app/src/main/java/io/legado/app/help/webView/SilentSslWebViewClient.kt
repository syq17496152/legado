package io.legado.app.help.webView

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 静默放行证书错误的 WebViewClient 基类（2026-09-21 用户裁决）。
 *
 * WebView 对证书错误（自签名 / 已过期 / 域名不匹配）的**默认行为是取消加载**——对类爬虫应用
 * 等价于「把大量源直接判死」。全项目统一改为静默放行，**本类是唯一实现点**：
 * 新增 WebViewClient 一律继承它，禁止再各自实现策略或引入拦截/确认弹窗。
 *
 * 例外：只渲染应用内置/本地 HTML（`file://`、`data:`）的客户端不存在远端证书路径，
 * 可继续直接继承 `WebViewClient`（如 EPUB 排版引擎、分享图渲染）。
 */
@Suppress("WebViewClientOnReceivedSslError")
open class SilentSslWebViewClient : WebViewClient() {

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler?,
        error: SslError?
    ) {
        handler?.proceed()
    }
}