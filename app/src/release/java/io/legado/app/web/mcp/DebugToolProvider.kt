package io.legado.app.web.mcp

/**
 * L3 调试观测工具提供者 —— **release 变体实现（空）**（web-mcp-productization 二期 · 修正版 §1.6.2）。
 *
 * **为什么是"按变体拆 sourceSet"而不是"main 空实现 + debug 同包同名覆盖"**：
 * 设计原稿写的是 main 放空实现、`src/debug` 放同名 `object` 覆盖它 —— 但 **Android 的 sourceSet
 * 覆盖机制只对 `res` / `assets` 这类资源生效，对**类**不生效**：debug 变体的编译输入是
 * `main + debug` 两个目录的**并集**，于是同一个 FQN 出现两次，Kotlin 直接报
 * `Redeclaration: DebugToolProvider`（Java 侧同样是"重复类"）。**实测铁证（2026-09-29）**：
 * 依原稿落地后 `compileAppDebugKotlin` 立即失败于两条 `Redeclaration` 错误。
 *
 * **落地机制（修正口径，已反哺 design §1.6.2 / tasks 0.4·5.3）**：
 * - `src/debug/java/.../mcp/DebugToolProvider.kt` → **真实 14 个 L3 工具**（release 不编）；
 * - `src/release/java/.../mcp/DebugToolProvider.kt`（本文件）→ **空实现**（debug 不编）；
 * - `src/main` **不放**该符号 —— 两个变体各自恰好拿到一份，永不重复。
 *
 * 这样 release 包内 L3 工具是**物理不存在**（不是运行时开关、也不依赖 R8 裁剪），
 * 与 REQ-2-403 的判据（release APK 反编译无 L3 包装类）严格一致。
 */
object DebugToolProvider {
    val tools: List<McpTool> = emptyList()
}
