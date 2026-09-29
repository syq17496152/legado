package io.legado.app.web.mcp

import android.content.Intent
import io.legado.app.constant.AppLog
import io.legado.app.ui.mcp.McpConfirmActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import splitties.init.appCtx

/**
 * MCP 端侧确认闸门（Web/MCP 产品化二期 · REQ-2-501）。
 *
 * 外部 AI 客户端可能被提示注入诱导执行破坏性操作（恢复备份 / 批量删源删书 / 装主题包），
 * 因此这类工具必须经**手机端人工物理确认**才放行。`confirm:true` 一类的入参不算人机确认
 * （AI 可自行填入），故本闸门只认端侧 Activity 的按钮回传结果。
 *
 * 由 `web/mcp/` 下的工具执行链在破坏性操作前调用 [requestConfirm] 挂起等待；宿主
 * [McpConfirmActivity] 弹出确认界面并调用 [resolve] 回传结果。
 *
 * 说明：本类不打印 [requestConfirm] 的 title/message 原文（可能含业务数据），仅记录技术性结论。
 */
object McpConfirmGate {

    /** 静默期（毫秒）：超过此时长未获端侧确认，视为「拒绝」。 */
    const val TIMEOUT_MS = 60_000L

    /** 串行化 [deferred] 的读写，保证「同一时刻仅一个待确认请求」。 */
    private val lock = Any()

    /** 当前待确认请求的承载（无待确认请求时为 null）。 */
    @Volatile
    private var deferred: CompletableDeferred<Boolean>? = null

    /** 当前是否有待确认请求（供并发验证：挂起期间其它工具调用不受阻）。 */
    val pending: Boolean
        get() = deferred != null

    /**
     * 挂起等待端侧人工确认。
     *
     * @return true=用户点了确认；false=用户拒绝、超时、拉起失败或已有其它待确认请求（后到者直接拒绝）。
     */
    suspend fun requestConfirm(
        title: String,
        message: String,
        timeoutMs: Long = TIMEOUT_MS,
    ): Boolean {
        val gate = CompletableDeferred<Boolean>()
        // 并发保护：同一时刻只允许一个待确认请求；已有 pending 时后到请求直接拒绝，
        // 既不排队堆积，也不留下悬挂的 deferred。
        val accepted = synchronized(lock) {
            if (deferred != null) {
                false
            } else {
                deferred = gate
                true
            }
        }
        if (!accepted) return false

        val launched = try {
            appCtx.startActivity(
                Intent(appCtx, McpConfirmActivity::class.java).apply {
                    // 从 Application 上下文拉起必须带 NEW_TASK
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(McpConfirmActivity.EXTRA_TITLE, title)
                    putExtra(McpConfirmActivity.EXTRA_MESSAGE, message)
                }
            )
            true
        } catch (e: Exception) {
            // 无法拉起确认界面 ⇒ 保守拒绝（绝不放行）
            AppLog.put("MCP 端侧确认：拉起确认界面失败，按拒绝处理", e)
            false
        }

        if (!launched) {
            clearIfCurrent(gate)
            return false
        }

        return try {
            withTimeout(timeoutMs) { gate.await() }
        } catch (e: TimeoutCancellationException) {
            // 静默期超时 ⇒ 视为拒绝，不向外抛异常
            AppLog.put("MCP 端侧确认：等待超时，按拒绝处理")
            false
        } finally {
            clearIfCurrent(gate)
        }
    }

    /** 由 [McpConfirmActivity] 回调（用户点确认/拒绝）。 */
    fun resolve(approved: Boolean) {
        synchronized(lock) {
            deferred?.complete(approved)
        }
    }

    /** 清理当前请求承载（仅当仍指向本次 gate 时，避免误清后续请求）。 */
    private fun clearIfCurrent(gate: CompletableDeferred<Boolean>) {
        synchronized(lock) {
            if (deferred === gate) {
                deferred = null
            }
        }
    }
}