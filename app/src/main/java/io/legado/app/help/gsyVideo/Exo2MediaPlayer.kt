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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.BehindLiveWindowException
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.exoplayer.PlayerInstancePool
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
import java.util.concurrent.atomic.AtomicBoolean
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer
import tv.danmaku.ijk.media.exo2.demo.EventLogger

class Exo2MediaPlayer(context: Context) : IjkExo2MediaPlayer(context) {
    companion object {
        private const val TAG = "GSYExo2MediaPlayer"
        private const val MAX_POSITION_FOR_SEEK_TO_PREVIOUS: Long = 3000
        // E2 优化：网络错误自动重试次数（避免临时网络抖动直接降级 WebView）
        // T2.4: 指数退避重试策略（对齐 hls.js）：最多重试 5 次（1s/2s/4s/8s/16s）
        private const val MAX_RETRY = 5
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
     * FR-3: scope 取消标志位（AtomicBoolean 保证多线程可见性）
     *
     * 与 isReleased 职责不同：
     * - isReleased (L78)：用于 applyMediaSourceByType 入口检查（防止 setMediaItem）
     * - isScopeCancelled（新增）：用于回调入口检查（防止 onPlaybackStateChanged/onPlayerError/onRenderedFirstFrame 触发业务逻辑）
     *
     * releaseSniffResources 中 set(true)，prepareAsyncInternal 成功后 set(false)
     */
    private val isScopeCancelled = AtomicBoolean(false)

    /**
     * V-003-P0-2: prepareAsyncInternal 重入保护
     *
     * 根因：R5 网络抓包命中后可能多次回调 prepareAsyncInternal（003 日志 9~16ms 内重入），
     * 导致 PlayerInstancePool.acquire 被调用两次，创建多个 ExoPlayer 实例竞争 + TrackSelector 崩溃。
     *
     * 方案：AtomicBoolean CAS 守卫，第一次进入设置 true，post Runnable 完成后重置 false。
     * 重入时跳过并记录日志。
     */
    private val isPreparing = java.util.concurrent.atomic.AtomicBoolean(false)

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
        // A2 修复：首次 BUFFERING 超时 25s（CDN 冷启动），后续 12s
        // isFirstPlay = !VideoPlay.hasPlayedSuccessfully（initSource 重置 false，STATE_READY 置 true）
        // 关键：switchToArticle 不重置 hasPlayedSuccessfully（切换同源文章时 CDN 已热，用 12s）
        // 注意：postDelayed 时已根据 isFirstPlay 设置超时时间，此处仅记录日志
        val isFirstPlay = !VideoPlay.hasPlayedSuccessfully
        AppLog.put(
            "ExoPlayer BUFFERING timeout, trigger fallback, " +
                "isFirstPlay=$isFirstPlay, " +
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
     * T1.1: 播放成功埋点 - 双标志位防同一 URL 重复埋点（对齐 ExoPlayer 官方事件指南）
     *
     * AD-01 决策：STATE_READY 判定播放成功（统计成功率），onRenderedFirstFrame 统计首帧耗时
     * - hasReportedReadySuccess: STATE_READY 已上报标志位
     * - hasReportedFirstFrame: onRenderedFirstFrame 已上报标志位
     * - playbackStartTime: 播放开始时间（prepareAsyncInternal 时记录），用于计算首帧耗时
     */
    private var hasReportedReadySuccess = false
    private var hasReportedFirstFrame = false
    private var playbackStartTime = 0L

    /**
     * R4-T8: 构建降级链类型列表
     *
     * T1.5 V2重构：按嗅探结果排序降级链（解决 Bug-7：降级链默认HLS优先与MP4直链不匹配）
     * V-003-P1-1: 清单类型降级链移除 Progressive（清单→Progressive 必然 3003）
     *
     * 优先级策略：
     * - 嗅探成功（HLS）：[HLS, DASH] —— 清单互降，Progressive 对 m3u8 必然 3003
     * - 嗅探成功（DASH）：[DASH, HLS] —— 清单互降
     * - 嗅探成功（SS）：[SS, HLS, DASH] —— 清单互降
     * - 嗅探成功（Progressive）：[Progressive, HLS, DASH] —— MP4直链优先 Progressive
     * - 嗅探失败（UNKNOWN）：按 URL 后缀启发式，直链保留 Progressive，清单移除 Progressive
     *
     * @param sniff 嗅探结果
     * @return 降级链 contentType 列表
     */
    private fun buildFallbackTypes(sniff: ExoPlayerHelper.SniffResult): List<Int> {
        // P1 嗅探成功率优化（2026-07-28）：区分两种 UNKNOWN 场景
        // 1. mimeType 是 HTML（text/html 等）→ 确实是 HTML 页面，返回空列表直接降级 WebView
        //    （铁证：002日志 /Player/Play.php 返回 text/html，3 次 HLS 重试必然 3002 失败）
        // 2. mimeType 为 null（嗅探超时或网络错误）→ 可能是视频流但嗅探失败，尝试 HLS 优先
        //    （铁证：002日志嗅探超时 6.6s 后直接降级 WebView，但 URL 实际可能是视频流）
        if (sniff.contentType == ExoPlayerHelper.SniffResult.TYPE_UNKNOWN) {
            val mt = sniff.mimeType?.lowercase()
            val isHtmlPage = mt != null && (
                mt.startsWith("text/html") ||
                mt.startsWith("application/xhtml+xml") ||
                mt.startsWith("application/xml")
            )
            if (isHtmlPage) {
                AppLog.put(
                    "ExoFallback: sniff UNKNOWN + HTML mimeType=$mt, skip video fallback, " +
                        "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
                )
                return emptyList()
            }
            // mimeType 为 null（嗅探超时）或非 HTML 类型 → 尝试 HLS 优先（最常见视频格式）
            // HLS 失败后会自动降级 WebView，不会卡死
            // P2-1: 默认降级链改为 [HLS, Progressive]
            AppLog.put(
                "ExoFallback: sniff UNKNOWN + mimeType=$mt, try HLS first, " +
                    "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
            )
            return listOf(C.TYPE_HLS, C.TYPE_OTHER)
        }
        return when (sniff.contentType) {
            // P2-1: 清单类型（HLS/DASH/SS）降级链优化
        // 铁证：003 日志 3 次 HLS BUFFERING 12s 超时 → Progressive → 21 Extractor 全失败 → 3003
        // 修复：HLS 降级链改为 [HLS, Progressive]，第二次 HLS 完全相同必然失败，
        // DASH 对 m3u8 无降级价值，Progressive 可覆盖某些 CDN 的 .m3u8 URL 实际返回 mp4 流的场景
        C.TYPE_HLS -> listOf(C.TYPE_HLS, C.TYPE_OTHER)  // HLS → Progressive
        C.TYPE_DASH -> listOf(C.TYPE_DASH, C.TYPE_DASH, C.TYPE_HLS)  // 前2项DASH重试，第3项HLS兼容
        C.TYPE_SS -> listOf(C.TYPE_SS, C.TYPE_SS, C.TYPE_HLS)  // 前2项SS重试，第3项HLS兼容
        C.TYPE_OTHER -> listOf(C.TYPE_OTHER, C.TYPE_OTHER, C.TYPE_HLS)  // 前2项Progressive重试，第3项HLS兼容
        else -> {
            // UNKNOWN: 按 URL 后缀启发式排序（保留现有逻辑），前 2 项相同 contentType
            when (ExoPlayerHelper.guessTypeByUrl(currentUrl)) {
                C.TYPE_OTHER -> listOf(C.TYPE_OTHER, C.TYPE_OTHER, C.TYPE_HLS)
                C.TYPE_DASH -> listOf(C.TYPE_DASH, C.TYPE_DASH, C.TYPE_HLS)
                C.TYPE_SS -> listOf(C.TYPE_SS, C.TYPE_SS, C.TYPE_HLS)
                else -> listOf(C.TYPE_HLS, C.TYPE_OTHER)  // P2-1: 默认 HLS → Progressive
            }
        }
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
        // P0-2: HLS 内部请求 Header 注入
        // 根因：HlsMediaSource 内部下载 AES-128 密钥和 TS 分片时，使用 okhttpDataFactory 发送请求，
        // 虽然 ExoPlayerManager 在 prepare 前调用了 setDefaultHeaders，但 applyMediaSourceByType 在降级链重试时
        // 可能覆盖/丢失 Header。此处双保险：每次创建 MediaSource 前重新注入 currentHeaders。
        if (currentHeaders.isNotEmpty()) {
            ExoPlayerHelper.setDefaultHeaders(currentHeaders)
        }
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
                        // 缓冲速度优化（P0）：仅解析 m3u8 清单即完成 preparation，首帧耗时降 30%+
                        .setAllowChunklessPreparation(true)
                        // P2-2: 指数退避重试策略（1s/2s/4s/8s/16s），最多 5 次
                        .setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy() {
                            override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                                val errorCount = loadErrorInfo.errorCount
                                if (errorCount >= 5) return C.TIME_UNSET
                                return (1L shl (errorCount - 1).coerceAtLeast(0)) * 1000L
                            }
                        })
                        // R4-T10: AES-128 加密流由 ExoPlayer 内置支持
                        // resolvingDataSource 已注入 Referer/Cookie/UA 防盗链头，
                        // ExoPlayer 内部会用此 factory 获取 #EXT-X-KEY 标签的密钥
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
            val beforeContentType = fallbackTypes[currentFallbackIndex]
            currentFallbackIndex++
            val afterContentType = fallbackTypes[currentFallbackIndex]
            AppLog.put(
                "ExoFallback: trigger reason=BUFFERING_TIMEOUT, " +
                    "before contentType=$beforeContentType, after contentType=$afterContentType, " +
                    "sameContentType=${beforeContentType == afterContentType}, " +
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
        // FR-3: 标记 scope 已取消，后续 onPlaybackStateChanged/onPlayerError/onRenderedFirstFrame 回调忽略
        isScopeCancelled.set(true)
        // FR-2: 同步停止渲染管线——scope.cancel 只取消协程，不解码器/渲染器连接。
        // mInternalPlayer 是父类 IjkExo2MediaPlayer 的 protected 字段，子类可访问。
        // stop() 立即断开解码器/渲染器，防止 cancelled 后仍触发首帧渲染回调（铁证：scope cancelled 后 217ms 仍触发 first frame rendered）
        kotlin.runCatching {
            mInternalPlayer?.let { player ->
                player.stop()
                player.playWhenReady = false
            }
        }
        // T2.3: 清除 BUFFERING 超时回调，避免 onDestroy 后误触发 tryNextFallback 操作已 release 的 mInternalPlayer
        bufferingTimeoutHandler.removeCallbacks(bufferingTimeoutRunnable)
        AppLog.put("ExoPlayer scope cancelled, isReleased=true, isScopeCancelled=true, urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}")
    }

    /**
     * T5.1: 将本实例的 listener 挂到播放器实例上（acquire 后调用）
     */
    private fun attachToPlayer(player: ExoPlayer) {
        player.addListener(this@Exo2MediaPlayer)
        player.addAnalyticsListener(this@Exo2MediaPlayer)
        mEventLogger?.let { player.addListener(it) }
    }

    /**
     * T5.1: 将本实例的 listener 从播放器实例上解绑（recycle 前调用）
     *
     * 池化复用前提：旧 Exo2MediaPlayer 的回调不得残留在池内实例上，
     * 否则后续 acquire 方会收到串扰事件（A Fragment 的播放事件回调到已释放的 B 对象）
     */
    private fun detachFromPlayer(player: ExoPlayer) {
        kotlin.runCatching {
            player.removeListener(this@Exo2MediaPlayer)
            player.removeAnalyticsListener(this@Exo2MediaPlayer)
            mEventLogger?.let { player.removeListener(it) }
        }.onFailure {
            AppLog.put("detachFromPlayer failed", it)
        }
    }

    /**
     * T5.1: 重写 release——mInternalPlayer 归还实例池而非销毁
     *
     * 父类行为（IjkExo2MediaPlayer 11.3.0 字节码实证）：
     * - release() 仅在 mInternalPlayer != null 时调 reset() + mEventLogger = null
     * - reset() 内 mInternalPlayer.release() + ExoSourceManager.release() + surface/尺寸字段清理
     *
     * 拦截策略：先 detach + recycle + 置 null，再调 super.reset()——
     * reset() 见 mInternalPlayer == null 跳过实例销毁，ExoSourceManager/字段清理正常执行，
     * 与父类 release() 行为完全等效（仅实例销毁替换为归还池）
     */
    override fun release() {
        releaseSniffResources()  // 双保险（内部 isReleased 防重复）
        mInternalPlayer?.let { player ->
            detachFromPlayer(player)
            PlayerInstancePool.recycle(player)
            mInternalPlayer = null
            AppLog.put(
                "ExoPlayer release: instance recycled to pool, " +
                    "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
            )
        }
        super.reset()
        mEventLogger = null
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
            // FR-3: 新播放会话开始，清除 scope cancelled 标志（确保回调正常触发）
            isScopeCancelled.set(false)
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
            // T1.1: 重置播放成功埋点标志位，记录播放开始时间（用于首帧耗时统计）
            hasReportedReadySuccess = false
            hasReportedFirstFrame = false
            playbackStartTime = System.currentTimeMillis()
            AppLog.put("ExoPlayer state reset: currentSniffResult=UNKNOWN, fallbackTypesSize=${fallbackTypes.size}, currentFallbackIndex=0")
            // T5.1: 三件套改为实例池全局共享（池实例构建参数兼容的前提）
            // - V-P0-1 修正：TrackSelector 每实例独立（共享单例并发二次 init 崩溃 ×5 FATAL），
            //   mTrackSelector/mEventLogger 移至 acquire 后按 player 实例获取（见下方）
            // - sharedRendererFactory：无状态（EXTENSION_RENDERER_MODE_PREFER 与原配置一致）
            // - LoadControl：池内每实例新建（共享同一 allocator 内存池），档位在实例创建时按当前带宽刷新
            if (mRendererFactory == null) {
                mRendererFactory = PlayerInstancePool.sharedRendererFactory
            }
            if (mLoadControl == null) {
                mLoadControl = PlayerInstancePool.createLoadControl()
            }
            // T5.1: 旧实例归还实例池（替代 T1.13 显式 release）——池内状态重置后供后续复用，
            // 避免快速滑动时反复销毁/重建 ExoPlayer 实例（渲染器初始化+解码器查询约 30-100ms）
            // 同时保留 T1.13 修复精神：旧实例不得直接覆盖引用（先 detach + recycle + 置 null）
            mInternalPlayer?.let { oldPlayer ->
                AppLog.put("mInternalPlayer recycled to pool before acquire")
                detachFromPlayer(oldPlayer)
                PlayerInstancePool.recycle(oldPlayer)
                mInternalPlayer = null
            }
            // T5.1: 从实例池获取（命中复用/未命中新建，构建参数与原逻辑一致：
            // resolvingDataSource 支持 SPLIT_TAG per-request Header 注入 + 直播时延 5 秒）
            mInternalPlayer = PlayerInstancePool.acquire(Looper.myLooper()!!)
            // V-P0-1: TrackSelector 每实例独立——acquire 后从池映射取与本 player 绑定的 selector，
            // EventLogger 必须与 player 使用同一 selector 实例才能追踪轨道选择事件。
            // 复用实例沿用其创建时登记的 selector（recycle 不移除映射，player 存活期间映射有效）。
            mTrackSelector = PlayerInstancePool.trackSelectorOf(mInternalPlayer)
            mEventLogger = mTrackSelector?.let { EventLogger(it) }
            attachToPlayer(mInternalPlayer)
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
                    // V-004-P0-1: 嗅探前 DNS 预解析（异步预热系统 DNS 缓存，降低嗅探耗时）
                    // 根因：004 日志嗅探耗时 3123ms，DNS 解析占主导（DoH 冷启动失败期间等待 2-3s）
                    // 方案：异步预解析域名，Range 请求发起时 DNS 命中缓存（0ms）
                    ExoPlayerHelper.preResolveDns(currentUrl)
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

        // FR-3: scope cancelled 后忽略回调，防止 cancelled 后仍触发重试/降级等业务逻辑
        if (isScopeCancelled.get()) return

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
        // 这类错误不应直接降级 WebView，给予重试机会：seekToDefaultPosition + prepare 重新加载
        // 覆盖错误码：IO_NETWORK_CONNECTION_FAILED / IO_NETWORK_CONNECTION_TIMEOUT / IO_UNSPECIFIED
        //
        // T2.4: 指数退避重试策略（对齐 hls.js）：1s/2s/4s/8s/16s
        // - 第 1 次重试：延迟 1s
        // - 第 2 次重试：延迟 2s
        // - 第 3 次重试：延迟 4s
        // - 第 4 次重试：延迟 8s
        // - 第 5 次重试：延迟 16s
        // 设计理由：立即重试可能在网络未恢复时再次失败，指数退避给网络恢复时间
        //
        // P0-fix: SSL握手失败是确定性错误（CDN拒绝TLS连接），重试不会成功，排除出可恢复网络错误
        // 铁证：91短视频 m3u8 播放，SSLHandshakeException 重试5次全部失败，应直接降级
        val isSslError = error.cause?.toString()?.contains("SSLHandshakeException") == true
            || error.cause?.cause?.toString()?.contains("SSLHandshakeException") == true
        val isNetworkError = !isSslError && (
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            || error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            || error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        )
        if (isNetworkError && retryCount < MAX_RETRY) {
            retryCount++
            // T2.4: 指数退避延迟（1s/2s/4s/8s/16s）
            val delayMs = (1L shl (retryCount - 1)) * 1000L // 2^(n-1) * 1000ms
            AppLog.put(
                "ExoPlayer 网络错误自动重试($retryCount/$MAX_RETRY): " +
                    "errorCode=${error.errorCodeName}, delay=${delayMs}ms, " +
                    "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
            )
            // T2.4: 延迟后重试（给网络恢复时间）
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isReleased) {
                    mInternalPlayer?.let { player ->
                        player.seekToDefaultPosition()
                        player.prepare()
                    }
                }
            }, delayMs)
            return
        }

