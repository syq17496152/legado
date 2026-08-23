package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.ReadMenuCustomButton
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadMenuCustomButtonDao {

    @Query("SELECT * FROM read_menu_custom_buttons ORDER BY sortOrder ASC, id ASC")
    fun flowAll(): Flow<List<ReadMenuCustomButton>>

    @Query("SELECT * FROM read_menu_custom_buttons ORDER BY sortOrder ASC, id ASC")
    fun all(): List<ReadMenuCustomButton>

    @Query("SELECT * FROM read_menu_custom_buttons WHERE id = :id")
    fun get(id: Long): ReadMenuCustomButton?

    @Query("SELECT MAX(sortOrder) FROM read_menu_custom_buttons")
    fun maxOrder(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(button: ReadMenuCustomButton): Long

    @Update
    fun update(button: ReadMenuCustomButton)

    @Delete
    fun delete(button: ReadMenuCustomButton)
}
