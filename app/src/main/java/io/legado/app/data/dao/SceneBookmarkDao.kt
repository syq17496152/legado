package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.SceneBookmark
import kotlinx.coroutines.flow.Flow

/**
 * W8 / REQ-31（AD-12）：名场面书签 DAO。
 *
 * 口径：`flowAll` 供库页全量回看（时间倒序）；`flowByBook` 供「本书名场面」与按书聚合；
 * `deleteByBook` 供「按书清空」。**不做联表**——按书聚合在 UI 侧用 `bookUrl` 分组即可。
 */
@Dao
interface SceneBookmarkDao {

    @get:Query("select * from sceneBookmarks order by time desc")
    val all: List<SceneBookmark>

    @Query("select * from sceneBookmarks order by time desc")
    fun flowAll(): Flow<List<SceneBookmark>>

    @Query("select * from sceneBookmarks where bookUrl = :bookUrl order by chapterIndex, time")
    fun flowByBook(bookUrl: String): Flow<List<SceneBookmark>>

    @Query("select * from sceneBookmarks where bookUrl = :bookUrl order by chapterIndex, time")
    fun getByBook(bookUrl: String): List<SceneBookmark>

    @Query("select count(*) from sceneBookmarks")
    fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(bookmark: SceneBookmark): Long

    @Update
    fun update(bookmark: SceneBookmark)

    @Delete
    fun delete(bookmark: SceneBookmark)

    @Query("delete from sceneBookmarks where id = :id")
    fun deleteById(id: Long)

    @Query("delete from sceneBookmarks where bookUrl = :bookUrl")
    fun deleteByBook(bookUrl: String)
}
