package io.legado.app.service

import io.legado.app.data.appDb
import io.legado.app.data.entities.DownloadTaskEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 下载任务状态（download-manager-maturity / download-manager-optimize）
 *
 * 由 DownloadService 在任务 入队/进度/成功/失败/移除 时写入；
 * Room 为主存（进程被杀/崩溃后任务不丢、可续传），StateFlow 为展示缓存。
 *
 * 批次B 改造：
 * - B1 进度节流：进度类更新的内存发射与 DB 落库共用同一 500ms 时间窗（窗口内合并、
 *   到期一次性发射+落库）；状态翻转（status 变化）绕过节流立即发射+立即落库，终态强制 flush。
 *   内存与 DB 同步不分叉，UI 与落库数据始终一致。
 * - B9 体积全程 Long（>2GB 正确）。
 * - B8 targetDir 随任务落库（记录实际落盘目录，消除目录变更后清理错位）。
 */
enum class DownloadStatus {
    WAITING, RUNNING, PAUSED, COMPLETED, FAILED
}

enum class DownloadTaskType {
    /** 直链 mp4/mkv/flv 等 */
    DIRECT,
    /** m3u8/HLS 流 */
    HLS
}

data class DownloadTask(
    val id: Long,
    val url: String,
    val fileName: String,
    val startTime: Long,
    val taskType: DownloadTaskType = DownloadTaskType.DIRECT,
    val status: DownloadStatus = DownloadStatus.WAITING,
    val progress: Int = 0,
    /** B9：总字节数（Long，>2GB 正确） */
    val totalSize: Long = 0,
    /** B9：已下载字节数（Long） */
    val downloadedSize: Long = 0,
    val speed: Long = 0,
    /** 完成后的本地文件绝对路径 */
    val localPath: String? = null,
    /** 失败错误码（DownloadError 名），成功为 null */
    val errorCode: String? = null,
    /** 下载请求头 JSON（防盗链 Referer/Cookie 等），续传/恢复时复用 */
    val headersJson: String? = null,
    /** B8：实际落盘目录（attempt 起始记录，删除清理优先使用，防目录变更错位） */
    val targetDir: String? = null
)

object DownloadState {

    @Volatile
    private var taskMap = MutableStateFlow<Map<Long, DownloadTask>>(emptyMap())
    val tasks: StateFlow<Map<Long, DownloadTask>> = taskMap.asStateFlow()

    private val lastBytes = hashMapOf<Long, Long>()
    private val lastTime = hashMapOf<Long, Long>()

    /** B1 进度节流窗口 */
    private const val PROGRESS_FLUSH_MS = 500L
    private val lastFlushTime = hashMapOf<Long, Long>()

    /** B1 窗口内合并的待发布任务（覆盖在 taskMap 之上作为最新已知状态） */
    private val pendingTasks = HashMap<Long, DownloadTask>()

    /** 最新已知状态 = 待发布覆盖 已发布 */
    private fun effective(id: Long): DownloadTask? =
        pendingTasks[id] ?: taskMap.value[id]

    /** 发布：内存发射 + Room 落库（同一时间点，内存与 DB 不分叉） */
    private fun publish(task: DownloadTask) {
        taskMap.value = taskMap.value + (task.id to task)
        appDb.downloadTaskDao.update(
            DownloadTaskEntity(
                id = task.id, url = task.url, fileName = task.fileName,
                taskType = task.taskType.name,
                headersJson = task.headersJson,
                status = task.status.name,
                progress = task.progress,
                totalSize = task.totalSize,
                downloadedSize = task.downloadedSize,
                speed = task.speed,
                localPath = task.localPath,
                errorCode = task.errorCode,
                targetDir = task.targetDir,
                startTime = task.startTime
            )
        )
    }

