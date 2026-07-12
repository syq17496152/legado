package io.legado.app.ui.video

import android.content.Context
import android.graphics.Color
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import io.legado.app.constant.AppLog
import io.legado.app.model.VideoPlay
import kotlin.math.abs
import kotlin.system.measureTimeMillis

/**
 * P0: WebView 视频播放器（降级方案）
 *
 * 当 ExoPlayer 播放失败（HLS SPS 解析错误/UnrecognizedInputFormatException/HlsPlaylistStuckException）时，
 * 降级到 WebView 播放，使用 skill V2 hls-video-player.html 模板（HLS.js + 进度条 + 倍速 + 全屏 + 横竖屏 + 上下集 + 错误重试）。
 *
 * 特性：
 * - 读取 assets/hls_video_player_template.html 模板
 * - 替换占位符（{{result}}/{{videoTitle}}/{{referer}}/{{headersJson}}）
 * - 支持 Headers 注入（防盗链 Referer 等）
 * - ViewPager2 兼容：pause()/resume()/release() 供 Fragment 生命周期调用
 *
 * 日志规范（永久）：Tag=WebViewPlayer，记录模板加载/变量替换/耗时
 */
class WebViewVideoPlayer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "WebViewPlayer"
        private const val TEMPLATE_PATH = "hls_video_player_template.html"
        private const val HLS_JS_PATH = "hls.min.js"
    }

    private val webView: WebView
    private var currentUrl: String = ""
    private var currentTitle: String = ""
    private var isReleased: Boolean = false

    // P0-1.6 修复（5.6 验证发现）：WebView 模式下垂直滑动检测状态
    // 用于 onInterceptTouchEvent 检测垂直滑动，恢复 ViewPager2 上下切换文章能力
    private var singleFingerStartX = 0f
    private var singleFingerStartY = 0f
    private var isVerticalSwipe = false

    init {
        setBackgroundColor(Color.BLACK)
        webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            // 防止 WebView 白屏，允许混合内容
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        addView(webView)
    }

    /**
     * 播放视频（使用 V2 模板包装视频 URL）
     *
     * @param url 视频 URL（支持 m3u8/mp4，支持逗号分隔多地址）
     * @param title 视频标题
     * @param headers 自定义 Headers（含 Referer 等，用于防盗链）
     */
    fun play(url: String, title: String, headers: Map<String, String>) {
        checkMainThread("play")
        if (isReleased) {
            Log.e(TAG, "play called after release, abort")
            return
        }
        currentUrl = url
        currentTitle = title

        val loadTime = measureTimeMillis {
            try {
                val template = context.assets.open(TEMPLATE_PATH).bufferedReader().use { it.readText() }
                // W1 优化：从 assets 读取 hls.min.js 内容内联注入（避免 CDN 不可用时降级方案也失败）
                val hlsJsContent = context.assets.open(HLS_JS_PATH).bufferedReader().use { it.readText() }

                // 提取 Referer（大小写不敏感）
                val referer = headers.entries
                    .firstOrNull { it.key.equals("Referer", ignoreCase = true) }
                    ?.value ?: ""

                // 构建其他 Headers 的 JSON 对象（排除 Referer）
                val headersJson = headers.entries
                    .filterNot { it.key.equals("Referer", ignoreCase = true) }
                    .joinToString(
                        prefix = "{",
                        postfix = "}",
                        separator = ","
                    ) { "\"${escapeJson(it.key)}\":\"${escapeJson(it.value)}\"" }

                // 替换模板占位符（W1: 新增 hlsJsContent 内联注入）
                val html = template
                    .replace("{{hlsJsContent}}", hlsJsContent)
                    .replace("{{result}}", escapeJs(url))
                    .replace("{{videoTitle}}", escapeJs(title))
                    .replace("{{referer}}", escapeJs(referer))
                    .replace("{{headersJson}}", headersJson)

                // P0 日志规范：永久日志，记录模板加载+变量替换（Tag=WebViewPlayer）
                Log.d(TAG, "play: title=$title, urlLen=${url.length}, headers=${headers.size}, refererEmpty=${referer.isEmpty()}, htmlLen=${html.length}")

                // 使用 about:blank 作为 baseURL 避免 file:// 权限问题
                webView.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null)
            } catch (e: Exception) {
                AppLog.put("WebView播放器模板加载失败: title=$title, urlTail=${url.takeLast(60)}", e)
                Log.e(TAG, "play failed", e)
            }
        }
        Log.d(TAG, "play loadTime=${loadTime}ms")
    }

    /**
     * 暂停播放（ViewPager2 切换到其他 Fragment 时调用）
     */
    fun pause() {
        checkMainThread("pause")
        if (isReleased) return
        webView.evaluateJavascript(
            "try{var v=document.getElementById('video-element');if(v){v.pause();}}catch(e){}",
            null
        )
        Log.d(TAG, "pause")
    }

    /**
     * 恢复播放（ViewPager2 切换回此 Fragment 时调用）
     */
    fun resume() {
        if (isReleased) return
        webView.evaluateJavascript(
            "try{var v=document.getElementById('video-element');if(v){v.play().catch(function(){});}}catch(e){}",
            null
        )
        Log.d(TAG, "resume")
    }

    /**
     * 释放资源（Fragment onDestroyView 时调用，防内存泄漏）
     */
    fun release() {
        checkMainThread("release")
        if (isReleased) return
        isReleased = true
        try {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeJavascriptInterface("Android")
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "release failed", e)
        }
        Log.d(TAG, "release: title=$currentTitle")
    }

    /**
     * P0-1.6 修复（5.6 验证发现）：WebView 模式下垂直滑动拦截
     *
     * 问题：WebView 全屏播放时消费所有触摸事件，导致 ViewPager2 无法上下滑动切换文章
     * 修复：重写 onInterceptTouchEvent，在文章模式下检测垂直滑动并拦截
     *
     * 实现原理：
     * 1. ACTION_DOWN：记录起始位置，返回 false（不拦截，让 WebView 处理 DOWN）
     * 2. ACTION_MOVE：检测垂直滑动（|dy|>|dx| 且 |dy|>30f）
     *    - 检测到垂直滑动：调用 parent.requestDisallowInterceptTouchEvent(false) 恢复 ViewPager2 拦截
     *      + 返回 true 拦截（阻止 WebView 继续处理，避免覆盖 disallowIntercept）
     *    - 未检测到：返回 false（让 WebView 处理点击/水平滑动）
     * 3. ACTION_UP/CANCEL：重置状态
     *
     * 为什么不用 OnTouchListener：FrameLayout 是 ViewGroup，其 OnTouchListener 只在
     * 子 View 不处理事件时才被调用，但 WebView 默认消费所有触摸事件，导致 OnTouchListener
     * 永远不触发。onInterceptTouchEvent 在事件传递给子 View 之前被调用，可以正确拦截。
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val isArticleMode = !VideoPlay.rssArticles.isNullOrEmpty()
            && VideoPlay.rssArticles!!.size > 1
        if (!isArticleMode) {
            return false  // 非文章模式：不拦截，让 WebView 完全控制
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                singleFingerStartX = ev.x
                singleFingerStartY = ev.y
                isVerticalSwipe = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount == 1) {
                    val dx = ev.x - singleFingerStartX
                    val dy = ev.y - singleFingerStartY
                    // 首次判定滑动方向：垂直滑动优先交给 ViewPager2
                    if (!isVerticalSwipe && abs(dy) > abs(dx) && abs(dy) > 30f) {
                        isVerticalSwipe = true
                        // 恢复 ViewPager2 拦截能力，让 ViewPager2 接管垂直滑动
                        parent?.requestDisallowInterceptTouchEvent(false)
                        return true  // 拦截，阻止 WebView 继续处理
                    }
                    if (isVerticalSwipe) {
                        // 持续垂直滑动：保持 ViewPager2 拦截能力
                        parent?.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isVerticalSwipe = false
            }
        }
        return false  // 默认不拦截，让 WebView 处理（点击/水平滑动）
    }

    /**
     * 检查是否已释放
     */
    fun isReleased(): Boolean = isReleased

    /**
     * P1-新-4.2: WebView 线程安全检查
     * 确保所有 WebView 方法在主线程执行，记录违规调用
     */
    private fun checkMainThread(methodName: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.w("WebViewThread", "$methodName called on non-main thread: ${Thread.currentThread().name}")
        }
    }

    /**
     * JSON 字符串转义
     */
    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    /**
     * JavaScript 字符串转义（用于嵌入 JS 代码中的字符串字面量）
     */
    private fun escapeJs(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
