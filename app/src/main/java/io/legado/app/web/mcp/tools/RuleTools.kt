package io.legado.app.web.mcp.tools

import com.google.gson.JsonElement
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.service.kernel.ReplaceRuleKernel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_RULE
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpParamException
import io.legado.app.web.mcp.McpTool

/**
 * ⑧⑨ 规则域工具声明（web-mcp-productization 二期 · tasks 2.14）。
 *
 * **本文件按 tasks 2.14 增量补齐**：当前落地"执行体只需一期 `ReplaceRuleKernel`"的工具；
 * 其余（草稿三件套 / 高亮规则 / TXT 目录规则 / 段落规则 / 字典规则 / 书内段落规则 / 查词 /
 * 导入导出 / 规则分组 / 规则补全 / 订阅规则）随 §2.28 与 §6.7 在后续批次追加。
 */
object RuleTools {

    val tools: List<McpTool> = listOf(
        McpTool(
            name = "replace_rule_get",
            title = "读取替换规则",
            description = "读取全部替换规则（含 name/pattern/replacement/isRegex/enabled/order 等字段）。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            ReplaceRuleKernel.allRules()
        },
        McpTool(
            name = "test_replace_rule",
            title = "替换规则测试",
            description = "用给定替换规则试算一段文本，返回替换后的结果（不落库）。" +
                "入参 rule 可以是规则对象或规则 JSON 字符串；执行出错时返回错误堆栈文本。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("rule" to McpJsonSchema.TYPE_OBJECT, "text" to TYPE_STRING),
                descriptions = mapOf(
                    "rule" to "替换规则对象（ReplaceRule 字段子集即可：name/pattern/replacement/isRegex）",
                    "text" to "待试算的原始文本"
                )
            ),
        ) { args ->
            val rule = parseRule(args.raw().get("rule"))
                ?: throw McpParamException("参数 rule 格式不对（须为替换规则对象或 JSON 字符串）")
            if (rule.pattern.isEmpty()) throw McpParamException("替换规则不能为空")
            ReplaceRuleKernel.testRule(rule, args.requireStr("text"))
        },
    )

    /** 与 REST 门面同口径：`rule` 既接受对象也接受 JSON 字符串（老页面既有传法）。 */
    private fun parseRule(element: JsonElement?): ReplaceRule? {
        if (element == null || element.isJsonNull) return null
        val json = if (element.isJsonPrimitive) element.asString else GSON.toJson(element)
        return GSON.fromJsonObject<ReplaceRule>(json).getOrNull()
    }
}
