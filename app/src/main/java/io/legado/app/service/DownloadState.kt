package io.legado.app.service

import android.app.DownloadManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import splitties.init.appCtx
import splitties.systemservices.downloadManager

/**
 * 下载任务状态（precise-manage：内存单例，非持久化，进程重启丢失）
 * 由 DownloadService 在 enqueue/queryState/success/remove 时写入
 */
enum class DownloadStatus {
    WAITING, RUNNING, PAUSED, COMPLETED, FAILED
}

data class DownloadTask(
    val id: Long,
    val url: String,
    val fileName: String,
    val startTime: Long,
    val status: DownloadStatus = DownloadStatus.WAITING,
    val progress: Int = 0,
    val totalSize: Int = 0,
    val downloadedSize: Int = 0,
    val speed: Long = 0
)

object DownloadState {
    private val taskMap = MutableStateFlow<Map<Long, DownloadTask>>(emptyMap())
    val tasks: StateFlow<Map<Long, DownloadTask>> = taskMap.asStateFlow()

    @Synchronized
    fun addTask(id: Long, url: String, fileName: String, startTime: Long) {
        taskMap.value = taskMap.value + (id to DownloadTask(id, url, fileName, startTime))
    }

    @Synchronized
    fun updateTask(
        id: Long,
        status: DownloadStatus? = null,
        progress: Int? = null,
        totalSize: Int? = null,
        downloadedSize: Int? = null
    ) {
        val old = taskMap.value[id] ?: return
        val prevBytes = old.downloadedSize
        val now = System.currentTimeMillis()
        val newBytes = downloadedSize ?: old.downloadedSize
        val speed = if (now > old.startTime && newBytes >= prevBytes) {
            newBytes * 1000L / (now - old.startTime)
        } else {
            old.speed
        }
        taskMap.value = taskMap.value + (id to old.copy(
            status = status ?: old.status,
            progress = progress ?: old.progress,
            totalSize = totalSize ?: old.totalSize,
            downloadedSize = newBytes,
            speed = speed
        ))
    }

    @Synchronized
    fun removeTask(id: Long) {
        taskMap.value = taskMap.value - id
    }

    @Synchronized
    fun clear() {
        taskMap.value = emptyMap()
    }

    @Synchronized
    fun cancelDownload(id: Long) {
        downloadManager.remove(id)
        removeTask(id)
    }

    @Synchronized
    fun queryAllTaskStatus(): List<DownloadTask> {
        val ids = taskMap.value.keys
        if (ids.isEmpty()) return taskMap.value.values.toList()
        val query = android.app.DownloadManager.Query()
        query.setFilterById(*ids.toLongArray())
        val result = taskMap.value.toMutableMap()
        downloadManager.query(query).use { cursor ->
            if (cursor.moveToFirst()) {
                val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                val progressIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val fileSizeIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                do {
                    val id = cursor.getLong(idIndex)
                    val progress = cursor.getInt(progressIndex)
                    val total = cursor.getInt(fileSizeIndex)
                    val status = when (cursor.getInt(statusIndex)) {
                        DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                        DownloadManager.STATUS_PENDING -> DownloadStatus.WAITING
                        DownloadManager.STATUS_RUNNING -> DownloadStatus.RUNNING
                        DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETED
                        DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                        else -> DownloadStatus.WAITING
                    }
                    result[id]?.let { old ->
                        val prevBytes = old.downloadedSize
                        val now = System.currentTimeMillis()
                        val speed = if (now > old.startTime && progress >= prevBytes) {
                            progress * 1000L / (now - old.startTime)
                        } else {
                            old.speed
                        }
                        result[id] = old.copy(
                            status = status,
                            progress = progress,
                            totalSize = total,
                            downloadedSize = progress,
                            speed = speed
                        )
                    }
                } while (cursor.moveToNext())
            }
        }
        taskMap.value = result
        return result.values.toList()
    }
}