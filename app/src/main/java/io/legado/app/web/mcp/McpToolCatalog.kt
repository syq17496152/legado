package io.legado.app.web.mcp

import io.legado.app.BuildConfig
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.tools.BackupTools
import io.legado.app.web.mcp.tools.BookmarkTools
import io.legado.app.web.mcp.tools.BookshelfTools
import io.legado.app.web.mcp.tools.ReadingTools
import io.legado.app.web.mcp.tools.RssTools
import io.legado.app.web.mcp.tools.RuleTools
import io.legado.app.web.mcp.tools.SourceTools

/**
 * MCP 工具目录（web-mcp-productization 二期 · §1.3.2 / REQ-2-203 / REQ-2-206）。
 *
 * **只做聚合 / 裁剪 / 查找，不含任何工具实现** —— 工具元数据与 `invoke` 都在按域拆分的
 * `web/mcp/tools/` 下按域拆分的文件里（禁止 232 个工具挤一个文件）。
 *
 * **规模口径**：release = `declared`（218）；debug = `declared` + `DebugToolProvider.tools`（+14 = 232）。
 * `DebugToolProvider` 按**变体拆 sourceSet**（`src/debug/` 真实实现 / `src/release/` 空实现，
 * `src/main` 不放该符号 —— 见 design §1.6.2 的修正口径），故 release 包内 L3 工具**物理不存在**
 * （REQ-2-403；不依赖运行时开关，也不依赖 R8 裁剪）。
 *
 * **可见性口径 = 累积**（REQ-2-202 附注）：`visibleFor(MANAGE)` 可见 READONLY + MANAGE 两级。
 */
object McpToolCatalog {

    /**
     * release 面工具声明（按域聚合）。
     *
     * 记法（分期落地）：本列表随 tasks §2 各域任务**逐批追加**域对象；
     * 未加入的域在该域任务完成时补上（缺口会在 §2.26 的**总数断言**上暴露为红灯，
     * 不会静默漏项 —— 这是刻意的"以断言兜底增量"设计）。
     */
    private val declared: List<McpTool> by lazy {
        buildList {
            addAll(BookshelfTools.tools)
            addAll(ReadingTools.tools)
            addAll(BookmarkTools.tools)
            addAll(SourceTools.tools)
            addAll(RssTools.tools)
            addAll(RuleTools.tools)
            addAll(BackupTools.tools)
        }.also(::requireUniqueNames)
    }

    /** L3 调试观测工具：debug sourceSet 真实实现；release 为 main 侧空实现 ⇒ 恒空。 */
    private val debugTools: List<McpTool> by lazy {
        if (BuildConfig.BUILD_DEBUG) DebugToolProvider.tools else emptyList()
    }

    /** 全部工具（release = 声明域；debug = 声明域 + L3）。 */
    fun all(): List<McpTool> = declared + debugTools

    /** 按令牌级别裁剪（第二闸·列表侧，REQ-2-303）。 */
    fun visibleFor(level: TokenManager.Level): List<McpTool> =
        all().filter { levelRank(it.level) <= levelRank(level) }

    /** 按名查找（第二闸·调用侧用；不存在返回 null）。 */
    fun find(name: String): McpTool? = all().firstOrNull { it.name == name }

    /** 按域筛选（上下文压力控制第②层）。 */
    fun byDomain(domain: String): List<McpTool> = all().filter { it.domain == domain }

    /** 全部 `domain` 取值（去重，按首次出现顺序）。 */
    fun domains(): List<String> = all().map { it.domain }.distinct()

    /** 级别序（NONE=0 < READONLY=1 < MANAGE=2 < ADMIN=3），与 [io.legado.app.web.WebAuth.allow] 同口径。 */
    fun levelRank(level: TokenManager.Level): Int = level.ordinal

    /**
     * 工具名唯一性（**启动期硬校验**）：重名会让 `find` 命中错误的执行体，
     * 属编程错误 ⇒ 必须早失败而非静默取首个。
     */
    private fun requireUniqueNames(tools: List<McpTool>) {
        val duplicated = tools.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) { "MCP 工具名重复：$duplicated" }
    }
}
