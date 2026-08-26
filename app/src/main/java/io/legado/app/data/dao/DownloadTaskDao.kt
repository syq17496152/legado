package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.DownloadTaskEntity

/**
 * 下载任务 DAO（download-manager-maturity）
 *
 * 负责下载任务的落库/读取/删除，支撑进程被杀后的任务恢复。
 * 同步方法为主（配合 AppDatabase 的 allowMainThreadQueries，
 * 由 DownloadState 在调度线程同步读写 Room，避免竞态）。
 */
@Dao
interface DownloadTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(task: DownloadTaskEntity): Long

    @Update
    fun update(task: DownloadTaskEntity)

    @Query("select * from download_tasks order by startTime desc")
    fun loadAll(): List<DownloadTaskEntity>

    @Query(
        "select * from download_tasks where status in ('WAITING','RUNNING','PAUSED','FAILED') order by startTime"
    )
    fun loadUnfinished(): List<DownloadTaskEntity>

    @Query("select * from download_tasks where id = :id")
    fun loadById(id: Long): DownloadTaskEntity?

    @Query("delete from download_tasks where id = :id")
    fun delete(id: Long)
}