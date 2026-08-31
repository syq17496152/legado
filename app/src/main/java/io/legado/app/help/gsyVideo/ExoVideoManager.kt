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
     * 非空效果链才允许 setVideoEffects——空列表调用会在 media3 1.10.1 激活 GL VideoGraph 管线，
     * 禁止以"显式清空"名义注入空列表（video-play-7001-videograph-fix 2.1.3/AD-01）。
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
            // video-play-7001-videograph-fix 2.1/AD-01：增强关闭时必须零注入。
            // 根因：media3 1.10.1 中 setVideoEffects(空列表) 也是非 null → 激活 GL VideoGraph 管线，
            // 叠加 GSY Surface(-1,-1) 负分辨率哨兵 → 切集首帧后 Presentation.createForWidthAndHeight(-1,-1) 抛 7001。
            // 原"空列表显式清空(K4)"的注入动作本身就是激活 GL 管线的操作，改为守卫不注入。
            if (effects.isEmpty()) return
            player.setVideoEffects(effects)
            // video-play-7001-videograph-fix 2.2/AD-02：标记实例已注入 effects → 用完即毁不入池（池污染隔离）
            io.legado.app.help.exoplayer.PlayerInstancePool.markTainted(player)
        } catch (t: Throwable) {
            // 效果链注入失败不影响播放（media3 管线异常兜底）
            AppLog.put("ImageEnhance: setVideoEffects failed: ${t.message}")
        }
    }

}