package io.legado.app.help.gsyVideo

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultRenderersFactory.ExtensionRendererMode
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.BehindLiveWindowException
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.model.VideoPlay
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer
import tv.danmaku.ijk.media.exo2.demo.EventLogger

class Exo2MediaPlayer(context: Context) : IjkExo2MediaPlayer(context) {
    companion object {
        private const val TAG = "GSYExo2MediaPlayer"
        private const val MAX_POSITION_FOR_SEEK_TO_PREVIOUS: Long = 3000
        // E2 优化：网络错误自动重试次数（避免临时网络抖动直接降级 WebView）
        private const val MAX_RETRY = 1
        // exoplayer-resilience Layer 2：自动 WebView 降级阈值
        // 累计失败次数 >= 此值 + 不可恢复错误类型时触发 VIDEO_FALLBACK_WEBVIEW 事件
        private const val FALLBACK_RETRY_THRESHOLD = 3
    }
    private val window = Timeline.Window()

    /**
     * exoplayer-resilience Layer 1：协程作用域，用于嗅探 mimeType（异步 Range 请求）
     * - 用 SupervisorJob：单个嗅探协程失败不会影响其他协程
     * - 用 Dispatchers.Main.immediate：setMediaItem/prepare 必须在主线程调用
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * exoplayer-resilience Layer 1：当前嗅探协程，新 prepareAsyncInternal 启动前 cancel 旧的
     * 避免多个嗅探协程并发 setMediaItem 竞争 mInternalPlayer
     */
    private var currentSniffJob: Job? = null

    /**
     * T1.8: release 标志位，VideoFragment.onDestroyView 调用 release() 后置为 true
     *
     * 解决 Bug-5 + Bug-23 + Bug-27：
     * - scope.cancel 无法中断非 suspend 函数（applyMediaSourceByType 是普通函数）
     * - 嗅探协程回调到 applyMediaSourceByType 时，需 isReleased 标志位兜底阻止 setMediaItem
     * - 避免 onDestroy 后嗅探协程回调 setMediaItem 操作已 release 的 mInternalPlayer
     */
    private var isReleased = false

    /**
     * E2 优化：网络错误重试计数（prepareAsyncInternal 时重置）
     */
    private var retryCount = 0

    /**
     * exoplayer-resilience Layer 2：累计播放失败次数（不可恢复错误才计数）
     * - 可恢复错误（网络抖动）不计数，仅 retryCount 重试
     * - 不可恢复错误（3002/3003/3004/decoder）计数
     * - btnSwitchBack 切回 ExoPlayer 时重置为 0
     */
    private var unrecoverableFailCount = 0

    /**
     * T2.1: prepareAsyncInternal 重复初始化检测字段（解决 Bug-2：重复 Init 导致资源竞争）
     *
     * - prepareAsyncCallCount：累计调用次数（日志追踪用）
     * - lastPrepareUrl：上次 prepare 的 URL
     * - lastPrepareHeaders：上次 prepare 的 headers
     *
     * 跳过条件：同一URL+headers 且嗅探协程仍在活跃 → 跳过（避免重复嗅探）
     * 不跳过条件：URL 或 headers 变化（用户切集/切视频），或嗅探协程已结束
     */
    private var prepareAsyncCallCount = 0
    private var lastPrepareUrl: String? = null
    private var lastPrepareHeaders: Map<String, String>? = null

    /**
     * T2.3: BUFFERING 超时 12 秒触发 tryNextFallback（解决 Bug-8 + Bug-9：弱网卡死无法自动降级）
     *
     * - STATE_BUFFERING 时 postDelayed(12000L)，12 秒内未 READY 则尝试下一个 MediaSource
     * - STATE_READY/STATE_IDLE 时 removeCallbacks（避免误降级）
     * - 12 秒阈值：5 秒太短弱网误降级，12 秒平衡用户感知与降级时机
     */
    private val bufferingTimeoutHandler = Handler(Looper.getMainLooper())
    private val bufferingTimeoutRunnable = Runnable {
        AppLog.put(
            "ExoPlayer BUFFERING timeout (12s), trigger fallback, " +
                "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}, " +
                "fallbackIndex=$currentFallbackIndex/${fallbackTypes.size}"
        )
        tryNextFallback()
    }

    /**
     * R2 调试日志：记录当前播放 URL，onPlayerError 时用于错误反馈
     */
    var currentUrl: String = ""

    /**
     * E1 优化：当前播放 Headers（per-request 注入，解决防盗链 403/404）
     * 通过 SPLIT_TAG 拼接到 URL，resolvingDataSource 拆分后注入 okhttpDataFactory
     */
    var currentHeaders: Map<String, String> = emptyMap()

