package io.legado.app.help.ai

/**
 * AI 工具名集合的**写入边界**（纯函数，JVM 可测）。
 *
 * 背景（铁证 2026-10-13）：`aiEnabledToolNames` 是 `SharedPreferences` 的 string-set，
 * 由 UI 与 **MCP 工具 `ai_agent_config_save`**（`AiKernel.agentConfigSave`）共同写入。
 * 若调用方传入超大数组/超长字符串，会被原样落盘 —— 实测调试机上该键被写成 **155 MB**，
 * 整个 prefs XML 涨到 **402 MB**，导致 `SharedPreferences` 首次加载解析极慢（应用卡顿）
 * 且所有依赖 prefs 的调试脚本超时不可用。
 *
 * 因此把「条数 + 单条长度」上限收口到**唯一持久化入口**（[io.legado.app.help.config.AppConfig]
 * 的 setter），UI 与 MCP 两条写入路径同时受约束。
 */
internal object AiToolNameLimits {

    /** 单条工具名最大长度（工具名形如 `book_search`，128 已是极大冗余）。 */
    const val MAX_NAME_LENGTH = 128

    /** 集合最大条数（当前工具总数在数百量级，512 足够且能挡住畸形入参）。 */
    const val MAX_NAMES = 512

    /**
     * 归一化待持久化的工具名集合：去空白项、去首尾空格、截断超长项、按上限截断条数。
     *
     * - 不抛异常（越界一律**收敛**而非失败，避免影响正常设置流程）；
     * - 截断后仍按**首次出现顺序**保留（`LinkedHashSet`），保证行为可预期。
     */
    fun sanitize(names: Collection<String>): Set<String> {
        val result = LinkedHashSet<String>(minOf(names.size, MAX_NAMES))
        for (raw in names) {
            if (result.size >= MAX_NAMES) break
            val name = raw.trim()
            if (name.isEmpty()) continue
            result += if (name.length > MAX_NAME_LENGTH) name.take(MAX_NAME_LENGTH) else name
        }
        return result
    }
}