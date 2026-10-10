package io.legado.app.model.localBook.epubcore.template

/**
 * 模板选定策略（epub-md-rich-rendering 阶段 4.8a 前置 / SP-06 / S2）。
 *
 * 回答一个具体的产品问题：**此刻这本书该用哪一套模板？**
 * - 用户显式应用过某套 ⇒ 用那套；
 * - 用户选的是「跟随主题」（[FollowTheme]）⇒ 按当前日夜取默认款；
 * - 选中的模板已不存在（被删除/被导入报告跳过）⇒ **回落**到主题默认，而不是"白屏无模板"。
 *
 * 默认款随主题（SP-06：默认模板暗色赛博冲撞浅色主题 ⇒ 改为浅/暗各一）：
 * 暗色 → [NightPreference]；浅色 → [DayPreference]。
 * 偏好序列中**未交付/不存在的 id 自动跳过**——这样后续补齐内置模板（如 A4「极简科技」）
 * 只需把 id 插进序列首位，无需改本策略与调用方。
 *
 * 纯函数（无 Android 依赖、无 IO）⇒ 逐格可单测。
 */
internal object ReaderTemplateSelection {

    /** 「跟随主题」哨兵值：应用模板字段取该值时按日夜取默认款。 */
    const val FollowTheme = ""

    /** 暗色主题默认款偏好序列（首位为设计指定的默认模板）。 */
    val NightPreference = listOf("builtin.neon-night")

    /**
     * 浅色主题默认款偏好序列。
     *
     * 设计指定浅色默认款为「极简科技」（A4），但其尚未交付 ⇒ 一期先回落「素笺」；
     * A4 交付后把 `builtin.minimal-tech` 插到首位即可生效（无需改本文件其它逻辑）。
     */
    val DayPreference = listOf("builtin.minimal-ink")

    fun isFollowTheme(appliedTemplateId: String): Boolean = appliedTemplateId.isBlank()

    /**
     * 解析实际生效的模板 id。
     *
     * @param appliedTemplateId 用户应用值（[FollowTheme] = 跟随主题）。
     * @param preferNight 当前是否夜间主题（决定默认款序列）。
     * @param availableIds 当前可用模板 id 全集（内置 + 用户库；顺序即"最后兜底"优先级）。
     * @return 生效 id；**无任何可用模板时返回 null**（调用方据此判定"用不了"并给提示）。
     */
    fun resolveId(
        appliedTemplateId: String,
        preferNight: Boolean,
        availableIds: List<String>
    ): String? {
        if (availableIds.isEmpty()) return null
        if (!isFollowTheme(appliedTemplateId) && appliedTemplateId in availableIds) {
            return appliedTemplateId
        }
        return defaultIdForTheme(preferNight, availableIds)
    }

    /** 主题默认款；偏好序列全落空时取可用集首个（保证"有模板可用就不落空"）。 */
    fun defaultIdForTheme(preferNight: Boolean, availableIds: List<String>): String? {
        val preference = if (preferNight) NightPreference else DayPreference
        preference.firstOrNull { it in availableIds }?.let { return it }
        // 对侧序列兜底：浅色偏好缺位时，暗色默认款也好过"无模板"（用户至少看到一套成品外观）
        val fallbackPreference = if (preferNight) DayPreference else NightPreference
        fallbackPreference.firstOrNull { it in availableIds }?.let { return it }
        return availableIds.firstOrNull()
    }
}
