package io.legado.app.help.gsyVideo

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
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
import androidx.media3.extractor.DefaultExtractorsFactory
import io.legado.app.help.exoplayer.CencDrmInitInjectingExtractorsFactory
import io.legado.app.help.exoplayer.CencDrmSessionProvider
import io.legado.app.help.exoplayer.CencKeyStore
import io.legado.app.help.exoplayer.CencLocalFallback
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.exoplayer.HlsKeyDataSourceFactory
import io.legado.app.help.exoplayer.BufferTierPolicy
import io.legado.app.help.exoplayer.CacheSizePolicy
import io.legado.app.help.exoplayer.DecodeHintPolicy
import io.legado.app.help.exoplayer.LONG_VIDEO_CACHE_THRESHOLD_MS
import io.legado.app.help.exoplayer.LivePlaybackPolicy
import io.legado.app.help.exoplayer.ManifestObserver
import io.legado.app.help.exoplayer.NetworkGate
import io.legado.app.help.exoplayer.PlayerInstancePool
import io.legado.app.help.exoplayer.PrebuiltPlayerHolder
import io.legado.app.help.exoplayer.PrefetchSnapshot
import io.legado.app.help.exoplayer.PrefetchNetPolicy
import io.legado.app.help.exoplayer.SegmentPrefetchPolicy
import io.legado.app.help.exoplayer.ThroughputEstimator
import io.legado.app.help.exoplayer.VideoSegmentPrefetcher
import io.legado.app.help.exoplayer.shouldWriteCache
import io.legado.app.help.player.PlaybackErrorAction
import io.legado.app.help.player.PlaybackErrorPolicy
import io.legado.app.constant.AppLog
import io.legado.app.help.video.VideoTrace
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

/**
 * UNKNOWN 嗅探结果下的降级链顺序（纯函数，单测锁定）。
 *
 * 预检确认「内容不是 m3u8 清单」时 Progressive 必须排首位——否则 HLS 解析必然失败，
 * GSY 判错后会释放播放器，后续降级再无机会（2026-10-24 真机实证：自动连播黑屏）。
 */
internal fun unknownFallbackOrder(preferProgressive: Boolean): List<Int> =
    if (preferProgressive) listOf(C.TYPE_OTHER, C.TYPE_HLS) else listOf(C.TYPE_HLS, C.TYPE_OTHER)

/**
 * CENC 播放门禁（纯函数，单测锁定）：kid 合法 + encryption=cenc-aes-ctr + 当前是**网络地址**。
 *
 * 为什么不是「URL 全等」：源站可能 302/拼接参数导致实际播放 URL 与声明地址不同
 * （真机实测全等比较会让 DRM 静默失效）；而本地明文降级文件是 `file://` ⇒ 天然不命中。
 * 残留风险由采集链每次入口 `VideoPlay.clearCenc()` 兜住。
 */
internal fun isCencPlaybackActive(currentUrl: String, kid: String?, encryption: String?): Boolean =
    CencDrmSessionProvider.isCencEncryption(encryption) &&
        CencDrmSessionProvider.normalizeKid(kid) != null &&
        currentUrl.startsWith("http")

class Exo2MediaPlayer(context: Context) : IjkExo2MediaPlayer(context) {
    companion object {
        private const val TAG = "GSYExo2MediaPlayer"
        private const val MAX_POSITION_FOR_SEEK_TO_PREVIOUS: Long = 3000
        // E2 优化：网络错误自动重试次数（避免临时网络抖动直接降级 WebView）
        // T2.4: 指数退避重试策略（对齐 hls.js）：最多重试 5 次（1s/2s/4s/8s/16s）
        private const val MAX_RETRY = 5
        // exoplayer-resilience Layer 2：不可恢复错误累计阈值
        // 累计失败次数 >= 此值 + 不可恢复错误类型时触发 VIDEO_PLAY_ERROR 统一错误提示
        private const val FALLBACK_RETRY_THRESHOLD = 3
        // video-play-7001-videograph-fix 2.3/AD-03：单次播放会话内 7001 重建上限（防重建循环）
        private const val MAX_7001_REBUILD = 2
        // video-regression-fix-0906 AD-01：单次播放会话内 4003 解码失败重建上限（1 次）
        // 铁证：用户真机日志"播放失败"均为 4003 + queueInputBuffer at Released state
        // （快速切换视频时旧 MediaCodec 释放与新实例竞争），重建实例即可恢复
        private const val MAX_4003_REBUILD = 1
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
     * video-play-7001-videograph-fix 2.3：当前播放会话内 7001 重建计数
     * 新 prepare 会话（prepareAsyncInternal）时归零，单会话内超过 MAX_7001_REBUILD 走降级链
     */
    private var rebuild7001Count = 0

    /**
     * video-regression-fix-0906 AD-01：当前播放会话内 4003 解码失败重建计数
     * 新 prepare 会话时归零，单会话内超过 MAX_4003_REBUILD 走既有不可恢复错误链
     */
    private var rebuild4003Count = 0

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

    // video-sniff-403-and-rss-classic-fix 2.8c/R-P1-9（Z5）：isPreparing 重入保护已删除——
    // 历史核实：V-003-P0-2 引入的 AtomicBoolean 自始至终无任何 CAS 读写（死代码），
    // 其防护目标（R5 双命中重复 acquire）已由 prepareAsyncInternal 的
    // "lastPrepareUrl+lastPrepareHeaders+currentSniffJob.isActive 跳过重复调用"守卫承担（L538-548）。
    // 如未来重入复现，在 prepareAsyncInternal 入口恢复 CAS 守卫并实际读写。

    /**
     * E2 优化：网络错误重试计数（prepareAsyncInternal 时重置）
     */
    private var retryCount = 0

    /**
     * §13.8 卡顿计时：只统计起播成功后的稳态缓冲。
     * seek 后的稳定期与小于 200ms 的换段抖动由 [RebufferTracker] 过滤（真机误报修正）。
     * 卡顿时长上报 [io.legado.app.model.VideoPlay.onRebuffer] 做会话累计与自愈提示。
     */
    private val rebufferTracker = RebufferTracker(clock = { android.os.SystemClock.uptimeMillis() })

    /**
     * exoplayer-resilience Layer 2：累计播放失败次数（不可恢复错误才计数）
     * - 可恢复错误（网络抖动）不计数，仅 retryCount 重试
     * - 不可恢复错误（3002/3003/3004/decoder）计数
     * - retryExoPlayback 用户重试时重置为 0
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
                "fallbackIndex=$currentFallbackIndex/${fallbackTypes.size}, " +
                // video-regression-fix-0906 日志增强：实例指纹（超时实例是否为池复用死实例的直接证据）
                "playerId=${System.identityHashCode(mInternalPlayer)}"
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
     * CENC Clear-Key（AD-05）：书源声明的加密标识与 kid（32 位 hex）。
     * 两者同时有效时才启用 DRM；null/空 ⇒ 完全走原明文播放路径。
     */
    var cencKid: String? = null
    var cencEncryption: String? = null

    /** 声明对应远端地址：仅当 currentUrl 与之匹配才启用 DRM（本地降级地址不匹配） */
    var cencUrl: String? = null

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
     * - 达到 fallbackTypes.size 时触发 VIDEO_PLAY_ERROR 统一错误提示
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
     * tasks §13.3/§13.4：媒体总时长快照（毫秒，未知 0）。
     *
     * **只能由应用线程写入、任意线程读取**：缓存写 sink 在 `CacheDataSource` 的加载线程
     * 现算分流，若在那里直接读 `mInternalPlayer.duration` 会抛
     * `IllegalStateException: Player is accessed on the wrong thread`
     * （真机铁证 2026-11-08 02:12，errorCode=1004 ⇒ 播放失败弹窗）。
     * 故此处仅在状态回调（应用线程）写快照，供应器只读该 volatile 字段。
     */
    @Volatile
    private var knownDurationMs = 0L

    /** video-buffer-prefetch-v2：当前视频码率（bps，READY 时从 Format 读取） */
    private var knownBitrateBps = 0L

    /** 当前预取会话号（每次 prepare 递增并同步给 VideoSegmentPrefetcher） */
    private var prefetchSessionId = 0L

    /** 灰条单调化基线（同一会话只进不退；seek/换集归零） */
    private var readyPercentFloor = 0

    /** IF-16 诊断：灰条回退日志限流（每会话最多 20 条） */
    private var readyDropLogCount = 0

    /** 近 30s 的 rebuffer 时间戳（档位裁决用） */
    private val recentRebufferAt = ArrayDeque<Long>()

    /** 上次档位切换时间 */
    private var lastTierSwitchAt = 0L

    /** 解码提示采样 */
    private var decodeSampleStartAt = 0L
    private var droppedSinceDecodeSample = 0
    private var decodeHinted = false

    /** 直播 offset 归因日志只打一次 */
    private var liveLogged = false

    /**
     * video-buffer-prefetch-v2 §1.19：per-load 实测吞吐 EMA（bps）。
     *
     * 带宽计的 bitrateEstimate 在高带宽下更新滞后（真机/模拟器实测长期停在初始值），
     * 故用 onLoadCompleted 的真实吞吐补位，取值见 [effectiveBandwidthBps]。
     */
    private var observedThroughputBps = 0L

    /** 最近一次有效吞吐样本时间（elapsedRealtime，过期后不采信） */
    private var observedThroughputAt = 0L

    /** 回看清理节流 */
    private var lastBackCacheTrimAt = 0L

    /** 预取补水定时器（5s；READY 期间运行） */
    private val prefetchTickHandler = Handler(Looper.getMainLooper())
    private val prefetchTickRunnable = object : Runnable {
        override fun run() {
            if (isReleased || isScopeCancelled.get()) return
            val player = mInternalPlayer ?: return
            if (player.playbackState != Player.STATE_READY) return
            updatePrefetchSnapshot()
            VideoSegmentPrefetcher.topUp(force = false)
            // 完成度联动（R37/AD-26）：本集预取完成后把带宽转下一集（仅 FAST+，每集一次）
            if (VideoSegmentPrefetcher.isEpisodeComplete(prefetchSessionId)) {
                io.legado.app.model.VideoPlay.onEpisodePrefetchComplete(prefetchSessionId)
            }
            maybeTrimBackCache()
            prefetchTickHandler.postDelayed(this, 5_000L)
        }
    }

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
    private fun buildFallbackTypes(
        sniff: ExoPlayerHelper.SniffResult,
        preferProgressive: Boolean = false
    ): List<Int> {
        // P1 嗅探成功率优化（2026-07-28）：区分两种 UNKNOWN 场景
        // 1. mimeType 是 HTML（text/html 等）→ HTML 页面形态（可能为壳内直链视频源）
        //    video-sniff-403-and-rss-classic-fix Phase 4 (5.5b/Z15·F-12)：不再返回空列表
        //    （.html 一票否决与 F-12"排除规则后置"语义对齐），保留降级链走 HLS→Progressive，
        //    链尾失败仍由既有失败承接通道（WebView 抓包/错误提示）承接，不产生承接真空
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
                    "ExoFallback: sniff UNKNOWN + HTML mimeType=$mt, keep fallback chain (5.5b F-12), " +
                        "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
                )
                // video-sniff-403-and-rss-classic-fix Phase 4 (5.5b/Z15·F-12)：原 emptyList()（空降级链）
                // 改为保留降级链——.html 页面可能为壳内直链视频（R6/F-12 对齐）
                return listOf(C.TYPE_HLS, C.TYPE_OTHER)
            }
            // mimeType 为 null（嗅探超时）或非 HTML 类型 → 尝试 HLS 优先（最常见视频格式）
            // HLS 失败后会自动降级 WebView，不会卡死
            // P2-1: 默认降级链改为 [HLS, Progressive]
            //
            // 2026-10-24 真机实证补丁：预检明确「不是 m3u8 清单」时把 Progressive 排首位。
            // 原实现 HLS 优先 ⇒ 内容为 MP4/HTML 时 HLS 解析必然失败，GSY 判错后直接释放播放器，
            // 后续降级无机会 ⇒ 自动连播到该集后永久黑屏。
            if (preferProgressive) {
                AppLog.put(
                    "ExoFallback: sniff UNKNOWN + preCheck 非 m3u8, try Progressive first, " +
                        "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
                )
                return unknownFallbackOrder(preferProgressive = true)
            }
            AppLog.put(
                "ExoFallback: sniff UNKNOWN + mimeType=$mt, try HLS first, " +
                    "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
            )
            return unknownFallbackOrder(preferProgressive = false)
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
     * CENC 声明是否生效：见 [isCencPlaybackActive]（纯函数，便于单测）。
     */
    private fun isCencActive(): Boolean =
        isCencPlaybackActive(currentUrl, cencKid, cencEncryption)

