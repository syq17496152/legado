package io.legado.app.help.webView

import android.content.Context
import android.content.MutableContextWrapper
import io.legado.app.ui.rss.read.VisibleWebView

class PooledWebView(
    val realWebView: VisibleWebView, // 真正的WebView实例
    val id: String, // 唯一标识
    val scope: WebViewPool.Scope = WebViewPool.Scope.GLOBAL
) {
    var isInUse: Boolean = false // 是否正在被使用
    var lastUseTime: Long = 0 // 最后一次被使用的时间戳
    var resetToken: Int = 0
    var isDestroyed: Boolean = false

    /**
     * crash-hardening AD-02：关键会话保护位。
     * 视频播放中 / 嗅探会话中 / 表单有未提交输入时置 true，CRITICAL 档也不得回收。
     */
    var protected: Boolean = false

    /**
     * crash-hardening AD-02：CRITICAL 档压力回收标记。
     * 置位后宿主应尽快移除容器并 [WebViewPool.release]；release 会直接销毁而不回流 idle。
     */
    var pendingDestroy: Boolean = false

    /** 宿主注册的"请回收我"回调（由 WebViewActivity 等在 UI 线程处理容器移除与重建） */
    var onRecycleRequest: (() -> Unit)? = null

    fun upContext(context: Context): PooledWebView {
        (realWebView.context as MutableContextWrapper).let {
            if (it.baseContext != context) {
                it.baseContext = context
            }
        }
        return this
    }
}