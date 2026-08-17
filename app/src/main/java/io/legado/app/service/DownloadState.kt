package io.legado.app.service

import android.app.DownloadManager
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefStringSet
import io.legado.app.utils.putPrefStringSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import splitties.init.appCtx
import splitties.systemservices.downloadManager

/**
 * 下载任务状态（precise-manage：内存单例，进程重启后由 queryAllTaskStatus 从系统 DownloadManager 恢复）
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

    /**
     * 仅隐藏记录并保留已下载文件（downloadManager.remove 会连文件一起删除）
     */
    @Synchronized
    fun clearTask(id: Long) {
        val dismissed = (appCtx.getPrefStringSet(PreferKey.downloadDismissedIds) ?: emptySet())
            .toMutableSet()
        if (dismissed.add(id.toString())) {
            appCtx.putPrefStringSet(PreferKey.downloadDismissedIds, dismissed)
        }
        removeTask(id)
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
        // 无过滤查询仅返回本应用入队的任务（系统 DownloadManager 按应用隔离），
        // 进程重启后内存清空，据此从系统侧恢复任务列表
        val dismissed = (appCtx.getPrefStringSet(PreferKey.downloadDismissedIds) ?: emptySet())
            .toMutableSet()
        val result = linkedMapOf<Long, DownloadTask>()
        val query = android.app.DownloadManager.Query()
        downloadManager.query(query).use { cursor ->
            if (cursor.moveToFirst()) {
                val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                val progressIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val fileSizeIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val titleIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_URI)
                val localUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val timeIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)

                fun columnString(index: Int): String? =
                    if (index >= 0) cursor.getString(index) else null

                do {
                    val id = cursor.getLong(idIndex)
                    val idStr = id.toString()
                    if (idStr in dismissed) {
                        continue
                    }
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
                    val old = taskMap.value[id]
                    result[id] = if (old == null) {
                        // 进程重启后内存丢失，从系统侧恢复元数据
                        DownloadTask(
                            id = id,
                            url = columnString(uriIndex) ?: "",
                            fileName = columnString(localUriIndex)?.substringAfterLast('/')
                                ?: columnString(titleIndex) ?: "",
                            startTime = if (timeIndex >= 0) cursor.getLong(timeIndex) else 0L,
                            status = status,
                            progress = progress,
                            totalSize = total,
                            downloadedSize = progress
                        )
                    } else {
                        val now = System.currentTimeMillis()
                        val speed = if (now > old.startTime && progress >= old.downloadedSize) {
                            progress * 1000L / (now - old.startTime)
                        } else {
                            old.speed
                        }
                        old.copy(
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
        // dismissed 中系统侧已不存在的 id 随之回收，防止集合无限增长
        val liveIds = result.keys
        val stale = dismissed.filter { it.toLongOrNull() !in liveIds }
        if (stale.isNotEmpty()) {
            dismissed.removeAll(stale.toSet())
            appCtx.putPrefStringSet(PreferKey.downloadDismissedIds, dismissed)
        }
        taskMap.value = result
        return result.values.toList()
    }
}