package io.legado.app.service.kernel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * 源调试**阶段追踪器**（web-mcp-productization 二期 · tasks 5.4 / REQ-2-404）。
 *
 * **为什么在 main**：书源/订阅源的调试链有三条消费方 —— ①App 内 WebSocket 调试
 * （`web/socket/BookSourceDebugWebSocket`）②REST 调试端点 ③MCP 的 L3 工具。
 * 三者若各写一套"阶段 + 计时 + 错误措辞"，必然漂移（同一坏源在三个入口给出不同结论）。
 * 故把**阶段模型与结果信封**单源化到本类，位于 `main` ⇒ release/debug 都编入，
 * L3 工具名则只在 debug 声明（REQ-2-403）。
 *
 * **阶段粒度为「步骤级」**（source → search/toc/content/explore/rss），**非规则行级**：
 * 既有解析链（`AnalyzeUrl` / `AnalyzeRule` / `WebBook` / `Rss`）在规则解析失败时**只抛异常**，
 * 不携带"失败发生在哪一条规则/哪一行"⇒ 若要行级定位，需在解析链内插桩改造（属后续事项，
 * 见 tasks 5.5 的接线评估）。本类**如实**只给步骤级，不虚报"已精确定位"。
 *
 * **输出安全**：错误文本经 [DiagKernel.maskUrl] 打码（本类不 import `web` 层，见 G-22）。
 *
 * **当前接线状态（诚实标注）**：已由 [SourceDebugKernel] 薄委托接入（同一份信封/计时/措辞）；
 * 与 `BookSourceDebugWebSocket` 的接线（tasks 5.5）待做 —— 那一步需要回归验证 App 内 WS 调试行为。
 */
object SourceStepTracer {

    /** 单步硬超时默认值（防慢源把调用挂死；与 [SourceDebugKernel] / `RssSourceKernel` 同量级）。 */
    const val DEFAULT_TIMEOUT_MS = 30_000L

    // ── 阶段名常量（三端统一措辞，避免同一环节在不同入口叫不同名字）──
    const val STEP_SOURCE = "source"
    const val STEP_INFO = "info"
    const val STEP_SEARCH = "search"
    const val STEP_TOC = "toc"
    const val STEP_CONTENT = "content"
    const val STEP_EXPLORE = "explore"
    const val STEP_RSS = "rss"

    /** 规则失败时未定位到具体行的固定措辞（防上层误读为"已精确诊断"）。 */
    const val NOTE_NO_RULE_LINE =
        "规则解析失败，未定位到具体规则行（既有解析 API 不返回失败规则/行号）"

    /** 单条阶段记录。 */
    data class Stage(
        val name: String,
        val ok: Boolean,
        val elapsedMs: Long,
        val detail: String? = null,
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "name" to name,
            "ok" to ok,
            "elapsedMs" to elapsedMs,
            "detail" to detail,
        )
    }

    /** 一次追踪的全过程（阶段序列 + 总判定）。 */
    data class Trace(
        val ok: Boolean,
        val stages: List<Stage>,
        val error: String? = null,
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "ok" to ok,
            "stages" to stages.map { it.toMap() },
            "error" to error,
        )
    }

    /**
     * 记录一步：耗时就地度量；失败**不中断**（记为 `ok=false` 并把打码后的错误留在该步）。
     *
     * @return 步骤成功时的返回值；失败返回 `null`（调用方据此决定后续是否继续）
     */
    suspend fun <T> record(
        stages: MutableList<Stage>,
        name: String,
        block: suspend () -> T,
    ): T? {
        val start = System.currentTimeMillis()
        return try {
            val value = block()
            stages.add(Stage(name, true, elapsedSince(start)))
            value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            stages.add(Stage(name, false, elapsedSince(start), describeError(e)))
            null
        }
    }

    /**
     * 带硬超时的探测执行。
     *
     * 口径：**真取消原样抛出**（协程取消不能被吞成"业务失败"）；**超时**与**业务异常**
     * 都归入 `Result.failure`，由调用方转成结构化信封。
     */
    suspend fun <T> probe(timeoutMs: Long = DEFAULT_TIMEOUT_MS, block: suspend () -> T): Result<T> =
        try {
            Result.success(withTimeout(timeoutMs) { block() })
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            Result.failure(e)
        } catch (e: Throwable) {
            Result.failure(e)
        }

    fun elapsedSince(start: Long): Long = System.currentTimeMillis() - start

    /** 异常转白话（URL 敏感参数打码；与诊断域复用同一实现，避免两套脱敏口径）。 */
    fun describeError(e: Throwable?): String {
        if (e == null) return "未知错误"
        val raw = e.localizedMessage ?: e.javaClass.simpleName
        return "${e.javaClass.simpleName}: ${DiagKernel.maskUrl(raw)}"
    }

    /** 成功信封（与 L3 工具的返回形状一致：`success/elapsedMs/detail/error`）。 */
    fun ok(elapsedMs: Long, detail: Any?): Map<String, Any?> = mapOf(
        "success" to true,
        "elapsedMs" to elapsedMs,
        "detail" to detail,
        "error" to null,
    )

    /** 失败信封（`step` 指出失败环节）。 */
    fun fail(step: String, message: String, elapsedMs: Long = 0L): Map<String, Any?> = mapOf(
        "success" to false,
        "step" to step,
        "elapsedMs" to elapsedMs,
        "detail" to null,
        "error" to message,
    )
}