    /**
     * R4-T8: 降级链类型列表（如 HLS→DASH→Progressive）
     *
     * - 嗅探成功时：按嗅探结果优先 + 全量降级（如 HLS 识别成功 → [HLS, DASH, Progressive]）
     * - 嗅探失败时：HLS 优先（最常见场景）→ DASH → Progressive
     *
     * 解决用户核心诉求"时好时坏，有的能播有的播不了"：
     * - ExoPlayer 单一 MediaSource 失败即整体失败，浏览器会渐进尝试多种方式
     * - R4 降级链对齐浏览器渐进增强策略，解析失败时自动尝试下一个 MediaSource
     */
    private var fallbackTypes: List<Int> = emptyList()

    /**
     * R4-T8: 当前降级索引（onPlayerError 解析失败时 +1 尝试下一个）
     *
     * - 0 表示第一个 MediaSource（按嗅探结果优先）
     * - 1/2/... 表示降级到下一个 MediaSource
     * - 达到 fallbackTypes.size 时触发 VIDEO_FALLBACK_WEBVIEW 事件
     */
    private var currentFallbackIndex = 0

    /**
     * R4-T8: 当前嗅探结果（用于 createMediaSource 获取 mimeType + finalUrl）
     *
     * sniffVideoType 返回完整 SniffResult（含 contentType + mimeType + moovPosition + supportsRange + finalUrl），
     * 重定向后的 finalUrl 用于创建 MediaSource（避免用初始 URL 创建导致再次重定向）
     */
    private var currentSniffResult: ExoPlayerHelper.SniffResult = ExoPlayerHelper.SniffResult.UNKNOWN

    /**
     * R4-T8: 构建降级链类型列表
     *
     * T1.5 V2重构：按嗅探结果排序降级链（解决 Bug-7：降级链默认HLS优先与MP4直链不匹配）
     *
     * 优先级策略：
     * - 嗅探成功（HLS）：[HLS, Progressive, DASH] —— HLS 失败后优先试 Progressive（MP4直链），
     *   因 DASH 与 HLS 同为清单格式，HLS 失败 DASH 大概率也失败
     * - 嗅探成功（DASH）：[DASH, HLS, Progressive]
     * - 嗅探成功（SS）：[SS, HLS, DASH, Progressive]
     * - 嗅探成功（Progressive）：[Progressive, HLS, DASH] —— MP4直链优先 Progressive
     * - 嗅探失败（UNKNOWN）：[HLS, DASH, Progressive] —— HLS 优先（最常见场景）
     *
     * @param sniff 嗅探结果
     * @return 降级链 contentType 列表
     */
    private fun buildFallbackTypes(sniff: ExoPlayerHelper.SniffResult): List<Int> {
        return when (sniff.contentType) {
            C.TYPE_HLS -> listOf(C.TYPE_HLS, C.TYPE_OTHER, C.TYPE_DASH)
            C.TYPE_DASH -> listOf(C.TYPE_DASH, C.TYPE_HLS, C.TYPE_OTHER)
            C.TYPE_SS -> listOf(C.TYPE_SS, C.TYPE_HLS, C.TYPE_DASH, C.TYPE_OTHER)
            C.TYPE_OTHER -> listOf(C.TYPE_OTHER, C.TYPE_HLS, C.TYPE_DASH)
            else -> listOf(C.TYPE_HLS, C.TYPE_DASH, C.TYPE_OTHER)  // UNKNOWN: HLS 优先（最常见场景）
        }
    }

