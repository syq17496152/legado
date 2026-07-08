package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.model.AutoTaskRule

/**
 * F-P1-1 自动任务规则 DAO
 * 借鉴自阅读T (skybbk1001/legadoT)
 */
@Dao
interface AutoTaskRuleDao {

    @Query("select * from auto_task_rules")
    fun all(): List<AutoTaskRule>

    @Query("select * from auto_task_rules where id = :id")
    fun getById(id: String): AutoTaskRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg rule: AutoTaskRule)

    @Update
    fun update(vararg rule: AutoTaskRule)

    @Query("delete from auto_task_rules where id = :id")
    fun delete(id: String)

    @Query("delete from auto_task_rules")
    fun deleteAll()
}
