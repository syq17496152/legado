package io.legado.app.service.kernel

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookParagraphRule
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.ParagraphRule
import io.legado.app.data.entities.RuleSub
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.RuleComplete
import io.legado.app.help.book.ParagraphRuleProcessor
import io.legado.app.ui.book.read.config.HighlightRule
import io.legado.app.ui.book.read.config.HighlightRuleGroupStore
import io.legado.app.ui.book.read.config.HighlightRuleStore
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * ⑧⑨ 规则域（替换/高亮/TXT目录/段落/字典/规则分组/补全/订阅规则）业务内核
 * （web-mcp-productization 二期 · tasks 2.14 / 2.28）。
 *
 * 契约同 [BookKernel]：只返回领域对象 / 全链挂起 / 零 `runBlocking` / 失败抛异常。
 *
 * 说明：替**换规则本身**的增删改查在 [ReplaceRuleKernel]（一期产物，含草稿三件套），本类负责
 * 其余规则族与规则分组/补全 —— 避免把 24 个规则工具全塞进一个 object。
 */
object RuleKernel {

    // ============================================================ 高亮规则（存储为 SharedPreferences JSON）

    /** 高亮规则列表（[HighlightRuleStore] 负责默认规则补齐与归一化）。 */
    suspend fun highlightRules(): List<HighlightRule> = withContext(IO) { HighlightRuleStore.load(appCtx) }

