package io.legado.app.web.mcp.tools

import com.google.gson.JsonElement
import io.legado.app.model.AutoTaskRule
import io.legado.app.service.kernel.AutoTaskKernel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_AUTOTASK
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_ARRAY
import io.legado.app.web.mcp.McpJsonSchema.TYPE_INTEGER
import io.legado.app.web.mcp.McpJsonSchema.TYPE_OBJECT
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpParamException
import io.legado.app.web.mcp.McpTool

/**
 * ⑫ 自动任务域工具声明（web-mcp-productization 二期 · tasks 2.17 / 2.28）。
 *
 * 红线（AD-10）：`invoke` **只调 `service/kernel`**，不内联业务、不 import `api.controller`（REQ-2-305）。
 * 元数据按域单源声明；执行体一律调 Kernel（[AutoTaskKernel]）。
 *
 * 口径说明：规则 CRUD / 手动触发调度 / 目录差异 / 任务日志 / 批量操作；
 * 日志取自规则实体的 `lastLog`（自动任务无独立日志表，本域只读不造）。
 */
object AutoTaskTools {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "auto_task_rule_get",
            title = "自动任务规则列表",
            description = "读取全部自动任务规则。返回 {total, rules}，单条规则字段：" +
                "id/name/enable/cron/comment/nextRunAt/lastRunAt/lastResult/lastError。",
            domain = MCP_DOMAIN_AUTOTASK,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            AutoTaskKernel.rules()
        },

        McpTool(
            name = "auto_task_rule_save",
            title = "保存自动任务规则",
            description = "新增 / 更新一条自动任务规则（id 缺省自动生成；name 必填）。" +
                "入参 rule 可以是规则对象或规则 JSON 串；返回保存后的规则。",
            domain = MCP_DOMAIN_AUTOTASK,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("rule" to TYPE_OBJECT),
                descriptions = mapOf("rule" to "自动任务规则对象（AutoTaskRule 字段子集；name 必填）"),
            ),
        ) { args ->
            val rule = parseRule(args.raw().get("rule"))
                ?: throw McpParamException("参数 rule 格式不对（须为规则对象或 JSON 字符串）")
            AutoTaskKernel.ruleSave(rule)
        },

        McpTool(
            name = "auto_task_rule_delete",
            title = "删除自动任务规则",
            description = "按 id 批量删除自动任务规则。返回 {deleted, ids}。",
            domain = MCP_DOMAIN_AUTOTASK,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("ids" to TYPE_ARRAY),
                descriptions = mapOf("ids" to "目标规则 id 数组（不能为空）"),
            ),
        ) { args ->
            val ids = args.strList("ids")
            if (ids.isEmpty()) throw McpParamException("缺少必填参数：ids")
            AutoTaskKernel.ruleDelete(ids)
        },

        McpTool(
            name = "auto_task_run",
            title = "触发自动任务调度",
            description = "拉起自动任务服务并刷新 cron 排程（可选 ruleId 只关注单条）。" +
                "返回 {scheduled, ruleIds, nextRunAt}；单条任务的即时执行仍由 App 服务驱动。",
            domain = MCP_DOMAIN_AUTOTASK,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                optional = mapOf("ruleId" to TYPE_STRING),
                descriptions = mapOf("ruleId" to "指定规则 id（省略 = 全部启用规则）"),
            ),
        ) { args ->
            AutoTaskKernel.run(args.str("ruleId"))
        },

        McpTool(
            name = "auto_task_toc_diff",
            title = "自动任务目录差异",
            description = "读取某规则最近一次的目录差异结果（由服务写入 lastResult）。" +
                "返回 ruleId/name/lastRunAt/lastResult/lastError。",
            domain = MCP_DOMAIN_AUTOTASK,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.singleRequiredString("ruleId", "自动任务规则 id"),
        ) { args ->
            AutoTaskKernel.tocDiff(args.requireStr("ruleId"))
        },

        McpTool(
            name = "auto_task_log",
            title = "自动任务日志",
            description = "读取某规则的最近一次运行日志（按 maxLength 截断）。" +
                "返回 ruleId/name/cron/nextRunAt/truncated/log/lastError。",
            domain = MCP_DOMAIN_AUTOTASK,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                required = mapOf("ruleId" to TYPE_STRING),
                optional = mapOf("maxLength" to TYPE_INTEGER),
                descriptions = mapOf(
                    "ruleId" to "自动任务规则 id",
                    "maxLength" to "日志最大返回字符数（默认 4000）",
                )
            ),
        ) { args ->
            AutoTaskKernel.log(args.requireStr("ruleId"), args.int("maxLength", 4000))
        },

        McpTool(
            name = "auto_task_batch_op",
            title = "自动任务批量管理",
            description = "批量管理自动任务规则。action 取值：" +
                "enable/disable（批量启停）、set_cron（批量设 cron，需 cron）、" +
                "reorder（按 ids 顺序重排）、export（导出所选规则）。",
            domain = MCP_DOMAIN_AUTOTASK,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("action" to TYPE_STRING),
                optional = mapOf("ids" to TYPE_ARRAY, "cron" to TYPE_STRING),
                descriptions = mapOf(
                    "action" to "enable/disable/set_cron/reorder/export",
                    "ids" to "目标规则 id 数组（省略 = 全部规则）",
                    "cron" to "set_cron 的 cron 表达式",
                )
            ),
        ) { args ->
            AutoTaskKernel.batchOp(
                action = args.requireStr("action"),
                ids = args.strList("ids"),
                cron = args.str("cron"),
            )
        },
    )

    /** 与 REST 门面同口径：`rule` 既接受对象也接受 JSON 字符串。 */
    private fun parseRule(element: JsonElement?): AutoTaskRule? {
        if (element == null || element.isJsonNull) return null
        val json = if (element.isJsonPrimitive) element.asString else GSON.toJson(element)
        return GSON.fromJsonObject<AutoTaskRule>(json).getOrNull()
    }
}