        // P0-fix: 网络错误重试耗尽后触发降级（SSL握手失败等不可恢复网络错误）
        // 铁证：91短视频 m3u8 播放，SSLHandshakeException 重试5次后卡死，不降级不报错
        // 修复：重试耗尽后走降级链 tryNextFallback，降级链耗尽则触发 VIDEO_FALLBACK_WEBVIEW
        if (isNetworkError && retryCount >= MAX_RETRY) {
            AppLog.put(
                "ExoFallback: network error retry exhausted ($retryCount/$MAX_RETRY), trigger fallback, " +
                    "errorCode=${error.errorCodeName}, urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
            )
            tryNextFallback()
            return
        }

        // P0-fix: SSL握手失败直接降级WebView（确定性错误，重试和降级链无意义）
        // 铁证：站点A m3u8，CDN 重置 TLS 连接，ExoPlayer OkHttp 无法握手
        // 关键：HLS 和 Progressive 用同一个 OkHttp 数据源，SSL 同样会失败，跳过 Progressive 直接 WebView
        // WebView 使用系统 WebView 的 TLS 栈（ conscrypt + Chromium），可成功握手
        if (isSslError) {
            AppLog.put(
                "ExoFallback: SSL handshake failed, switch to WebView directly (skip OkHttp-based fallbacks), " +
                    "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
            )
            postEvent(
                EventBus.VIDEO_FALLBACK_WEBVIEW,
                Triple(currentUrl, VideoPlay.videoTitle ?: "", currentHeaders)
            )
            return
        }

