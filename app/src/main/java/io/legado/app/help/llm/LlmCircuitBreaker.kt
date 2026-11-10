package io.legado.app.help.llm

import io.legado.app.constant.AppLog
import java.util.concurrent.TimeUnit

/**
 * crash-hardening AD-07：`:llm` 推理进程崩溃熔断。
 *
 * 为什么需要：单次 native 崩溃的代价是"整个推理进程消失"，若某模型/某 prompt 必崩，
 * 会形成「启动 → 崩 → 主进程重连 → 再崩」的风暴。这里按时间窗统计死亡次数，
 * 达到阈值后冷却一段时间，让调用方**直接回落规则结果**而不是反复拉起 native。
 *
 * 约束：
 * - 只在内存里计数（重启归零），启动时用 [ExitInfoRecorder] 的落盘记录播种；
 * - 冷却到期自动恢复；用户可手动"重试一次"提前解除（[allowManualRetry]）；
 * - 所有方法失败静默，绝不影响主流程。
 */
object LlmCircuitBreaker {

    /** 统计窗口：10 分钟 */
    private val WINDOW_MS = TimeUnit.MINUTES.toMillis(10)

    /** 冷却时长：30 分钟 */
    private val COOLDOWN_MS = TimeUnit.MINUTES.toMillis(30)

    /** 窗口内死亡达到该次数即熔断 */
    private const val MAX_DEATHS = 2

    private val deaths = mutableListOf<Long>()
    private var cooledUntil = 0L

    /** 记录一次推理进程异常退出 */
    @Synchronized
    fun recordDeath(now: Long = System.currentTimeMillis()) {
        deaths.add(now)
        deaths.removeAll { now - it > WINDOW_MS }
        if (deaths.size >= MAX_DEATHS) {
            cooledUntil = now + COOLDOWN_MS
            AppLog.putWarn("LLM 熔断：10 分钟内推理进程异常 ${deaths.size} 次，冷却 30 分钟")
        }
    }

    /** 启动时用落盘取证记录播种（不含本次调用） */
    @Synchronized
    fun seedDeaths(count: Int, now: Long = System.currentTimeMillis()) {
        repeat(count.coerceAtLeast(0)) { deaths.add(now) }
        deaths.removeAll { now - it > WINDOW_MS }
        if (deaths.size >= MAX_DEATHS) {
            cooledUntil = now + COOLDOWN_MS
            AppLog.putWarn("LLM 熔断（启动播种）：近期推理进程异常 ${deaths.size} 次，冷却 30 分钟")
        }
    }

    @Synchronized
    fun isCoolingDown(now: Long = System.currentTimeMillis()): Boolean = now < cooledUntil

    /** 用户手动"重试一次"：清空窗口计数并解除冷却（仅本次） */
    @Synchronized
    fun allowManualRetry() {
        deaths.clear()
        cooledUntil = 0L
        AppLog.put("LLM 熔断已手动解除（本次）")
    }

    @Synchronized
    fun deathCount(now: Long = System.currentTimeMillis()): Int {
        deaths.removeAll { now - it > WINDOW_MS }
        return deaths.size
    }

    /** 测试辅助 */
    @Synchronized
    internal fun resetForTest() {
        deaths.clear()
        cooledUntil = 0L
    }
}
