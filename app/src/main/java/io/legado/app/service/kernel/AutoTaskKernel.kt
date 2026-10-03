package io.legado.app.service.kernel

import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRule
import io.legado.app.utils.CronSchedule
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * ⑫ 自动任务域业务内核（web-mcp-productization 二期 · tasks 2.17 / 2.28）。
 *
 * 契约同 [BookKernel]：只返回结构化 Map、全链挂起、零 `runBlocking`、失败抛异常。
 *
 * 数据源：`AutoTask`（object，含「CacheManager → Room」一次性迁移）+ `appDb.autoTaskRuleDao`。
 * 日志口径：自动任务**无独立日志表** —— 结果落在 [AutoTaskRule.lastResult] / `lastError` / `lastLog`
 * 三个字段（由 `AutoTaskService` 写入），本内核**只读不造**，避免与 App 内日志生成逻辑漂移。
 *
 * `runBlocking` 说明：`AutoTask` 的 CRUD 是 `@Synchronized` 同步方法（内部走 Room 同步 DAO），
 * 故统一用 `withContext(IO)` 包住，不阻塞调用协程所在线程。
 */
object AutoTaskKernel {

    private fun AutoTaskRule.nextRunAt(): Long? {
        val expr = cron?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { CronSchedule.parse(expr)?.nextTimeAfter(System.currentTimeMillis()) }.getOrNull()
    }

    private fun AutoTaskRule.toBrief(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "enable" to enable,
        "cron" to cron,
        "comment" to comment,
        "nextRunAt" to nextRunAt(),
        "lastRunAt" to lastRunAt,
        "lastResult" to lastResult,
        "lastError" to lastError,
    )

    /** `auto_task_rule_get`：全部规则（含 cron 下次触发时间）。 */
    suspend fun rules(): Map<String, Any?> = withContext(IO) {
        val list = AutoTask.getRules()
        mapOf("total" to list.size, "rules" to list.map { it.toBrief() })
    }

    /** `auto_task_rule_save`：新增 / 更新一条规则（`id` 缺省由实体 UUID 生成）。 */
    suspend fun ruleSave(rule: AutoTaskRule): AutoTaskRule = withContext(IO) {
        require(rule.name.isNotBlank()) { "规则名不能为空" }
        AutoTask.upsert(rule)
        rule
    }

    /** `auto_task_rule_delete`：按 id 批量删除。 */
    suspend fun ruleDelete(ids: List<String>): Map<String, Any?> = withContext(IO) {
        require(ids.isNotEmpty()) { "ids 不能为空" }
        AutoTask.delete(*ids.toTypedArray())
        mapOf("deleted" to ids.size, "ids" to ids)
    }

    /**
     * `auto_task_run`：手动触发调度。
     *
     * 口径（有意为之）：MCP 侧只负责「**让调度生效**」（拉起服务 + 刷新 cron 排程），
     * 单条任务的即时执行仍由 `AutoTaskService` 驱动 —— 任务本身可能跑数分钟，
     * 直接同步执行会超出 15s 工具超时并被截断成"超时错误"，反而更不可用。
     *
     * 注意：`AutoTask.refreshSchedule` 内部受 `PreferKey.autoTaskService` 开关守卫
     * （开关关闭时不排程）—— 返回结果里的 `scheduled` 会如实反映这一点。
     */
    suspend fun run(ruleId: String? = null): Map<String, Any?> = withContext(IO) {
        AutoTask.start(appCtx)
        AutoTask.refreshSchedule(appCtx)
        val enabled = AutoTask.getRules().filter { it.enable && (ruleId == null || it.id == ruleId) }
        mapOf(
            "scheduled" to enabled.size,
            "ruleIds" to enabled.map { it.id },
            "nextRunAt" to enabled.mapNotNull { it.nextRunAt() }.minOrNull(),
        )
    }

    /** `auto_task_toc_diff`：读取某规则最近一次的目录差异结果（由服务写入 `lastResult`）。 */
    suspend fun tocDiff(ruleId: String): Map<String, Any?> = withContext(IO) {
        val rule = AutoTask.getRules().firstOrNull { it.id == ruleId }
            ?: throw NoSuchElementException("自动任务规则不存在：$ruleId")
        mapOf(
            "ruleId" to rule.id,
            "name" to rule.name,
            "lastRunAt" to rule.lastRunAt,
            "lastResult" to rule.lastResult,
            "lastError" to rule.lastError,
        )
    }

    /** `auto_task_log`：读任务日志（`lastLog`，按 `maxLength` 截断）+ cron 下次触发。 */
    suspend fun log(ruleId: String, maxLength: Int = 4000): Map<String, Any?> = withContext(IO) {
        val rule = AutoTask.getRules().firstOrNull { it.id == ruleId }
            ?: throw NoSuchElementException("自动任务规则不存在：$ruleId")
        val raw = rule.lastLog.orEmpty()
        val limit = if (maxLength > 0) maxLength else raw.length
        mapOf(
            "ruleId" to rule.id,
            "name" to rule.name,
            "cron" to rule.cron,
            "nextRunAt" to rule.nextRunAt(),
            "truncated" to (raw.length > limit),
            "log" to raw.take(limit),
            "lastError" to rule.lastError,
        )
    }

    /**
     * `auto_task_batch_op`：批量操作。
     *
     * action：`enable` / `disable`（批量启停）、`set_cron`（批量设置 cron，需 `cron`）、
     * `reorder`（按传入 `ids` 顺序重排，未列出的规则保持在后）、`export`（导出所选规则）。
     */
    suspend fun batchOp(
        action: String,
        ids: List<String> = emptyList(),
        cron: String? = null,
    ): Map<String, Any?> = withContext(IO) {
        val all = AutoTask.getRules()
        val targets = if (ids.isEmpty()) all else all.filter { it.id in ids }
        when (action) {
            "enable", "disable" -> {
                require(targets.isNotEmpty()) { "未命中任何规则（ids=${ids.size}）" }
                targets.forEach { it.enable = action == "enable" }
                AutoTask.saveRules(all)
                mapOf("action" to action, "affected" to targets.size, "ids" to targets.map { it.id })
            }

            "set_cron" -> {
                val expr = cron?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("set_cron 需要 cron 参数")
                require(CronSchedule.parse(expr) != null) { "cron 表达式非法：$expr" }
                require(targets.isNotEmpty()) { "未命中任何规则（ids=${ids.size}）" }
                targets.forEach { it.cron = expr }
                AutoTask.saveRules(all)
                mapOf("action" to action, "affected" to targets.size, "cron" to expr)
            }

            "reorder" -> {
                require(ids.isNotEmpty()) { "reorder 需要 ids 顺序" }
                val ordered = ids.mapNotNull { id -> all.firstOrNull { it.id == id } }
                val rest = all.filter { it.id !in ids }
                AutoTask.saveRules(ordered + rest)
                mapOf("action" to action, "affected" to ordered.size)
            }

            "export" -> mapOf("action" to action, "count" to targets.size, "rules" to targets)

            else -> throw IllegalArgumentException(
                "未知 action：$action（支持 enable/disable/set_cron/reorder/export）"
            )
        }
    }
}