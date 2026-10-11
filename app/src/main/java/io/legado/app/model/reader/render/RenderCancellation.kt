package io.legado.app.model.reader.render

import kotlin.coroutines.cancellation.CancellationException

/**
 * 渲染链路的并发取消语义（epub-md-rich-rendering 补充任务 S8；AD-28⑨）。
 *
 * **为什么单列**：本项目既有约定用 `kotlin.runCatching` 收敛异常（AGENTS.md 代码约束），
 * 而 `runCatching` 会**连 [CancellationException] 一起吞掉**——协程取消被当成普通业务失败，
 * 表现为「取消后仍继续渲染 / 仍上报结果」，破坏结构化并发。AD-28⑨ 明令取消异常必须正确重抛。
 *
 * 纯函数 ⇒ JVM 可测；供渲染链路（含 WebView 注入回调的异步分支）统一使用。
 */
object RenderCancellation {

    /** 取消异常必须**原样重抛**，不得被当作业务失败收敛（AD-28⑨）。 */
    fun rethrowIfCancellation(t: Throwable) {
        if (t is CancellationException) throw t
    }

    /**
     * 渲染链路专用 `runCatching`：语义与 `kotlin.runCatching` 一致，
     * 但**取消异常重抛**。链路内所有 `runCatching` 都应改用本函数，避免吞掉取消。
     */
    inline fun <T> runRenderCatching(block: () -> T): Result<T> =
        kotlin.runCatching(block).onFailure { rethrowIfCancellation(it) }
}