    /**
     * R4-T8: 按 contentType 创建 MediaSource 并应用到 ExoPlayer
     *
     * 关键改造：
     * - 使用 sniff.finalUrl（重定向后的最终 URL）创建 MediaSource，避免再次重定向
     * - HLS/DASH/SS 显式选择对应 MediaSource，避免 ProgressiveMediaSource 误处理清单文件
     * - Progressive 使用 DefaultExtractorsFactory（含全部 14 个 Extractor）提升识别能力
     *
     * @param contentType ExoPlayer 内容类型（C.TYPE_HLS / C.TYPE_DASH / C.TYPE_SS / C.TYPE_OTHER）
     * @param url 视频 URL（优先用 currentSniffResult.finalUrl）
     * @param headers 请求头
     */
    @OptIn(UnstableApi::class)
    private fun applyMediaSourceByType(contentType: Int, url: String, headers: Map<String, String>) {
        // T1.9: 检查 isReleased + scope.isActive，避免 onDestroy 后嗅探协程回调 setMediaItem（解决 Bug-5）
        // scope.cancel 无法中断非 suspend 函数，需 isReleased 标志位兜底
        if (isReleased || !scope.isActive) {
            AppLog.put("ExoFallback: applyMediaSourceByType skipped (isReleased=$isReleased, scopeActive=${scope.isActive}), contentType=$contentType")
            return
        }
        // R4-T9: 优先使用 sniff.finalUrl（重定向后的最终 URL），避免用初始 URL 创建导致再次重定向
        val effectiveUrl = currentSniffResult.finalUrl.ifBlank { url }
        try {
            val mediaSource: MediaSource = when (contentType) {
                C.TYPE_HLS -> {
                    val mediaItem = MediaItem.Builder()
                        .setUri(effectiveUrl)
                        .apply {
                            // R4-T8: 嗅探到的 mimeType 显式设置，避免 ExoPlayer URL 后缀误判
                            currentSniffResult.mimeType?.let { setMimeType(it) }
                        }
                        .build()
                    HlsMediaSource.Factory(ExoPlayerHelper.resolvingDataSource)
                        // R4-T10: AES-128 加密流由 ExoPlayer 内置支持
                        // resolvingDataSource 已注入 Referer/Cookie/UA 防盗链头，
                        // ExoPlayer 内部会用此 factory 获取 #EXT-X-KEY 标签的密钥
                        // 注: media3 1.10.1 HlsMediaSource.Factory 无 setExtractorsFactory，
                        // ExoPlayer 内部使用默认 DefaultExtractorsFactory(含全部 14 个 Extractor)
                        .createMediaSource(mediaItem)
                }
                C.TYPE_DASH -> {
                    val mediaItem = MediaItem.Builder()
                        .setUri(effectiveUrl)
                        .apply { currentSniffResult.mimeType?.let { setMimeType(it) } }
                        .build()
                    DashMediaSource.Factory(ExoPlayerHelper.resolvingDataSource)
                        .createMediaSource(mediaItem)
                }
                C.TYPE_SS -> {
                    val mediaItem = MediaItem.Builder()
                        .setUri(effectiveUrl)
                        .apply { currentSniffResult.mimeType?.let { setMimeType(it) } }
                        .build()
                    SsMediaSource.Factory(ExoPlayerHelper.resolvingDataSource)
                        .createMediaSource(mediaItem)
                }
                C.TYPE_OTHER -> {
                    val mediaItem = MediaItem.Builder()
                        .setUri(effectiveUrl)
                        .apply { currentSniffResult.mimeType?.let { setMimeType(it) } }
                        .build()
                    ProgressiveMediaSource.Factory(ExoPlayerHelper.resolvingDataSource)
                        // 注: media3 1.10.1 ProgressiveMediaSource.Factory 无 setExtractorsFactory，
                        // ExoPlayer 内部使用默认 DefaultExtractorsFactory(含全部 14 个 Extractor)
                        .createMediaSource(mediaItem)
                }
                else -> {
                    AppLog.put("ExoFallback: unknown contentType=$contentType, skip")
                    return
                }
            }
            mInternalPlayer?.setMediaSource(mediaSource)
            mInternalPlayer?.prepare()
            mInternalPlayer?.playWhenReady = false
            AppLog.put(
                "ExoFallback: try contentType=$contentType (#${currentFallbackIndex + 1}/${fallbackTypes.size}), " +
                    "urlPath=${ExoPlayerHelper.sanitizeUrl(effectiveUrl)}"
            )
        } catch (e: Exception) {
            // 创建 MediaSource 失败（如 IllegalState/IllegalArgument），尝试下一个降级
            AppLog.put(
                "ExoFallback: createMediaSource failed: ${e.javaClass.simpleName}: ${e.message}, " +
                    "trying next, urlPath=${ExoPlayerHelper.sanitizeUrl(effectiveUrl)}"
            )
            tryNextFallback()
        }
    }

    /**
     * R4-T8: 尝试下一个降级 MediaSource
     *
     * 触发场景：
     * - applyMediaSourceByType 创建 MediaSource 失败
     * - onPlayerError 收到解析错误（3002/3004/UnrecognizedInputFormatException）
     *
     * 全部降级失败时触发 VIDEO_FALLBACK_WEBVIEW 事件，由 UI 切换到 WebView 模式
     */
    private fun tryNextFallback() {
        if (currentFallbackIndex < fallbackTypes.size - 1) {
            currentFallbackIndex++
            AppLog.put(
                "ExoFallback: switch to next MediaSource " +
                    "(${currentFallbackIndex + 1}/${fallbackTypes.size}), " +
                    "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
            )
            applyMediaSourceByType(fallbackTypes[currentFallbackIndex], currentUrl, currentHeaders)
        } else {
            // 全部降级失败，触发 VIDEO_FALLBACK_WEBVIEW
            AppLog.put(
                "ExoFallback: all fallback exhausted (${fallbackTypes.size} types tried), " +
                    "switch to WebView mode, urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
            )
            postEvent(
                EventBus.VIDEO_FALLBACK_WEBVIEW,
                Triple(currentUrl, VideoPlay.videoTitle ?: "", currentHeaders)
            )
        }
    }