    /** B6：内存缺失时从 DB 加载合并（消除 updateTask 静默丢状态） */
    private fun rebuildFromEntity(id: Long): DownloadTask? {
        val entity = appDb.downloadTaskDao.loadById(id) ?: return null
        return DownloadTask(
            id = entity.id,
            url = entity.url,
            fileName = entity.fileName,
            startTime = entity.startTime,
            taskType = runCatching { DownloadTaskType.valueOf(entity.taskType) }
                .getOrDefault(DownloadTaskType.DIRECT),
            status = runCatching { DownloadStatus.valueOf(entity.status) }
                .getOrDefault(DownloadStatus.WAITING),
            progress = entity.progress,
            totalSize = entity.totalSize,
            downloadedSize = entity.downloadedSize,
            speed = entity.speed,
            localPath = entity.localPath,
            errorCode = entity.errorCode,
            headersJson = entity.headersJson,
            targetDir = entity.targetDir
        )
    }

    /** 新增任务：先落库获得持久化 id，再回填内存缓存。返回任务 id。 */
    @Synchronized
    fun addTask(
        url: String,
        fileName: String,
        taskType: DownloadTaskType = DownloadTaskType.DIRECT,
        startTime: Long = System.currentTimeMillis(),
        headersJson: String? = null
    ): Long {
        val entity = DownloadTaskEntity(
            url = url,
            fileName = fileName,
            taskType = taskType.name,
            headersJson = headersJson,
            status = DownloadStatus.WAITING.name,
            startTime = startTime
        )
        val id = appDb.downloadTaskDao.insert(entity)
        val task = DownloadTask(
            id = id, url = url, fileName = fileName, startTime = startTime,
            taskType = taskType, status = DownloadStatus.WAITING
        )
        taskMap.value = taskMap.value + (id to task)
        return id
    }

    /**
     * 更新任务（进度/状态/本地路径/错误码/目标目录）。
     * B1：状态翻转立即发布；进度更新按 500ms 窗合并发布。
     * B3：clearError=true 时显式清空 errorCode（成功态调用，防历史失败码残留）。
     */
    @Synchronized
    fun updateTask(
        id: Long,
        status: DownloadStatus? = null,
        progress: Int? = null,
        totalSize: Long? = null,
        downloadedSize: Long? = null,
        localPath: String? = null,
        errorCode: String? = null,
        clearError: Boolean = false,
        targetDir: String? = null
    ) {
        val base = effective(id) ?: rebuildFromEntity(id)?.also { rebuilt ->
            // B6：内存缺失，先把 DB 态补回内存再继续更新
            taskMap.value = taskMap.value + (id to rebuilt)
        } ?: return
        val now = System.currentTimeMillis()
        val newBytes = downloadedSize ?: base.downloadedSize
        // B7：恢复 RUNNING 时重置速度基准（lastBytes/lastTime 置当前值），避免跨暂停时段瞬时超速
        val isResume = status == DownloadStatus.RUNNING && base.status != DownloadStatus.RUNNING
        val speed = if (isResume) {
            lastBytes[id] = newBytes
            lastTime[id] = now
            0L
        } else {
            val prevBytes = lastBytes[id] ?: 0L
            val prevTime = lastTime[id] ?: now
            val elapsed = now - prevTime
            if (elapsed > 0 && newBytes >= prevBytes) {
                (newBytes - prevBytes) * 1000L / elapsed
            } else {
                base.speed
            }
        }
        if (!isResume) {
            lastBytes[id] = newBytes
            lastTime[id] = now
        }
        val task = base.copy(
            status = status ?: base.status,
            progress = progress ?: base.progress,
            totalSize = totalSize ?: base.totalSize,
            downloadedSize = newBytes,
            speed = speed,
            localPath = localPath ?: base.localPath,
            // B3：clearError 优先；否则沿用合并语义
            errorCode = if (clearError) null else (errorCode ?: base.errorCode),
            targetDir = targetDir ?: base.targetDir
        )
        val isStatusChange = status != null && status != base.status
        val last = lastFlushTime[id] ?: 0L
        if (isStatusChange || now - last >= PROGRESS_FLUSH_MS) {
            // 状态翻转立即发布（终态强制 flush）；进度窗口到期一次性发布
            pendingTasks.remove(id)
            publish(task)
            lastFlushTime[id] = now
        } else {
            // B1：窗口内合并，仅暂存不发射不落库
            pendingTasks[id] = task
        }
    }

