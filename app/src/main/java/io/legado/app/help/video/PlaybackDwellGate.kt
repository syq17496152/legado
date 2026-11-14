package io.legado.app.help.video

import io.legado.app.constant.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 预载「实播门控」（T1-45 / V-11，AD-48）。
 *
 * 背景：`onStarted` 在 setUp 下发后立刻触发，首帧未渲染、用户可能 3 秒内退出；
 * 旧逻辑只有「预载总开关 + 网络」门禁 ⇒ 点开即退也会预嗅探下一集、抢占主播放带宽。
 *
 * 方案：按**播放位置增量**累计实播时长（不用墙钟，暂停/缓冲自然不累计），
 * 达到 [PRELOAD_DWELL_MS] 且切换 token 未变才回调 onGo。
 */
object PlaybackDwellGate {

    /** 需要的实播时长（毫秒） */
    const val PRELOAD_DWELL_MS = 15_000L

    /** 采样间隔 */
    const val SAMPLE_MS = 1_000L

    /** 单次采样允许计入的最大前进量（过滤拖拽/跳转造成的跳变） */
    const val MAX_STEP_MS = 3_000L

    /**
     * 累计一次采样（纯函数，单测锁定）。
     *
     * 仅在「位置正向前进且步长 ≤ [MAX_STEP_MS]」时累加；暂停（delta=0）、回退、跳变都不计。
     */
    fun accrue(accMs: Long, lastPosMs: Long, posMs: Long): Long {
        if (lastPosMs <= 0L || posMs <= 0L) return accMs
        val delta = posMs - lastPosMs
        return if (delta in 1..MAX_STEP_MS) accMs + delta else accMs
    }

    fun shouldFire(accMs: Long): Boolean = accMs >= PRELOAD_DWELL_MS

    /**
     * 启动门控。
     *
     * @param token 当前切换令牌（每切集递增）；变化即取消，避免旧集门控误触发
     * @param positionMs 播放位置采样
     * @param onGo 达到实播时长后的动作（调用预载）
     */
    fun arm(
        scope: CoroutineScope,
        token: () -> Long,
        positionMs: () -> Long,
        onGo: () -> Unit,
    ): Job = scope.launch {
        val startToken = token()
        var lastPos = 0L
        var acc = 0L
        while (isActive) {
            delay(SAMPLE_MS)
            if (token() != startToken) return@launch
            val pos = runCatching { positionMs() }.getOrDefault(0L)
            acc = accrue(acc, lastPos, pos)
            if (pos > 0L) lastPos = pos
            if (shouldFire(acc)) {
                AppLog.put("PlaybackDwellGate: dwell reached accMs=$acc, token=$startToken")
                runCatching { onGo() }
                return@launch
            }
        }
    }
}
