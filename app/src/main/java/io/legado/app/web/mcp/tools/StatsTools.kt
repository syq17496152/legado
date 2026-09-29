package io.legado.app.web.mcp.tools

import io.legado.app.service.kernel.ReadStatsKernel
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_STATS
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_INTEGER
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpTool

/**
 * ⑩ 统计域工具声明（web-mcp-productization 二期 · tasks 2.15 / 2.28）。
 *
 * 红线（AD-10）：`invoke` **只调 `service/kernel`**，不内联业务、不 import `api.controller`（REQ-2-305）。
 * 元数据按域单源声明；执行体一律调 Kernel（[ReadStatsKernel]）。
 *
 * 口径说明：统计为**现算聚合**（不落库）—— 记录列表 / 统计 / 热力图 / 阅读目标四类查询 + 目标保存。
 */
object StatsTools {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "read_records_list",
            title = "日阅读记录列表",
            description = "读取日阅读记录（按日期倒序）。返回 {total, returned, records}，" +
                "单条记录字段：date（yyyy-MM-dd）/readTimeMs/readMinutes/updatedAt。",
            domain = MCP_DOMAIN_STATS,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("limit" to TYPE_INTEGER),
                descriptions = mapOf("limit" to "最多返回条数（默认 0 = 不限，按日期倒序取前 N 条）")
            ),
        ) { args ->
            ReadStatsKernel.records(args.int("limit", 0))
        },

        McpTool(
            name = "read_stats_get",
            title = "阅读统计",
            description = "读取阅读统计（今日/本周/本月/累计时长 + 打卡天数 + 近 30 天明细）。" +
                "返回字段：todayMs/weekMs/monthMs/totalMs/activeDays/daily（时长为毫秒）。",
            domain = MCP_DOMAIN_STATS,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            ReadStatsKernel.stats()
        },

        McpTool(
            name = "read_heatmap_get",
            title = "阅读热力图数据",
            description = "读取热力图数据（日期 + 当日阅读时长）。返回 {total, cells}，" +
                "cells 单元素：date（yyyy-MM-dd）/readTimeMs。",
            domain = MCP_DOMAIN_STATS,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("days" to TYPE_INTEGER),
                descriptions = mapOf("days" to "回溯天数（默认 0 = 全部历史）")
            ),
        ) { args ->
            ReadStatsKernel.heatmap(args.int("days", 0))
        },

        McpTool(
            name = "read_goal_get",
            title = "阅读目标",
            description = "读取阅读目标配置（userName/avatar/dailyGoalMinutes）——即阅读记录页顶部的打卡目标。",
            domain = MCP_DOMAIN_STATS,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            ReadStatsKernel.goalGet()
        },

        McpTool(
            name = "read_goal_save",
            title = "保存阅读目标",
            description = "保存阅读目标（用户名 / 头像 / 每日目标分钟）。**未传的字段沿用现值**" +
                "（避免改目标时把头像等意外清空）。返回保存后的目标配置。",
            domain = MCP_DOMAIN_STATS,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                optional = mapOf(
                    "userName" to TYPE_STRING,
                    "avatar" to TYPE_STRING,
                    "dailyGoalMinutes" to TYPE_INTEGER,
                ),
                descriptions = mapOf(
                    "userName" to "打卡用户名（省略 = 不改）",
                    "avatar" to "头像地址（省略 = 不改）",
                    "dailyGoalMinutes" to "每日目标分钟数（省略 = 不改）",
                )
            ),
        ) { args ->
            // 用 has(...) 区分「未传」与「显式传值」：未传 = null（沿用现值），传空串 = 清空该字段。
            val userName = if (args.raw().has("userName")) args.str("userName") else null
            val avatar = if (args.raw().has("avatar")) args.str("avatar") else null
            val dailyGoalMinutes = if (args.raw().has("dailyGoalMinutes")) args.int("dailyGoalMinutes") else null
            ReadStatsKernel.goalSave(
                userName = userName,
                avatar = avatar,
                dailyGoalMinutes = dailyGoalMinutes,
            )
        },
    )
}