    /**
     * 统一 MediaItem 构建（AD-05）：嗅探 mimeType + 条件式 CENC DrmConfiguration。
     * 非 CENC 媒体与改造前完全等价（仅多了 mimeType 设置）。
     */
    private fun buildCencAwareMediaItem(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        currentSniffResult.mimeType?.let { builder.setMimeType(it) }
        if (!isCencActive()) return builder.build()
        return CencDrmSessionProvider.attachCencIfNeeded(builder, cencKid, cencEncryption).build()
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
        // 诊断（保留）：CENC 门禁要素，便于排查「明文文件却被当加密轨 / DRM 未激活」
        AppLog.put(
            "CencGate: contentType=$contentType, active=${isCencActive()}, " +
                "kidNull=${cencKid == null}, encNull=${cencEncryption == null}, " +
                "cencUrlNull=${cencUrl == null}, isHttp=${currentUrl.startsWith("http")}"
        )
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
        if (currentHeaders.isNotEmpty() || io.legado.app.help.exoplayer.M3u8UrlShape.matches(url)) {
            // HLS AES-128 清单的 key 请求需 Referer（无 Referer 时 URLError）；已有 Referer 不覆盖。
            // 注意用**原始清单 URL**（而非重定向后的 CDN 地址）作 Referer —— 站点校验的是代理域名。
            ExoPlayerHelper.setDefaultHeaders(
                io.legado.app.help.exoplayer.M3u8UrlShape
                    .headersWithHlsReferer(currentHeaders, url)
            )
        }
        try {
            val mediaSource: MediaSource = when (contentType) {
                C.TYPE_HLS -> {
                    val mediaItem = buildCencAwareMediaItem(effectiveUrl)
                    // video-sniff-403-and-rss-classic-fix 2.8b/R-P1-8（Z3）：HlsKeyDataSourceFactory 接入主链路——
                    // 对 AES-128 key URL 额外注入 currentPlayHeaders 防盗链头（部分 CDN 对 key 请求校验更严格），
                    // 对齐旧入口 createMediaSource 的 P1-8 包装（原先仅旧入口接入，主链路缺失）
                    val hlsDataSourceFactory =
                        HlsKeyDataSourceFactory().wrap(ExoPlayerHelper.resolvingDataSource)
                    HlsMediaSource.Factory(hlsDataSourceFactory)
                        // CENC Clear-Key（AD-01）：条件式 DRM，未声明 cenc-aes-ctr 的媒体行为不变
                        .setDrmSessionManagerProvider(ExoPlayerHelper.cencDrmSessionProvider)
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
                    val mediaItem = buildCencAwareMediaItem(effectiveUrl)
                    DashMediaSource.Factory(ExoPlayerHelper.resolvingDataSource)
                        // CENC Clear-Key（AD-01）：条件式 DRM，未声明 cenc-aes-ctr 的媒体行为不变
                        .setDrmSessionManagerProvider(ExoPlayerHelper.cencDrmSessionProvider)
                        .createMediaSource(mediaItem)
                }
                C.TYPE_SS -> {
                    val mediaItem = buildCencAwareMediaItem(effectiveUrl)
                    SsMediaSource.Factory(ExoPlayerHelper.resolvingDataSource)
                        // CENC Clear-Key（AD-01）：条件式 DRM，未声明 cenc-aes-ctr 的媒体行为不变
                        .setDrmSessionManagerProvider(ExoPlayerHelper.cencDrmSessionProvider)
                        .createMediaSource(mediaItem)
                }
                C.TYPE_OTHER -> {
                    val mediaItem = buildCencAwareMediaItem(effectiveUrl)
                    // CENC 且源流缺 pssh 时，用注入式 ExtractorsFactory 给 Format 补 ClearKey schemeData（AD-04）
                    val progressiveFactory = if (isCencActive()) {
                        ProgressiveMediaSource.Factory(
                            ExoPlayerHelper.resolvingDataSource,
                            CencDrmInitInjectingExtractorsFactory(DefaultExtractorsFactory(), cencKid!!)
                        )
                    } else {
                        ProgressiveMediaSource.Factory(ExoPlayerHelper.resolvingDataSource)
                    }
                    progressiveFactory
                        .setDrmSessionManagerProvider(ExoPlayerHelper.cencDrmSessionProvider)
                        // 注: media3 1.10.1 ProgressiveMediaSource.Factory 无 setExtractorsFactory，
                        // 需在构造时传入 ExtractorsFactory（CENC 注入走构造参数）
                        .createMediaSource(mediaItem)
                }
                else -> {
                    AppLog.put("ExoFallback: unknown contentType=$contentType, skip")
                    return
                }
            }
            // 降级重建必须**延续原播放意图**：原实现无条件 `playWhenReady = false`，
            // 而降级路径之后没有任何 `start()` 调用 ⇒ 自动连播/失败降级后画面停在 00:00 黑屏
            //（2026-10-24 真机实证：m3u8 解析失败回退 Progressive 后不再起播）。
            val wantPlay = mInternalPlayer?.playWhenReady == true
            mInternalPlayer?.setMediaSource(mediaSource)
            mInternalPlayer?.prepare()
            mInternalPlayer?.playWhenReady = wantPlay
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
     * 全部降级失败时触发 VIDEO_PLAY_ERROR 统一错误提示（由 UI 弹"重试/系统浏览器"对话框）
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
            // 全部降级失败，触发统一错误提示（video-sniff-403-and-rss-classic-fix Phase 2 (3.5)：
            // WebView 播放器已删除，原 VIDEO_FALLBACK_WEBVIEW 改 VIDEO_PLAY_ERROR"重试/系统浏览器"承接）
            AppLog.put(
                "ExoFallback: all fallback exhausted (${fallbackTypes.size} types tried), " +
                    "prompt retry/browser, urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
            )
            val errorInfo = buildString {
                appendLine("播放失败：全部解码方式均失败")
                appendLine("播放地址: ${ExoPlayerHelper.sanitizeUrl(currentUrl)}")
                appendLine("建议: 可重试，或复制到系统浏览器播放")
            }
            // video-regression-fix-0906 AD-03：全降级失败清书源解析登记（防短路重放持续复用失效地址）
            VideoPlay.resolvedChapterUrl = null
            postEvent(EventBus.VIDEO_PLAY_ERROR, errorInfo)
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
        // 诊断日志（永久保留）：释放点必须可追溯到调用方——历史故障中「非当前 Fragment 的
        // onDestroyView 释放了全局嗅探资源 ⇒ 正在播放的集被掐断」难以定位，仅记方法名不含业务字段
        AppLog.put(
            "Exo2MediaPlayer.releaseSniffResources caller=" +
                Throwable().stackTrace.drop(1).take(6).joinToString("<-") { it.methodName }
        )
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
        // video-buffer-prefetch-v2：停止预取补水与在途任务
        prefetchTickHandler.removeCallbacks(prefetchTickRunnable)
        VideoSegmentPrefetcher.stop()
        AppLog.put("ExoPlayer scope cancelled, isReleased=true, isScopeCancelled=true, urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}")
    }

    /**
     * T5.1: 将本实例的 listener 挂到播放器实例上（acquire 后调用）
     */
    private fun attachToPlayer(player: ExoPlayer) {
        player.addListener(this@Exo2MediaPlayer)
        player.addAnalyticsListener(this@Exo2MediaPlayer)
        mEventLogger?.let { player.addListener(it) }
        // tasks §13.1：缓存命中率出口（CacheDataSource 事件 → 本实例统计）
        ExoPlayerHelper.cacheMetricsSink = { cachedBytes -> onCacheBytesRead(cachedBytes) }
        // tasks §13.3/§13.4：缓存写策略所需的当前时长。
        // **不得在此 lambda 内读 player**（sink 在加载线程创建 ⇒ 跨线程访问播放器会抛 1004），
        // 只读应用线程写入的时长快照。
        ExoPlayerHelper.currentDurationProvider = { knownDurationMs }
        // video-buffer-prefetch-v2：写缓存 gate 快照（直播判定在 READY 后更新）
        ExoPlayerHelper.currentIsLiveSnapshot = false
        ExoPlayerHelper.currentBackCacheSecSnapshot = VideoPlay.longVideoBackCacheSec
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
            // tasks §13.1：解绑缓存指标出口，避免写入已释放实例
            ExoPlayerHelper.cacheMetricsSink = null
            ExoPlayerHelper.currentDurationProvider = null
            ExoPlayerHelper.currentIsLiveSnapshot = false
            ExoPlayerHelper.currentBackCacheSecSnapshot = 0
            prefetchTickHandler.removeCallbacks(prefetchTickRunnable)
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
                    "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}, " +
                    // video-regression-fix-0906 日志增强：实例指纹（追踪池复用死实例）
                    "playerId=${System.identityHashCode(mInternalPlayer)}"
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
            // video-play-7001-videograph-fix 2.3：新播放会话重置 7001 重建计数
            rebuild7001Count = 0
            // video-regression-fix-0906 AD-01：新播放会话重置 4003 重建计数
            rebuild4003Count = 0
            // exoplayer-resilience Layer 2：新播放重置不可恢复错误计数
            // 理由：新播放/切换视频/切回 ExoPlayer 时给新的累计机会，避免历史失败影响当前播放
            unrecoverableFailCount = 0
            // W2 / REQ-13：新播放会话复位「播放错误自愈」记账（与上方各计数同口径 ⇒ 不跨会话串扰）
            VideoPlay.routeSelfHealSession.reset()
            VideoPlay.lastPlaybackErrorKind = null
            // T1.12: 重置嗅探状态变量（解决 Bug-13：切换视频时状态变量未重置导致降级链错乱）
            // 在 prepareAsyncInternal 入口重置，确保每次新播放都从干净状态开始
            currentSniffResult = ExoPlayerHelper.SniffResult.UNKNOWN
            fallbackTypes = buildFallbackTypes(currentSniffResult)
            currentFallbackIndex = 0
            // T1.1: 重置播放成功埋点标志位，记录播放开始时间（用于首帧耗时统计）
            hasReportedReadySuccess = false
            hasReportedFirstFrame = false
            playbackStartTime = System.currentTimeMillis()
            // tasks §13.3/§13.4：新会话未知时长先归零（按短视频处理，宁多写不漏写）
            knownDurationMs = 0L
            // video-buffer-prefetch-v2：新会话 → 重置预取会话/统计，并同步本次下发的倍速
            ExoPlayerHelper.playbackSpeedSnapshot = mSpeedPlaybackParameters?.speed ?: 1f
            prefetchSessionId = ManifestObserver.newSession()
            VideoSegmentPrefetcher.beginSession(prefetchSessionId)
            // 新会话：灰条从 0 重新起算（上一集的就绪基线不能串集）
            readyPercentFloor = 0
            knownBitrateBps = 0L
            recentRebufferAt.clear()
            // §1.19：新会话沿用上次实测吞吐作为先验（换集不改变网络能力），
            // 首个新样本到达后由 EMA 纠正；避免连播时退回带宽计初始值导致窗口/预算变保守。
            observedThroughputBps = ExoPlayerHelper.lastObservedThroughputBps
            observedThroughputAt = if (observedThroughputBps > 0L) {
                android.os.SystemClock.elapsedRealtime()
            } else {
                0L
            }
            lastTierSwitchAt = 0L
            decodeSampleStartAt = 0L
            droppedSinceDecodeSample = 0
            decodeHinted = false
            liveLogged = false
            lastBackCacheTrimAt = 0L
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
                    // CENC 软件降级（AD-04）：设备不具备 ClearKey 时，先本地解密再按普通文件播放
                    if (isCencActive() && CencLocalFallback.isRequired(cencKid, cencEncryption)) {
                        val kid = CencDrmSessionProvider.normalizeKid(cencKid)
                        val key = kid?.let { CencKeyStore.provider.key(it) }
                        if (kid != null && key != null) {
                            val local = CencLocalFallback.prepare(currentUrl, currentHeaders, kid, key)
                            if (!isActive) return@launch
                            if (local != null) {
                                currentUrl = Uri.fromFile(local).toString()
                                currentHeaders = emptyMap()
                                cencKid = null
                                cencEncryption = null
                                currentSniffResult = ExoPlayerHelper.SniffResult(C.TYPE_OTHER, null)
                                AppLog.put("CencFallback: 切换到本地明文文件播放")
                                applyMediaSourceByType(C.TYPE_OTHER, currentUrl, emptyMap())
                                return@launch
                            }
                        } else {
                            AppLog.put("CencFallback: 缺少 kid 或 key，无法软件解密, kidLen=${cencKid?.length ?: 0}")
                        }
                    }
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
                    // 2026-10-24：UNKNOWN（无后缀/无 MIME）时再做一次内容预检，明确不是 m3u8 清单
                    // 就把 Progressive 排首位，避免注定失败的 HLS 解析触发 GSY 释放播放器（黑屏）
                    var preferProgressive = false
                    if (sniff.contentType == ExoPlayerHelper.SniffResult.TYPE_UNKNOWN) {
                        val verdict = ExoPlayerHelper.detectM3u8Manifest(currentUrl, currentHeaders)
                        AppLog.put(
                            "ExoFallback: M3U8 precheck verdict=$verdict, " +
                                "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
                        )
                        preferProgressive = verdict == false
                    }
                    fallbackTypes = buildFallbackTypes(sniff, preferProgressive)
                    currentFallbackIndex = 0

                    // video-sniff-403-and-rss-classic-fix 2.6/R-P1-4：预检被源站确定性拒绝（auth-retry 后仍 403/410）
                    // 不再黑屏直连（原逻辑会用注定 403 的地址创建 HLS MediaSource → onPlayerError → 黑屏等待），
                    // 直接发 VIDEO_PLAY_ERROR 提示用户（错误对话框提供"重试/系统浏览器"通道）
                    if (sniff.preCheckRejected) {
                        AppLog.put(
                            "ExoFallback: preCheck rejected after auth-retry, skip doomed direct play, " +
                                "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
                        )
                        val rejectInfo = buildString {
                            appendLine("播放失败")
                            appendLine("错误信息: 视频地址被源站拒绝（防盗链 403），已尝试补齐鉴权头重试仍失败")
                            appendLine("播放地址: ${ExoPlayerHelper.sanitizeUrl(currentUrl)}")
                            appendLine("建议: 请稍后重试，或切换线路/源；也可复制到系统浏览器播放")
                        }
                        postEvent(EventBus.VIDEO_PLAY_ERROR, rejectInfo)
                        return@launch
                    }

                    if (fallbackTypes.isEmpty()) {
                        // 嗅探失败 + 降级链为空（理论上不会发生，buildFallbackTypes 至少返回 3 项）
                        AppLog.put(
                            "ExoFallback: empty fallback chain, prompt error, " +
                                "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
                        )
                        // Phase 2 (3.5)：原 VIDEO_FALLBACK_WEBVIEW 改统一错误提示
                        postEvent(
                            EventBus.VIDEO_PLAY_ERROR,
                            "播放失败：视频地址嗅探失败且无可用降级方式，请重试或复制到系统浏览器播放"
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

        runCatching {
            VideoTrace.event(
                stage = "player_error",
                "code" to error.errorCode,
                "name" to error.errorCodeName,
                "playerId" to mInternalPlayer?.let { System.identityHashCode(it) },
                "retry" to retryCount,
                "url" to VideoTrace.urlPath(currentUrl)
            )
        }

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

        // video-sniff-403-and-rss-classic-fix 2.8/R-P1-6：403/410 确定性拒绝快速补头重试
        // 复用 416 反射先例读取 responseCode；补 CookieManager 实时 Cookie 后立即重试一次，
        // 缩短黑屏窗口（不进指数退避/长降级链）；重试仍失败走原降级链兜底
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && retryCount < MAX_RETRY) {
            val cause403 = error.cause
            if (cause403?.javaClass?.simpleName == "InvalidResponseCodeException") {
                try {
                    val responseCodeField = cause403.javaClass.getDeclaredField("responseCode")
                    responseCodeField.isAccessible = true
                    val responseCode = responseCodeField.get(cause403) as? Int
                    // 策略抽取（纯函数单测）：403/410/451 才补头重试，其余走降级链
                    if (responseCode != null &&
                        PlaybackHttpRetryPolicy.decide(responseCode, retryCount, MAX_RETRY) ==
                        HttpRetryAction.REFRESH_HEADERS
                    ) {
                        retryCount++
                        val refreshedHeaders = (currentHeaders ?: emptyMap()).toMutableMap()
                        val refreshedCookie = runCatching {
                            android.webkit.CookieManager.getInstance().getCookie(currentUrl)
                        }.getOrNull()
                        if (!refreshedCookie.isNullOrBlank()) {
                            refreshedHeaders["Cookie"] = refreshedCookie
                        }
                        AppLog.put(
                            "ExoPlayer 403 快速补头重试($retryCount/$MAX_RETRY): code=$responseCode, " +
                                "headers=${refreshedHeaders.keys}, urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
                        )
                        currentHeaders = refreshedHeaders
                        ExoPlayerHelper.setDefaultHeaders(refreshedHeaders)
                        mInternalPlayer?.let { player ->
                            // §13.9（design §1.14 #7）：补头重试必须**续播原进度**。
                            // 旧实现用 seekToDefaultPosition()，长视频中途 403 会直接回到 0 从头播。
                            val resumePosMs = player.currentPosition.coerceAtLeast(0L)
                            AppLog.put(
                                "ExoPlayer 403 补头重试续播: resumePosMs=$resumePosMs, " +
                                    "durMs=${player.duration}"
                            )
                            player.seekTo(resumePosMs)
                            player.prepare()
                        }
                        return
                    }
                } catch (e: Exception) {
                    // 反射失败，忽略（走原降级链）
                }
            }
        }

        // video-play-7001-videograph-fix 2.3/AD-03：ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED(7001) 兜底重建
        // 根因（media3 1.10.1 源码实证）：videoEffects 一旦被 setVideoEffects 写入（增强开启）即激活 GL
        // VideoGraph 管线并终生不回置 null；GSY Surface(-1,-1) 负分辨率哨兵触发 Presentation
        // createForWidthAndHeight(-1,-1) 抛异常。空 effects/清 effects/同实例 seekTo+prepare 均无法弥合
        // （videoSink/VideoGraph 对象随 release 才销毁）。
        // 方案（design 2.3.4）：不重试同一实例（GL 残留必败死循环），改为——
        //   标记旧实例污染 → 清空池（防其他复用实例再碰 GL 残留）→ 旧实例 recycle（tainted 用完即毁）
        //   → acquire 全新实例重绑（照抄 prepareAsyncInternal 初始化链）→ 按当前降级类型重建 MediaSource 重试。
        // 单会话重建超 MAX_7001_REBUILD 次仍 7001 → tryNextFallback 降级链（耗尽后统一错误提示兜底）。
        // 增强关闭态已由 AD-01 守卫保证不进 GL 管线，本分支仅增强开启用户兜底。
        if (error.errorCode == 7001) {
            if (rebuild7001Count >= MAX_7001_REBUILD) {
                AppLog.put(
                    "ExoPlayer 视频渲染管线错误(7001): rebuild limit reached($rebuild7001Count), advance fallback, " +
                        "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
                )
                rebuild7001Count = 0
                tryNextFallback()
                return
            }
            rebuild7001Count++
            AppLog.put(
                "ExoPlayer 视频渲染管线错误(7001): tainted旧实例+清池+acquire全新实例重建(#$rebuild7001Count), " +
                    "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
            )
            val oldPlayer = mInternalPlayer
            // 标记实例已污染（含 GL 残留），pool.recycle 时会用完即毁不入池
            runCatching { oldPlayer?.let { PlayerInstancePool.markTainted(it) } }
            // 清空池：避免同 Activity 内其他复用实例再次碰 VideoGraph GL 残留
            runCatching { PlayerInstancePool.clear() }
            oldPlayer?.let { old ->
                detachFromPlayer(old)
                PlayerInstancePool.recycle(old) // tainted → release directly（GL 管线随 release 销毁）
            }
            mInternalPlayer = null
            // 全新实例初始化链（照抄 prepareAsyncInternal L563-593 模式）
            if (mRendererFactory == null) {
                mRendererFactory = PlayerInstancePool.sharedRendererFactory
            }
            if (mLoadControl == null) {
                mLoadControl = PlayerInstancePool.createLoadControl()
            }
            mInternalPlayer = PlayerInstancePool.acquire(Looper.myLooper()!!)
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
            // 重置 scope/release 标志位，允许新实例回调正常触发
            isScopeCancelled.set(false)
            isReleased = false
            // 按当前降级类型重建 MediaSource 重试（实例级故障优先重试同类型）
            val contentType = fallbackTypes.getOrNull(currentFallbackIndex)
            if (contentType != null) {
                applyMediaSourceByType(contentType, currentUrl, currentHeaders)
            } else {
                tryNextFallback()
            }
            return
        }

        // video-regression-fix-0906 AD-01：ERROR_CODE_DECODING_FAILED(4003) 解码竞态重建重试
        // 铁证（用户真机日志）：快速切换视频时旧 MediaCodec 已 Released 仍被 queueInputBuffer
        // → 4003 终止播放。方案对齐 7001 先例：旧实例 markTainted + 清池 + acquire 全新实例重建，
        // 按当前降级类型重建 MediaSource 重试；单会话超 MAX_4003_REBUILD 走既有不可恢复错误链
        if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED) {
            if (rebuild4003Count >= MAX_4003_REBUILD) {
                AppLog.put(
                    "ExoPlayer 解码失败(4003): rebuild limit reached($rebuild4003Count), advance existing unrecoverable chain, " +
                        "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
                )
                // 不消耗 unrecoverableFailCount 双计——直接走既有不可恢复错误处理（降级链/统一提示）
                unrecoverableFailCount++
                tryNextFallback()
                return
            }
            rebuild4003Count++
            AppLog.put(
                "ExoPlayer 解码失败(4003): 解码竞态重建重试(#$rebuild4003Count/$MAX_4003_REBUILD), " +
                    "tainted旧实例+清池+acquire全新实例, urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
            )
            val oldPlayer4003 = mInternalPlayer
            // 旧实例解码器已 Released 竞态，标记污染用完即毁不入池
            runCatching { oldPlayer4003?.let { PlayerInstancePool.markTainted(it) } }
            // 清池：避免其他复用实例携带竞态残留
            runCatching { PlayerInstancePool.clear() }
            oldPlayer4003?.let { old ->
                detachFromPlayer(old)
                PlayerInstancePool.recycle(old)
            }
            mInternalPlayer = null
            // 全新实例初始化链（对齐 7001 重建链）
            if (mRendererFactory == null) {
                mRendererFactory = PlayerInstancePool.sharedRendererFactory
            }
            if (mLoadControl == null) {
                mLoadControl = PlayerInstancePool.createLoadControl()
            }
            mInternalPlayer = PlayerInstancePool.acquire(Looper.myLooper()!!)
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
            isScopeCancelled.set(false)
            isReleased = false
            val contentType4003 = fallbackTypes.getOrNull(currentFallbackIndex)
            if (contentType4003 != null) {
                applyMediaSourceByType(contentType4003, currentUrl, currentHeaders)
            } else {
                tryNextFallback()
            }
            return
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
        // 修复：重试耗尽后走降级链 tryNextFallback，降级链耗尽则触发 VIDEO_PLAY_ERROR 统一错误提示
        if (isNetworkError && retryCount >= MAX_RETRY) {
            AppLog.put(
                "ExoFallback: network error retry exhausted ($retryCount/$MAX_RETRY), trigger fallback, " +
                    "errorCode=${error.errorCodeName}, urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
            )
            tryNextFallback()
            return
        }

        // P0-fix + Phase 2 (3.5)：SSL握手失败走统一错误提示（确定性错误，重试和降级链无意义）
        // 铁证：站点A m3u8，CDN 重置 TLS 连接，ExoPlayer OkHttp 无法握手
        // 原"直接降级 WebView（系统 WebView TLS 栈可握手）"已随 WebView 播放器删除，
        // 改为提示用户"重试/系统浏览器"承接（系统浏览器具备独立 TLS 栈，能力等价；占比低，列入监控）
        if (isSslError) {
            AppLog.put(
                "ExoFallback: SSL handshake failed, prompt retry/browser (WebView channel removed), " +
                    "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
            )
            // W2 / REQ-13：既有链路已放弃该 URL ⇒ 询问策略（可能由上层换线路自愈接管）
            if (handleTerminalErrorByPolicy(error)) return
            val errorInfo = buildString {
                appendLine("播放失败：SSL 握手失败（CDN 拒绝 TLS 连接）")
                appendLine("播放地址: ${ExoPlayerHelper.sanitizeUrl(currentUrl)}")
                appendLine("建议: 重试无效时可用系统浏览器打开（其独立 TLS 栈可能成功握手）")
            }
            postEvent(EventBus.VIDEO_PLAY_ERROR, errorInfo)
            return
        }

        // exoplayer-resilience Layer 2：不可恢复错误累计 + 统一错误提示
        // 触发条件：unrecoverableFailCount >= FALLBACK_RETRY_THRESHOLD(3) + 不可恢复错误类型
        // 不可恢复错误类型（参考 design.md AD-03）：
        // - 3002 PARSING_CONTAINER_MALFORMED：m3u8/mp4 解析错误（格式不兼容）
        // - 3003 PARSING_CONTAINER_UNSUPPORTED：容器格式不支持（V-P1-2 补入白名单）
        // - 3004 PARSING_MANIFEST_MALFORMED：清单格式错误
        // - ERROR_CODE_DECODER_INIT_FAILED：解码器初始化失败
        // - ERROR_CODE_DECODING_FAILED：解码失败
        // 设计理由：可恢复错误（网络抖动）重试即可，不可恢复错误重试无意义，达阈值后统一错误提示
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

            // V-P1-2: 末端解析失败（currentFallbackIndex 已在末端）——直接错误提示兜底，
            // 不等 unrecoverableFailCount 累计阈值（否则末端失败陷入空转：既不降级也不提示）
            // Phase 2 (3.5)：原"WebView 兜底"改统一错误提示
            if (isParsingError && currentFallbackIndex >= fallbackTypes.size - 1) {
                AppLog.put(
                    "ExoFallback: terminal fallback failed (${error.errorCodeName}), prompt error, " +
                        "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
                )
                // W2 / REQ-13：既有降级链已到末端 ⇒ 询问策略（可能由上层换线路自愈接管）
                if (handleTerminalErrorByPolicy(error)) return
                val errorInfo = buildString {
                    appendLine("播放失败：视频格式不支持或地址已失效")
                    appendLine("错误码: ${error.errorCode} (${error.errorCodeName})")
                    appendLine("播放地址: ${ExoPlayerHelper.sanitizeUrl(currentUrl)}")
                    appendLine("建议: 可重试，或复制到系统浏览器播放")
                }
                postEvent(EventBus.VIDEO_PLAY_ERROR, errorInfo)
                return
            }

            if (unrecoverableFailCount >= FALLBACK_RETRY_THRESHOLD) {
                // W2 / REQ-13：既有重试/降级链已耗尽 ⇒ 询问策略（DEGRADE 时先走既有降级链，ABORT 才提示）
                if (handleTerminalErrorByPolicy(error)) return
                // T1.2 + Phase 2 (3.5)：重试耗尽后发送 VIDEO_PLAY_ERROR 事件（UI 统一错误提示）
                // AD-02: 所有失败场景必须有 UI 提示，不能静默
                // Phase 2：原"再触发 VIDEO_FALLBACK_WEBVIEW 自动降级"已随 WebView 播放器删除
                val errorInfo = buildString {
                    appendLine("播放失败（已重试 $unrecoverableFailCount 次）")
                    appendLine("错误码: ${error.errorCode} (${error.errorCodeName})")
                    appendLine("错误信息: ${error.message ?: "无"}")
                    appendLine("播放地址: ${ExoPlayerHelper.sanitizeUrl(currentUrl)}")
                    appendLine("原因: ${error.cause?.toString() ?: "未知"}")
                    appendLine("建议: 视频格式不兼容或地址已失效，可重试或复制到系统浏览器播放")
                }
                AppLog.put(errorInfo, error)
                postEvent(EventBus.VIDEO_PLAY_ERROR, errorInfo)
                return
            }
            // 未达阈值，继续走友好提示让用户知道当前失败原因
        }

        // T1.6: 用 AppLog.put 替代 Log.d（解决 Bug-4：release 包无日志输出，AppLog.kt L86 if (BuildConfig.BUILD_DEBUG) 导致 release 包 Log.e 不输出）
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
        // W2 / REQ-13：通用终端点 —— 既有链路未接管的错误在此统一交给策略裁决，
        // 可能由上层「换线路自愈」接管（返回 true 则不弹错误提示）
        if (handleTerminalErrorByPolicy(error)) return
        AppLog.put(errorInfo, error)
        postEvent(EventBus.VIDEO_PLAY_ERROR, errorInfo)
    }

    /**
     * W2 / REQ-13（AD-04）：**终端点**错误裁决 —— 与既有重试链**合并而非叠加**。
     *
     * 调用点全部位于「既有链路已对该错误放弃」的终端点（统一错误提示之前），因此：
     * - 既有分支（416/403 补头 / 7001 重建 / 4003 重建 / 指数退避 / 降级链）**逐行未改**，
     *   不存在「两套重试同时生效」的双重重试；
     * - 本方法只做三件事：① 错误分类归因（统一日志，后续新增错误码不再需要往长链里加 if）；
     *   ② 会话记账（上限 3 次 + 冷却 60s ⇒ 「全线路失败有上限不循环」）；
     *   ③ [PlaybackErrorAction.DEGRADE] 且降级链仍有下一档时，替终端提示执行一次既有
     *   [tryNextFallback]（换播放方式）；
     * - 不可自愈类（解码 / DRM / 未知）裁决为 [PlaybackErrorAction.ABORT]，**不换线**；
     * - 无论结果如何都把错误大类写入 [VideoPlay.lastPlaybackErrorKind]，供 UI 侧「换线路自愈」判据。
     *
     * @return true = 已由策略接管（调用方须立刻 `return`，不再弹统一错误提示）
     */
    private fun handleTerminalErrorByPolicy(error: PlaybackException): Boolean {
        val kind = PlaybackErrorPolicy.classify(error.errorCode)
        VideoPlay.lastPlaybackErrorKind = kind
        val session = VideoPlay.routeSelfHealSession
        val action = session.decide(kind)
        AppLog.putInfo(
            "ExoPlaybackSelfHeal: kind=$kind, action=$action, " +
                "code=${error.errorCode}(${error.errorCodeName}), " +
                "attempts=${session.attempts}/${PlaybackErrorPolicy.MAX_ATTEMPTS}, " +
                "cooling=${session.isCoolingDown()}, " +
                "fallbackIdx=$currentFallbackIndex/${fallbackTypes.size - 1}, " +
                "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
        )
        if (action == PlaybackErrorAction.DEGRADE &&
            currentFallbackIndex < fallbackTypes.size - 1
        ) {
            AppLog.put("ExoPlaybackSelfHeal: policy DEGRADE → tryNextFallback(kind=$kind)")
            tryNextFallback()
            return true
        }
        return false
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
        runCatching {
            val player = mInternalPlayer
            val pos = player?.currentPosition ?: 0L
            val dur = player?.duration ?: 0L
            // tasks §13.3/§13.4：应用线程写时长快照（直播/未知时 duration 为 C.TIME_UNSET 或 0，不覆盖）
            if (dur > 0L) knownDurationMs = dur
            if (state == Player.STATE_READY) updateKnownBitrate(player)
            // tasks §13.4：READY 后输出一次分流决策（确定性取证点——写 sink 是否被创建取决于
            // 是否真有上游读取，命中缓存/预填时不会创建，故不能只依赖 sink 侧日志）
            // video-buffer-prefetch-v2：加入直播/回看维度（isLive 优先于 duration）
            if (state == Player.STATE_READY && dur > 0L) {
                val live = player?.let { isLivePlayback(it) } ?: false
                val write = shouldWriteCache(live, VideoPlay.longVideoBackCacheSec, dur)
                val reason = when {
                    write -> ""
                    live -> "(reason=live)"
                    else -> "(reason=long_video)"
                }
                AppLog.put(
                    "[BufferSpeed] cache_policy durMs=$dur write=${if (write) "on" else "off"}$reason"
                )
            }
            val buf = (player?.bufferedPosition ?: 0L) - pos
            VideoTrace.event(
                stage = "engine_state",
                "state" to stateName,
                "playerId" to player?.let { System.identityHashCode(it) },
                "posMs" to pos,
                "bufMs" to buf,
                "durMs" to dur,
                "fallbackIdx" to currentFallbackIndex,
                "url" to VideoTrace.urlPath(currentUrl)
            )
            if (state == Player.STATE_READY) {
                VideoTrace.sampled(
                    stage = "buffer",
                    intervalMs = 1000L,
                    "posMs" to pos,
                    "bufMs" to buf,
                    "durMs" to dur,
                    "url" to VideoTrace.urlPath(currentUrl)
                )
            }
        }

        // T1.1: STATE_READY 播放成功埋点（统计播放成功率）
        // AD-01: 双标志位防同一 URL 重复埋点（seek/rebuffer 后重复 READY 不重复统计）
        if (state == Player.STATE_READY && !hasReportedReadySuccess) {
            hasReportedReadySuccess = true
            // A2 修复：首帧渲染成功，置 VideoPlay.hasPlayedSuccessfully = true
            // 后续切换文章 BUFFERING 超时用 12s（CDN 已热），切换源（initSource）时重置为 false
            VideoPlay.hasPlayedSuccessfully = true
            // §14.3：首帧到达 ⇒ 输出重建耗时（initVideo → 首帧），用于切集成本归因
            PlaybackRebuildTrace.onFirstFrame()
            // W2 / REQ-13：本次起播成功 ⇒ 自愈记账复位（"会话内成功即清零"，避免历史失败压制后续换线）
            VideoPlay.routeSelfHealSession.reset()
            VideoPlay.lastPlaybackErrorKind = null
            AppLog.put(
                "ExoPlayer play success (STATE_READY): urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}, " +
                    "contentType=${currentSniffResult.contentType}, fallbackIndex=$currentFallbackIndex/${fallbackTypes.size}"
            )
            // tasks §13.1：首帧 READY 时输出一次编解码信息（解码器缺口补齐）
            logTrackCodecIfVideo()
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
                // §13.8：只有起播成功后的缓冲才算卡顿（首缓冲不计）
                if (VideoPlay.hasPlayedSuccessfully) {
                    rebufferTracker.onBufferingStart()
                    // video-buffer-prefetch-v2：近 30s rebuffer 信号（档位裁决用）
                    val now = android.os.SystemClock.elapsedRealtime()
                    recentRebufferAt.addLast(now)
                    while (recentRebufferAt.isNotEmpty() && now - recentRebufferAt.first() > 30_000L) {
                        recentRebufferAt.removeFirst()
                    }
                }
                updatePrefetchSnapshot()
            }
            Player.STATE_READY, Player.STATE_ENDED -> {
                // 离开 BUFFERING：清除超时回调，避免误降级
                // - READY：播放成功，无需降级
                // - ENDED：播放结束，无需降级
                bufferingTimeoutHandler.removeCallbacks(bufferingTimeoutRunnable)
                if (state == Player.STATE_READY) {
                    // video-buffer-prefetch-v2：起播/恢复后启动补水、预建实例、回看清理、解码提示
                    updatePrefetchSnapshot()
                    VideoSegmentPrefetcher.onReady()
                    schedulePrefetchTick()
                    PrebuiltPlayerHolder.warmUp()
                    maybeTrimBackCache()
                    maybeHintDecode()
                }
                // §13.8：上报稳态卡顿时长（seek 引发的缓冲由 tracker 过滤）
                rebufferTracker.onBufferingEnd()?.let { VideoPlay.onRebuffer(it) }
            }
            Player.STATE_IDLE -> {
                // IDLE：播放失败（onPlayerError 会跟进），避免重复降级；同时复位计时
                bufferingTimeoutHandler.removeCallbacks(bufferingTimeoutRunnable)
                prefetchTickHandler.removeCallbacks(prefetchTickRunnable)
                VideoSegmentPrefetcher.stop()
                rebufferTracker.reset()
            }
        }
    }

