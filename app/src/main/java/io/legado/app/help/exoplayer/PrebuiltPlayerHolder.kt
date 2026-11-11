package io.legado.app.help.exoplayer

import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.constant.AppLog

/**
 * 预建空闲播放器实例（video-buffer-prefetch-v2 §1.17 / AD-15）。
 *
 * 与 `PlayerInstancePool` 的区别：**不开启池化**，只在起播成功后预建 1 个空闲实例，
 * 切集时优先取用（省 Builder + Renderer 初始化约 100ms）；取用失败/超时回退直建。
 *
 * 线程/生命周期：
 * - 必须在主线程创建（`setLooper(main)`）；
 * - Activity `onDestroy` → `PlayerInstancePool.clear()` → 本对象 `clear()` 释放；
 * - 不 prepare、不绑 media、不占解码器。
 */
object PrebuiltPlayerHolder {

    private var spare: ExoPlayer? = null
    private var spareLooper: Looper? = null

    /** 起播成功后预热一个空闲实例（幂等） */
    @Synchronized
    fun warmUp(looper: Looper = Looper.getMainLooper()) {
        if (spare != null) return
        val player = kotlin.runCatching { PlayerInstancePool.buildPlayer(looper) }.getOrNull() ?: return
        spare = player
        spareLooper = looper
        AppLog.put(
            "PrebuiltPlayer: warmUp, playerId=${System.identityHashCode(player)}, thread=${Thread.currentThread().name}"
        )
    }

    /** 取用预建实例；Looper 不一致或未预热返回 null（调用方回退直建） */
    @Synchronized
    fun take(looper: Looper): ExoPlayer? {
        if (!canTake(sparePresent = spare != null, looperMatches = spareLooper == looper)) return null
        val player = spare ?: return null
        spare = null
        spareLooper = null
        return player
    }

    /** 释放（Activity 销毁） */
    @Synchronized
    fun clear() {
        val player = spare ?: return
        spare = null
        spareLooper = null
        PlayerInstancePool.releaseUnused(player)
        AppLog.put(
            "PrebuiltPlayer: cleared, playerId=${System.identityHashCode(player)}"
        )
    }
}

/**
 * 取用判据（纯函数，便于 JVM 单测）：必须「已预热」且「Looper 一致」。
 *
 * Looper 不一致时不得复用（ExoPlayer 约束：同一实例绑定创建线程），调用方回退直建。
 */
internal fun canTake(sparePresent: Boolean, looperMatches: Boolean): Boolean =
    sparePresent && looperMatches
