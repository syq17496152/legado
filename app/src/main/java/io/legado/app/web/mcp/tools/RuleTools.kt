package io.legado.app.web.mcp.tools

import com.google.gson.JsonElement
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.ParagraphRule
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RuleSub
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.service.kernel.ReplaceRuleKernel
import io.legado.app.service.kernel.RuleKernel
import io.legado.app.ui.book.read.config.HighlightRule
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_RULE
import io.legado.app.web.mcp.McpJsonSchema
import io.legado.app.web.mcp.McpJsonSchema.TYPE_BOOLEAN
import io.legado.app.web.mcp.McpJsonSchema.TYPE_INTEGER
import io.legado.app.web.mcp.McpJsonSchema.TYPE_STRING
import io.legado.app.web.mcp.McpParamException
import io.legado.app.web.mcp.McpTool

/**
 * ⑧⑨ 规则域工具声明（web-mcp-productization 二期 · tasks 2.14）。
 *
 * 红线（AD-10）：`invoke` 只调 [ReplaceRuleKernel] / [RuleKernel]。
 *
 * **草稿三件套**（`replace_rule_draft_upsert` → `apply` → `rollback`，spec §6.7 / SC-2-15）：
 * AI 改规则的推荐路径 —— 先写草稿（不落库）→ 应用（应用前自动存快照）→ 不满意可一键回滚。
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
            name = "replace_rule_save",
            title = "保存替换规则",
            description = "保存替换规则（入参 rule 可为对象或 JSON 串；order 省略时追加到末尾）。返回保存后的规则。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("rule" to McpJsonSchema.TYPE_OBJECT),
                descriptions = mapOf("rule" to "替换规则对象或 JSON 串"),
            ),
        ) { args ->
            val rule = parseRule(args.raw().get("rule"))
                ?: throw McpParamException("参数 rule 格式不对")
            if (rule.pattern.isEmpty()) throw McpParamException("替换规则 pattern 不能为空")
            ReplaceRuleKernel.saveRule(rule)
            rule
        },

        McpTool(
            name = "replace_rule_delete",
            title = "删除替换规则",
            description = "按 id 删除替换规则。返回删除条数。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("ids" to McpJsonSchema.TYPE_ARRAY),
                descriptions = mapOf("ids" to "规则 id 数组"),
            ),
        ) { args ->
            val ids = args.strList("ids").mapNotNull { it.toLongOrNull() }.toSet()
            val rules = ReplaceRuleKernel.allRules().filter { it.id in ids }
            rules.forEach { ReplaceRuleKernel.deleteRule(it) }
            mapOf("deleted" to rules.size)
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
                    "rule" to "替换规则对象（name/pattern/replacement/isRegex 子集即可）",
                    "text" to "待试算的原始文本"
                )
            ),
        ) { args ->
            val rule = parseRule(args.raw().get("rule"))
                ?: throw McpParamException("参数 rule 格式不对（须为替换规则对象或 JSON 字符串）")
            if (rule.pattern.isEmpty()) throw McpParamException("替换规则不能为空")
            ReplaceRuleKernel.testRule(rule, args.requireStr("text"))
        },

        McpTool(
            name = "replace_rule_draft_upsert",
            title = "写入规则草稿",
            description = "把修改后的替换规则写入**草稿区**（不落库，可反复覆盖）。草稿区在 apply 前对规则库零影响。" +
                "返回草稿信息。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("rule" to McpJsonSchema.TYPE_OBJECT),
                descriptions = mapOf("rule" to "替换规则对象或 JSON 串（id 决定覆盖哪条）"),
            ),
        ) { args ->
            val rule = parseRule(args.raw().get("rule"))
                ?: throw McpParamException("参数 rule 格式不对")
            ReplaceRuleKernel.draftUpsert(rule)
        },

        McpTool(
            name = "replace_rule_draft_apply",
            title = "应用规则草稿",
            description = "把草稿区全部改动落库；**应用前自动保存原规则快照**，因此可随时 rollback 还原。" +
                "返回应用条数与快照数。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            ReplaceRuleKernel.draftApply()
        },

        McpTool(
            name = "replace_rule_draft_rollback",
            title = "回滚规则草稿",
            description = "回滚到最近一次 apply 之前：原规则被还原，apply 时新增的规则被删除。" +
                "返回 {restored,deleted}。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            ReplaceRuleKernel.draftRollback()
        },

        McpTool(
            name = "highlight_rules_get",
            title = "读取高亮规则",
            description = "读取高亮规则列表（id/name/pattern/sampleText/group/targetScope/enabled/颜色等）。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            RuleKernel.highlightRules()
        },

        McpTool(
            name = "highlight_rule_save",
            title = "保存高亮规则",
            description = "新增 / 更新高亮规则（按 id 命中则替换，否则追加）。返回保存结果。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("rule" to McpJsonSchema.TYPE_OBJECT),
                descriptions = mapOf("rule" to "高亮规则对象或 JSON 串"),
            ),
        ) { args ->
            val rule = parseJson<HighlightRule>(args.raw().get("rule"))
                ?: throw McpParamException("参数 rule 格式不对")
            RuleKernel.saveHighlightRule(rule)
        },

        McpTool(
            name = "highlight_rule_delete",
            title = "删除高亮规则",
            description = "按 id 批量删除高亮规则。返回删除条数。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("ids" to McpJsonSchema.TYPE_ARRAY),
                descriptions = mapOf("ids" to "高亮规则 id 数组（字符串）"),
            ),
        ) { args ->
            mapOf("deleted" to RuleKernel.deleteHighlightRules(args.strList("ids")))
        },

        McpTool(
            name = "txt_toc_rule_get",
            title = "读取 TXT 目录规则",
            description = "读取 TXT 目录规则列表（id/name/rule/replacement/example/enable/序号）。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            RuleKernel.txtTocRules()
        },

        McpTool(
            name = "txt_toc_rule_save",
            title = "保存 TXT 目录规则",
            description = "新增 / 保存 TXT 目录规则。返回保存后的规则。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("rule" to McpJsonSchema.TYPE_OBJECT),
                descriptions = mapOf("rule" to "TXT 目录规则对象或 JSON 串"),
            ),
        ) { args ->
            val rule = parseJson<TxtTocRule>(args.raw().get("rule"))
                ?: throw McpParamException("参数 rule 格式不对")
            RuleKernel.saveTxtTocRule(rule)
        },

        McpTool(
            name = "txt_toc_rule_delete",
            title = "删除 TXT 目录规则",
            description = "按 id 批量删除 TXT 目录规则。返回删除条数。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("ids" to McpJsonSchema.TYPE_ARRAY),
                descriptions = mapOf("ids" to "规则 id 数组（整数）"),
            ),
        ) { args ->
            mapOf("deleted" to RuleKernel.deleteTxtTocRules(args.strList("ids").mapNotNull { it.toLongOrNull() }))
        },

        McpTool(
            name = "paragraph_rule_get",
            title = "读取段落规则",
            description = "读取段落规则列表（id/name/jsLib/script/order 等）。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            RuleKernel.paragraphRules()
        },

        McpTool(
            name = "paragraph_rule_save",
            title = "保存段落规则",
            description = "新增 / 保存段落规则。返回保存后的规则。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("rule" to McpJsonSchema.TYPE_OBJECT),
                descriptions = mapOf("rule" to "段落规则对象或 JSON 串"),
            ),
        ) { args ->
            val rule = parseJson<ParagraphRule>(args.raw().get("rule"))
                ?: throw McpParamException("参数 rule 格式不对")
            RuleKernel.saveParagraphRule(rule)
        },

        McpTool(
            name = "test_paragraph_rule",
            title = "段落规则调试",
            description = "段落规则调试：读取指定章节正文后按其启用规则跑一遍，返回 before/after 与是否发生变化。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("bookUrl" to TYPE_STRING, "chapterIndex" to TYPE_INTEGER),
                optional = mapOf("maxLength" to TYPE_INTEGER),
                descriptions = mapOf(
                    "bookUrl" to "书籍唯一地址",
                    "chapterIndex" to "章节序号",
                    "maxLength" to "返回文本的最大长度（默认 2000，0 = 不限）",
                )
            ),
        ) { args ->
            RuleKernel.testParagraphRule(
                bookUrl = args.requireStr("bookUrl"),
                chapterIndex = args.requireInt("chapterIndex"),
                maxLength = args.int("maxLength", 2000),
            )
        },

        McpTool(
            name = "dict_rule_get",
            title = "读取字典规则",
            description = "读取字典规则列表（name/urlRule/showRule/enabled/sortNumber）。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            RuleKernel.dictRules()
        },

        McpTool(
            name = "dict_rule_save",
            title = "保存字典规则",
            description = "新增 / 保存字典规则（name 为业务主键）。返回保存后的规则。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("rule" to McpJsonSchema.TYPE_OBJECT),
                descriptions = mapOf("rule" to "字典规则对象或 JSON 串（name 必填）"),
            ),
        ) { args ->
            val rule = parseJson<DictRule>(args.raw().get("rule"))
                ?: throw McpParamException("参数 rule 格式不对")
            if (rule.name.isBlank()) throw McpParamException("字典规则 name 不能为空")
            RuleKernel.saveDictRule(rule)
        },

        McpTool(
            name = "book_paragraph_rule_get",
            title = "读取书内段落规则",
            description = "读取某本书的段落规则绑定（bookRules）+ 全部可选段落规则（allRules）。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.singleRequiredString("bookUrl", "书籍唯一地址"),
        ) { args ->
            RuleKernel.bookParagraphRules(args.requireStr("bookUrl"))
        },

        McpTool(
            name = "book_paragraph_rule_save",
            title = "保存书内段落规则",
            description = "设置某本书启用的段落规则集合（先清空原有绑定再写入）。返回绑定后的启用规则数。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("bookUrl" to TYPE_STRING, "ruleIds" to McpJsonSchema.TYPE_ARRAY),
                optional = mapOf("enabled" to TYPE_BOOLEAN),
                descriptions = mapOf(
                    "bookUrl" to "书籍唯一地址",
                    "ruleIds" to "段落规则 id 数组",
                    "enabled" to "是否启用（默认 true）",
                )
            ),
        ) { args ->
            RuleKernel.saveBookParagraphRules(
                bookUrl = args.requireStr("bookUrl"),
                ruleIds = args.strList("ruleIds").mapNotNull { it.toLongOrNull() },
                enabled = args.bool("enabled", true),
            )
        },

        McpTool(
            name = "dict_lookup",
            title = "书中查词",
            description = "用指定字典规则查词，返回字典页面文本（释义/候选词）。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                required = mapOf("name" to TYPE_STRING, "word" to TYPE_STRING),
                descriptions = mapOf("name" to "字典规则名（dict_rule_get 返回的 name）", "word" to "要查的词"),
            ),
        ) { args ->
            RuleKernel.dictLookup(args.requireStr("name"), args.requireStr("word"))
        },

        McpTool(
            name = "rule_import",
            title = "导入规则",
            description = "导入规则（type=replace/txt_toc/dict，JSON 数组）。返回导入条数。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("type" to TYPE_STRING, "json" to TYPE_STRING),
                descriptions = mapOf("type" to "replace / txt_toc / dict", "json" to "规则 JSON 数组"),
            ),
        ) { args ->
            RuleKernel.importRules(args.requireStr("type"), args.requireStr("json"))
        },

        McpTool(
            name = "rule_export",
            title = "导出规则",
            description = "导出规则 JSON 串（type=replace/txt_toc/dict/highlight）。返回 {type,content}。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                required = mapOf("type" to TYPE_STRING),
                descriptions = mapOf("type" to "replace / txt_toc / dict / highlight"),
            ),
        ) { args ->
            RuleKernel.exportRules(args.requireStr("type"))
        },

        McpTool(
            name = "rule_groups_get",
            title = "读取规则分组",
            description = "读取规则分组：replaceGroups（替换规则分组）+ highlightGroups（高亮规则分组）。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            RuleKernel.ruleGroups()
        },

        McpTool(
            name = "rule_group_save",
            title = "保存规则分组",
            description = "规则分组重命名（scope=replace/highlight；newName 空串 = 删除分组标记）。返回受影响条数。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("scope" to TYPE_STRING, "oldName" to TYPE_STRING),
                optional = mapOf("newName" to TYPE_STRING),
                descriptions = mapOf(
                    "scope" to "replace / highlight",
                    "oldName" to "原分组名",
                    "newName" to "新分组名（空串 = 删除）",
                )
            ),
        ) { args ->
            mapOf(
                "affected" to RuleKernel.renameRuleGroup(
                    scope = args.requireStr("scope"),
                    oldName = args.requireStr("oldName"),
                    newName = args.str("newName"),
                )
            )
        },

        McpTool(
            name = "rule_completions_get",
            title = "规则补全提示",
            description = "规则补全：给定规则串（可选前置规则与本类型）返回补全后的规则串（与书源编辑页补全同一实现）。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.of(
                required = mapOf("rules" to TYPE_STRING),
                optional = mapOf("preRule" to TYPE_STRING, "type" to TYPE_INTEGER),
                descriptions = mapOf(
                    "rules" to "待补全的规则串",
                    "preRule" to "前置规则（可选）",
                    "type" to "1 文本 / 2 链接 / 3 图片（默认 1）",
                )
            ),
        ) { args ->
            RuleKernel.completions(args.requireStr("rules"), args.str("preRule"), args.int("type", 1))
        },

        McpTool(
            name = "rule_sub_get",
            title = "读取订阅规则",
            description = "读取订阅规则（规则订阅源：id/name/url/type/autoUpdate/updateInterval/js/showRule/sourceUrl）。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.READONLY,
            readOnlyHint = true,
            inputSchema = McpJsonSchema.empty(),
        ) { _ ->
            RuleKernel.ruleSubs()
        },

        McpTool(
            name = "rule_sub_save",
            title = "保存订阅规则",
            description = "新增 / 保存订阅规则（url 为业务主键）。返回保存后的规则。",
            domain = MCP_DOMAIN_RULE,
            level = TokenManager.Level.MANAGE,
            inputSchema = McpJsonSchema.of(
                required = mapOf("sub" to McpJsonSchema.TYPE_OBJECT),
                descriptions = mapOf("sub" to "订阅规则对象或 JSON 串（url 必填）"),
            ),
        ) { args ->
            val sub = parseJson<RuleSub>(args.raw().get("sub"))
                ?: throw McpParamException("参数 sub 格式不对")
            if (sub.url.isBlank()) throw McpParamException("订阅规则 url 不能为空")
            RuleKernel.saveRuleSub(sub)
        },
    )

    /** 与 REST 门面同口径：对象或 JSON 字符串均可。 */
    private fun parseRule(element: JsonElement?): ReplaceRule? = parseJson(element)

    private inline fun <reified T> parseJson(element: JsonElement?): T? {
        if (element == null || element.isJsonNull) return null
        val json = if (element.isJsonPrimitive) element.asString else GSON.toJson(element)
        return GSON.fromJsonObject<T>(json).getOrNull()
    }
}
