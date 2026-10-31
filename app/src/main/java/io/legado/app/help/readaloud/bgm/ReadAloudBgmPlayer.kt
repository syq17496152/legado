package io.legado.app.help.readaloud.bgm

import android.content.Context
import android.media.MediaPlayer
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadAloudBgmTrack
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.delay
import java.io.File

/**
 * 朗读配乐播放器（T8.4「场景卡联动 BGM」）。
 *
 * 口径：把「智能配乐」开关真正接到声音输出 ——
 * 开关关闭 ⇒ 无 BGM；开关打开 ⇒ 从配乐库取第一条**已启用且文件存在**的配乐（`assetType=bgm`），
 * 循环播放并与朗读音频并行混音。生命周期与朗读服务一一对应（播/停/暂停/恢复）。
 *
 * 边界：本播放器只负责「有声/无声 + 音量 + 生命周期」，不做段落级配乐编排
 * （`AiReadAloudBgmService` 的 cue 级分配属于后续混合能力，本轮不接）。
 */
object ReadAloudBgmPlayer {

    /** 目标音量上限：配乐不得盖过人声（设计口径 0.22） */
    private const val MAX_VOLUME = 0.22f
    private const val FADE_IN_MS = 1800L
    private const val FADE_STEP_MS = 150L

    private var player: MediaPlayer? = null

    /**
     * 纯函数（JVM 可测）：是否应该出声 —— 开关打开且存在可用配乐。
     * 拆出来是为了让「关掉后无 BGM」这条 DoD 有可断言的缝。
     */
    internal fun shouldPlay(enabled: Boolean, hasTrack: Boolean): Boolean = enabled && hasTrack

    /**
     * 纯函数（JVM 可测）：目标音量 = 上限 × 曲目默认音量 × 全局音乐音量。
     * 结果夹紧到 [0, MAX_VOLUME]，避免用户把全局音量拉满时配乐盖过人声。
     */
    internal fun targetVolume(trackVolume: Float, globalScale: Float): Float {
        val raw = MAX_VOLUME * trackVolume.coerceIn(0f, 1f) * globalScale.coerceIn(0f, 1f)
        return raw.coerceIn(0f, MAX_VOLUME)
    }

    /** 开始（幂等）：已在播放直接返回；暂停态续播；无实例则准备并淡入 */
    @Synchronized
    fun start(context: Context) {
        if (!AppConfig.aiReadAloudBgmEnabled) {
            stop()
            return
        }
        player?.let { mp ->
            if (!mp.isPlaying) mp.runCatching { start() }
            return
        }
        val appContext = context.applicationContext
        Coroutine.async {
            prepare(appContext)
        }.onError {
            AppLog.put("配乐播放失败\n${it.localizedMessage}", it)
        }
    }

    @Synchronized
    fun pause() {
        player?.runCatching {
            if (isPlaying) pause()
        }
    }

    @Synchronized
    fun stop() {
        release()
    }

    private fun prepare(context: Context) {
        val track = pickEnabledTrack()
        if (!shouldPlay(AppConfig.aiReadAloudBgmEnabled, track != null)) {
            AppLog.putDebug("配乐开关已开但无可用的已启用配乐，跳过")
            return
        }
        val file = File(track!!.filePath)
        if (!file.isFile) {
            AppLog.putDebug("配乐文件不存在，跳过：${track.fileName}")
            return
        }
        val volume = targetVolume(track.defaultVolume, AppConfig.aiReadAloudBgmVolumeScale)
        synchronized(this) {
            if (player != null) return
            val mp = MediaPlayer()
            val ok = kotlin.runCatching {
                mp.setDataSource(file.absolutePath)
                mp.isLooping = true
                mp.setVolume(0f, 0f)
                mp.prepare()
                mp.start()
            }.isSuccess
            if (!ok) {
                mp.release()
                AppLog.putDebug("配乐准备失败，跳过：${track.fileName}")
                return
            }
            player = mp
        }
        // 正式诊断日志（非临时排查）：真机联调靠它确认"配乐确实起播"与曲目/音量口径
        AppLog.putDebugWithTag(
            AppLog.TAG_TTS_TRACE,
            "配乐起播 曲目=${track.displayName()} 音量=${"%.2f".format(volume)}",
            level = AppLog.Level.INFO
        )
        fadeIn(volume)
    }

    /** 线性淡入到目标音量；播放器被停止后剩余步进自动失效（runCatching 吞掉已释放态） */
    private fun fadeIn(target: Float) {
        Coroutine.async {
            val steps = (FADE_IN_MS / FADE_STEP_MS).toInt().coerceAtLeast(1)
            repeat(steps) { i ->
                val v = target * ((i + 1).toFloat() / steps)
                player?.runCatching { setVolume(v, v) }
                delay(FADE_STEP_MS)
            }
        }
    }

    private fun pickEnabledTrack(): ReadAloudBgmTrack? {
        val tracks = kotlin.runCatching {
            appDb.readAloudBgmDao.enabledTracksByType(ReadAloudBgmTrack.TYPE_BGM)
        }.getOrDefault(emptyList())
        return tracks.firstOrNull { File(it.filePath).isFile }
    }

    private fun release() {
        player?.runCatching { stop() }
        player?.release()
        player = null
    }
}
