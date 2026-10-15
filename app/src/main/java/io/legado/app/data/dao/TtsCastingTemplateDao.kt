package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.TtsCastingTemplate
import kotlinx.coroutines.flow.Flow

@Dao
interface TtsCastingTemplateDao {

    @Query("select * from ttsCastingTemplates where enabled = 1 order by sortOrder")
    suspend fun getEnabled(): List<TtsCastingTemplate>

    @Query("select * from ttsCastingTemplates order by sortOrder")
    fun observeAll(): Flow<List<TtsCastingTemplate>>

    @Query("select * from ttsCastingTemplates order by sortOrder")
    suspend fun all(): List<TtsCastingTemplate>

    @Query("select * from ttsCastingTemplates where id = :id")
    suspend fun get(id: String): TtsCastingTemplate?

    // B25 退役（2026-10-16）：`flowById` 零调用 ⇒ 与上方 `get(id)`（SQL 逐字相同、suspend 版）
    // 构成「同一查询两处实现」，`TtsCastingStore` 实际走 `get(id)`。

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg templates: TtsCastingTemplate)

    @Update
    suspend fun update(vararg templates: TtsCastingTemplate)

    @Delete
    suspend fun delete(vararg templates: TtsCastingTemplate)

    @Query("delete from ttsCastingTemplates where id = :id")
    suspend fun deleteById(id: String)

    // B25 退役（2026-10-16）：`deleteUserTemplates`（清全部非内置模板）零调用 ——
    // 已由 `optimize-tts-engine-phase2/code-review-20260910.md` 定性为死代码；批量删除走 `delete(vararg)`。
}
