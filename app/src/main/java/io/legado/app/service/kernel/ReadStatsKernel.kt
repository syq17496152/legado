package io.legado.app.service.kernel

import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadRecordDaily
import io.legado.app.ui.about.ReadRecordGoalConfig
import io.legado.app.ui.about.ReadRecordWidgetStore
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * ⑩ 统计域业务内核（web-mcp-productization 二期 · tasks 2.15 / 2.28）。
 *
 * 契约同 [BookKernel]：**只返回领域对象 / 结构化 Map**、**全链挂起**、**零 `runBlocking`**、失败抛异常。
 *
 * 数据源（全部为既有能力，不新增存储）：
 * - 日阅读记录：`appDb.readRecordDailyDao.allDesc`（`ReadRecordDaily`，`date` 为主键，`yyyy-MM-dd`）
 * - 阅读目标：`ReadRecordWidgetStore.loadGoalConfig()/saveGoalConfig()`（键 `PreferKey.readRecordGoalConfig`）
 * - 热力图：与 `ui/main/readrecord/ReadRecordFragment` 同源（现算，不落库）
 *
 * 已知上限：统计口径为**现算聚合**（不落库），超大历史（数千天）时按 `limit` 截断返回；
 * 升级路径：需要时改为 DAO 侧 `SUM` 聚合查询。
 */
object ReadStatsKernel {

    /** 与 `ReadRecordDaily.date` 一致的键格式（`yyyy-MM-dd`）。 */
    private val DATE_KEY = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private fun ReadRecordDaily.toMap(): Map<String, Any?> = mapOf(
        "date" to date,
        "readTimeMs" to readTime,
        "readMinutes" to (readTime / 60_000L),
        "updatedAt" to updatedAt,
    )

    /** `read_records_list`：日阅读记录列表（倒序；`limit <= 0` 表示不限）。 */
    suspend fun records(limit: Int = 0): Map<String, Any?> = withContext(IO) {
        val all = appDb.readRecordDailyDao.allDesc
        val picked = if (limit > 0) all.take(limit) else all
        mapOf(
            "total" to all.size,
            "returned" to picked.size,
            "records" to picked.map { it.toMap() },
        )
    }

    /**
     * `read_stats_get`：阅读统计（日/周/月/总）。
     *
     * 口径（显式声明，便于 REST ↔ MCP 对拍）：
     * - `today` = 键为今日的记录；`week` = 本周（ISO 周，周一起）；`month` = 本月；
     * - `all` = 全部历史；`activeDays` = 有记录的日期数（打卡天数）。
     */
    suspend fun stats(): Map<String, Any?> = withContext(IO) {
        val all = appDb.readRecordDailyDao.allDesc
        val today = LocalDate.now()
        val weekStart = today.with(WeekFields.ISO.dayOfWeek(), 1L)
        val monthStart = today.withDayOfMonth(1)

        fun sumSince(predicate: (LocalDate) -> Boolean): Long =
            all.filter { runCatching { LocalDate.parse(it.date, DATE_KEY) }.getOrNull()?.let(predicate) == true }
                .sumOf { it.readTime }

        val todayMs = all.firstOrNull { it.date == today.format(DATE_KEY) }?.readTime ?: 0L
        val weekMs = sumSince { !it.isBefore(weekStart) && !it.isAfter(today) }
        val monthMs = sumSince { !it.isBefore(monthStart) && !it.isAfter(today) }
        val totalMs = all.sumOf { it.readTime }

        mapOf(
            "todayMs" to todayMs,
            "weekMs" to weekMs,
            "monthMs" to monthMs,
            "totalMs" to totalMs,
            "activeDays" to all.size,
            "daily" to all.take(30).map { it.toMap() },
        )
    }

    /** `read_heatmap_get`：热力图数据（日期 + 阅读时长；`days` 为回溯天数，<=0 表示全部）。 */
    suspend fun heatmap(days: Int = 0): Map<String, Any?> = withContext(IO) {
        val all = appDb.readRecordDailyDao.allDesc
        val picked = if (days > 0) {
            val from = LocalDate.now().minusDays(days.toLong() - 1)
            all.filter { runCatching { LocalDate.parse(it.date, DATE_KEY) }.getOrNull()?.let { d -> !d.isBefore(from) } == true }
        } else {
            all
        }
        mapOf(
            "total" to all.size,
            "cells" to picked.map { mapOf("date" to it.date, "readTimeMs" to it.readTime) },
        )
    }

    /** `read_goal_get`：阅读目标（`ReadRecordGoalConfig`：用户名 / 头像 / 每日目标分钟）。 */
    suspend fun goalGet(): ReadRecordGoalConfig = withContext(IO) { ReadRecordWidgetStore.loadGoalConfig() }

    /**
     * `read_goal_save`：保存阅读目标。
     *
     * 入参可只给部分字段（其余沿用现值 —— 避免"改目标把头像清空"这类意外）。
     */
    suspend fun goalSave(
        userName: String? = null,
        avatar: String? = null,
        dailyGoalMinutes: Int? = null,
    ): ReadRecordGoalConfig = withContext(IO) {
        val current = ReadRecordWidgetStore.loadGoalConfig()
        val updated = current.copy(
            userName = userName ?: current.userName,
            avatar = avatar ?: current.avatar,
            dailyGoalMinutes = dailyGoalMinutes ?: current.dailyGoalMinutes,
        )
        ReadRecordWidgetStore.saveGoalConfig(updated)
        updated
    }

    /** 便于上层构造 `yyyy-MM-dd` 键（与 [ReadRecordDaily.date] 同口径）。 */
    fun todayKey(): String = LocalDate.now().format(DATE_KEY)

    /** 便于测试与调用方统一 locale（周起始判定用 ISO 周，不受系统 locale 影响）。 */
    internal val ISO_LOCALE: Locale = Locale.ROOT
}