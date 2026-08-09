package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.SourceRecycleBin

/**
 * AD-04: 规则回收站 DAO（B7，参考 youfengknight_Legado_Max）
 */
@Dao
interface SourceRecycleBinDao {

    @Query("select * from source_recycle_bin order by deletedAt desc, id desc")
    fun flowAll(): kotlinx.coroutines.flow.Flow<List<SourceRecycleBin>>

    @Query("select * from source_recycle_bin where type = :type order by deletedAt desc, id desc")
    fun flowByType(type: String): kotlinx.coroutines.flow.Flow<List<SourceRecycleBin>>

    @Query("select * from source_recycle_bin where id = :id")
    fun getById(id: Long): SourceRecycleBin?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg items: SourceRecycleBin)

    @Delete
    fun delete(vararg items: SourceRecycleBin)

    @Query("delete from source_recycle_bin where id = :id")
    fun deleteById(id: Long)

    @Query("delete from source_recycle_bin where expireAt < :now")
    fun deleteExpired(now: Long): Int

    @Query("delete from source_recycle_bin")
    fun deleteAll()
}
