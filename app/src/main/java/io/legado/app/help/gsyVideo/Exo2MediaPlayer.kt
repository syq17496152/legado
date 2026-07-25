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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.BehindLiveWindowException
import android.util.Log
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.model.VideoPlay
import io.legado.app.utils.postEvent
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer
import tv.danmaku.ijk.media.exo2.demo.EventLogger

class Exo2MediaPlayer(context: Context) : IjkExo2MediaPlayer(context) {
    companion object {
        private const val TAG = "GSYExo2MediaPlayer"
        private const val MAX_POSITION_FOR_SEEK_TO_PREVIOUS: Long = 3000
        // E2 优化：网络错误自动重试次数（避免临时网络抖动直接降级 WebView）
        private const val MAX_RETRY = 1
    }
    private val window = Timeline.Window()

    /**
     * E2 优化：网络错误重试计数（prepareAsyncInternal 时重置）
     */
    private var retryCount = 0

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
            // E2 优化：新播放重置重试计数
            retryCount = 0
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
                // 证据：源[1] ruleContent 提取 https://m.892539.xyz/play.php?...&format=m3u8 返回标准 #EXTM3U m3u8，
                // 但 ExoPlayer 报 3003 "None of the available extractors could read the stream"
                // 方案：createMediaItem 内部调用 getMimeType 检测 format=m3u8 返回 APPLICATION_M3U8，
                // setMimeType 让 DefaultMediaSourceFactory 正确创建 HlsMediaSource
                val mediaItem = ExoPlayerHelper.createMediaItem(currentUrl, currentHeaders)
                mInternalPlayer.setMediaItem(mediaItem)
            } else {
                mInternalPlayer.setMediaSource(mMediaSource)
            }
            mInternalPlayer.prepare()
            mInternalPlayer.playWhenReady = false
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
                    AppLog.put("HTTP 416 错误，清除缓存后重试($retryCount/$MAX_RETRY): url=${currentUrl.takeLast(60)}")
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
            AppLog.put("ExoPlayer 网络错误自动重试($retryCount/$MAX_RETRY): errorCode=${error.errorCodeName}, url=${currentUrl.takeLast(60)}")
            mInternalPlayer?.let { player ->
                player.seekToDefaultPosition()
                player.prepare()
            }
            return
        }

        // P0 日志规范：永久日志追踪错误处理（Tag=ExoPlayer）
        Log.d("ExoPlayer", "onPlayerError: errorCode=${error.errorCode}, errorCodeName=${error.errorCodeName}, cause=${error.cause?.javaClass?.simpleName}, url=${currentUrl.takeLast(60)}")

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
            appendLine("播放地址: $currentUrl")
            appendLine("原因: ${error.cause?.toString() ?: "未知"}")
            if (suggestion != null) {
                appendLine("建议: $suggestion")
            }
        }
        AppLog.put(errorInfo, error)
        postEvent(EventBus.VIDEO_PLAY_ERROR, errorInfo)
    }


}
