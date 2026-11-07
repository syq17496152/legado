package io.legado.app.help.gsyVideo

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Message
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.PlaceholderSurface
import com.shuyu.gsyvideoplayer.cache.ICacheManager
import com.shuyu.gsyvideoplayer.model.GSYModel
import com.shuyu.gsyvideoplayer.model.VideoOptionModel
import com.shuyu.gsyvideoplayer.player.BasePlayerManager
import io.legado.app.constant.AppLog
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.video.VideoTrace
import io.legado.app.model.VideoPlay
import tv.danmaku.ijk.media.player.IMediaPlayer


/**
 * 播放器差异管理接口
 */
@UnstableApi
class ExoPlayerManager : BasePlayerManager() {
    companion object {
        private const val SURFACE_CONTROL_NAME = "surfacedemo"
    }
    private var surface: Surface? = null
    private var dummySurface: PlaceholderSurface? = null
    private var surfaceControl: SurfaceControl? = null
    private var videoSurface: Surface? = null
    private var mediaPlayer: Exo2MediaPlayer? = null
    override fun getMediaPlayer(): IMediaPlayer? {
        return mediaPlayer
    }

    override fun initVideoPlayer(
        context: Context,
        msg: Message,
        optionModelList: List<VideoOptionModel>?,
        cacheManager: ICacheManager
    ) {
        val initStart = android.os.SystemClock.uptimeMillis()
        mediaPlayer = Exo2MediaPlayer(context)
        // §14.3：开始计重建耗时（initVideo → 首帧），首帧到达时由 Exo2MediaPlayer 消费并输出
        PlaybackRebuildTrace.onSetupStart()
        VideoTrace.event(
            stage = "init_video_start",
            "playerId" to System.identityHashCode(mediaPlayer),
            "thread" to Thread.currentThread().name
        )
        mediaPlayer!!.setAudioStreamType(AudioManager.STREAM_MUSIC)
        if (dummySurface == null) {
            dummySurface = PlaceholderSurface.newInstance(context, false)
        }
        val model = msg.obj as GSYModel
        try {
            if (model.url.isNullOrEmpty()) {
                // 处理URL为空的情况
                return
            }
            mediaPlayer!!.currentUrl = model.getUrl()
            // CENC Clear-Key（AD-05）：由采集链写入 VideoPlay，此处透传给播放器；
            // 非 CENC 场景恒为 null ⇒ 与改造前行为一致
            mediaPlayer!!.cencKid = VideoPlay.cencKid
            mediaPlayer!!.cencEncryption = VideoPlay.cencEncryption
            mediaPlayer!!.cencUrl = VideoPlay.cencUrl
            mediaPlayer!!.setLooping(model.isLooping)
            mediaPlayer!!.setPreview(!model.getMapHeadData().isNullOrEmpty())
            // R5 Header 修复：在 setDataSource 前注入 Header 到 okhttpDataFactory，
            // 确保 Exo2MediaPlayer.prepareAsyncInternal 构建的 HTTP 请求带上 Header（解决 CDN 防盗链 404）
            // E1 优化：同时设置 currentHeaders，通过 SPLIT_TAG per-request 注入（双保险，解决 setDefaultHeaders 全局覆盖问题）
            val baseHeaders = model.getMapHeadData().orEmpty()
            // HLS AES-128 清单的 key 请求需 Referer（无 Referer 时 URLError）：
            // 单 URL 模式下 headers 常为空，此处按清单 URL 同源兜底；已有 Referer 不覆盖。
            val effectiveHeaders = io.legado.app.help.exoplayer.M3u8UrlShape
                .headersWithHlsReferer(baseHeaders, mediaPlayer!!.currentUrl ?: model.getUrl())
            if (effectiveHeaders.isNotEmpty()) {
                ExoPlayerHelper.setDefaultHeaders(effectiveHeaders)
                mediaPlayer!!.currentHeaders = effectiveHeaders
            }
            if (model.isCache()) {
                //通过管理器处理
                cacheManager.doCacheLogic(
                    context,
                    mediaPlayer,
                    model.getUrl(),
                    model.getMapHeadData(),
                    model.cachePath
                )
            } else {
                //通过自己的内部缓存机制
                mediaPlayer!!.setCache(model.isCache())
                mediaPlayer!!.setCacheDir(model.cachePath)
                mediaPlayer!!.setOverrideExtension(model.getOverrideExtension())
                mediaPlayer!!.setDataSource(
                    context,
                    model.getUrl().toUri(),
                    model.getMapHeadData()
                )
            }
            //很遗憾，EXO2的setSpeed只能在播放前生效
            if (model.getSpeed() != 1f && model.getSpeed() > 0) {
                mediaPlayer!!.setSpeed(model.getSpeed(), 1f)
            }
            // 2026-10-25 真机反馈修复（切集黑屏但流量在跑、刷新几次才出画面）：
            // 切集时 GSY 的显示面没有变化 ⇒ 不会重新派发 setDisplay，若此处无条件把新 mediaPlayer
            // 绑到 0x0 占位 Surface（API29+）/ dummySurface（低版本），解码与下载照常进行但**永不显示**。
            // 故优先复用上一次已拿到的真实显示 Surface（isValid 守卫，避免用已释放的面）。
            val reuseSurface = surface?.takeIf { it.isValid }
            if (reuseSurface != null) {
                mediaPlayer!!.setSurface(reuseSurface)
                AppLog.put("ExoPlayerManager.initVideoPlayer: 复用已存在显示面（切集不重建 Surface）")
                VideoTrace.event(
                    stage = "surface",
                    "mode" to "reuse",
                    "playerId" to System.identityHashCode(mediaPlayer),
                    "initCostMs" to (android.os.SystemClock.uptimeMillis() - initStart)
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                surfaceControl = SurfaceControl.Builder()
                    .setName(SURFACE_CONTROL_NAME)
                    .setBufferSize(0, 0)
                    .build()
                videoSurface = Surface(surfaceControl!!)
                mediaPlayer!!.setSurface(videoSurface)
                AppLog.put("ExoPlayerManager.initVideoPlayer: 无可用显示面，先用 0x0 占位（等待 setDisplay）")
                VideoTrace.event(
                    stage = "surface",
                    "mode" to "placeholder_0x0",
                    "playerId" to System.identityHashCode(mediaPlayer)
                )
            } else {
                mediaPlayer!!.setSurface(dummySurface)
                AppLog.put("ExoPlayerManager.initVideoPlayer: 无可用显示面，先用 dummy 占位（等待 setDisplay）")
            }
        } catch (e: Exception) {
            AppLog.put("ExoPlayerManager: init", e)
        }
        initSuccess(model)
    }

    override fun showDisplay(msg: Message) {
        if (mediaPlayer == null) return
        val target = resolveDisplaySurface(msg.obj)
        if (target == null) {
            // null = GSY 主动解绑（切容器/隐藏画面）；同时清空缓存面，避免下次 init 误复用
            surface = null
            mediaPlayer!!.setSurface(dummySurface)
            AppLog.put("ExoPlayerManager.showDisplay: 空/不可用显示面 ⇒ 切 dummy")
        } else {
            surface = target
            mediaPlayer!!.setSurface(target)
            AppLog.put(
                "ExoPlayerManager.showDisplay: 绑定显示面, " +
                    "objType=${msg.obj?.javaClass?.simpleName}"
            )
        }
    }

    /**
     * 解析 GSY 派发的显示面载体。
     *
     * GSY 的 `setDisplayNew(holder)` 可能传 SurfaceView（Surface 渲染模式），
     * 原实现只认 `Surface` ⇒ 强转失败落空 ⇒ 播放有声音/有流量但**永不出画面**。
     */
    private fun resolveDisplaySurface(obj: Any?): Surface? = when (obj) {
        null -> null
        is Surface -> obj.takeIf { it.isValid }
        is SurfaceView -> obj.holder?.surface?.takeIf { it.isValid }
        is SurfaceHolder -> obj.surface?.takeIf { it.isValid }
        else -> null
    }

    override fun setSpeed(speed: Float, soundTouch: Boolean) {
        //很遗憾，EXO2的setSpeed只能在播放前生效
        //Debuger.printfError("很遗憾，目前EXO2的setSpeed只能在播放前设置生效");
        if (mediaPlayer != null) {
            try {
                mediaPlayer!!.setSpeed(speed, 1f)
            } catch (e: Exception) {
                AppLog.put("ExoPlayerManager: onPlayerError", e)
            }
        }
    }

    override fun setNeedMute(needMute: Boolean) {
        if (mediaPlayer != null) {
            if (needMute) {
                mediaPlayer!!.setVolume(0f, 0f)
            } else {
                mediaPlayer!!.setVolume(1f, 1f)
            }
        }
    }

    override fun setVolume(left: Float, right: Float) {
        if (mediaPlayer != null) {
            mediaPlayer!!.setVolume(left, right)
        }
    }

    override fun releaseSurface() {
        if (surface != null) {
            //surface.release();
            surface = null
        }
    }

    /**
     * 测试异步释放
     */
    @SuppressLint("NewApi")
    override fun release() {
        // video-regression-fix-0906 日志增强：wrapper 级释放定点标记（追踪 release 与实例池生命周期的时序竞态）
        if (mediaPlayer != null) {
            AppLog.put(
                "ExoPlayerManager.release: wrapper release begin, " +
                    "mediaPlayerHash=${System.identityHashCode(mediaPlayer)}"
            )
        }
        if (mediaPlayer != null) {
            mediaPlayer!!.setSurface(null)
            mediaPlayer!!.release()
            mediaPlayer = null
        }
        if (dummySurface != null) {
            dummySurface!!.release()
            dummySurface = null
        }
        if (surfaceControl != null) {
            surfaceControl!!.release()
            surfaceControl = null
        }
        if (videoSurface != null) {
            videoSurface!!.release()
            videoSurface = null
        }
    }

    override fun getBufferedPercentage(): Int {
        if (mediaPlayer != null) {
            return mediaPlayer!!.bufferedPercentage
        }
        return -1
    }

    /**
     * 上一集
     */
    fun previous() {
        if (mediaPlayer == null) {
            return
        }
        mediaPlayer!!.previous()
    }

    /**
     * 下一集
     */
    fun next() {
        if (mediaPlayer == null) {
            return
        }
        mediaPlayer!!.next()
    }

    /**
     * 获取所有音轨
     */
    fun getAudioTracks(): List<Pair<Int, String>> {
        if (mediaPlayer == null) return emptyList()
        return mediaPlayer!!.getAudioTracks()
    }

    /**
     * 切换音轨
     */
    fun selectAudioTrack(groupIndex: Int) {
        if (mediaPlayer != null) {
            mediaPlayer!!.selectAudioTrack(groupIndex)
        }
    }

    override fun getNetSpeed(): Long {
        return 0
    }

    override fun setSpeedPlaying(speed: Float, soundTouch: Boolean) {
        // 播放中动态调速：与 setSpeed 同链路，IjkExo2MediaPlayer.setSpeed 内部会更新 ExoPlayer.playbackParameters
        if (mediaPlayer != null) {
            try {
                mediaPlayer!!.setSpeed(speed, 1f)
            } catch (e: Exception) {
                AppLog.put("ExoPlayerManager: setSpeedPlaying", e)
            }
        }
    }

    override fun start() {
        if (mediaPlayer != null) {
            mediaPlayer!!.start()
        }
    }

    override fun stop() {
        if (mediaPlayer != null) {
            mediaPlayer!!.stop()
        }
    }

    override fun pause() {
        if (mediaPlayer != null) {
            mediaPlayer!!.pause()
        }
    }

    override fun getVideoWidth(): Int {
        if (mediaPlayer != null) {
            return mediaPlayer!!.videoWidth
        }
        return 0
    }

    override fun getVideoHeight(): Int {
        if (mediaPlayer != null) {
            return mediaPlayer!!.videoHeight
        }
        return 0
    }

    override fun isPlaying(): Boolean {
        if (mediaPlayer != null) {
            return mediaPlayer!!.isPlaying()
        }
        return false
    }

    override fun seekTo(time: Long) {
        if (mediaPlayer != null) {
            mediaPlayer!!.seekTo(time)
        }
    }

    override fun getCurrentPosition(): Long {
        if (mediaPlayer != null) {
            return mediaPlayer!!.getCurrentPosition()
        }
        return 0
    }

    override fun getDuration(): Long {
        if (mediaPlayer != null) {
            return mediaPlayer!!.getDuration()
        }
        return 0
    }

    override fun getVideoSarNum(): Int {
        if (mediaPlayer != null) {
            return mediaPlayer!!.videoSarNum
        }
        return 1
    }

    override fun getVideoSarDen(): Int {
        if (mediaPlayer != null) {
            return mediaPlayer!!.videoSarDen
        }
        return 1
    }

    override fun isSurfaceSupportLockCanvas(): Boolean {
        return false
    }


}
