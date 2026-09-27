package io.legado.app.help.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 嗅探赛马器（W2 / REQ-14 / AD-05）。
 *
 * 借鉴 FongMi/TV `parse/ParseJob.superParse`（GPL-3.0）「多策略同跑、谁先出权威结果谁赢」的协议，
 * 用 Kotlin 协程重写为**与解析结果类型无关**的通用并发原语（`T` = 调用方的嗅探结果类型）。
 *
 * 协议（design §9.2#1 预置决策）：
 * 1. **并发上限 2**：默认 [DEFAULT_MAX_CONCURRENT]；额度只统计**发起网络请求**的策略
 *    （零请求的瞬时策略不占额度，避免被慢策略饿死 —— 这是「低置信兜底必须随时可用」的前提）；
 * 2. **先到先用**：任一 `canWin = true` 的策略返回非 null 结果即胜出；
 * 3. **结构化取消**：胜出或总超时后，其余策略立即被取消（`scope.cancel()`），不再继续占资源；
 * 4. **低置信不抢占**：`canWin = false` 的策略结果只作**兜底**（列表序 = 优先级序，取第一个非空兜底）；
 * 5. **全灭等总超时**：所有策略都无结果时，在 [totalTimeoutMs] 内等待最后一个策略结束，返回其兜底结果或 null。
 *
 * 线程与释放说明：内部使用**独立作用域**（不挂在调用方作用域上），因此调用方不会因某个策略阻塞在
 * 同步网络调用上而被拖住；`finally { scope.cancel() }` 保证返回时所有子任务均已取消（无协程泄漏；
 * 同步网络栈自身的超时由各自的 OkHttp 超时兜底）。
 */
object SniffRace {

    /** 网络类策略并发上限（预置决策 design §9.2#1：定 2，不再保留「2-3」区间）。 */
    const val DEFAULT_MAX_CONCURRENT = 2

    /** 单个策略的运行结果（含归因信息，供日志与单测断言）。 */
    data class StrategyResult<T>(
        val value: T,
        val strategyName: String,
        val confidence: Float,
        val elapsedMs: Long
    )

    /**
     * 一路嗅探策略。
     *
     * @property name 归因名（日志 / 单测）
     * @property confidence 置信度 0~1（仅用于归因与兜底排序，不参与抢占判定）
     * @property timeoutMs 单策略超时（独立预算，慢策略不拖累快策略）
     * @property canWin 是否允许「先到先用」抢占（低置信兜底策略置 false）
     * @property networkBound 是否发起网络请求（决定是否占用 [DEFAULT_MAX_CONCURRENT] 额度）
     */
    interface Strategy<T> {
        val name: String
        val confidence: Float
        val timeoutMs: Long
        val canWin: Boolean
        val networkBound: Boolean

        suspend fun run(url: String, headers: Map<String, String>): T?
    }

    /** 策略工厂（调用方以 lambda 提供实现，避免为每路策略各写一个类）。 */
    fun <T> strategy(
        name: String,
        confidence: Float,
        timeoutMs: Long,
        canWin: Boolean = true,
        networkBound: Boolean = true,
        block: suspend (url: String, headers: Map<String, String>) -> T?
    ): Strategy<T> = object : Strategy<T> {
        override val name: String = name
        override val confidence: Float = confidence
        override val timeoutMs: Long = timeoutMs
        override val canWin: Boolean = canWin
        override val networkBound: Boolean = networkBound
        override suspend fun run(url: String, headers: Map<String, String>): T? = block(url, headers)
    }

    /**
     * 启动赛马并返回胜出/兜底结果。
     *
     * @return 胜出结果；全灭（含整体超时且无任何兜底）返回 null
     */
    suspend fun <T> race(
        url: String,
        headers: Map<String, String>,
        strategies: List<Strategy<T>>,
        maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
        totalTimeoutMs: Long
    ): StrategyResult<T>? {
        if (strategies.isEmpty()) return null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val winner = CompletableDeferred<StrategyResult<T>?>()
        val fallback = AtomicReference<StrategyResult<T>?>(null)
        val permits = Semaphore(maxConcurrent.coerceAtLeast(1))
        val remaining = AtomicInteger(strategies.size)
        try {
            strategies.forEach { strategy ->
                scope.launch {
                    val start = System.currentTimeMillis()
                    val value: T? = if (strategy.networkBound) {
                        permits.withPermit {
                            withTimeoutOrNull(strategy.timeoutMs) { strategy.run(url, headers) }
                        }
                    } else {
                        withTimeoutOrNull(strategy.timeoutMs) { strategy.run(url, headers) }
                    }
                    if (value != null) {
                        val result = StrategyResult<T>(
                            value, strategy.name, strategy.confidence,
                            System.currentTimeMillis() - start
                        )
                        fallback.compareAndSet(null, result)
                        if (strategy.canWin) winner.complete(result)
                    }
                    // 最后一个策略结束：以兜底结果收口（若已有胜出者，complete 返回 false，无副作用）
                    if (remaining.decrementAndGet() == 0) winner.complete(fallback.get())
                }
            }
            return withTimeoutOrNull(totalTimeoutMs) { winner.await() } ?: fallback.get()
        } finally {
            // 结构化取消：胜出 / 超时 / 异常返回时取消其余策略
            scope.cancel()
        }
    }
}

/**
 * 赛马资源闸（W2 / REQ-14 / AD-05）：**连续失败 3 次进入 60s 冷却**，冷却期内不发起赛马。
 *
 * 目的：网络全断 / 站点整体不可用时，避免每次嗅探都并发打两路请求（资源与观感双输）。
 * 冷却结束后计数自动复位，恢复赛马。
 */
class SniffRaceLimiter(
    private val maxConsecutiveFailures: Int = MAX_CONSECUTIVE_FAILURES,
    private val cooldownMs: Long = COOLDOWN_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    var consecutiveFailures: Int = 0
        private set

    private var cooldownUntil: Long = 0L

    /** 是否处于冷却期。 */
    fun isCoolingDown(): Boolean = clock() < cooldownUntil

    /** 此刻是否允许发起赛马（冷却期外允许）。 */
    fun canRace(): Boolean = !isCoolingDown()

    /** 记录一次赛马成功（有权威结果）：连续失败计数复位。 */
    fun noteSuccess() {
        consecutiveFailures = 0
    }

    /** 记录一次赛马全灭：达阈值即进入冷却。 */
    fun noteFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= maxConsecutiveFailures) {
            cooldownUntil = clock() + cooldownMs
            consecutiveFailures = 0
        }
    }

    /** 复位（开关重新打开 / 会话切换）。 */
    fun reset() {
        consecutiveFailures = 0
        cooldownUntil = 0L
    }

    companion object {
        /** 连续失败阈值（预置决策 design §9.2#1 / #16）。 */
        const val MAX_CONSECUTIVE_FAILURES = 3

        /** 冷却时长（预置决策 design §9.2#1 / #16）。 */
        const val COOLDOWN_MS = 60_000L
    }
}