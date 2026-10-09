package io.legado.app.model.reader.render

/**
 * 后端切换状态机 + 代际令牌语义（epub-md-rich-rendering 阶段 2.1 / AD-28）。
 *
 * 状态图（设计文档 §三）：
 * ```
 * Idle → Preparing → Rendered ⇄ Measuring
 *                      ├─→ Switching → Preparing（代际++）
 *                      └─→ Degraded  → Rendered（冷却后重试 ≤1 次 / 15s）
 * Preparing → Failed → Degraded（按降级链）
 * ```
 *
 * **代际令牌语义（本类核心）**：每次 [Switching]/[Degraded] 递增 `generation`；
 * **晚到的异步结果必须校验** `token.generation == generation` **且** 期间未发生切换，
 * 否则丢弃。这类竞态是 WebView 后端的固有风险（注入脚本回调可跨代际到达）。
 *
 * 时钟可注入（[clock]）⇒ 冷却逻辑可在 JVM 单测中确定性验证（blueprint §十一 A9）。
 */
class RenderBackendStateMachine(
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val degradeCooldownMs: Long = DEFAULT_DEGRADE_COOLDOWN_MS
) {

    enum class State {
        Idle,
        Preparing,
        Rendered,
        Measuring,
        Switching,
        Degraded,
        Failed
    }

    var state: State = State.Idle
        private set

    /** 当前代际（晚到结果校验基准）。 */
    var generation: Long = 0L
        private set

    private var degradeStartedAt: Long = 0L
    private var degradeReason: RenderFailure? = null

    /** 最近一次失败原因（诊断用）。 */
    val lastFailure: RenderFailure?
        get() = degradeReason

    fun currentToken(): RenderToken = RenderToken(generation)

    /** 该 token 是否为当前代际（晚到结果过滤：false ⇒ **必须丢弃**）。 */
    fun isCurrent(token: RenderToken): Boolean = token.generation == generation

    /** 开始准备渲染（Idle/Rendered/Failed/Switching → Preparing）。 */
    fun beginPrepare(): RenderToken {
        state = State.Preparing
        return currentToken()
    }

    /** 渲染成功（Preparing → Rendered）。 */
    fun markRendered() {
        state = State.Rendered
    }

    /** 进入重测（Rendered → Measuring）。 */
    fun beginRemeasure() {
        if (state == State.Rendered) state = State.Measuring
    }

    /** 重测完成（Measuring → Rendered）。 */
    fun markRemeasured() {
        if (state == State.Measuring) state = State.Rendered
    }

    /** 切换后端/模板/引擎：递增代际并回到 Preparing（旧代际的晚到结果全部失效）。 */
    fun beginSwitch(): RenderToken {
        generation++
        state = State.Switching
        return currentToken()
    }

    /** 渲染失败（Preparing/Measuring → Failed）。 */
    fun markFailed(failure: RenderFailure) {
        degradeReason = failure
        state = State.Failed
    }

    /**
     * 按降级链进入降级态：递增代际（旧后端结果失效）并开始冷却计时。
     *
     * @return 降级后的新 token。
     */
    fun beginDegrade(failure: RenderFailure): RenderToken {
        generation++
        degradeReason = failure
        degradeStartedAt = clock()
        state = State.Degraded
        return currentToken()
    }

    /** 冷却是否结束（Degraded 态 ≤1 次 / [degradeCooldownMs] 的重试门）。 */
    fun canRetryAfterDegrade(): Boolean {
        if (state != State.Degraded) return true
        return clock() - degradeStartedAt >= degradeCooldownMs
    }

    /**
     * 冷却结束后回到可用态。
     *
     * @return true ⇒ 已恢复（可重试渲染）；false ⇒ 冷却未到，仍处 Degraded。
     */
    fun retryIfCooledDown(): Boolean {
        if (!canRetryAfterDegrade()) return false
        state = State.Rendered
        degradeReason = null
        return true
    }

    /** 释放（任何状态 → Idle，并递增代际使在途结果全部失效）。 */
    fun dispose() {
        generation++
        state = State.Idle
        degradeReason = null
    }

    companion object {
        const val DEFAULT_DEGRADE_COOLDOWN_MS = 15_000L
    }
}