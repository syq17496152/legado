package io.legado.app.web.mcp

import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.tools.DiagnosticsTools
import io.legado.app.web.mcp.tools.SourceDebugTools

/**
 * L3 调试观测工具提供者 —— **debug 变体实现（真实）**（web-mcp-productization 二期 · 修正版 §1.6.2）。
 *
 * **红线（REQ-2-403）**：14 个 L3 工具**只能编入 debug 变体**，release 包内物理不存在。
 *
 * **机制（实测修正，2026-09-29）**：原设计"`src/main` 空实现 + `src/debug` 同包同名 `object` 覆盖"
 * **不成立** —— Android sourceSet 覆盖对**类**无效（debug 变体编译输入是 `main + debug` 的并集），
 * 依原稿落地会立即报 `Redeclaration: DebugToolProvider`。修正为**按变体拆 sourceSet**：
 * - 本文件（`src/debug/`）= 真实 14 个 L3 工具；
 * - `src/release/java/.../mcp/DebugToolProvider.kt` = 空实现；
 * - `src/main` **不放**该符号 ⇒ 每个变体恰好拿到一份。
 *
 * 详细论证与反哺记录见 `src/release/.../DebugToolProvider.kt` 的类注释与 design §1.6.2。
 *
 * **本文件按 tasks 5.12 增量补齐**：14 个 L3 工具 = 源测试 5 + 订阅测试 2 + 校验 2 + 诊断 5。
 * 当前已落地"不依赖尚未建成的调试读出层"的 `perf_metrics_get`；其余 13 个随 §5.6–5.13
 * （`AppLogBuffer` / `NetworkLogStore` / `SourceDebugKernel` / `SourceStepTracer`）落地后追加。
 */
object DebugToolProvider {

    /** 性能指标（一期遗留，实现保持不动）。 */
    private val perfTools: List<McpTool> = listOf(
        McpTool(
            name = "perf_metrics_get",
            title = "性能指标",
            description = "读取运行时性能指标：JVM 堆已用/总/上限字节数、可用处理器数、当前线程数、" +
                "进程运行时长；可选先触发一次 GC 再采样。",
            domain = MCP_DOMAIN_DIAG,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("gc" to McpJsonSchema.TYPE_BOOLEAN),
                descriptions = mapOf("gc" to "采样前是否先触发一次 GC（默认 false）")
            ),
        ) { args ->
            if (args.bool("gc")) System.gc()
            val runtime = Runtime.getRuntime()
            mapOf(
                "heapUsed" to runtime.totalMemory() - runtime.freeMemory(),
                "heapTotal" to runtime.totalMemory(),
                "heapMax" to runtime.maxMemory(),
                "availableProcessors" to runtime.availableProcessors(),
                "threadCount" to Thread.activeCount(),
                "uptimeMs" to android.os.SystemClock.elapsedRealtime()
            )
        },
    )

    /**
     * 14 个 L3 工具（tasks 5.12）：
     * 性能 1（`perf_metrics_get`）+ 源/订阅调试 9（[SourceDebugTools]）+ 诊断 4（[DiagnosticsTools]）。
     *
     * 三者都是 **debug sourceSet** 才有的符号 ⇒ release 包内物理不存在（REQ-2-403）。
     */
    val tools: List<McpTool> = perfTools + SourceDebugTools.tools + DiagnosticsTools.tools
}