    /**
     * §13.8：标记用户 seek，避免把「拖动进度条后的一次缓冲」误判为卡顿。
     */
    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        super.onPositionDiscontinuity(oldPosition, newPosition, reason)
        if (isScopeCancelled.get()) return
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            rebufferTracker.markSeek()
            // seek 后灰条按新位置重新起算（否则单调基线会把回退的灰条卡在高位）
            readyPercentFloor = 0
            // video-buffer-prefetch-v2：seek 后取消在途预取并按新位置重算（1s 节流）
            updatePrefetchSnapshot()
            VideoSegmentPrefetcher.onSeek()
            maybeTrimBackCache()
        }
    }

    /**
     * video-buffer-prefetch-v2：运行中变速 ⇒ 更新倍速快照并重算预取窗口。
     */
    override fun onPlaybackParametersChanged(
        eventTime: AnalyticsListener.EventTime,
        playbackParameters: PlaybackParameters
    ) {
        super.onPlaybackParametersChanged(eventTime, playbackParameters)
        if (isScopeCancelled.get()) return
        ExoPlayerHelper.playbackSpeedSnapshot = playbackParameters.speed
        updatePrefetchSnapshot()
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
        // video-buffer-prefetch-v2：解码提示采样（每会话最多一次）
        droppedSinceDecodeSample += droppedFrames
        if (mInternalPlayer?.playbackState == Player.STATE_READY) maybeHintDecode()
        if (droppedFrames > 3) {
            AppLog.put(
                "[BufferSpeed] onDroppedVideoFrames: dropped=$droppedFrames, elapsed=${elapsedMs}ms, " +
                    "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
            )
        }
    }

    /** tasks §13.1：累计从缓存读取的字节（SimpleCache 命中量） */
    private var cachedBytesTotal: Long = 0L

    /** tasks §13.1：累计网络加载字节（与 cachedBytesTotal 一起算命中率） */
    private var networkBytesTotal: Long = 0L

    /**
     * video-buffer-prefetch-v2 AD-05：加载统计按 per-load 记录，不再把「整段加载耗时」当 TTFB。
     *
     * 旧实现（连续 3 次加载耗时 >1s 就降 WEAK）在好网大分片下会误触发；
     * 新判据见 [BufferTierPolicy]（带宽估计 + 需求带宽 + 近 30s rebuffer）。
     */
    private var loadStatCount = 0

    /**
     * P2: 加载完成埋点（per-load）
     */
    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        super.onLoadCompleted(eventTime, loadEventInfo, mediaLoadData)
        val bytesLoaded = loadEventInfo.bytesLoaded
        val dataType = mediaLoadData.dataType
        val loadDurationMs = loadEventInfo.loadDurationMs
        val dataTypeName = when (dataType) {
            C.DATA_TYPE_MEDIA -> "media"
            C.DATA_TYPE_MANIFEST -> "manifest"
            else -> "type$dataType"
        }
        val estimate = ExoPlayerHelper.bandwidthMeter.bitrateEstimate
        if (loadDurationMs > 500) {
            AppLog.put(
                "[BufferSpeed] onLoadCompleted SLOW: loadDurationMs=$loadDurationMs, " +
                    "bytes=$bytesLoaded, dataType=$dataTypeName, " +
                    "urlPath=${ExoPlayerHelper.sanitizeUrl(currentUrl)}"
            )
        }
        if (dataType == C.DATA_TYPE_MEDIA) {
            val now = android.os.SystemClock.elapsedRealtime()
            // READY 时 currentTracks 可能尚未就绪（HLS 首片到达前）⇒ 用首个媒体加载的 trackFormat 补码率
            if (knownBitrateBps <= 0L) {
                val trackBitrate = mediaLoadData.trackFormat?.bitrate ?: 0
                if (trackBitrate > 0) {
                    knownBitrateBps = trackBitrate.toLong()
                    // R42/AD-22 v1.2：跨会话保留最近码率，供下一次 prepare 的内存桶判定使用
                    ExoPlayerHelper.lastKnownBitrateBps = knownBitrateBps
                }
            }
            while (recentRebufferAt.isNotEmpty() && now - recentRebufferAt.first() > 30_000L) {
                recentRebufferAt.removeFirst()
            }
            // §1.19：per-load 实测吞吐 EMA（带宽计在高带宽下更新滞后，用它补位）
            val sample = ThroughputEstimator.sampleBps(bytesLoaded, loadDurationMs)
            if (sample > 0L) {
                observedThroughputBps = ThroughputEstimator.update(observedThroughputBps, sample)
                observedThroughputAt = now
                // §1.19：跨会话保留，供下一次 prepare 的档位选择使用
                ExoPlayerHelper.lastObservedThroughputBps = observedThroughputBps
            }
            val speed = runCatching {
                mInternalPlayer?.playbackParameters?.speed ?: 1f
            }.getOrDefault(1f)
            val effectiveEstimate = effectiveBandwidthBps()
            val decision = BufferTierPolicy.decide(
                currentForceTier = ExoPlayerHelper.forceTier,
                bandwidthEstimateBps = effectiveEstimate,
                trackBitrateBps = knownBitrateBps,
                speed = speed,
                rebufferCount = recentRebufferAt.size,
                msSinceLastSwitch = now - lastTierSwitchAt,
            )
            if (decision.reason != "none" && decision.forceTier != ExoPlayerHelper.forceTier) {
                val from = ExoPlayerHelper.forceTier
                ExoPlayerHelper.forceTier = decision.forceTier
                lastTierSwitchAt = now
                AppLog.put(
                    "[BufferSpeed] tier from=$from to=${decision.forceTier} reason=${decision.reason} " +
                        "rebuffers=${recentRebufferAt.size} estimate=$effectiveEstimate " +
                        "meter=$estimate observed=$observedThroughputBps " +
                        "bitrate=$knownBitrateBps speed=$speed"
                )
            }
            loadStatCount++
            if (loadStatCount <= 10 || loadDurationMs > 500) {
                val throughput = if (loadDurationMs > 0L) {
                    (bytesLoaded.toDouble() * 8000.0 / loadDurationMs).toLong()
                } else {
                    0L
                }
                AppLog.put(
                    "[BufferSpeed] load_stat loadDurationMs=$loadDurationMs bytes=$bytesLoaded " +
                        "throughputBps=$throughput estimate=$estimate " +
                        "observedEma=$observedThroughputBps effective=$effectiveEstimate " +
                        "bitrate=$knownBitrateBps"
                )
            }
        }
        networkBytesTotal += bytesLoaded
    }

    // ===================== video-buffer-prefetch-v2 预取/回看/解码提示 =====================

    /**
     * 有效带宽（bps）：带宽计估计与 per-load 实测吞吐 EMA 取较大值。
     *
     * 实测样本超过 [ThroughputEstimator.STALE_MS] 未更新时回落到带宽计，
     * 避免长时间暂停后用旧高速值继续放大预取预算。
     */
    private fun effectiveBandwidthBps(): Long {
        val meterBps = ExoPlayerHelper.bandwidthMeter.bitrateEstimate
        val age = if (observedThroughputAt > 0L) {
            android.os.SystemClock.elapsedRealtime() - observedThroughputAt
        } else {
            Long.MAX_VALUE
        }
        return ThroughputEstimator.effective(meterBps, observedThroughputBps, age)
    }

    /** 从当前轨道读取视频码率（READY 时调用） */
    private fun updateKnownBitrate(player: ExoPlayer?) {
        player ?: return
        val format = player.currentTracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
            ?.let { group -> (0 until group.length).firstNotNullOfOrNull { group.getTrackFormat(it) } }
            ?: return
        if (format.bitrate > 0) knownBitrateBps = format.bitrate.toLong()
    }

    /** 直播判定：播放器动态时间轴优先（`isCurrentMediaItemLive` 为权威） */
    private fun isLivePlayback(player: ExoPlayer): Boolean {
        val byFlag = runCatching { player.isCurrentMediaItemLive }.getOrDefault(false)
        val dynamic = runCatching {
            val timeline = player.currentTimeline
            if (timeline.isEmpty) {
                false
            } else {
                val w = Timeline.Window()
                timeline.getWindow(0, w)
                w.isDynamic
            }
        }.getOrDefault(false)
        return SnapshotLiveDecision.resolve(byFlag, dynamic, knownDurationMs)
    }

    /** 把应用线程快照写给预取器（预取线程只读，不触碰 Player） */
    private fun updatePrefetchSnapshot() {
        val player = mInternalPlayer ?: return
        val live = isLivePlayback(player)
        val speed = runCatching { player.playbackParameters.speed }.getOrDefault(1f)
        if (live && !liveLogged) {
            liveLogged = true
            val plan = LivePlaybackPolicy.offsetPlan(0L)
            AppLog.put(
                "[BufferSpeed] live detected, offsetOverride=${plan.overrideMs} " +
                    "expectedMinMs=${plan.expectedMinMs} (use Media3 default)"
            )
        }
        ExoPlayerHelper.currentIsLiveSnapshot = live
        ExoPlayerHelper.currentBackCacheSecSnapshot = if (live) 0 else VideoPlay.longVideoBackCacheSec
        val cacheSizeBytes = CacheSizePolicy.effectiveMb().toLong() * 1024L * 1024L
        VideoSegmentPrefetcher.updateSnapshot(
            PrefetchSnapshot(
                sessionId = prefetchSessionId,
                positionMs = runCatching { player.currentPosition }.getOrDefault(0L),
                bufferedPositionMs = runCatching { player.bufferedPosition }.getOrDefault(0L),
                durationMs = knownDurationMs,
                speed = speed,
                isLive = live,
                buffering = player.playbackState == Player.STATE_BUFFERING,
                paused = !player.playWhenReady,
                foreground = true,
                // 计量网络门禁（AD-46）：默认允许移动网络预取；仅用户显式关闭时才拦
                isMetered = PrefetchNetPolicy.blockedByMetered(
                    meteredNow = NetworkGate.isMeteredNow(),
                    prefetchOnMobile = VideoPlay.prefetchOnMobile,
                ),
                videoCacheEnabled = VideoPlay.videoCache,
                prefetchEnabled = VideoPlay.segmentPrefetchEnabled,
                cacheSizeBytes = cacheSizeBytes,
                bitrateBps = knownBitrateBps,
                bandwidthEstimateBps = effectiveBandwidthBps(),
                hasDrm = isCencActive(),
                headers = currentHeaders,
                directUri = if (currentSniffResult.contentType == C.TYPE_OTHER) {
                    // 直链缓存键必须与播放一致：播放用 finalUrl（重定向后）创建 MediaSource
                    currentSniffResult.finalUrl.ifBlank { currentUrl }
                } else {
                    null
                },
                contentType = currentSniffResult.contentType,
                supportsRange = currentSniffResult.supportsRange,
                bucketDepthMs = ExoPlayerHelper.currentMaxBufferMs.toLong().coerceAtLeast(0L),
                episodeWrittenBytes = VideoSegmentPrefetcher.episodeWrittenBytes(),
                hasMeasuredSample = observedThroughputBps > 0L,
                allowFullEpisode = allowFullEpisodeGate(),
            )
        )
    }

    /**
     * 播放就绪进度（0..100）：内存桶与磁盘预取游标取大者（AD-39）。
     *
     * 与 [getBufferedPercentage]（只反映内存桶，约数十秒）语义不同：
     * 这里的「就绪」包含已落盘的分片，用户看到的灰条 == 可秒开的范围。
     */
    fun prefetchReadyPercentage(): Int {
        // IF-16：时长未知/播放器暂不可用时返回上次值，禁止写 0（否则灰条归零再涨回 = 视觉「来回跳」）
        val player = mInternalPlayer
        if (knownDurationMs <= 0L || player == null) {
            return readyPercentFloor.coerceIn(0, 100)
        }
        val buffered = runCatching { player.bufferedPosition }.getOrDefault(0L)
        val cursor = VideoSegmentPrefetcher.prefetchCursorMs(prefetchSessionId)
        val direct = io.legado.app.help.exoplayer.DirectRangePrefetcher.lastReadyMs
        val current = ReadyProgress.percent(buffered, cursor, knownDurationMs, direct)
        // 会话内只进不退：内存桶缺失/降级重建会让 bufferedPosition 回退，灰条不能跟着倒退
        val stable = ReadyProgress.percentOrKeep(readyPercentFloor, buffered, cursor, knownDurationMs, direct)
        if (current < readyPercentFloor && readyDropLogCount < 20) {
            readyDropLogCount++
            AppLog.put(
                "ReadyPct: drop cur=$current floor=$readyPercentFloor buffered=$buffered " +
                    "cursor=$cursor direct=$direct dur=$knownDurationMs"
            )
        }
        readyPercentFloor = stable
        return stable
    }

    /**
     * 整集全量的运行时闸门（R44 / AD-32）：
     * 后台 / 电量 <20% 未充电 / 系统热节流 / 近 30s 卡顿 ⇒ 不允许整集，仅保留窗口预取。
     */
    private fun allowFullEpisodeGate(): Boolean {
        if (!io.legado.app.help.ForegroundState.isForeground()) return false
        val ctx = splitties.init.appCtx
        val battery = runCatching {
            ctx.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
        }.getOrNull()
        if (battery != null) {
            val percent = battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (percent in 0..19 && !battery.isCharging) return false
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val power = runCatching {
                ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            }.getOrNull()
            if (power != null &&
                power.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_MODERATE
            ) {
                return false
            }
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (recentRebufferAt.isNotEmpty() && now - recentRebufferAt.last() < 30_000L) {
            return false
        }
        return true
    }

    /** READY 期间每 5s 补水 */
    private fun schedulePrefetchTick() {
        prefetchTickHandler.removeCallbacks(prefetchTickRunnable)
        prefetchTickHandler.postDelayed(prefetchTickRunnable, 5_000L)
    }

    /** 长视频回看滚动清理（30s 一次；直播/短视频跳过） */
    private fun maybeTrimBackCache() {
        val player = mInternalPlayer ?: return
        if (isLivePlayback(player)) return
        if (knownDurationMs < LONG_VIDEO_CACHE_THRESHOLD_MS) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastBackCacheTrimAt != 0L && now - lastBackCacheTrimAt < 30_000L) return
        lastBackCacheTrimAt = now
        val cacheSizeBytes = CacheSizePolicy.effectiveMb().toLong() * 1024L * 1024L
        val windowSec = SegmentPrefetchPolicy.backCacheSeconds(
            configSec = VideoPlay.longVideoBackCacheSec,
            cacheSizeBytes = cacheSizeBytes,
            bitrateBps = knownBitrateBps,
        )
        if (windowSec <= 0) return
        val windowMs = windowSec * 1000L
        val position = runCatching { player.currentPosition }.getOrDefault(0L)
        val removed = if (currentSniffResult.contentType == C.TYPE_HLS) {
            VideoSegmentPrefetcher.trimBackCache(position, windowMs)
        } else {
            VideoSegmentPrefetcher.trimBackCacheSingleKey(
                key = currentUrl,
                positionMs = position,
                windowMs = windowMs,
                bitrateBps = knownBitrateBps,
            )
        }
        if (removed > 0) {
            AppLog.putDebug("[BufferSpeed] back_cache trim removed=$removed windowSec=$windowSec")
        }
    }

    /** 解码降级提示（每会话一次；只提示，不自动改清晰度） */
    private fun maybeHintDecode() {
        if (decodeHinted) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (decodeSampleStartAt == 0L) {
            decodeSampleStartAt = now
            droppedSinceDecodeSample = 0
            return
        }
        val windowMs = now - decodeSampleStartAt
        if (windowMs < DecodeHintPolicy.MIN_WINDOW_MS) return
        val player = mInternalPlayer ?: return
        val format = player.currentTracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
            ?.let { group -> (0 until group.length).firstNotNullOfOrNull { group.getTrackFormat(it) } }
        val signal = DecodeHintPolicy.Signal(
            format = format?.let {
                DecodeHintPolicy.VideoFormat(it.sampleMimeType, it.codecs, it.width, it.height)
            },
            droppedFrames = droppedSinceDecodeSample,
            windowMs = windowMs,
            alreadyHinted = decodeHinted,
        )
        if (DecodeHintPolicy.shouldHint(signal)) {
            decodeHinted = true
            postEvent(EventBus.VIDEO_PLAY_TIP, "该编码对当前设备较重，可切换线路或降低清晰度")
            AppLog.put(
                "[BufferSpeed] decode_hint dropped=$droppedSinceDecodeSample windowMs=$windowMs " +
                    "codec=${format?.codecs} size=${format?.width}x${format?.height}"
            )
        }
        droppedSinceDecodeSample = 0
        decodeSampleStartAt = now
    }

    /**
     * tasks §13.1：编解码信息（补「解码器」缺口）。
     *
     * 框架限制：本仓 GSY 基类未暴露 `onDecoderInitialized`（AnalyticsListener 该回调
     * 在当前 AAR 的转发集合之外，override 会编译失败），故改为从 `currentTracks`
     * 读取 Format 的 `codecs` / `sampleMimeType` / 码率——同样是「用什么解码」的可观测证据，
     * 且不臆造实现名。
     */
    private fun logTrackCodecIfVideo() {
        val player = mInternalPlayer ?: return
        val videoFormat = player.currentTracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
            ?.let { group -> (0 until group.length).firstNotNullOfOrNull { group.getTrackFormat(it) } }
            ?: return
        AppLog.put(
            "[BufferSpeed] codec: " + formatCodecSummary(
                codecs = videoFormat.codecs,
                sampleMimeType = videoFormat.sampleMimeType,
                bitrate = videoFormat.bitrate,
                width = videoFormat.width,
                height = videoFormat.height,
            )
        )
    }

    /** tasks §13.1：缓存读取次数（限频输出用） */
    private var cacheReadCount = 0

    /**
     * tasks §13.1：缓存命中累计（由 ExoPlayerHelper 的 CacheDataSource 事件回调驱动）。
     *
     * 为什么不在本类 override `onCachedBytesRead`：GSY 基类未转发该 AnalyticsListener
     * 回调（override 编译失败）。改走 `CacheDataSource.Factory.setEventListener`，
     * 语义一致（都是「本次从 SimpleCache 读出的字节」）。
     */
    internal fun onCacheBytesRead(cachedBytes: Long) {
        cachedBytesTotal += cachedBytes
        cacheReadCount++
        if (cacheReadCount <= 3 || cacheReadCount % 10 == 0) {
            AppLog.put(
                "[BufferSpeed] cache_stat: cachedBytes=$cachedBytesTotal, " +
                    "networkBytes=$networkBytesTotal, " +
                    "hitRate=${io.legado.app.help.exoplayer.cacheHitRatePercent(cachedBytesTotal, networkBytesTotal)}%, " +
                    "reads=$cacheReadCount"
            )
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

    /**
     * video-player-image-enhance B 批（AD-03）：对外暴露内部 ExoPlayer 实例
     * 供画质增强运行时调用 `player.setVideoEffects(...)`（ExoPlayer 接口公开 API，支持热更新；
     * 绕开 PlayerInstancePool 池化复用分支不重建实例导致 Builder 注入失效的问题）。
     * internal 作用域（模块内可见），mInternalPlayer 为父类 protected 字段。
     */
    internal val exoPlayerInstance: ExoPlayer?
        get() = mInternalPlayer


}
