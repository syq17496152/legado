package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.PlayHistory

/**
 * AD-04: 播放历史 DAO
 */
@Dao
interface PlayHistoryDao {

    @Query("select * from playHistories where articleUrl = :articleUrl and videoUrl = :videoUrl")
    fun get(articleUrl: String, videoUrl: String): PlayHistory?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg history: PlayHistory)

    @Query("delete from playHistories where articleUrl = :articleUrl and videoUrl = :videoUrl")
    fun delete(articleUrl: String, videoUrl: String)

    @Query("delete from playHistories")
    fun clearAll()
}
