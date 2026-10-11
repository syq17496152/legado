package io.legado.app.model.reader.render

/**
 * 重测分页**防抖**策略（epub-md-rich-rendering 补充任务 S11 / AD-28⑤）。
 *
 * **为什么需要**：富渲染（mermaid / KaTeX / 代码高亮）注入完成后正文高度会变，宿主因此向沙箱
 * 下 `remeasure` 指令触发**二次分页**（AD-28⑤ 的 P-05）。若多个来源（注入完成、主题/字号变更、
 * 视口变化、宿主显式调用）在短窗口内各发一次，就会**连续多次重排** ⇒ 用户看到页码反复跳。
 *
 * **语义（尾沿合并 / trailing debounce）**：窗口内**合并为一次**——
 * 首个请求排定一次延迟触发；窗口内后续请求只**顺延**截止时刻，不再重复排定；
 * 定时器到点时若窗口已**静默**则执行一次（[onTimer] 返回 true），否则继续等待。
 *
 * 纯逻辑（时钟由调用方注入），无 Handler / WebView / IO ⇒ 可 JVM 单测。
 * **接线**在 `TemplateRenderBackend.remeasure`（唯一消费者）。
 */
class RepaginationDebouncePolicy(
    private val windowMs: Long = DEFAULT_WINDOW_MS
) {

    private var scheduledUntil: Long = Long.MIN_VALUE
    private var hasScheduled: Boolean = false

    /** 已有挂起（定时器已排定且尚未执行）。 */
    val isPending: Boolean get() = hasScheduled

    /**
     * 收到一次重测请求。
     *
     * @return true＝需要**排定**一次延迟触发；false＝已有挂起 ⇒ 本次仅**顺延**（合并，勿重复排定）。
     */
    fun onRequest(nowMs: Long): Boolean {
        scheduledUntil = nowMs + windowMs
        if (hasScheduled) return false
        hasScheduled = true
        return true
    }

    /**
     * 定时器到点。
     *
     * @return true＝窗口已静默 ⇒ 应执行重测（并清挂起）；false＝窗口内又有新请求 ⇒ 应继续等待。
     */
    fun onTimer(nowMs: Long): Boolean {
        if (!hasScheduled) return false
        if (nowMs < scheduledUntil) return false
        hasScheduled = false
        return true
    }

    /** 复位（释放/换章：不留悬空挂起给下一次）。 */
    fun reset() {
        hasScheduled = false
        scheduledUntil = Long.MIN_VALUE
    }

    companion object {
        /** 合并窗口（ms）：远小于人眼可感的"卡住"，又足以覆盖一次注入完成的多路回声。 */
        const val DEFAULT_WINDOW_MS = 180L
    }
}
