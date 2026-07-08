package io.legado.app.help.gsyVideo

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.model.VideoPlay
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer
import tv.danmaku.ijk.media.exo2.demo.EventLogger

class Exo2MediaPlayer(context: Context) : IjkExo2MediaPlayer(context) {
    companion object {
        private const val TAG = "GSYExo2MediaPlayer"
        private const val MAX_POSITION_FOR_SEEK_TO_PREVIOUS: Long = 3000
    }
    private val window = Timeline.Window()

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
                        DefaultMediaSourceFactory(
                            ResolvingDataSource.Factory(ExoPlayerHelper.cacheDataSourceFactory){ it }
                        )
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
            mInternalPlayer.setMediaSource(mMediaSource)
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


}