        // exoplayer-resilience Layer 2：不可恢复错误累计 + 自动 WebView 降级
        // 触发条件：unrecoverableFailCount >= FALLBACK_RETRY_THRESHOLD(3) + 不可恢复错误类型
        // 不可恢复错误类型（参考 design.md AD-03）：
        // - 3002 PARSING_CONTAINER_MALFORMED：m3u8/mp4 解析错误（格式不兼容）
        // - 3003 PARSING_CONTAINER_UNSUPPORTED：容器格式不支持（V-P1-2 补入白名单）
        // - 3004 PARSING_MANIFEST_MALFORMED：清单格式错误
        // - ERROR_CODE_DECODER_INIT_FAILED：解码器初始化失败
        // - ERROR_CODE_DECODING_FAILED：解码失败
        // 设计理由：可恢复错误（网络抖动）重试即可，不可恢复错误重试无意义，达阈值后切换 WebView
        // V-P1-2 修正：3003 ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED 真实存在（原注释误判"3003 未使用已移除"），
        // 缺失导致 3003 逃逸白名单 → isParsingError 对 3003 成死代码 → 降级末端失败双触发路径全死
        val isUnrecoverableError = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
            || error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
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
                || error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
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

            // V-P1-2: 末端解析失败（currentFallbackIndex 已在末端）——直接 WebView 兜底 + 错误提示，
            // 不等 unrecoverableFailCount 累计阈值（否则末端失败陷入空转：既不降级也不 WebView 兜底）
            if (isParsingError && currentFallbackIndex >= fallbackTypes.size - 1) {
                val title = VideoPlay.videoTitle ?: ""
                AppLog.put(
                    "ExoFallback: terminal fallback failed (${error.errorCodeName}), trigger WebView, " +
                        "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
                )
                val errorInfo = buildString {
                    appendLine("播放失败：视频格式不支持或地址已失效")
                    appendLine("错误码: ${error.errorCode} (${error.errorCodeName})")
                    appendLine("播放地址: ${ExoPlayerHelper.sanitizeUrl(currentUrl)}")
                    appendLine("建议: 正在切换到 WebView 播放...")
                }
                postEvent(EventBus.VIDEO_PLAY_ERROR, errorInfo)
                postEvent(
                    EventBus.VIDEO_FALLBACK_WEBVIEW,
                    Triple(currentUrl, title, currentHeaders)
                )
                return
            }

