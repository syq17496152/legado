package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.UrlRecord
import kotlinx.coroutines.flow.Flow

// precise-manage: 网址记录 DAO（借鉴 Legado_Max UrlRecordDao，排序统一 timestamp DESC）
@Dao
interface UrlRecordDao {

    @Query("select * from url_records order by timestamp desc limit 2000")
    fun flowAll(): Flow<List<UrlRecord>>

    @Query(
        """select * from url_records
        where url like '%' || :keyword || '%'
           or domain like '%' || :keyword || '%'
           or sourceName like '%' || :keyword || '%'
        order by timestamp desc limit 2000"""
    )
    fun flowSearch(keyword: String): Flow<List<UrlRecord>>

    @Query("select * from url_records where domain = :domain order by timestamp desc limit 2000")
    fun flowByDomain(domain: String): Flow<List<UrlRecord>>

    @Query("select * from url_records where sourceName = :sourceName order by timestamp desc limit 2000")
    fun flowBySourceName(sourceName: String): Flow<List<UrlRecord>>

    @Query("select * from url_records where method = :method order by timestamp desc limit 2000")
    fun flowByMethod(method: String): Flow<List<UrlRecord>>

    @Query(
        """select * from url_records
        where (:success = 1 and responseCode between 200 and 299)
           or (:success = 0 and (responseCode < 200 or responseCode >= 300))
        order by timestamp desc limit 2000"""
    )
    fun flowByStatus(success: Boolean): Flow<List<UrlRecord>>

    @Query("select distinct domain from url_records order by domain")
    fun flowAllDomains(): Flow<List<String>>

    @Query("select distinct sourceName from url_records where sourceName is not null and sourceName != '' order by sourceName")
    fun flowAllSourceNames(): Flow<List<String>>

    @Query("select distinct method from url_records order by method")
    fun flowAllMethods(): Flow<List<String>>

    @Query(
        """select * from url_records
        where (:domain is null or domain = :domain)
          and (:sourceName is null or sourceName = :sourceName)
          and (:method is null or method = :method)
          and (:success is null or (:success = 1 and responseCode between 200 and 299) or (:success = 0 and (responseCode < 200 or responseCode >= 300)))
          and (:keyword is null or url like '%' || :keyword || '%' or domain like '%' || :keyword || '%' or sourceName like '%' || :keyword || '%')
        order by timestamp desc limit 2000"""
    )
    fun flowFilter(
        domain: String?,
        sourceName: String?,
        method: String?,
        success: Boolean?,
        keyword: String?
    ): Flow<List<UrlRecord>>

    @Query("select * from url_records order by timestamp desc limit 2000")
    fun getAll(): List<UrlRecord>

    @Query("select * from url_records where domain = :domain order by timestamp desc")
    fun getByDomain(domain: String): List<UrlRecord>

    @Query("select * from url_records where sourceName = :sourceName order by timestamp desc")
    fun getBySourceName(sourceName: String): List<UrlRecord>

    @Query(
        """select * from url_records
        where url like '%' || :keyword || '%' or domain like '%' || :keyword || '%' or sourceName like '%' || :keyword || '%'
        order by timestamp desc"""
    )
    fun search(keyword: String): List<UrlRecord>

    @Query("select count(*) from url_records")
    fun getCount(): Int

    @Query("delete from url_records where id = :id")
    fun delete(id: Long)

    @Query("delete from url_records")
    fun deleteAll(): Int

    @Query("delete from url_records where timestamp < :timestamp")
    fun deleteOldRecords(timestamp: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg records: UrlRecord)
}