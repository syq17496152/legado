package io.legado.app.service.kernel

import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.utils.replace
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

/**
 * 替换规则域业务内核（web-mcp-productization 一期 · 2.3.2 / REQ-1-201）。
 *
 * 契约同 [BookKernel]：只返回领域对象 / 全链挂起 / 零 `runBlocking`。
 * [testRule] 为**纯 CPU 逻辑**（无 IO），二期 MCP 可直接复用同一份试算实现。
 */
object ReplaceRuleKernel {

    /** 未指定顺序的哨兵值（源自 App 内既有约定：`Int.MIN_VALUE` = 追加到末尾）。 */
    private const val ORDER_UNSET = Int.MIN_VALUE

    suspend fun allRules(): List<ReplaceRule> = withContext(IO) { appDb.replaceRuleDao.all }

    /** 保存规则；`order` 为哨兵值时追加到末尾（`maxOrder + 1`，与原 Controller 一致）。 */
    suspend fun saveRule(rule: ReplaceRule) {
        if (rule.order == ORDER_UNSET) {
            rule.order = withContext(IO) { appDb.replaceRuleDao.maxOrder } + 1
        }
        withContext(IO) { appDb.replaceRuleDao.insert(rule) }
    }

    suspend fun deleteRule(rule: ReplaceRule) {
        withContext(IO) { appDb.replaceRuleDao.delete(rule) }
    }

    /**
     * 试算替换效果（原 `testRule` 的规则执行部分原样搬出）。
     *
     * 失败时返回**堆栈字符串**（沿用原行为：把失败原因直接回给网页调试面板）。
     */
    fun testRule(rule: ReplaceRule, text: String): String = try {
        if (rule.isRegex) {
            text.replace(
                rule.name,
                rule.pattern.toRegex(),
                rule.replacement,
                rule.getValidTimeoutMillisecond()
            )
        } else {
            text.replace(rule.pattern, rule.replacement)
        }
    } catch (e: Exception) {
        e.stackTraceStr
    }

    /**
     * 批量顺序应用替换规则（二期 tasks 2.14 `book_purify` / 规则链复用）。
     *
     * 与 [testRule] **同一份执行口径**（正则走 `utils.replace` 带超时，非正则走字面替换），
     * 差别仅在"逐条作用于同一段文本并累积结果"。失败时**保留上一步结果并跳过该条**，
     * 避免一条坏规则让整段净化失败（净化是"尽力而为"的清洗，不是校验）。
     */
    fun applyRules(rules: List<ReplaceRule>, text: String): String {
        var result = text
        rules.forEach { rule ->
            result = kotlin.runCatching {
                if (rule.isRegex) {
                    result.replace(
                        rule.name,
                        rule.pattern.toRegex(),
                        rule.replacement,
                        rule.getValidTimeoutMillisecond()
                    )
                } else {
                    result.replace(rule.pattern, rule.replacement)
                }
            }.getOrDefault(result)
        }
        return result
    }

    // ============================================================ 草稿三件套（二期 tasks 2.14 / §6.7）

    /**
     * 草稿区（AI 三件套：`draft_upsert` → `draft_apply` → `draft_rollback`）。
     *
     * **为什么需要**：MCP 场景里 AI 会反复改规则并复测，若每次都直接落库，"改坏 → 还原"无路径。
     * 草稿区把"候选改动"与"已生效规则"分开，`apply` 时先存**原规则快照**，`rollback` 可逐条还原
     * （SC-2-15：回滚后原规则完全不变）。
     *
     * **已知上限（刻意的）**：草稿与快照**只存内存**（进程存活期内有效），不做持久化 ——
     * 落盘会引入"草稿与库不一致"的新故障面，而 AI 的一次修源会话天然在同一进程内完成。
     * 升级路径：需要跨进程续用时可改为 `CacheManager` 落盘 + 版本号。
     */
    private val drafts = java.util.concurrent.ConcurrentHashMap<Long, ReplaceRule>()

    /** 应用前快照：`ruleId -> 原规则`（`null` 值代表"原规则不存在"⇒ 回滚时删除）。 */
    private val snapshots = java.util.concurrent.ConcurrentHashMap<Long, ReplaceRule?>()

    /** 写入 / 覆盖一条草稿（不入库）。 */
    fun draftUpsert(rule: ReplaceRule): Map<String, Any?> {
        val isNew = !drafts.containsKey(rule.id)
        drafts[rule.id] = rule
        return mapOf(
            "draftId" to rule.id,
            "new" to isNew,
            "draftCount" to drafts.size,
            "ruleName" to rule.name,
        )
    }

    /** 查看当前草稿（供 AI 确认改动内容）。 */
    fun draftList(): List<ReplaceRule> = drafts.values.sortedBy { it.id }

    /** 丢弃草稿（不影响已生效规则）。 */
    fun draftDiscard(): Int {
        val size = drafts.size
        drafts.clear()
        return size
    }

    /**
     * 应用全部草稿：先把"每条草稿对应的当前规则"存入快照，再落库。
     *
     * 幂等性：重复 `apply` 会用**本次之前的库内状态**覆盖快照 —— 即"回滚到最近一次 apply 之前"。
     */
    suspend fun draftApply(): Map<String, Any?> {
        if (drafts.isEmpty()) return mapOf("applied" to 0, "reason" to "草稿区为空")
        var applied = 0
        drafts.values.forEach { draft ->
            snapshots[draft.id] = withContext(IO) { appDb.replaceRuleDao.findById(draft.id) }
            saveRule(draft)
            applied++
        }
        drafts.clear()
        return mapOf("applied" to applied, "snapshotCount" to snapshots.size)
    }

    /**
     * 回滚到最近一次 `apply` 之前：原规则被还原，`apply` 时新增的规则被删除。
     *
     * 返回 `restored`（还原条数）与 `deleted`（删除条数）。
     */
    suspend fun draftRollback(): Map<String, Any?> {
        if (snapshots.isEmpty()) return mapOf("restored" to 0, "deleted" to 0, "reason" to "无快照可回滚")
        var restored = 0
        var deleted = 0
        snapshots.forEach { (id, original) ->
            withContext(IO) {
                if (original == null) {
                    appDb.replaceRuleDao.findById(id)?.let {
                        appDb.replaceRuleDao.delete(it)
                        deleted++
                    }
                } else {
                    appDb.replaceRuleDao.insert(original)
                    restored++
                }
            }
        }
        snapshots.clear()
        drafts.clear()
        return mapOf("restored" to restored, "deleted" to deleted)
    }
}