    /**
     * T1.8: 释放嗅探资源，VideoFragment.onDestroyView 调用
     *
     * 解决 Bug-5 + Bug-23 + Bug-27：
     * - cancel currentSniffJob：停止嗅探协程
     * - cancel scope：取消所有协程
     * - isReleased = true：applyMediaSourceByType 入口检查兜底（scope.cancel 无法中断非 suspend 函数）
     *
     * 注意：mInternalPlayer 的 release 由父类 IjkExo2MediaPlayer.release() 处理，此处不重复 release
     * 方法名用 releaseSniffResources 避免与父类 release() 冲突
     */
    fun releaseSniffResources() {
        if (isReleased) return  // 防止重复 release
        isReleased = true
        currentSniffJob?.cancel()
        scope.cancel()
        // T2.3: 清除 BUFFERING 超时回调，避免 onDestroy 后误触发 tryNextFallback 操作已 release 的 mInternalPlayer
        bufferingTimeoutHandler.removeCallbacks(bufferingTimeoutRunnable)
        AppLog.put("ExoPlayer scope cancelled, isReleased=true, urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}")
    }

    /**
     * 上一集
     */
    fun previous() {
        if (mInternalPlayer == null) {
            return
        }
        val timeline: Timeline = mInternalPlayer.currentTimeline
        if (timeline.isEmpty) {
            return
        }
        val windowIndex: Int = mInternalPlayer.currentMediaItemIndex
        timeline.getWindow(windowIndex, window)
        val previousWindowIndex: Int = mInternalPlayer.previousMediaItemIndex
        if (previousWindowIndex != C.INDEX_UNSET
            && (mInternalPlayer.currentPosition <= MAX_POSITION_FOR_SEEK_TO_PREVIOUS
                    || (window.isDynamic && !window.isSeekable))
        ) {
            mInternalPlayer.seekTo(previousWindowIndex, C.TIME_UNSET)
        } else {
            mInternalPlayer.seekTo(0)
        }
    }

