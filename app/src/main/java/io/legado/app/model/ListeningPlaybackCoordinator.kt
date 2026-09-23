package io.legado.app.model

/**
 * R22（B4）朗读 / 听书（音频播放）**互斥协调器**（纯 Kotlin，可 JVM 单测）。
 *
 * 动因：`ReadAloudService`（TTS/HTTP 朗读）与 `AudioPlayService`（有声书/音频播放）是**两条独立
 * 播放链路**，各自持 MediaSession + 音频焦点；同时起播会出现「双音轨叠加 / 焦点互相抢」。
 * 原先没有统一出口 ⇒ 只能靠各入口自觉；本类把「谁在播」收敛为**单一状态 + 唯一获取入口**。
 *
 * 语义：
 * - [acquire]：请求播放权。若当前已被**另一模式**占用 ⇒ **先占位为目标模式，再终止旧模式**
 *   （两向互斥；占位在先保证终止回调中不会把状态抢回去）；
 * - [release]：释放播放权（服务销毁时调用）。**模式不匹配则忽略** ⇒ 防「A 停止误清 B 的占用」；
 * - 同模式重复 [acquire] 为幂等 no-op（不重复终止、不重复记录）。
 *
 * 线程模型：[acquire]/[release] 全程持锁（含终止回调）—— 回调只允许做「下发停止命令」这类
 * **快速非阻塞**动作（服务侧真实实现即如此），不得在其中等待播放器完全停止（否则阻塞其他入口）。
 *
 * 已知上限：本类只协调**应用内**两条链路；系统级音频焦点竞争仍由各自 `requestFocus()` 处理
 * （两者互补：本类避免"自家两路同时播"，焦点避免"与外部应用同时播"）。
 */
object ListeningPlaybackCoordinator {

    enum class Mode {
        /** 朗读（TTS / HTTP 朗读） */
        READ_ALOUD,

        /** 听书（音频播放 / 有声书） */
        AUDIO_PLAY
    }

    private val lock = Any()
    private var activeMode: Mode? = null

    /** 最近动作序列（形如 `active:AUDIO_PLAY` / `stop:READ_ALOUD` / `release:READ_ALOUD`），供单测与诊断 */
    private val actions = ArrayDeque<String>()

    private const val MAX_ACTIONS = 32

    private fun record(action: String) {
        actions.addLast(action)
        while (actions.size > MAX_ACTIONS) {
            actions.removeFirst()
        }
    }

    /**
     * 取得播放权（唯一入口）。
     *
     * @param stopCurrent 终止「当前占用模式」的回调（仅当占用模式与 [target] 不同时才调用）
     * @return 被终止的模式；无占用或同模式 ⇒ null
     */
    fun acquire(target: Mode, stopCurrent: (Mode) -> Unit): Mode? = synchronized(lock) {
        val current = activeMode
        if (current == target) {
            return@synchronized null
        }
        // 占位在先：终止回调内部若触发 release(current)，因模式不匹配会被忽略 ⇒ 状态不会被抢回
        activeMode = target
        record("active:$target")
        if (current != null) {
            record("stop:$current")
            stopCurrent(current)
        }
        current
    }

    /** 释放播放权（服务销毁 / 明确停止时调用）；模式不匹配则忽略 */
    fun release(mode: Mode) {
        synchronized(lock) {
            if (activeMode == mode) {
                activeMode = null
                record("release:$mode")
            }
        }
    }

    /** 当前占用模式（无占用 ⇒ null） */
    fun active(): Mode? = synchronized(lock) { activeMode }

    /** 最近动作序列快照（单测/诊断用） */
    fun recentActions(): List<String> = synchronized(lock) { actions.toList() }

    /** 仅测试用：清空状态与动作记录 */
    internal fun resetForTest() {
        synchronized(lock) {
            activeMode = null
            actions.clear()
        }
    }
}