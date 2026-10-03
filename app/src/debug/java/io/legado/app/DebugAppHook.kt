package io.legado.app

import io.legado.app.service.McpDebugService

/**
 * App 启动钩子 —— **debug 变体实现**（web-mcp-productization 二期 · tasks 5.1）。
 *
 * 机制同 `DebugToolProvider`（决策 #24）：**按变体拆 sourceSet** —— 本文件在 `src/debug/`，
 * `src/release/` 有同名空实现，`src/main` **不放**该符号 ⇒ 每个变体恰好拿到一份，
 * 不会触发 `Redeclaration`（Android sourceSet 覆盖对**类**无效的已知陷阱）。
 *
 * 用途：App 启动时自动拉起 MCP 内腿服务（8765），开发者 AI 经 `adb reverse` 即可直连，
 * 无需手工 `am startservice`。
 */
object DebugAppHook {

    /** App `onCreate` 时调用（幂等）。 */
    fun onAppCreate() {
        McpDebugService.start()
    }
}