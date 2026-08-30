package io.legado.app.help.webView

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.rss.read.VisibleWebView
import io.legado.app.utils.runOnUI
import io.legado.app.utils.setDarkeningAllowed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.util.Stack
import kotlin.math.max
import kotlin.random.Random

object WebViewPool {
    const val BLANK_HTML = "about:blank"
    const val DATA_HTML = "data:text/html;charset=utf-8;base64,"

    enum class Scope {
        GLOBAL,
        DISCOVERY,
        RSS
    }

    private class ScopePool(
        val scope: Scope,
        val maxCached: Int,
        val idleTimeout: Long,
        val lastIdleTimeout: Long
    ) {
        val idlePool = Stack<PooledWebView>()
        val inUsePool = mutableMapOf<String, PooledWebView>()
        val resettingPool = mutableMapOf<String, PooledWebView>()
        var needInitialize = true
        var cleanupJob: Job? = null
        var destroyJob: Job? = null
    }

    // P1-C 修复：主线程 Handler，用于在非 UI 线程切回主线程销毁 WebView
    private val mainHandler = Handler(Looper.getMainLooper())

    private val globalMaxCached = max(AppConfig.updateCacheThreadCount / 10, 5)
    private const val IDLE_TIME_OUT: Long = 5 * 60 * 1000 // 闲置5分钟后销毁
    private const val IDLE_TIME_OUT_LAST: Long = 30 * 60 * 1000 // 最后一个闲置30分钟后销毁
    private const val SCOPED_WEB_VIEW_MAX_NUM = 2
    private const val SCOPED_IDLE_TIME_OUT: Long = 30 * 1000
    private val cleanupScope by lazy { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
    private val pools = mutableMapOf<Scope, ScopePool>()

    private fun pool(scope: Scope): ScopePool {
        return pools.getOrPut(scope) {
            when (scope) {
                Scope.GLOBAL -> ScopePool(scope, globalMaxCached, IDLE_TIME_OUT, IDLE_TIME_OUT_LAST)
                Scope.DISCOVERY, Scope.RSS -> ScopePool(
                    scope,
                    SCOPED_WEB_VIEW_MAX_NUM,
                    SCOPED_IDLE_TIME_OUT,
                    SCOPED_IDLE_TIME_OUT
                )
            }
        }
    }

    /**
     * 全局是否空闲（所有 scope 的 inUsePool 均为空）
     * sniff-regression-rss-image-crash: pauseTimers/resumeTimers 为进程级 API，
     * 守卫条件的作用域必须与其一致：scoped 池只隔离缓存容量与闲置回收，
     * 不隔离进程级副作用（回归修复 bbc9d0a89）
     */
    private fun isGlobalIdle(): Boolean = synchronized(this) {
        pools.values.all { it.inUsePool.isEmpty() }
    }

    // 获取一个WebView
    @Synchronized
    fun acquire(context: Context, scope: Scope = Scope.GLOBAL): PooledWebView {
        val scopePool = pool(scope)
        scopePool.destroyJob?.cancel()
        scopePool.destroyJob = null
        val pooledWebView = if (scopePool.idlePool.isNotEmpty()) {
            scopePool.idlePool.pop() // 复用闲置实例
        } else {
            if (scopePool.needInitialize) {
                scopePool.needInitialize = false
                startCleanupTimer(scopePool)
            }
            createNewWebView(scope) // 创建新实例
        }
        pooledWebView.upContext(context).apply {
            realWebView.settings.setDarkeningAllowed(AppConfig.isNightTheme) //设置是否夜间
            // sniff-regression-rss-image-crash: resumeTimers 为进程级 API，取用时无条件恢复，
            // 防止其他 scope 的 pauseTimers 误冻结本实例（嗅探 6s 窗口内 JS 被冻结导致超时）
            realWebView.resumeTimers()
            isDestroyed = false
            isInUse = true
        }
        scopePool.inUsePool[pooledWebView.id] = pooledWebView
        pooledWebView.realWebView.setBackgroundColor(Color.TRANSPARENT)
        return pooledWebView
    }

    // 释放WebView回池
    @Synchronized
    fun release(pooledWebView: PooledWebView) {
        if (pooledWebView.isDestroyed) return
        val scopePool = pool(pooledWebView.scope)
        if (scopePool.inUsePool.remove(pooledWebView.id) == null) {
            scopePool.resettingPool.remove(pooledWebView.id)
            pooledWebView.isDestroyed = true
            destroyOnMainThread(pooledWebView.realWebView)
            return
        }
        scopePool.resettingPool[pooledWebView.id] = pooledWebView
        // 重置WebView状态
        pooledWebView.realWebView.run {
            (parent as? ViewGroup)?.removeView(this)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            stopLoading()
            clearFocus() //清除焦点
            setOnLongClickListener(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setOnScrollChangeListener(null)
            }
            setDownloadListener(null)
            outlineProvider = null
            clipToOutline = false
            webChromeClient = null
            removeJavascriptInterface(WebJsExtensions.nameBasic)
            removeJavascriptInterface(WebJsExtensions.nameJava)
            removeJavascriptInterface(WebJsExtensions.nameSource)
            removeJavascriptInterface(WebJsExtensions.nameCache)
            clearFormData() //清除表单数据
            clearMatches() //清除查找匹配项
            clearDisappearingChildren() //清除消失中的子视图
            clearAnimation() //清除动画
            pooledWebView.upContext(appCtx)
            if (scopePool.idlePool.size >= scopePool.maxCached - scopePool.inUsePool.size) {
                // 池子已满，直接销毁
                scopePool.resettingPool.remove(pooledWebView.id)
                pooledWebView.isDestroyed = true
                destroyOnMainThread(pooledWebView.realWebView)
                return
            }
            webViewClient = object: WebViewClient() {
                @SuppressLint("SetJavaScriptEnabled")
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url != BLANK_HTML) return
                    view?.let{ webview ->
                        webview.settings.apply {
                            javaScriptEnabled = false
                            javaScriptEnabled = true // 禁用再启用来重置js环境，注意需要禁用的订阅源需要再次执行
                            blockNetworkImage = false // 确保允许加载网络图片
                            cacheMode = WebSettings.LOAD_DEFAULT // 重置缓存模式
                            useWideViewPort = false // 恢复默认关闭宽视模式
                            loadWithOverviewMode = false // 恢复默认
                            textZoom = 100
                        }
                        // sniff-regression-rss-image-crash: pauseTimers 为进程级 API（冻结进程内所有
                        // WebView 的 JS 定时器与解析），bbc9d0a89 分层后此处只按单 scope 判断，导致
                        // 发现页/订阅页释放时误冻结 GLOBAL 池中正在嗅探的 WebView；守卫改为跨全部
                        // scope 的 inUsePool 全局判断（等价恢复分层前"全进程互斥"语义）
                        if (isGlobalIdle()) {
                            webview.pauseTimers()
                        }
                        webview.onPause()
                    }
                    pooledWebView.isInUse = false
                    pooledWebView.lastUseTime = System.currentTimeMillis()
                    synchronized(this@WebViewPool) {
                        scopePool.resettingPool.remove(pooledWebView.id)
                        if (!pooledWebView.isDestroyed) {
                            scopePool.idlePool.push(pooledWebView)
                            startCleanupTimer(scopePool)
                        }
                    }
                }
            }
            loadUrl(BLANK_HTML)
        }
    }

    fun scheduleDestroyScope(scope: Scope, delayMillis: Long = SCOPED_IDLE_TIME_OUT) {
        if (scope == Scope.GLOBAL) return
        logScopedDestroy(scope, "计划销毁")
        val scopePool = synchronized(this) { pool(scope) }
        scopePool.destroyJob?.cancel()
        scopePool.destroyJob = cleanupScope.launch {
            delay(delayMillis)
            destroyScope(scope)
        }
    }

    fun destroyScope(scope: Scope) {
        if (scope == Scope.GLOBAL) return
        val toDestroy = synchronized(this) {
            val scopePool = pool(scope)
            scopePool.destroyJob?.cancel()
            scopePool.destroyJob = null
            scopePool.cleanupJob?.cancel()
            scopePool.cleanupJob = null
            scopePool.needInitialize = true
            val list = scopePool.idlePool.toMutableList() +
                scopePool.inUsePool.values +
                scopePool.resettingPool.values
            scopePool.idlePool.clear()
            scopePool.inUsePool.clear()
            scopePool.resettingPool.clear()
            list
        }
        logScopedDestroy(scope, "执行销毁", toDestroy.size)
        toDestroy.forEach { destroyNow(it) }
    }

    // B13: 内存压力时清空闲置 WebView 池（本项目独有，保留）
    fun trimMemory() = synchronized(this) {
        pools.values.forEach { scopePool ->
            if (scopePool.idlePool.isEmpty()) return@forEach
            val toRemove = scopePool.idlePool.toList()
            scopePool.idlePool.clear()
            scopePool.needInitialize = true
            scopePool.cleanupJob?.cancel()
            scopePool.cleanupJob = null
            toRemove.forEach { pooled ->
                destroyNow(pooled)
            }
        }
    }

    private fun logScopedDestroy(scope: Scope, action: String, count: Int? = null) {
        val pageName = when (scope) {
            Scope.DISCOVERY -> "发现页"
            Scope.RSS -> "订阅页"
            Scope.GLOBAL -> return
        }
        val countText = count?.let { ", count=$it" }.orEmpty()
        AppLog.put("$pageName WebView $action: scope=${scope.name}$countText")
    }

    private fun destroyNow(pooledWebView: PooledWebView) {
        pooledWebView.isDestroyed = true
        runOnUI {
            try {
                pooledWebView.realWebView.run {
                    (parent as? ViewGroup)?.removeView(this)
                    stopLoading()
                    loadUrl(BLANK_HTML)
                    destroy()
                }
            } catch (e: Exception) {
                AppLog.put("WebViewPool: destroyNow failed", e)
            }
        }
    }

    /**
     * 安全销毁 WebView，最多重试 3 次
     * P1-C 修复：WebView.destroy() 必须在主线程调用
     * 证据：appLog-26-07-12 多次 "destroy failed after 3 attempts" +
     *       "WebView method was called on thread 'DefaultDispatcher-worker-4'" (5次复发跨多日)
     * 根因：WebView 单线程约束，所有方法必须在 UI 线程调用
     */
    private fun destroyOnMainThread(webView: WebView, maxRetries: Int = 3) {
        // 如果已在主线程，直接执行；否则 post 到主线程
        if (Looper.myLooper() == Looper.getMainLooper()) {
            var attempt = 0
            while (attempt < maxRetries) {
                try {
                    webView.destroy()
                    return
                } catch (e: Exception) {
                    attempt++
                    if (attempt >= maxRetries) {
                        AppLog.put("WebViewPool: destroy failed after $maxRetries attempts", e)
                    }
                }
            }
        } else {
            mainHandler.post { destroyOnMainThread(webView, maxRetries) }
        }
    }

    private fun createNewWebView(scope: Scope): PooledWebView {
        val webView = VisibleWebView(MutableContextWrapper(appCtx))
        preInitWebView(webView)
        return PooledWebView(webView, generateId(scope), scope)
    }

    private fun generateId(scope: Scope): String {
        return "web_${scope.name.lowercase()}_${System.currentTimeMillis()}_${Random.nextLong()}"
    }

    // 初始化
    @SuppressLint("SetJavaScriptEnabled")
    private fun preInitWebView(webView: WebView) {
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.settings.apply {
            javaScriptEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowContentAccess = true
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 100
        }
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.setBackgroundColor(Color.TRANSPARENT)
    }

    // 定时清理闲置过久的WebView
    private fun startCleanupTimer(scopePool: ScopePool) {
        if (scopePool.cleanupJob?.isActive == true) return
        scopePool.cleanupJob = cleanupScope.launch {
            while (true) {
                delay(30_000) // 每30秒执行一次清理
                val now = System.currentTimeMillis()
                val toRemove = mutableListOf<PooledWebView>()
                var shouldCancel = false
                synchronized(this@WebViewPool) {
                    for ((index, pooled) in scopePool.idlePool.withIndex()) {
                        val timeout = if (index == 0) {
                            scopePool.lastIdleTimeout
                        } else {
                            scopePool.idleTimeout
                        }
                        if (now - pooled.lastUseTime > timeout) {
                            toRemove.add(pooled)
                        }
                    }
                    toRemove.forEach { pooled ->
                        scopePool.idlePool.remove(pooled)
                        destroyNow(pooled)
                    }
                    if (scopePool.idlePool.isEmpty()) {
                        shouldCancel = true
                    }
                }
                if (shouldCancel) {
                    scopePool.needInitialize = true
                    this@launch.cancel()
                }
            }
        }
    }
}