            if (unrecoverableFailCount >= FALLBACK_RETRY_THRESHOLD) {
                // T1.2: 重试耗尽后先发送 VIDEO_PLAY_ERROR 事件（UI 错误提示），再触发 VIDEO_FALLBACK_WEBVIEW 降级
                // AD-02: 所有失败场景必须有 UI 提示，不能静默降级
                val title = VideoPlay.videoTitle ?: ""
                val errorInfo = buildString {
                    appendLine("播放失败（已重试 $unrecoverableFailCount 次）")
                    appendLine("错误码: ${error.errorCode} (${error.errorCodeName})")
                    appendLine("错误信息: ${error.message ?: "无"}")
                    appendLine("播放地址: ${ExoPlayerHelper.sanitizeUrl(currentUrl)}")
                    appendLine("原因: ${error.cause?.toString() ?: "未知"}")
                    appendLine("建议: 视频格式不兼容或地址已失效，正在切换到 WebView 播放...")
                }
                AppLog.put(errorInfo, error)
                postEvent(EventBus.VIDEO_PLAY_ERROR, errorInfo)

                // 触发自动 WebView 降级
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
        // FR-3: scope cancelled 后忽略回调，防止 cancelled 后仍触发 BUFFERING 超时/首帧埋点等业务逻辑
        if (isScopeCancelled.get()) return
        val stateName = when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN($state)"
        }
        AppLog.put("ExoPlayer state: $stateName, urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}")

