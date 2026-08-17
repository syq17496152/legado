package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.SourceGroupCover

@Dao
interface SourceGroupCoverDao {

    @Query("SELECT * FROM source_group_covers WHERE kind = :kind")
    suspend fun getCoversByKind(kind: String): List<SourceGroupCover>

    @Query("SELECT * FROM source_group_covers WHERE kind = :kind AND groupName = :groupName")
    suspend fun getSourceGroupCover(kind: String, groupName: String): SourceGroupCover?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cover: SourceGroupCover)

    @Query("DELETE FROM source_group_covers WHERE kind = :kind AND groupName = :groupName")
    suspend fun delete(kind: String, groupName: String)
}