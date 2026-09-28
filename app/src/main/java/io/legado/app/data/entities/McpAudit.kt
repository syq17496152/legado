package io.legado.app.data.entities

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Web / MCP 调用审计记录（web-mcp-productization 一期 · §3.6 / design §1.5）。
 *
 * 用途：写面（`manage` / `admin`）调用的"谁在什么时候对什么做了什么"留痕，供三期控制台
 * 与四期"一键断电"取证使用。**只记录元数据**（路径 / 级别 / 目标 / 成败 / 耗时），
 * 不记录请求体与响应体。
 *
 * 设计约束：
 * 1. `@Keep` —— 该模型会被 Gson 序列化给控制台（含无集合字段，但字段名必须稳定）；
 * 2. [target] 必须是**已脱敏**的值（URL 里的 token / key / password 等参数一律 `***`，
 *    见 `web/utils/AuditSanitizer`）；
 * 3. 列顺序与 `DatabaseMigrations.migration_112_113` 的 DDL 必须逐列一致（Room schema 校验）。
 */
@Keep
@Entity(
    tableName = "mcp_audit",
    indices = [Index("time"), Index("source"), Index("level")]
)
data class McpAudit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 调用时间戳（epoch ms） */
    val time: Long = 0,
    /** 通道来源："rest" | "mcp" | "ws"（一期只有 rest 落库） */
    val source: String = "",
    /** 端点路径 / MCP 工具名 */
    val channel: String = "",
    /** 令牌级别：readonly / manage / admin */
    val level: String = "",
    /** 操作对象（**脱敏后**；URL 里 token/key 参数已打码） */
    val target: String = "",
    /** HTTP 方法 / "tools/call" */
    val method: String = "",
    val success: Boolean = false,
    val errorMsg: String = "",
    val elapsedMs: Long = 0
)
