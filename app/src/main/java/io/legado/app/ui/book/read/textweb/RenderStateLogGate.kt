package io.legado.app.ui.book.read.textweb

/**
 * 沙箱状态日志限频门禁（时间窗节流）。
 *
 * 背景（2026-10-15 真机实测，两次否证后定稿）：
 *  · 稳态下 `TemplateRenderBackend.onRenderState` 的兜底分支写出 **138 行 / 10 秒**；
 *  · 冷路径（画布面→模板面）下 **634 行 / 6 秒**，且**只有 2 个状态名**交替
 *    （`settle` 318 次 / `page-frame` 316 次）。
 *  ⇒ 早先设想的「同状态名去重」**对它无效**（两次上报的状态名本来就不同，去重逻辑会全部放行）。
 *  正确的口径是**按时间窗限频**：无论状态名是否变化，两条日志之间至少间隔 [MIN_INTERVAL_MS]。
 *
 * 危害（为什么要限频）：①每行都是 `Log.e`，走同步日志通道占用 JS 桥/宿主线程；
 * ②logcat 环形缓冲被刷爆，更早的关键行（如模板内容准备的 `prepare: … manual=N`）被挤出
 * ⇒ 排查时表现为「该日志压根不存在」的假象（本轮 L2 验证即先被此假象误导）。
 */
object RenderStateLogGate {

    /** 两条状态日志之间的最小间隔（毫秒）——稳态与交替上报一律受此约束 */
    const val MIN_INTERVAL_MS = 300L

    /**
     * 距上次输出达到 [minIntervalMs] 才允许输出。
     *
     * @param elapsedMs 与上次输出的时间差（毫秒；从未输出过时应传一个不小于间隔的值）
     */
    fun shouldLog(elapsedMs: Long, minIntervalMs: Long = MIN_INTERVAL_MS): Boolean =
        elapsedMs >= minIntervalMs
}
