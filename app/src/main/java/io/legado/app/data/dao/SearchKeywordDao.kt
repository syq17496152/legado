package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.SearchKeyword
import kotlinx.coroutines.flow.Flow


@Dao
interface SearchKeywordDao {

    /**
     * 全部搜索历史（备份/恢复使用，不分 type）
     *
     * 注意：业务查询请使用带 type 参数的 flowByUsage / flowByTime / flowSearch，
     * 避免书源与订阅源搜索历史混在一起。
     */
    @get:Query("SELECT * FROM search_keywords")
    val all: List<SearchKeyword>

    @Query("SELECT * FROM search_keywords WHERE type = :type ORDER BY usage DESC")
    fun flowByUsage(type: Int): Flow<List<SearchKeyword>>

    @Query("SELECT * FROM search_keywords WHERE type = :type ORDER BY lastUseTime DESC")
    fun flowByTime(type: Int): Flow<List<SearchKeyword>>

    @Query("SELECT * FROM search_keywords WHERE type = :type AND word LIKE '%'||:key||'%' ORDER BY usage DESC")
    fun flowSearch(type: Int, key: String): Flow<List<SearchKeyword>>

    @Query("SELECT * FROM search_keywords WHERE word = :key AND type = :type")
    fun get(key: String, type: Int): SearchKeyword?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg keywords: SearchKeyword)

    @Update
    fun update(vararg keywords: SearchKeyword)

    @Delete
    fun delete(vararg keywords: SearchKeyword)

    /**
     * 清空指定 type 的搜索历史
     *
     * @param type 0=书源，1=订阅源
     */
    @Query("DELETE FROM search_keywords WHERE type = :type")
    fun deleteAll(type: Int)

}
