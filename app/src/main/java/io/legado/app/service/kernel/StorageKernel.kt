package io.legado.app.service.kernel

import io.legado.app.constant.IntentAction
import io.legado.app.data.appDb
import io.legado.app.model.Download
import io.legado.app.service.DownloadService
import io.legado.app.service.DownloadState
import io.legado.app.service.DownloadTaskType
import io.legado.app.utils.startService
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

/**
 * ⑯ 文件与存储域业务内核（web-mcp-productization 二期 · tasks 2.20 / 2.28）。
 *
 * 契约同 [BookKernel]：**只返回领域对象 / 结构化 Map**、**全链挂起**、**零 `runBlocking`**、失败抛异常。
 *
 * 数据源（全部既有能力，不新增存储）：
 * - 缓存占用 / 清理：**直接复用 [DiagKernel.cacheStats] / [DiagKernel.cacheClear]**（避免两套口径，
 *   报告 §⑯ 亦指向 `CacheManageViewModel` 的四类目录与 `CacheStorageDetail`）；
 * - 下载任务：`appDb.downloadTaskDao.loadAll()` + `DownloadState.queryAllTaskStatus()` **合并**
 *   （内存态优先，与 `DownloadState.resumeFromDb` 的「内存不降级」语义一致）；
 * - 下载发起 / 清理：`model/Download.start(...)` / `DownloadState.removeTask` /
 *   `DownloadService.deleteTaskFiles(...)`（报告 §⑯；`DownloadService` **无 start/stop 静态入口**）；
 * - 本地文件：**`java.io.File` 直接列目录 / 删文件**（见下方降级说明）。
 *
 * 已知上限 / 降级（如实声明）：
 * 1. **不存在 `help/FileHelp`**（报告 §⑯ 全仓 0 命中）；最接近的入口是
 *    `ui/file/FileManageViewModel`（`AndroidViewModel`，依赖 `BaseViewModel.execute` + `LiveData`），
 *    **内核不宜持有 ViewModel** ⇒ 本内核用 `java.io.File` 自实现列目录/删除；
 * 2. **根目录口径**：`appCtx.getExternalFilesDir(null)?.parentFile`（与 `FileManageViewModel.rootDoc`
 *    初值一字不差）；所有路径参数一律**相对根目录**解析并做**越界校验**（canonical path 必须落在根内）；
 * 3. `fileDelete` 的内核职责**只有执行删除 + 逐项回告**：批量（>3）端侧二次确认由**工具层**负责；
 *    删除**目录**用 `deleteRecursively()`，删根目录一律拒绝（回告 `deleted = false`）；
 * 4. `downloadManage("pause")` 经 `IntentAction.pause` 派发给 `DownloadService`
 *    （`DownloadService.onStartCommand` 支持 pause/resume/resumeAll/stop）；
 *    **无「是否运行中」API** ⇒ 运行态只能由 `DownloadState` 状态判定；
 * 5. URL（下载地址）经 [DiagKernel.maskUrl] 打码敏感 query；本地路径为文件系统路径，原样返回。
 */
object StorageKernel {

    /** 文件列表默认上限。 */
    const val DEFAULT_FILE_LIMIT = 200

    // ============================================================ 缓存 / 存储管理

    /**
     * `storage_manage`：查看占用（始终返回）+ 按类别清理（`clear = true` 时）。
     *
     * `kind` 取值同 [DiagKernel]：`books` / `video` / `audio` / `webview` / `all`。
     * 清理释放量取自 [DiagKernel.cacheClear] 的 `freedBytes`；播放/朗读中命中的目录会被**跳过**并回告。
     */
    suspend fun manage(
        kind: String = DiagKernel.KIND_ALL,
        clear: Boolean = false,
    ): Map<String, Any?> = withContext(IO) {
        val stats = DiagKernel.cacheStats()
        if (!clear) {
            return@withContext mapOf(
                "kind" to kind,
                "stats" to stats,
                "cleared" to false,
                "freedBytes" to 0L,
            )
        }
        val result = DiagKernel.cacheClear(kind)
        mapOf(
            "kind" to kind,
            "stats" to stats,
            "cleared" to true,
            "freedBytes" to (result["freedBytes"] as? Long ?: 0L),
            "clearResult" to result,
        )
    }