    @Synchronized
    fun removeTask(id: Long) {
        pendingTasks.remove(id)
        lastBytes.remove(id)
        lastTime.remove(id)
        lastFlushTime.remove(id)
        taskMap.value = taskMap.value - id
        appDb.downloadTaskDao.delete(id)
    }

    /** 返回当前全部任务（含窗口内未发布的最新进度，供过滤/判定） */
    fun queryAllTaskStatus(): List<DownloadTask> {
        val base = taskMap.value
        if (pendingTasks.isEmpty()) return base.values.toList()
        val merged = base.toMutableMap()
        pendingTasks.forEach { (id, task) -> merged[id] = task }
        return merged.values.toList()
    }

    /**
     * 启动恢复：从 Room 加载全部任务（含已完成）**合并**进内存缓存。
     * 返回**需要自动续传**的任务 id 列表（内存缺失且原处于 RUNNING/WAITING 的任务），
     * 由调用方（DownloadService.onCreate / 管理页）重新入队调度，避免"正在下载的任务丢失"。
     *
     * 铁证（用户实测两轮 + live_logcat 22:45 会话）：
     * 1. 此前"无条件清空缓存 + 仅恢复未完成任务"导致任务完成后 Service stopSelf、
     *    用户新增任务触发 onCreate 时已完成任务从内存缓存被抹掉；
     * 2. 关键根因：Service 重建/进程被杀后，恢复的任务仅回填内存（RUNNING→PAUSED）
     *    而**不重新调度**，任务停在 PAUSED 从"下载中"消失，用户误以为丢失；
     * 3. 铁证（用户新增任务复测）：管理页打开时无条件用 DB 覆盖内存 taskMap 会把
     *    运行中任务 RUNNING 降级为 PAUSED，若该任务恰好完成，内存 COMPLETED 被 DB
     *    旧值覆盖 → 任务从"完成列表"消失、被重新调度重复下载，表现为"任务丢失"。
     * 现改为**合并而非覆盖**：
     * - 内存已有任务（正在运行/已展示）：保留内存最新状态，绝不降级、不覆盖；
     * - 内存缺失任务（进程重启/Service 重建后）：从 DB 重建补充；
     * - 原 RUNNING/WAITING 且内存缺失 → 重建为 PAUSED 待重新入队（避免重复线程），
     *   并计入返回值自动续传；原 PAUSED 保持暂停（用户主动暂停，不自动续传）。
     */
    @Synchronized
    fun resumeFromDb(): List<Long> {
        val all = appDb.downloadTaskDao.loadAll()
        val existing = taskMap.value
        val rebuilt = mutableMapOf<Long, DownloadTask>()
        val autoResume = mutableListOf<Long>()
        all.forEach { entity ->
            // 内存已存在（Service 运行中 / 管理页已展示）：保留内存状态，不覆盖不降级
            val memTask = existing[entity.id]
            if (memTask != null) {
                rebuilt[memTask.id] = memTask
                return@forEach
            }
            // 内存缺失（进程重启/Service 重建）：从 DB 重建
            val wasActive = entity.status == "RUNNING" || entity.status == "WAITING"
            val task = DownloadTask(
                id = entity.id,
                url = entity.url,
                fileName = entity.fileName,
                startTime = entity.startTime,
                taskType = runCatching { DownloadTaskType.valueOf(entity.taskType) }
                    .getOrDefault(DownloadTaskType.DIRECT),
                // FAILED 保留 FAILED 状态供用户手动重试；RUNNING 恢复为 PAUSED 待重新入队（避免重复线程）
                status = if (entity.status == "RUNNING") DownloadStatus.PAUSED
                else runCatching { DownloadStatus.valueOf(entity.status) }
                    .getOrDefault(DownloadStatus.PAUSED),
                progress = entity.progress,
                totalSize = entity.totalSize,
                downloadedSize = entity.downloadedSize,
                localPath = entity.localPath,
                errorCode = entity.errorCode,
                headersJson = entity.headersJson,
                targetDir = entity.targetDir
            )
            rebuilt[task.id] = task
            if (wasActive) autoResume.add(task.id)
        }
        pendingTasks.clear()
        taskMap.value = rebuilt
        return autoResume
    }
}
