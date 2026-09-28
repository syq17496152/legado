package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.legado.app.data.entities.McpAudit

/**
 * 审计表 DAO（web-mcp-productization 一期 · §3.6 / design §1.5）。
 */
@Dao
interface McpAuditDao {

    @Insert
    suspend fun insert(audit: McpAudit)

    /** 供三期控制台轮询：查某目标最近 N 条（[key] 为 `LIKE` 模式，调用方自行加通配符）。 */
    @Query("SELECT * FROM mcp_audit WHERE target LIKE :key ORDER BY time DESC LIMIT :limit")
    suspend fun recentByTarget(key: String, limit: Int = 20): List<McpAudit>

    /** 保留策略：删除早于 [before] 的记录（默认保留 7 天）。 */
    @Query("DELETE FROM mcp_audit WHERE time < :before")
    suspend fun deleteBefore(before: Long)

    /** 当前记录数（供审计页展示与真机覆盖安装探针核对）。 */
    @Query("SELECT COUNT(*) FROM mcp_audit")
    suspend fun count(): Long
}