        // T1.1: STATE_READY 播放成功埋点（统计播放成功率）
        // AD-01: 双标志位防同一 URL 重复埋点（seek/rebuffer 后重复 READY 不重复统计）
        if (state == Player.STATE_READY && !hasReportedReadySuccess) {
            hasReportedReadySuccess = true
            // A2 修复：首帧渲染成功，置 VideoPlay.hasPlayedSuccessfully = true
            // 后续切换文章 BUFFERING 超时用 12s（CDN 已热），切换源（initSource）时重置为 false
            VideoPlay.hasPlayedSuccessfully = true
            AppLog.put(
                "ExoPlayer play success (STATE_READY): urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}, " +
                    "contentType=${currentSniffResult.contentType}, fallbackIndex=$currentFallbackIndex/${fallbackTypes.size}"
            )
        }

        // T2.3: BUFFERING 超时 12 秒触发 tryNextFallback（解决 Bug-8 + Bug-9：弱网卡死无法自动降级）
        when (state) {
            Player.STATE_BUFFERING -> {
                // A2 修复：首次 BUFFERING 超时 25s（CDN 冷启动），后续 12s
                // isFirstPlay = !VideoPlay.hasPlayedSuccessfully（initSource 重置，STATE_READY 置 true）
                // 铁证：原硬编码 12000L 导致首次播放也只等 12s 就降级，25s 逻辑未生效
                val isFirstPlay = !VideoPlay.hasPlayedSuccessfully
                val timeoutMs = if (isFirstPlay) 25_000L else 12_000L
                bufferingTimeoutHandler.postDelayed(bufferingTimeoutRunnable, timeoutMs)
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

    /**
     * T1.1: onRenderedFirstFrame 首帧渲染埋点（统计首帧耗时）
     *
     * AD-01 决策：用于验收 Phase 2 首帧预加载效果（目标命中率 ≥80%）
     * - 首帧耗时 = onRenderedFirstFrame 时间 - playbackStartTime
     * - 双标志位防同一 URL 重复埋点
     */
    override fun onRenderedFirstFrame() {
        super.onRenderedFirstFrame()
        // FR-3: scope cancelled 后忽略回调，防止 cancelled 后仍触发首帧埋点业务逻辑
        if (isScopeCancelled.get()) return
        if (!hasReportedFirstFrame) {
            hasReportedFirstFrame = true
            val firstFrameLatency = System.currentTimeMillis() - playbackStartTime
            AppLog.put(
                "ExoPlayer first frame rendered: latency=${firstFrameLatency}ms, " +
                    "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
            )
        }
    }

    // P2 性能监控埋点（2026-07-28，对齐 design.md AD-04）
    // 采集 7 类指标：TTFB/首帧/rebuffer/丢帧/带宽/状态转换/帧偏移
    // release 包通过 AppLog.put 输出（WARN/ERROR 始终输出）

    /**
     * P2: 丢帧率监控（对齐 tasks.md §8.4）
     * - 指标定义：onDroppedVideoFrames 累计 droppedFrames
     * - 告警阈值：单次 droppedFrames > 3 时输出 WARN
     * - 方法名对齐 Media3 1.10.1 AnalyticsListener 接口（非 onDroppedFrames）
     */
    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long
    ) {
        super.onDroppedVideoFrames(eventTime, droppedFrames, elapsedMs)
        if (droppedFrames > 3) {
            AppLog.put(
                "[BufferSpeed] onDroppedVideoFrames: dropped=$droppedFrames, elapsed=${elapsedMs}ms, " +
                    "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
            )
        }
    }

    /**
     * P2: 加载开始时间记录（对齐 tasks.md §8.2，用于计算 TTFB）
     * - 记录 onLoadStarted 时间戳，onLoadCompleted 时计算 TTFB
     */
    private var loadStartTimeMs: Long = 0L

    /**
     * FR-5: TTFB 降档统计字段
     *
     * - ttfbSlowCount: 连续慢 TTFB（>1000ms）计数，达到 3 次触发降档
     * - ttfbFastCount: 连续快 TTFB（<500ms）计数，达到 3 次恢复自动档位
     * - lastSwitchTime: 上次降档时间戳，最小切换间隔 30 秒（防抖动）
     *
     * 注意：只统计 DATA_TYPE_MEDIA（视频分片加载），不统计 manifest/密钥等
     */
    private var ttfbSlowCount = 0
    private var ttfbFastCount = 0
    private var lastSwitchTime = 0L

    /**
     * P2: 加载开始埋点（对齐 tasks.md §8.2）
     * - 记录加载开始时间，用于 onLoadCompleted 时计算 TTFB
     */
    override fun onLoadStarted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        super.onLoadStarted(eventTime, loadEventInfo, mediaLoadData)
        loadStartTimeMs = System.currentTimeMillis()
    }