    /** 新增 / 更新高亮规则（按 `id` 命中则替换，否则追加）。 */
    suspend fun saveHighlightRule(rule: HighlightRule): Map<String, Any?> = withContext(IO) {
        val rules = HighlightRuleStore.load(appCtx)
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index >= 0) rules[index] = rule else rules.add(rule)
        HighlightRuleStore.save(appCtx, rules)
        HighlightRuleGroupStore.ensureFromRules(appCtx, rules)
        mapOf("updated" to (index >= 0), "count" to rules.size, "id" to rule.id)
    }

    /** 删除高亮规则（按 id 列表）。 */
    suspend fun deleteHighlightRules(ids: List<String>): Int = withContext(IO) {
        val rules = HighlightRuleStore.load(appCtx)
        val before = rules.size
        rules.removeAll { it.id in ids }
        HighlightRuleStore.save(appCtx, rules)
        before - rules.size
    }

    // ============================================================ TXT 目录规则

    suspend fun txtTocRules(): List<TxtTocRule> = withContext(IO) { appDb.txtTocRuleDao.all }

    suspend fun saveTxtTocRule(rule: TxtTocRule): TxtTocRule = withContext(IO) {
        val exist = appDb.txtTocRuleDao.get(rule.id)
        if (exist == null) {
            appDb.txtTocRuleDao.insert(rule)
        } else {
            appDb.txtTocRuleDao.update(rule)
        }
        rule
    }

    suspend fun deleteTxtTocRules(ids: List<Long>): Int = withContext(IO) {
        var count = 0
        ids.forEach { id ->
            appDb.txtTocRuleDao.get(id)?.let {
                appDb.txtTocRuleDao.delete(it)
                count++
            }
        }
        count
    }

    // ============================================================ 段落规则

    suspend fun paragraphRules(): List<ParagraphRule> = withContext(IO) { appDb.paragraphRuleDao.all() }

    suspend fun saveParagraphRule(rule: ParagraphRule): ParagraphRule = withContext(IO) {
        if (appDb.paragraphRuleDao.get(rule.id) == null) {
            appDb.paragraphRuleDao.insert(rule)
        } else {
            appDb.paragraphRuleDao.update(rule)
        }
        rule
    }

    suspend fun deleteParagraphRule(ruleId: Long): Boolean = withContext(IO) {
        val rule = appDb.paragraphRuleDao.get(ruleId) ?: return@withContext false
        appDb.paragraphRuleDao.deleteWithRelations(rule)
        true
    }

    /** 书内段落规则绑定（`enabled` 由关联表 [BookParagraphRule] 承载）。 */
    suspend fun bookParagraphRules(bookUrl: String): Map<String, Any?> = withContext(IO) {
        mapOf(
            "bookUrl" to bookUrl,
            "rules" to appDb.paragraphRuleDao.bookRules(bookUrl),
            "allRules" to appDb.paragraphRuleDao.all(),
        )
    }

    /** 书内段落规则保存（先删后插，语义为"设置该书启用的段落规则集合"）。 */
    suspend fun saveBookParagraphRules(bookUrl: String, ruleIds: List<Long>, enabled: Boolean): Map<String, Any?> =
        withContext(IO) {
            if (ruleIds.isEmpty()) throw IllegalArgumentException("ruleIds 不能为空")
            appDb.paragraphRuleDao.bookRules(bookUrl).forEach {
                appDb.paragraphRuleDao.deleteBookRule(bookUrl, it.ruleId)
            }
            ruleIds.forEachIndexed { index, ruleId ->
                if (appDb.paragraphRuleDao.get(ruleId) == null) return@forEachIndexed
                appDb.paragraphRuleDao.insertBookRule(
                    BookParagraphRule(bookUrl = bookUrl, ruleId = ruleId, enabled = enabled, order = index)
                )
            }
            mapOf("bookUrl" to bookUrl, "bound" to appDb.paragraphRuleDao.enabledRuleIdsForBook(bookUrl).size)
        }

    /**
     * 段落规则调试：读取指定章节正文后按该书启用的段落规则跑一遍，返回处理前后文本。
     *
     * 复用 [ParagraphRuleProcessor.process] 的**字符串重载**（与阅读页处理同一份实现）。
     */
    suspend fun testParagraphRule(bookUrl: String, chapterIndex: Int, maxLength: Int): Map<String, Any?> {
        val book = withContext(IO) { appDb.bookDao.getBook(bookUrl) }
            ?: throw NoSuchElementException("书籍不存在：$bookUrl")
        val chapter = withContext(IO) { appDb.bookChapterDao.getChapter(bookUrl, chapterIndex) }
            ?: throw NoSuchElementException("章节不存在：$chapterIndex")
        val before = BookKernel.bookContent(bookUrl, chapterIndex)
            ?: throw NoSuchElementException("章节正文不存在：$chapterIndex")
        val after = ParagraphRuleProcessor.process(book, chapter, before)
        val limit = if (maxLength <= 0) Int.MAX_VALUE else maxLength
        return mapOf(
            "bookUrl" to bookUrl,
            "chapterIndex" to chapterIndex,
            "ruleCount" to appDb.paragraphRuleDao.enabledRuleIdsForBook(bookUrl).size,
            "changed" to (before != after),
            "before" to before.take(limit),
            "after" to after.take(limit),
        )
    }

    // ============================================================ 字典规则 / 查词

    suspend fun dictRules(): List<DictRule> = withContext(IO) { appDb.dictRuleDao.all }

    suspend fun saveDictRule(rule: DictRule): DictRule = withContext(IO) {
        if (appDb.dictRuleDao.getByName(rule.name) == null) {
            appDb.dictRuleDao.insert(rule)
        } else {
            appDb.dictRuleDao.update(rule)
        }
        rule
    }

    /** 书中查词（复用 [DictRule.search] 的既有实现，返回字典页面文本）。 */
    suspend fun dictLookup(name: String, word: String): Map<String, Any?> {
        if (word.isBlank()) throw IllegalArgumentException("查词内容不能为空")
        val rule = withContext(IO) { appDb.dictRuleDao.getByName(name) }
            ?: throw NoSuchElementException("字典规则不存在：$name")
        return mapOf("dict" to name, "word" to word, "result" to rule.search(word))
    }

    // ============================================================ 规则分组 / 导入导出 / 补全

    /** 规则分组（替换规则分组 + 高亮规则分组）。 */
    suspend fun ruleGroups(): Map<String, Any?> = withContext(IO) {
        mapOf(
            "replaceGroups" to appDb.replaceRuleDao.allGroups(),
            "highlightGroups" to HighlightRuleGroupStore.load(appCtx),
        )
    }

    /**
     * 规则分组重命名（`scope = replace|highlight`）。
     *
     * 替换规则分组同样是**规则行上的字符串字段**（`ReplaceRule.group`），故重命名 = 遍历该组规则改字段。
     */
    suspend fun renameRuleGroup(scope: String, oldName: String, newName: String?): Int = withContext(IO) {
        when (scope) {
            "replace" -> {
                val rules = appDb.replaceRuleDao.getByGroup(oldName)
                rules.forEach { it.group = newName?.takeIf { name -> name.isNotBlank() } }
                if (rules.isNotEmpty()) appDb.replaceRuleDao.update(*rules.toTypedArray())
                rules.size
            }

            "highlight" -> {
                val groups = HighlightRuleGroupStore.load(appCtx)
                val rules = HighlightRuleStore.load(appCtx)
                rules.filter { it.group == oldName }.forEach { it.group = newName.orEmpty() }
                HighlightRuleStore.save(appCtx, rules)
                val index = groups.indexOf(oldName)
                if (index >= 0) {
                    if (newName.isNullOrBlank()) groups.removeAt(index) else groups[index] = newName
                    HighlightRuleGroupStore.save(appCtx, groups)
                }
                rules.count { it.group == newName }
            }

            else -> throw IllegalArgumentException("scope 仅支持 replace/highlight")
        }
    }

    /**
     * 规则导入（`type = replace|txt_toc|dict`，JSON 数组）。
     *
     * 只做"解析 + 落库"，不做批量勾选（Web/MCP 无 UI）；解析复用既有 GSON 数组读法。
     */
    suspend fun importRules(type: String, json: String): Map<String, Any?> {
        var count = 0
        withContext(IO) {
            when (type) {
                "replace" -> {
                    val rules = GSON.fromJsonArray<io.legado.app.data.entities.ReplaceRule>(json).getOrThrow()
                    rules.forEach { ReplaceRuleKernel.saveRule(it) }
                    count = rules.size
                }

                "txt_toc" -> {
                    val rules = GSON.fromJsonArray<TxtTocRule>(json).getOrThrow()
                    rules.forEach { appDb.txtTocRuleDao.insertIfAbsent(it) }
                    count = rules.size
                }

                "dict" -> {
                    val rules = GSON.fromJsonArray<DictRule>(json).getOrThrow()
                    rules.forEach { appDb.dictRuleDao.insert(it) }
                    count = rules.size
                }

                else -> throw IllegalArgumentException("type 仅支持 replace/txt_toc/dict")
            }
        }
        return mapOf("type" to type, "imported" to count)
    }

    /** 规则导出（JSON 串）。 */
    suspend fun exportRules(type: String): Map<String, Any?> {
        val json = withContext(IO) {
            when (type) {
                "replace" -> GSON.toJson(appDb.replaceRuleDao.all)
                "txt_toc" -> GSON.toJson(appDb.txtTocRuleDao.all)
                "dict" -> GSON.toJson(appDb.dictRuleDao.all)
                "highlight" -> GSON.toJson(HighlightRuleStore.load(appCtx))
                else -> throw IllegalArgumentException("type 仅支持 replace/txt_toc/dict/highlight")
            }
        }
        return mapOf("type" to type, "content" to json)
    }

    /** 规则补全提示（复用 [RuleComplete.autoComplete]，与书源编辑页补全同一实现）。 */
    suspend fun completions(rules: String?, preRule: String?, type: Int): Map<String, Any?> = mapOf(
        "input" to rules,
        "result" to RuleComplete.autoComplete(rules, preRule, type),
    )

    // ============================================================ 订阅规则（rule_sub_*）

    suspend fun ruleSubs(): List<RuleSub> = withContext(IO) { appDb.ruleSubDao.all }

    suspend fun saveRuleSub(sub: RuleSub): RuleSub = withContext(IO) {
        // `RuleSub.id` 是 val（构造期生成），故以 `url` 作业务主键判存在性（DAO 亦提供 findByUrl）
        if (appDb.ruleSubDao.findByUrl(sub.url) == null) {
            appDb.ruleSubDao.insert(sub)
        } else {
            appDb.ruleSubDao.update(sub)
        }
        sub
    }

    suspend fun deleteRuleSubs(ids: List<Long>): Int = withContext(IO) {
        val targets = appDb.ruleSubDao.all.filter { it.id in ids }
        targets.forEach { appDb.ruleSubDao.delete(it) }
        targets.size
    }
}
