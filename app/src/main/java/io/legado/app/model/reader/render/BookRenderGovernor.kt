package io.legado.app.model.reader.render

import android.os.SystemClock

/**
 * 书级渲染治理（epub-md-rich-rendering 补充任务 S6；AD-25③ / AD-28②）。
 *
 * **为什么单列本类**：[BackendSelectionPolicy] 只回答「单章该走哪个后端」、
 * [RenderBackendStateMachine] 只治理「单个渲染会话」的代际与冷却。两者都不承担
 * 设计文档明确要求的两件事：
 * 1. **书级一致性**（AD-25③）：同书多数章节出版方控制 ⇒ **全书**同一后端，
 *    避免「第 3 章 WebView、第 4 章 canvas」的逐章样式抖动；
 * 2. **降级为书级原子切换 + 熔断**（AD-28②）：一次失败即**整书**切后端（不存在
 *    部分章节降级、部分未降的中间态）；同一章的重复失败在冷却窗内被熔断拒绝。
 *
 * 语义边界（如实登记，不假装覆盖）：本类是**纯状态对象**（时钟可注入、无 WebView / 协程 / IO），
 * 只产出「该书当前应该用哪个后端」的判据；**把判据接到宿主渲染链路**属阶段 2.4 接线，
 * 当前尚未接线（见 tasks.md 2.4 条与 S6 条）。
 */
class BookRenderGovernor(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val degradeCooldownMs: Long = DEFAULT_DEGRADE_COOLDOWN_MS
) {

    /** 一次章级失败上报的结果。 */
    data class DegradeOutcome(
        /** 本次是否真的发生了降级（false ⇒ 被熔断拒绝，或书未开局）。 */
        val degraded: Boolean,
        /** 降级后的**书级**后端（未降级时为当前后端）。 */
        val backend: BackendSelectionPolicy.Backend,
        /** 触发降级的失败原因（被熔断时为上一次的原因）。 */
        val failure: RenderFailure?
    )

    private var bookKey: String? = null
    private var backend: BackendSelectionPolicy.Backend? = null
    private var lastFailure: RenderFailure? = null
    private var degradedChapter: Int = -1
    private var degradeStartedAt: Long = 0L

    /** 当前书级后端（未开局返回 null）。 */
    val currentBackend: BackendSelectionPolicy.Backend?
        get() = backend

    /**
     * 开局/换书：按 [initial] 建立**书级**后端。
     *
     * 换书（bookKey 变化）时重置全部降级状态，避免「上一本的降级状态串到下一本」。
     * 同一本书重复调用不重置（保持书级粘性）。
     */
    fun startBook(
        bookKey: String,
        initial: BackendSelectionPolicy.Backend
    ): BackendSelectionPolicy.Backend {
        if (this.bookKey != bookKey) {
            this.bookKey = bookKey
            this.backend = initial
            this.lastFailure = null
            this.degradedChapter = -1
            this.degradeStartedAt = 0L
        }
        return requireNotNull(this.backend)
    }

    /** 指定书当前应使用的后端（书级一致：全书同值；非当前书返回 null）。 */
    fun backendFor(bookKey: String): BackendSelectionPolicy.Backend? =
        backend?.takeIf { this.bookKey == bookKey }

    /**
     * 章级渲染失败上报 ⇒ **书级原子降级**。
     *
     * 熔断（AD-28②）：**同一章**在 [degradeCooldownMs] 内只允许降一级；期间重复上报
     * 视为同一故障的余波 ⇒ 拒绝（`degraded=false`）；冷却结束后（或**另一章**首次失败）
     * 才放行下一级。降级效果是书级的——落定后对该书**所有章节**生效。
     */
    fun onChapterFailure(
        bookKey: String,
        chapterIndex: Int,
        failure: RenderFailure
    ): DegradeOutcome {
        val current = backendFor(bookKey)
            ?: return DegradeOutcome(
                degraded = false,
                backend = BackendSelectionPolicy.Backend.CANVAS,
                failure = failure
            )
        if (!canDegradeAt(chapterIndex)) {
            return DegradeOutcome(degraded = false, backend = current, failure = lastFailure)
        }
        val next = BackendSelectionPolicy.degradeNext(current)
        backend = next
        lastFailure = failure
        degradedChapter = chapterIndex
        degradeStartedAt = clock()
        return DegradeOutcome(degraded = true, backend = next, failure = failure)
    }

    private fun canDegradeAt(chapterIndex: Int): Boolean {
        // 不同章：属新故障，不受「同章熔断」约束，允许立即降级。
        if (chapterIndex != degradedChapter) return true
        // 同章：冷却窗内视为同一故障余波 ⇒ 拒绝（熔断）。
        return clock() - degradeStartedAt >= degradeCooldownMs
    }

    /** 释放（退出阅读页/换书）：清空全部状态。 */
    fun dispose() {
        bookKey = null
        backend = null
        lastFailure = null
        degradedChapter = -1
        degradeStartedAt = 0L
    }

    companion object {
        /** 与 [RenderBackendStateMachine.DEFAULT_DEGRADE_COOLDOWN_MS] 同源（AD-28② 的 15s）。 */
        const val DEFAULT_DEGRADE_COOLDOWN_MS = RenderBackendStateMachine.DEFAULT_DEGRADE_COOLDOWN_MS
    }
}