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

    @Query("select * from ttsCastingTemplates where id = :id")
    fun flowById(id: String): Flow<TtsCastingTemplate?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg templates: TtsCastingTemplate)

    @Update
    suspend fun update(vararg templates: TtsCastingTemplate)

    @Delete
    suspend fun delete(vararg templates: TtsCastingTemplate)

    @Query("delete from ttsCastingTemplates where id = :id")
    suspend fun deleteById(id: String)

    @Query("delete from ttsCastingTemplates where builtin = 0")
    suspend fun deleteUserTemplates()
}