    /** `cache_stats`：缓存占用统计（复用 [DiagKernel.cacheStats]，与 ⑬ 同源）。 */
    suspend fun cacheStats(): Map<String, Any?> = DiagKernel.cacheStats()

    // ============================================================ 本地文件

    /** 根目录（与 `FileManageViewModel.rootDoc` 初值同口径）。 */
    private fun rootDir(): File = appCtx.getExternalFilesDir(null)?.parentFile ?: appCtx.filesDir

    /** 相对路径 → 根内绝对路径（canonical 越界即拒绝）。 */
    private fun resolveSafe(path: String): File {
        val root = rootDir().canonicalFile
        val target = (if (path.isBlank()) root else File(root, path)).canonicalFile
        require(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            "路径越界（必须位于根目录内）：$path"
        }
        return target
    }

    private fun relativeTo(root: File, file: File): String =
        file.absolutePath.removePrefix(root.absolutePath).trimStart(File.separatorChar)

    /** 目录递归体积（文件返回自身长度）。 */
    private fun sizeOf(file: File): Long = when {
        !file.exists() -> 0L
        file.isFile -> file.length().coerceAtLeast(0L)
        else -> runCatching { file.listFiles()?.sumOf { sizeOf(it) } ?: 0L }.getOrDefault(0L)
    }

    /**
     * `file_list`：列本地文件（相对根目录；目录在前、再按名称排序 —— 与 `FileManageViewModel.upFiles` 同规则）。
     *
     * @param path 相对根目录的路径（空白 = 根目录）
     * @throws NoSuchElementException 路径不存在
     * @throws IllegalArgumentException 路径越界或不是目录
     */
    suspend fun fileList(path: String = "", limit: Int = DEFAULT_FILE_LIMIT): Map<String, Any?> =
        withContext(IO) {
            val root = rootDir().canonicalFile
            val dir = resolveSafe(path)
            if (!dir.exists()) throw NoSuchElementException("路径不存在：$path")
            if (!dir.isDirectory) throw IllegalArgumentException("不是目录：$path")
            val children = (dir.listFiles() ?: emptyArray())
                .sortedWith(compareBy({ it.isFile }, { it.name }))
            val picked = if (limit > 0) children.take(limit) else children.toList()
            mapOf(
                "root" to root.absolutePath,
                "path" to relativeTo(root, dir),
                "total" to children.size,
                "returned" to picked.size,
                "files" to picked.map {
                    mapOf(
                        "name" to it.name,
                        "path" to relativeTo(root, it),
                        "isDir" to it.isDirectory,
                        "size" to sizeOf(it),
                        "lastModified" to it.lastModified(),
                    )
                },
            )
        }

    /**
     * `file_delete`：删除本地文件 / 目录（**逐项回告**，单项失败不影响其它项）。
     *
     * `paths` 为相对根目录的路径列表；批量（>3）端侧二次确认由**工具层**负责（见类注释「已知上限 3」）。
     */
    suspend fun fileDelete(paths: List<String>): Map<String, Any?> = withContext(IO) {
        require(paths.isNotEmpty()) { "paths 不能为空" }
        val root = rootDir().canonicalFile
        val results = paths.map { path ->
            try {
                val target = resolveSafe(path)
                when {
                    target.path == root.path ->
                        mapOf("path" to path, "deleted" to false, "reason" to "不允许删除根目录")

                    !target.exists() ->
                        mapOf("path" to path, "deleted" to false, "reason" to "不存在")

                    target.isDirectory -> {
                        val ok = target.deleteRecursively()
                        mapOf("path" to path, "deleted" to ok, "reason" to if (ok) null else "目录删除失败")
                    }

                    else -> {
                        val ok = target.delete()
                        mapOf("path" to path, "deleted" to ok, "reason" to if (ok) null else "删除失败")
                    }
                }
            } catch (e: IllegalArgumentException) {
                mapOf("path" to path, "deleted" to false, "reason" to e.message)
            }
        }
        mapOf(
            "total" to results.size,
            "deleted" to results.count { it["deleted"] == true },
            "results" to results,
        )
    }