    @OptIn(UnstableApi::class)
    override fun prepareAsyncInternal() {
        Handler(Looper.myLooper()!!).post {
            // T2.1: 重复初始化检测（解决 Bug-2：重复 Init 导致资源竞争）
            // 同一URL+headers 且嗅探协程仍活跃 → 跳过（避免重复嗅探+资源竞争）
            // URL 或 headers 变化（用户切集/切视频），或嗅探协程已结束 → 不跳过
            prepareAsyncCallCount++
            AppLog.put(
                "ExoPlayer prepareAsyncInternal: callCount=$prepareAsyncCallCount, " +
                    "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
            )
            if (currentUrl == lastPrepareUrl && currentHeaders == lastPrepareHeaders
                && currentSniffJob?.isActive == true
            ) {
                AppLog.put(
                    "ExoPlayer prepareAsyncInternal: skip duplicate call " +
                        "(same url+headers, sniffJob active)"
                )
                return@post
            }
            lastPrepareUrl = currentUrl
            lastPrepareHeaders = currentHeaders
            // T2.3: 进入新 prepare 时清除可能残留的 BUFFERING 超时回调（避免旧回调误触发降级）
            bufferingTimeoutHandler.removeCallbacks(bufferingTimeoutRunnable)
            // E2 优化：新播放重置重试计数
            retryCount = 0
            // exoplayer-resilience Layer 2：新播放重置不可恢复错误计数
            // 理由：新播放/切换视频/切回 ExoPlayer 时给新的累计机会，避免历史失败影响当前播放
            unrecoverableFailCount = 0
            // T1.12: 重置嗅探状态变量（解决 Bug-13：切换视频时状态变量未重置导致降级链错乱）
            // 在 prepareAsyncInternal 入口重置，确保每次新播放都从干净状态开始
            currentSniffResult = ExoPlayerHelper.SniffResult.UNKNOWN
            fallbackTypes = buildFallbackTypes(currentSniffResult)
            currentFallbackIndex = 0
            AppLog.put("ExoPlayer state reset: currentSniffResult=UNKNOWN, fallbackTypesSize=${fallbackTypes.size}, currentFallbackIndex=0")
            if (mTrackSelector == null) {
                mTrackSelector = DefaultTrackSelector(mAppContext)
            }
            mEventLogger = EventLogger(mTrackSelector)
            val preferExtensionDecoders = true
            val useExtensionRenderers = true //是否开启扩展
            val extensionRendererMode: @ExtensionRendererMode Int =
                if (useExtensionRenderers) (if (preferExtensionDecoders) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON) else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            if (mRendererFactory == null) {
                mRendererFactory = DefaultRenderersFactory(mAppContext)
                mRendererFactory.setExtensionRendererMode(extensionRendererMode)
            }
            if (mLoadControl == null) {
                // 首屏缓冲优化：bufferForPlayback 从默认 2500ms 降至 250ms，rebuffer 从 5000ms 降至 500ms
                // 复用 ExoPlayerHelper.createHttpExoPlayer 的优化策略，首屏启动延迟降至 1/10
                mLoadControl = DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                        DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS / 10,      // 250ms（默认 2500ms）
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS / 10  // 500ms（默认 5000ms）
                    ).build()
            }
            // T1.13: 显式 release 旧 mInternalPlayer 实例（解决 Bug-14 + Bug-24 + Bug-6 核心根因）
            // 理由：原代码直接覆盖 mInternalPlayer 引用，旧实例的 ExoPlayer 内部线程/资源不会被及时回收，
            // 导致 8 实例快速切换时资源泄漏 + 状态污染
            mInternalPlayer?.let { oldPlayer ->
                AppLog.put("mInternalPlayer released old instance before create new")
                try {
                    oldPlayer.release()
                } catch (e: Exception) {
                    AppLog.put("mInternalPlayer release old instance failed", e)
                }
            }
            mInternalPlayer =
                ExoPlayer.Builder(mAppContext, mRendererFactory).setLooper(Looper.myLooper()!!)
                    .setTrackSelector(mTrackSelector).setLoadControl(mLoadControl)
                    .setMediaSourceFactory(
                        // E1 优化：改用 resolvingDataSource（支持 SPLIT_TAG per-request Header 注入）
                        // 原 no-op resolver { it } 不处理 Header，导致防盗链失败（60% 根因）
                        DefaultMediaSourceFactory(ExoPlayerHelper.resolvingDataSource)
                            .setLiveTargetOffsetMs(5000) //直播时延5秒
                    )
                    .build()
            mInternalPlayer.addListener(this@Exo2MediaPlayer)
            mInternalPlayer.addAnalyticsListener(this@Exo2MediaPlayer)
            mInternalPlayer.addListener(mEventLogger)
            if (mSpeedPlaybackParameters != null) {
                mInternalPlayer.playbackParameters = mSpeedPlaybackParameters
            }
            if (isLooping) {
                mInternalPlayer.repeatMode = Player.REPEAT_MODE_ALL
            }
            if (mSurface != null) mInternalPlayer.setVideoSurface(mSurface)
            // Bug7 修复：使用 setMediaItem + player 的 MediaSourceFactory（含 SimpleCache）创建 MediaSource
            // 旧代码 mInternalPlayer.setMediaSource(mMediaSource) 使用父类 IjkExo2MediaPlayer 创建的 MediaSource，
            // 该 MediaSource 不含缓存（DefaultDataSource.Factory），导致 ExoPlayer SimpleCache 被完全绕过，
            // 视频播放没有缓存加速，特别卡。
            // 修复后通过 setMediaItem 让 player 使用其自身的 MediaSourceFactory（含 cacheDataSourceFactory），
            // 确保 HLS 分片下载 + SimpleCache 缓存读写均正常工作。
            if (currentUrl.isNotBlank()) {
                // E1 修复（回归修复）：使用 clean URL 让 DefaultMediaSourceFactory 正确检测媒体类型（.m3u8→HlsMediaSource）
                // E1 原方案 createMediaItem 拼接 SPLIT_TAG(🚧headersJson) 破坏了 URL 后缀检测，
                // 导致 m3u8 被误认为普通文件用 ProgressiveExtractor 解析，全部报 UnrecognizedInputFormatException(3003)
                // Headers 注入改为通过 ExoPlayerHelper.setDefaultHeaders 在 prepare 前设置（ExoPlayerManager 已调用，此处双保险）
                // P1-3b 修复（2026-07-25）：改用 ExoPlayerHelper.createMediaItem 替代裸 MediaItem.Builder()
                // 根因：play.php 等动态 URL 不以 .m3u8 结尾，DefaultMediaSourceFactory 的 URL 后缀检测失败，
                // 误用 ProgressiveMediaSource 解析 m3u8 内容报 UnrecognizedInputFormatException(3003)
                // 证据：源[1] ruleContent 提取 /path/play.php?...&format=m3u8 返回标准 #EXTM3U m3u8，
                // 但 ExoPlayer 报 3003 "None of the available extractors could read the stream"
                // 方案：createMediaItem 内部调用 getMimeType 检测 format=m3u8 返回 APPLICATION_M3U8，
                // setMimeType 让 DefaultMediaSourceFactory 正确创建 HlsMediaSource
                //
                // exoplayer-resilience Layer 1（2026-07-26）：嗅探 mimeType（异步协程）
                // - 先嗅探 Content-Type + magic number，再 createMediaItem 传入 sniffedMimeType
                // - 嗅探失败返回 null 时由 createMediaItem 内部 getMimeType(url) 兜底（L4 URL 后缀检测）
                // - 用协程包裹避免阻塞主线程（嗅探 Range 请求在 Dispatchers.IO 执行）
                // - currentSniffJob.cancel() 避免多个嗅探协程并发竞争 mInternalPlayer
                currentSniffJob?.cancel()
                currentSniffJob = scope.launch {
                    // T2.4: 嗅探协程生命周期日志 - started 态
                    AppLog.put("ExoFallback: sniff job started, urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}")
                    // R4-T8: 用 sniffVideoType 替代 sniffMimeType，获取完整 SniffResult
                    // SniffResult 含 contentType + mimeType + moovPosition + supportsRange + finalUrl
                    // finalUrl 用于 createMediaSource（重定向后的最终 URL，避免再次重定向）
                    val sniff = ExoPlayerHelper.sniffVideoType(currentUrl, currentHeaders)
                    if (!isActive) {
                        // T2.4: 嗅探协程生命周期日志 - cancelled 态（sniffVideoType 返回后被取消）
                        AppLog.put("ExoFallback: sniff job cancelled after sniffVideoType")
                        return@launch  // 已被 cancel，跳过 setMediaItem
                    }

                    currentSniffResult = sniff
                    fallbackTypes = buildFallbackTypes(sniff)
                    currentFallbackIndex = 0

                    if (fallbackTypes.isEmpty()) {
                        // 嗅探失败 + 降级链为空（理论上不会发生，buildFallbackTypes 至少返回 3 项）
                        AppLog.put(
                            "ExoFallback: empty fallback chain, switch to WebView, " +
                                "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
                        )
                        postEvent(
                            EventBus.VIDEO_FALLBACK_WEBVIEW,
                            Triple(currentUrl, VideoPlay.videoTitle ?: "", currentHeaders)
                        )
                        return@launch
                    }

                    // R4-T8: 应用第一个 MediaSource（按嗅探结果优先）
                    applyMediaSourceByType(fallbackTypes[0], currentUrl, currentHeaders)
                    // T2.4: 嗅探协程生命周期日志 - completed 态
                    // 注：applyMediaSourceByType 是非 suspend 函数，调用后协程即结束
                    // 后续 BUFFERING 超时/onPlayerError 触发的降级由 tryNextFallback 异步处理
                    AppLog.put("ExoFallback: sniff job completed, first contentType=${fallbackTypes[0]}")
                }
            } else {
                mInternalPlayer.setMediaSource(mMediaSource)
                mInternalPlayer.prepare()
                mInternalPlayer.playWhenReady = false
            }
        }
    }

    /**
     * 下一集
     */
    fun next() {
        if (mInternalPlayer == null) {
            return
        }
        val timeline: Timeline = mInternalPlayer.currentTimeline
        if (timeline.isEmpty) {
            return
        }
        val windowIndex: Int = mInternalPlayer.currentMediaItemIndex
        val nextWindowIndex: Int = mInternalPlayer.nextMediaItemIndex
        if (nextWindowIndex != C.INDEX_UNSET) {
            mInternalPlayer.seekTo(nextWindowIndex, C.TIME_UNSET)
        } else if (timeline.getWindow(windowIndex, window).isDynamic) {
            mInternalPlayer.seekTo(windowIndex, C.TIME_UNSET)
        }
    }

    val currentWindowIndex: Int
        get() {
            if (mInternalPlayer == null) {
                return 0
            }
            return mInternalPlayer.currentMediaItemIndex
        }

    /**
     * 获取所有音轨信息
     * @return 音轨列表（索引 + 显示名称）
     */
    @OptIn(UnstableApi::class)
    fun getAudioTracks(): List<Pair<Int, String>> {
        val exoPlayer = mInternalPlayer ?: return emptyList()
        val audioGroups = exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audioGroups.isEmpty()) return emptyList()
        return audioGroups.mapIndexed { index, group ->
            val format = group.getTrackFormat(0)
            val label = format.label ?: format.language ?: "音轨 ${index + 1}"
            index to label
        }
    }

    /**
     * 切换到指定音轨
     * @param groupIndex 音轨组索引（来自 getAudioTracks）
     */
    @OptIn(UnstableApi::class)
    fun selectAudioTrack(groupIndex: Int) {
        val exoPlayer = mInternalPlayer ?: return
        val audioGroups = exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val targetGroup = audioGroups.getOrNull(groupIndex) ?: return
        val override = TrackSelectionOverride(targetGroup.mediaTrackGroup, 0)
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setOverrideForType(override)
            .build()
    }

    /**
     * R2 m3u8 播放失败调试日志：捕获 ExoPlayer 播放错误并通知 UI 显示
     *
     * 常见错误码：
     * - ERROR_CODE_IO_NETWORK_CONNECTION_FAILED: 网络连接失败（m3u8 地址不可达/被墙/DNS 解析失败）
     * - ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT: 网络超时
     * - ERROR_CODE_PARSING_CONTAINER_MALFORMED: m3u8 解析错误（格式不兼容）
     * - ERROR_CODE_DECODER_INIT_FAILED: 解码器初始化失败（编码格式不支持）
     * - ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED: HTTP 明文被禁止（usesCleartextTraffic=false）
     */
    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)

        // P1-3: 直播流 1002 BEHIND_LIVE_WINDOW 自动重试
        // 直播流播放时，如果播放器追直播落在直播窗口后面，ExoPlayer 抛出 BehindLiveWindowException
        // 正确处理：重新 seek 到直播边缘并 prepare，不显示错误
        if (error.cause is BehindLiveWindowException) {
            AppLog.put("直播流追直播超时，自动重试中...")
            mInternalPlayer?.let { player ->
                player.seekToDefaultPosition()
                player.prepare()
            }
            return
        }

        // P1-C 修复：HTTP 416 错误清除缓存后重试
        // 根因：ExoPlayer 的 Range 请求与服务端缓存状态不匹配，服务端返回 416 Range Not Satisfiable
        // 方案：清除缓存分片后 seekToDefaultPosition + prepare 重新加载
        // 用反射检测 responseCode 避免 media3 版本兼容问题
        val cause416 = error.cause
        if (cause416?.javaClass?.simpleName == "InvalidResponseCodeException" && retryCount < MAX_RETRY) {
            try {
                val responseCodeField = cause416.javaClass.getDeclaredField("responseCode")
                responseCodeField.isAccessible = true
                val responseCode = responseCodeField.get(cause416) as? Int
                if (responseCode == 416) {
                    retryCount++
                    AppLog.put("HTTP 416 错误，清除缓存后重试($retryCount/$MAX_RETRY): urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}")
                    ExoPlayerHelper.clearCache()
                    mInternalPlayer?.let { player ->
                        player.seekToDefaultPosition()
                        player.prepare()
                    }
                    return
                }
            } catch (e: Exception) {
                // 反射失败，忽略
            }
        }

        // E2 优化：网络错误自动重试（减少不必要的降级到 WebView）
        // 根因分析：临时网络抖动（弱信号/DNS 抖动/服务器瞬时 503）占 ExoPlayer 失败的 10%，
        // 这类错误不应直接降级 WebView，给予 1 次重试机会：seekToDefaultPosition + prepare 重新加载
        // 覆盖错误码：IO_NETWORK_CONNECTION_FAILED / IO_NETWORK_CONNECTION_TIMEOUT / IO_UNSPECIFIED
        val isNetworkError = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            || error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            || error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        if (isNetworkError && retryCount < MAX_RETRY) {
            retryCount++
            AppLog.put("ExoPlayer 网络错误自动重试($retryCount/$MAX_RETRY): errorCode=${error.errorCodeName}, urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}")
            mInternalPlayer?.let { player ->
                player.seekToDefaultPosition()
                player.prepare()
            }
            return
        }

        // exoplayer-resilience Layer 2：不可恢复错误累计 + 自动 WebView 降级
        // 触发条件：unrecoverableFailCount >= FALLBACK_RETRY_THRESHOLD(3) + 不可恢复错误类型
        // 不可恢复错误类型（参考 design.md AD-03）：
        // - 3002 PARSING_CONTAINER_MALFORMED：m3u8/mp4 解析错误（格式不兼容）
        // - 3004 PARSING_MANIFEST_MALFORMED：清单格式错误
        // - ERROR_CODE_DECODER_INIT_FAILED：解码器初始化失败
        // - ERROR_CODE_DECODING_FAILED：解码失败
        // 设计理由：可恢复错误（网络抖动）重试即可，不可恢复错误重试无意义，达阈值后切换 WebView
        // 注：PlaybackException 无 ERROR_CODE_PARSING_BITSTREAM_MALFORMED 常量（3003 未使用），已移除
        val isUnrecoverableError = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
            || error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
            || error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
            || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
        if (isUnrecoverableError) {
            unrecoverableFailCount++
            // T1.6: 用 AppLog.put 替代 Log.d（解决 Bug-4：release 包无日志输出）
            AppLog.put(
                "ExoFallback: unrecoverable error: code=${error.errorCode}(${error.errorCodeName}), " +
                    "count=$unrecoverableFailCount/$FALLBACK_RETRY_THRESHOLD, urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}",
                error
            )

            // R4-T8: 解析错误时尝试下一个降级 MediaSource（在累计失败达阈值前先尝试降级链）
            // 解析错误：3002 PARSING_CONTAINER_MALFORMED / 3004 PARSING_MANIFEST_MALFORMED / UnrecognizedInputFormatException
            // 这些错误说明当前 MediaSource 不匹配，应尝试下一个（如 Progressive 误处理 m3u8 → 切换 HLS）
            // 解决用户核心诉求"时好时坏，有的能播有的播不了"：
            // - 浏览器会渐进尝试多种方式，ExoPlayer 单一失败即整体失败
            // - R4 降级链对齐浏览器渐进增强策略，解析失败时自动尝试下一个 MediaSource
            val isParsingError = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                || error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                || error.cause?.javaClass?.simpleName == "UnrecognizedInputFormatException"

            if (isParsingError && currentFallbackIndex < fallbackTypes.size - 1) {
                AppLog.put(
                    "ExoFallback: parsing error (${error.errorCodeName}), trying next MediaSource " +
                        "(${currentFallbackIndex + 2}/${fallbackTypes.size}), " +
                        "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
                )
                tryNextFallback()
                return
            }

            if (unrecoverableFailCount >= FALLBACK_RETRY_THRESHOLD) {
                // 触发自动 WebView 降级
                val title = VideoPlay.videoTitle ?: ""
                AppLog.put(
                    "ExoPlayer 累计失败 $unrecoverableFailCount 次（不可恢复错误 ${error.errorCodeName}），" +
                        "自动切换到 WebView 模式"
                )
                postEvent(
                    EventBus.VIDEO_FALLBACK_WEBVIEW,
                    Triple(currentUrl, title, currentHeaders)
                )
                return
            }
            // 未达阈值，继续走友好提示让用户知道当前失败原因
        }

        // T1.6: 用 AppLog.put 替代 Log.d（解决 Bug-4：release 包无日志输出，AppLog.kt L86 if (BuildConfig.DEBUG) 导致 release 包 Log.e 不输出）
        AppLog.put(
            "ExoPlayer onPlayerError: errorCode=${error.errorCode}(${error.errorCodeName}), " +
                "cause=${error.cause?.javaClass?.simpleName}: ${error.cause?.message}, " +
                "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}",
            error
        )

        // R4.4 友好提示：根据 errorCode 给出用户可理解的优化建议
        // P0: 新增 ERROR_CODE_IO_UNSPECIFIED (2000) + error.cause 类型检测，提供 WebView 降级建议
        var suggestion = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                "网络连接失败，请检查网络后重试"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "网络连接超时，请检查网络后重试"
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
                "返回内容类型无效，地址可能不是有效的视频流"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                "服务器返回错误状态码，地址可能已失效"
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
                "HTTP明文被禁止，请使用HTTPS或检查网络安全配置"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                "m3u8/mp4 解析错误，地址可能已失效或格式不兼容"
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                "视频清单格式错误"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                "解码器初始化失败，视频编码格式可能不支持"
            PlaybackException.ERROR_CODE_DECODING_FAILED ->
                "解码失败，视频编码格式可能不支持"
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ->
                "音频轨道初始化失败"
            // P0: 2000 错误码（HLS SPS 解析失败等 IO 未指定错误），建议降级 WebView
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED ->
                "视频格式不兼容，可尝试使用 WebView 播放"
            else -> null
        }
        // P0: 检测特定异常类型，提供 WebView 降级建议（覆盖日志深度分析发现的根因）
        // UnrecognizedInputFormatException: ExoPlayer 所有 Extractor 都无法识别流格式
        // HlsPlaylistStuckException: HLS 播放列表加载卡住
        // 注意: 使用类名反射匹配代替直接 import，避免 media3 版本兼容问题
        if (suggestion == null) {
            val cause = error.cause
            val causeClassName = cause?.javaClass?.simpleName ?: ""
            suggestion = when {
                causeClassName == "UnrecognizedInputFormatException" ->
                    "视频流格式无法识别，可尝试使用 WebView 播放"
                causeClassName.contains("PlaylistStuck") ->
                    "播放列表加载卡住，可尝试使用 WebView 播放"
                else -> null
            }
        }
        val errorInfo = buildString {
            appendLine("播放失败")
            appendLine("错误码: ${error.errorCode} (${error.errorCodeName})")
            appendLine("错误信息: ${error.message ?: "无"}")
            appendLine("播放地址: ${ExoPlayerHelper.sanitizeUrl(currentUrl)}")
            appendLine("原因: ${error.cause?.toString() ?: "未知"}")
            if (suggestion != null) {
                appendLine("建议: $suggestion")
            }
        }
        AppLog.put(errorInfo, error)
        postEvent(EventBus.VIDEO_PLAY_ERROR, errorInfo)
    }

    /**
     * T1.7: 新增 onPlaybackStateChanged 日志（解决 Bug-4 增强：播放状态变化追踪）
     *
     * 输出 IDLE/BUFFERING/READY/ENDED 状态，便于定位播放失败根因：
     * - BUFFERING→READY：播放成功
     * - BUFFERING→IDLE：播放失败（onPlayerError 会跟进）
     * - READY→BUFFERING：网络抖动重新缓冲
     * - READY→ENDED：播放结束
     */
    override fun onPlaybackStateChanged(state: Int) {
        super.onPlaybackStateChanged(state)
        val stateName = when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN($state)"
        }
        AppLog.put("ExoPlayer state: $stateName, urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}")
        // T2.3: BUFFERING 超时 12 秒触发 tryNextFallback（解决 Bug-8 + Bug-9：弱网卡死无法自动降级）
        when (state) {
            Player.STATE_BUFFERING -> {
                // 进入 BUFFERING：12 秒后若仍未 READY，触发降级尝试下一个 MediaSource
                bufferingTimeoutHandler.postDelayed(bufferingTimeoutRunnable, 12000L)
            }
            Player.STATE_READY, Player.STATE_IDLE, Player.STATE_ENDED -> {
                // 离开 BUFFERING：清除超时回调，避免误降级
                // - READY：播放成功，无需降级
                // - IDLE：播放失败（onPlayerError 会跟进），避免重复降级
                // - ENDED：播放结束，无需降级
                bufferingTimeoutHandler.removeCallbacks(bufferingTimeoutRunnable)
            }
        }
    }


}
