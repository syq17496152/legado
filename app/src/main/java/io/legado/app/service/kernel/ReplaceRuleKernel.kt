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
}