    // ============================================================ 下载任务

    /** `download_list`：下载任务列表（Room 主存 + `DownloadState` 内存态**合并**，内存态优先）。 */
    suspend fun downloadList(): Map<String, Any?> = withContext(IO) {
        val entities = appDb.downloadTaskDao.loadAll()
        val memory = DownloadState.queryAllTaskStatus().associateBy { it.id }
        val items = entities.map { entity ->
            val mem = memory[entity.id]
            mapOf(
                "taskId" to entity.id,
                "fileName" to entity.fileName,
                "url" to DiagKernel.maskUrl(entity.url),
                "taskType" to entity.taskType,
                "status" to (mem?.status?.name ?: entity.status),
                "progress" to (mem?.progress ?: entity.progress),
                "totalSize" to (mem?.totalSize ?: entity.totalSize),
                "downloadedSize" to (mem?.downloadedSize ?: entity.downloadedSize),
                "speed" to (mem?.speed ?: entity.speed),
                "localPath" to (mem?.localPath ?: entity.localPath),
                "errorCode" to (mem?.errorCode ?: entity.errorCode),
                "startTime" to entity.startTime,
            )
        }
        mapOf("total" to items.size, "items" to items)
    }

    /**
     * `download_manage`：下载任务管理。
     *
     * `action` 取值：
     * - `start`：需 `url`（可选 `fileName`）⇒ 委派 `Download.start(appCtx, url, fileName)`；
     * - `pause`：需 `taskId` ⇒ 经 `IntentAction.pause` 派发给 `DownloadService`；
     * - `delete`：需 `taskId` ⇒ `DownloadState.removeTask` + `DownloadService.deleteTaskFiles`（清本地产物）。
     *
     * @throws IllegalArgumentException 入参缺失或 `action` 未知
     * @throws NoSuchElementException `delete` 时任务不存在
     */
    suspend fun downloadManage(
        action: String,
        taskId: Long = 0L,
        url: String? = null,
        fileName: String? = null,
    ): Map<String, Any?> = withContext(IO) {
        when (action.lowercase()) {
            "start" -> {
                require(!url.isNullOrBlank()) { "start 需提供 url" }
                Download.start(appCtx, url, fileName)
                mapOf(
                    "action" to "start",
                    "accepted" to true,
                    "url" to DiagKernel.maskUrl(url),
                    "fileName" to fileName.orEmpty(),
                )
            }

            "pause" -> {
                require(taskId > 0) { "pause 需提供 taskId" }
                appCtx.startService<DownloadService> {
                    this.action = IntentAction.pause
                    putExtra("downloadId", taskId)
                }
                mapOf("action" to "pause", "accepted" to true, "taskId" to taskId)
            }

            "delete" -> {
                require(taskId > 0) { "delete 需提供 taskId" }
                val entity = appDb.downloadTaskDao.loadById(taskId)
                val mem = DownloadState.queryAllTaskStatus().firstOrNull { it.id == taskId }
                if (entity == null && mem == null) {
                    throw NoSuchElementException("下载任务不存在：taskId=$taskId")
                }
                val taskType = mem?.taskType ?: entity?.let {
                    runCatching { DownloadTaskType.valueOf(it.taskType) }
                        .getOrDefault(DownloadTaskType.DIRECT)
                } ?: DownloadTaskType.DIRECT
                val localPath = mem?.localPath ?: entity?.localPath
                val name = mem?.fileName ?: entity?.fileName.orEmpty()
                DownloadState.removeTask(taskId)
                DownloadService.deleteTaskFiles(appCtx, taskType, localPath, name, taskId)
                mapOf("action" to "delete", "accepted" to true, "taskId" to taskId, "filesCleaned" to true)
            }

            else -> throw IllegalArgumentException("未知下载动作：$action（支持 start/pause/delete）")
        }
    }
}