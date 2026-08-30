package io.legado.app.help.gsyVideo

import android.os.Message
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.shuyu.gsyvideoplayer.GSYVideoBaseManager
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.exoplayer.ImageEnhanceEffects
import io.legado.app.model.VideoPlay

/**
基类管理器
 */
class ExoVideoManager: GSYVideoBaseManager() {
    companion object {
        val SMALL_ID: Int = R.id.small_id
        val FULLSCREEN_ID: Int = R.id.full_id
        var TAG: String = "GSYExoVideoManager"
    }

    init {
        super.init()
    }

    @OptIn(UnstableApi::class)
    override fun getPlayManager(): ExoPlayerManager {
        return ExoPlayerManager()
    }

//    fun prepare(
//        url: String,
//        mapHeadData: MutableMap<String?, String?>?,
//        index: Int,
//        loop: Boolean,
//        speed: Float,
//        cache: Boolean,
//        cachePath: File?,
//        overrideExtension: String?
//    ) {
//        val msg = Message()
//        msg.what = HANDLER_PREPARE
//        msg.obj =GSYModel(url, mapHeadData, loop, speed, cache, cachePath, overrideExtension)
//        sendMessage(msg)
//    }
    /**
     * 上一集
     */
    @OptIn(UnstableApi::class)
    fun previous() {
        if (playerManager == null) {
            return
        }
        (playerManager as ExoPlayerManager).previous()
    }


    fun setDisplayNew(holder: Any?) {
        val msg = Message()
        msg.what = HANDLER_SETDISPLAY
        msg.obj = holder
        if (playerManager != null) {
            playerManager.showDisplay(msg)
        }
    }

    /**
     * 下一集
     */
    @OptIn(UnstableApi::class)
    fun next() {
        if (playerManager == null) {
            return
        }
        (playerManager as ExoPlayerManager).next()
    }

    /**
     * 获取所有音轨
     */
    @OptIn(UnstableApi::class)
    fun getAudioTracks(): List<Pair<Int, String>> {
        if (playerManager == null) return emptyList()
        return (playerManager as ExoPlayerManager).getAudioTracks()
    }

    /**
     * 切换音轨
     */
    @OptIn(UnstableApi::class)
    fun selectAudioTrack(groupIndex: Int) {
        if (playerManager != null) {
            (playerManager as ExoPlayerManager).selectAudioTrack(groupIndex)
        }
    }

    /**
     * T1.8: 释放嗅探资源（VideoFragment.onDestroyView 调用）
     *
     * 通过 ExoPlayerManager 获取 Exo2MediaPlayer 实例，调用 releaseSniffResources()
     * 取消嗅探协程 + 设置 isReleased 标志位，避免 onDestroy 后嗅探协程回调 setMediaItem
     */
    @OptIn(UnstableApi::class)
    fun releaseSniffResources() {
        try {
            val exoPlayer = (playerManager as? ExoPlayerManager)?.getMediaPlayer() as? Exo2MediaPlayer
            exoPlayer?.releaseSniffResources()
        } catch (e: Exception) {
            // 忽略释放异常，避免影响 onDestroyView 流程
        }
    }

    /**
     * video-player-image-enhance B2.1: 应用画质增强效果链（锐化/降噪 media3-effect）
     * playerManager 为 protected，访问链（playerManager→getMediaPlayer→exoPlayerInstance）
     * 必须在管理器内部完成（同 getAudioTracks/releaseSniffResources 先例）。
     * 档位全关时 setVideoEffects(空列表) 显式清空（K4 防池化实例跨会话残留）。
     */
    @OptIn(UnstableApi::class)
    fun applyImageEnhanceEffects() {
        try {
            val exoManager = playerManager as? ExoPlayerManager ?: return
            val mediaPlayer = exoManager.getMediaPlayer() as? Exo2MediaPlayer ?: return
            val player = mediaPlayer.exoPlayerInstance ?: return
            val effects = ImageEnhanceEffects.buildEffects(
                VideoPlay.enhanceSharpenLevel,
                VideoPlay.enhanceDenoiseLevel
            )
            android.util.Log.d("EnhanceGov", "applyImageEnhanceEffects size=${effects.size}")
            player.setVideoEffects(effects)
        } catch (t: Throwable) {
            // 效果链注入失败不影响播放（media3 管线异常兜底）
            AppLog.put("ImageEnhance: setVideoEffects failed: ${t.message}")
        }
    }

}