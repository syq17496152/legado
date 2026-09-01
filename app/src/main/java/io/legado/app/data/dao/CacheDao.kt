package io.legado.app.data.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.Cache

@Dao
interface CacheDao {

    @Query("select * from caches where `key` = :key")
    fun get(key: String): Cache?

    @Query("select value from caches where `key` = :key and (deadline = 0 or deadline > :now)")
    fun get(key: String, now: Long): String?

    // 7.11h 现代发现套件：有界读取（防止发现/套件缓存单行超限导致进程崩溃）
    @Query(
        """select length(cast(value as blob)) as byteCount,
        case when length(cast(value as blob)) <= :maxBytes then value else null end as value
        from caches where `key` = :key and (deadline = 0 or deadline > :now)"""
    )
    fun getBoundedValue(key: String, now: Long, maxBytes: Long): BoundedCacheValue?

    @Query("delete from caches where `key` = :key and value is :value")
    fun deleteIfValueMatches(key: String, value: String?)

    @Query(
        """delete from caches where `key` = :key
        and length(cast(value as blob)) > :maxBytes"""
    )
    fun deleteIfValueOversized(key: String, maxBytes: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg cache: Cache)

    @Query("delete from caches where `key` = :key")
    fun delete(key: String)

    @Query(
        """delete from caches where `key` like 'v_' || :key || '_%'
        or `key` = 'userInfo_' || :key
        or `key` = 'loginHeader_' || :key
        or `key` = 'sourceVariable_' || :key
        or `key` = 'infoMap_' || :key"""
    )
    fun deleteSourceVariables(key: String)

    @Query("delete from caches where deadline > 0 and deadline < :now")
    fun clearDeadline(now: Long)

    // P0-S2 脚本缓存按源隔离：前缀查询/清理（like 语法先例 deleteSourceVariables；仅新增 @Query 零 schema 变更免 migration）
    @Query("select * from caches where `key` like :prefix || '%'")
    fun getByPrefix(prefix: String): List<Cache>

    @Query("delete from caches where `key` like :prefix || '%'")
    fun deleteByPrefix(prefix: String)

    // F-P0-2 备份选择器（借鉴蛋蛋Max）获取书源运行数据缓存
    @Query(
        """select * from caches
        where substr(`key`, 1, 2) = 'v_'
        or substr(`key`, 1, 9) = 'userInfo_'
        or substr(`key`, 1, 11) = 'loginHeader_'
        or substr(`key`, 1, 15) = 'sourceVariable_'
        or substr(`key`, 1, 8) = 'infoMap_'"""
    )
    fun getRuntimeSourceCaches(): List<Cache>

}

// 7.11h 现代发现套件：cache 有界查询结果载体
data class BoundedCacheValue(
    @ColumnInfo(name = "byteCount") val byteCount: Long,
    @ColumnInfo(name = "value") val value: String?
)