    /**
     * P2: 加载完成埋点（对齐 tasks.md §8.2 + §8.5）
     * - TTFB = onLoadCompleted 时间 - onLoadStarted 时间
     * - 实际带宽 = loadEventInfo.bytesLoaded / TTFB
     * - 告警阈值：TTFB > 500ms 输出 WARN
     */
    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        super.onLoadCompleted(eventTime, loadEventInfo, mediaLoadData)
        if (loadStartTimeMs > 0) {
            val loadElapsed = System.currentTimeMillis() - loadStartTimeMs
            val bytesLoaded = loadEventInfo.bytesLoaded
            val dataType = mediaLoadData.dataType
            val dataTypeName = when (dataType) {
                C.DATA_TYPE_MEDIA -> "media"
                C.DATA_TYPE_MANIFEST -> "manifest"
                else -> "type$dataType"
            }
            // TTFB > 500ms 输出 WARN（对齐 tasks.md §8.2）
            if (loadElapsed > 500) {
                AppLog.put(
                    "[BufferSpeed] onLoadCompleted SLOW: ttfb=${loadElapsed}ms, " +
                        "bytes=$bytesLoaded, dataType=$dataTypeName, " +
                        "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
                )
            }
            // FR-5: 连续慢/快 TTFB 强制降档/恢复（只统计视频分片加载 DATA_TYPE_MEDIA）
            if (dataType == C.DATA_TYPE_MEDIA) {
                if (loadElapsed > 1000) {
                    ttfbSlowCount++
                    ttfbFastCount = 0
                    if (ttfbSlowCount >= 3 && System.currentTimeMillis() - lastSwitchTime > 30_000) {
                        val currentTier = ExoPlayerHelper.getCurrentBandwidthTier()
                        val newTier = when (currentTier) {
                            ExoPlayerHelper.BandwidthTier.GOOD -> ExoPlayerHelper.BandwidthTier.MEDIUM
                            ExoPlayerHelper.BandwidthTier.MEDIUM -> ExoPlayerHelper.BandwidthTier.WEAK
                            else -> null
                        }
                        if (newTier != null) {
                            ExoPlayerHelper.forceTier = newTier
                            lastSwitchTime = System.currentTimeMillis()
                            AppLog.put(
                                "FR-5: force downgrade to $newTier, ttfbSlowCount=$ttfbSlowCount, " +
                                    "ttfb=${loadElapsed}ms, urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
                            )
                        }
                        ttfbSlowCount = 0
                    }
                } else if (loadElapsed < 500) {
                    ttfbFastCount++
                    if (ttfbFastCount >= 3 && ExoPlayerHelper.forceTier != null) {
                        ExoPlayerHelper.forceTier = null
                        AppLog.put(
                            "FR-5: recover to auto tier, ttfbFastCount=$ttfbFastCount, " +
                                "ttfb=${loadElapsed}ms, urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
                        )
                        ttfbFastCount = 0
                    }
                    ttfbSlowCount = 0
                }
            }
            loadStartTimeMs = 0L
        }
    }

    /**
     * P2: 带宽采样埋点（对齐 tasks.md §8.5）
     * - 指标定义：onBandwidthEstimate 返回的 bitrateEstimate（Media3 1.10.1 接口，非 onBandwidthSample）
     * - 告警阈值：< 1Mbps 输出 WARN（弱网提示）
     * - 参数说明：totalLoadTimeMs 累计加载耗时 / totalBytesLoaded 累计加载字节 / bitrateEstimate 估算比特率(bps)
     */
    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long
    ) {
        super.onBandwidthEstimate(eventTime, totalLoadTimeMs, totalBytesLoaded, bitrateEstimate)
        // < 1Mbps 输出 WARN（弱网提示，bitrateEstimate 单位为 bps）
        if (bitrateEstimate in 1..999_999L) {
            AppLog.put(
                "[BufferSpeed] onBandwidthEstimate WEAK: bitrate=${bitrateEstimate}bps, " +
                    "loadTime=${totalLoadTimeMs}ms, bytes=$totalBytesLoaded, " +
                    "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
            )
        }
    }


}
