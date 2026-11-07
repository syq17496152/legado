package io.legado.app.help.gsyVideo

/**
 * 稳态卡顿计时器（纯逻辑，可 JVM 单测；design §1.14 #6）
 *
 * 判定口径：
 * - 只在「已起播成功」之后开始计时（首缓冲不计入卡顿）；
 * - 用户 seek 引发的 BUFFERING 不计入（[markSeek]）；
 * - 只返回一次真实卡顿时长，未开始计时/被标记 seek 时返回 null。
 *
 * @param clock 时间源（生产传 `SystemClock.uptimeMillis`，测试注入假时钟）
 */
internal class RebufferTracker(private val clock: () -> Long) {

    private var bufferingStartAt = 0L
    private var seekInduced = false

    /** 用户 seek：下一次缓冲结束不计入卡顿 */
    fun markSeek() {
        seekInduced = true
    }

    /** 进入 BUFFERING（调用方保证已起播成功） */
    fun onBufferingStart() {
        bufferingStartAt = clock()
    }

    /**
     * 离开 BUFFERING：返回本次真实卡顿时长（ms），null=不计入。
     */
    fun onBufferingEnd(): Long? {
        if (bufferingStartAt <= 0L) return null
        val duration = (clock() - bufferingStartAt).coerceAtLeast(0L)
        bufferingStartAt = 0L
        val skipped = seekInduced
        seekInduced = false
        return if (skipped) null else duration
    }

    /** 会话复位（换源/新集） */
    fun reset() {
        bufferingStartAt = 0L
        seekInduced = false
    }
}
