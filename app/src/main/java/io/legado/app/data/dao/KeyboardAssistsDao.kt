package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.KeyboardAssist
import kotlinx.coroutines.flow.Flow

@Dao
interface KeyboardAssistsDao {

    @get:Query("select * from keyboardAssists order by serialNo")
    val all: List<KeyboardAssist>

    // B27 退役（2026-10-16）：`getByType`（List 版）零调用 ⇒ 活替代＝下方 `flowByType`（Flow 版，
    // 同 SQL；`KeyboardToolPop` 在用）。

    @get:Query("select * from keyboardAssists order by serialNo")
    val flowAll: Flow<List<KeyboardAssist>>

    @Query("select * from keyboardAssists where type = :type order by serialNo")
    fun flowByType(type: Int): Flow<List<KeyboardAssist>>

    @get:Query("select max(serialNo) from keyboardAssists order by serialNo")
    val maxSerialNo: Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg keyboardAssist: KeyboardAssist)

    @Update
    fun update(vararg keyboardAssist: KeyboardAssist)

    @Delete
    fun delete(vararg keyboardAssist: KeyboardAssist)

    @Query("delete from keyboardAssists")
    suspend fun deleteAll()

}