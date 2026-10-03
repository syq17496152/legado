package io.legado.app.web

import io.legado.app.data.appDb
import io.legado.app.data.entities.McpAudit
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.web.api.ApiRoute
import io.legado.app.web.utils.AuditSanitizer

/**
 * 写面调用审计落库（web-mcp-productization 一期 · §3.7 / REQ-1-307）。
 *
 * 三条硬约束：
 * 1. **审计范围 = 写面**：只有 `route.level != READONLY` 的端点落库。口径与「过渡期按级别判定」
 *    （决策 #7）一致 —— `GET /backup`（ADMIN）虽读但可整包导出，必须留痕；普通只读接口不落库，
 *    避免把审计表写成访问日志。
 * 2. **不阻塞响应**：插入走 [Coroutine.async] 投递到 IO 线程，HTTP 线程立即返回。
 * 3. **不留明文凭证**：`target` 经 [AuditSanitizer] 打码后才落库。
 */
object McpAuditor {

    /** 审计保留期（与 design §1.5 的「超过 7 天定期清理」一致）。 */
    const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * 记录一次调用。
     *
     * @param level 令牌级别（用于回答"这次调用是谁的权限"）
     * @param elapsedMs 处理耗时（毫秒）
     */
    fun record(
        route: ApiRoute,
        level: TokenManager.Level,
        postData: String?,
        success: Boolean,
        errorMsg: String,
        elapsedMs: Long
    ) {
        if (route.level == TokenManager.Level.READONLY) return
        val audit = McpAudit(
            time = System.currentTimeMillis(),
            source = SOURCE_REST,
            channel = route.path,
            level = level.name.lowercase(),
            target = AuditSanitizer.extractTarget(postData),
            method = route.method.name,
            success = success,
            errorMsg = errorMsg.take(MAX_ERROR_LENGTH),
            elapsedMs = elapsedMs
        )
        // 异步落库：审计失败不得影响业务响应（对齐"审计表写入阻塞请求"的反模式）
        Coroutine.async {
            appDb.mcpAuditDao.insert(audit)
        }
    }

    /** 清理超期记录（在 Web 服务启动时投递一次，不参与请求路径）。 */
    fun purgeExpired() {
        val before = System.currentTimeMillis() - RETENTION_MS
        Coroutine.async {
            appDb.mcpAuditDao.deleteBefore(before)
        }
    }

    private const val SOURCE_REST = "rest"
    private const val MAX_ERROR_LENGTH = 300
}
