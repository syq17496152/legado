package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiReadAloudRoleCache

@Dao
interface AiReadAloudRoleCacheDao {

    @Query("SELECT * FROM ai_read_aloud_role_caches WHERE cacheKey = :cacheKey LIMIT 1")
    fun get(cacheKey: String): AiReadAloudRoleCache?

    @Query("SELECT * FROM ai_read_aloud_role_caches WHERE bookUrl = :bookUrl")
    fun listByBook(bookUrl: String): List<AiReadAloudRoleCache>

    // B25 退役（2026-10-16）：`latestByChapter`（按 status 取最近一条）零调用，
    // 活替代＝`latestUsableByChapter`（同章节、口径更宽：非 success 也回退可用缓存）。

    @Query(
        """
        SELECT * FROM ai_read_aloud_role_caches
        WHERE bookUrl = :bookUrl
          AND chapterIndex = :chapterIndex
          AND segmentsJson != ''
        ORDER BY
          CASE WHEN status = 'success' THEN 0 ELSE 1 END,
          updatedAt DESC
        LIMIT 1
        """
    )
    fun latestUsableByChapter(
        bookUrl: String,
        chapterIndex: Int
    ): AiReadAloudRoleCache?

    // B25 退役（2026-10-16）：`latestSuccessByChapterContent`（同条件 `LIMIT 1`）零调用，
    // 活替代＝下方 `successCandidatesByChapterContent`（同筛选条件，`LIMIT 20` 供上层择优）。

    @Query(
        """
        SELECT * FROM ai_read_aloud_role_caches
        WHERE bookUrl = :bookUrl
          AND chapterIndex = :chapterIndex
          AND contentHash = :contentHash
          AND status = 'success'
          AND segmentsJson != ''
          AND mode LIKE '%' || :preprocessVersion || '%'
        ORDER BY updatedAt DESC
        LIMIT 20
        """
    )
    fun successCandidatesByChapterContent(
        bookUrl: String,
        chapterIndex: Int,
        contentHash: String,
        preprocessVersion: String
    ): List<AiReadAloudRoleCache>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(cache: AiReadAloudRoleCache)

    @Query("DELETE FROM ai_read_aloud_role_caches WHERE bookUrl = :bookUrl AND chapterIndex = :chapterIndex")
    fun deleteByChapter(bookUrl: String, chapterIndex: Int)

    @Query("DELETE FROM ai_read_aloud_role_caches WHERE bookUrl = :bookUrl")
    fun deleteByBook(bookUrl: String)
}
