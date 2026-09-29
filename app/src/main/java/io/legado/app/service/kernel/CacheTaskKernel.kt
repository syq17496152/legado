package io.legado.app.service.kernel

import io.legado.app.help.AppCloudStorage
import io.legado.app.help.book.CacheCloudIndexItem
import io.legado.app.help.book.CacheCloudIndexStore
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

/**
 * ㉒ 缓存与任务域业务内核（web-mcp-productization 二期 · tasks 2.28）。
 *
 * 契约同 [BookKernel]：只返回领域对象 / 结构化 Map、全链挂起、零阻塞调用、失败抛异常。
 *
 * 数据源（全部为既有能力，不新增存储）：
 * - 云存储：`AppCloudStorage`（缓存包上传/下载/删除、备份、备份名列表）；
 * - 缓存索引：`CacheCloudIndexStore.readLocal(AppCloudStorage.cacheStorageKey())`（本地索引文件）。
 *
 * ## 边界声明
 * 本内核不做网络/凭据处理：云存储的后端选择与鉴权由 `AppCloudStorage` / 各 Backend 既有实现负责，
 * 本内核只编排既有入口；**不 import `web` 层**、不返回任何凭据。
 *
 * ## 已知上限 / 降级
 * 1. [cacheDownload] 命名沿用工具协议，实际执行 `AppCloudStorage.uploadCachePackage`（本地缓存包 → 云端上行）；
 *    下行（云端 → 本地）入口为 `downloadCachePackage`，已由 [syncRun] 的 `download` 分支调用。
 * 2. [syncList] 的本地索引输出侧**只取技术字段**（省略 `bookUrl` / `origin` / `originName` / `coverUrl` / `intro`
 *    / `latestChapterTitle` 等可能含来源域名或业务文本的字段）。
 * 3. [cacheExport] 与 [syncRun] 的 `restore` / `delete` 分支属**危险操作**（覆盖/清空/删除），危险属性由工具层标记。
 */
object CacheTaskKernel {

    private fun cachePackageDir(): File =
        File(appCtx.cacheDir, "cache_packages").apply { mkdirs() }

    /**
     * `cache_download`：上传本地缓存包到云端（`AppCloudStorage.uploadCachePackage`）。
     *
     * 命名沿用工具协议；`zipFile` 为本地缓存包文件（危险操作，属性由工具层标记）。
     */
    suspend fun cacheDownload(fileName: String, zipFile: File): Map<String, Any?> = withContext(IO) {
        require(fileName.isNotBlank()) { "fileName 不能为空" }
        require(zipFile.isFile) { "缓存包文件不存在：${zipFile.absolutePath}" }
        AppCloudStorage.uploadCachePackage(fileName, zipFile)
        mapOf("uploaded" to true, "fileName" to fileName, "bytes" to zipFile.length())
    }

    /**
     * `cache_export`：完整备份（`AppCloudStorage.backup`；`webDav=true` 时强制走 `backupToWebDav`）。
     *
     * 危险操作（覆盖同名云端备份），属性由工具层标记。
     */
    suspend fun cacheExport(fileName: String, webDav: Boolean = false): Map<String, Any?> = withContext(IO) {
        require(fileName.isNotBlank()) { "fileName 不能为空" }
        if (webDav) {
            AppCloudStorage.backupToWebDav(fileName)
        } else {
            AppCloudStorage.backup(fileName)
        }
        mapOf("exported" to true, "fileName" to fileName, "webDav" to webDav)
    }

    private fun CacheCloudIndexItem.toTechnicalMap(): Map<String, Any?> = mapOf(
        "cacheKey" to cacheKey,
        "name" to name,
        "author" to author,
        "type" to type,
        "totalChapterCount" to totalChapterCount,
        "cachedChapterCount" to cachedChapterCount,
        "zipFileName" to zipFileName,
        "updatedAt" to updatedAt,
    )

    /** `sync_list`：云端备份名列表 + 本地缓存索引（技术字段）。 */
    suspend fun syncList(): Map<String, Any?> = withContext(IO) {
        val backups = AppCloudStorage.getBackupNames()
        val storageKey = AppCloudStorage.cacheStorageKey()
        val indexItems = CacheCloudIndexStore.readLocal(storageKey)
        mapOf(
            "backupCount" to backups.size,
            "backups" to backups,
            "cacheIndexCount" to indexItems.size,
            "cacheIndex" to indexItems.map { it.toTechnicalMap() },
        )
    }

    /**
     * `sync_run`：缓存同步。
     *
     * `direction`：`download`（云端缓存包 → 本地临时目录，`downloadCachePackage`）/
     * `restore`（云端备份覆盖恢复，`restore`，危险）/ `delete`（删除云端缓存包，`deleteCachePackage`，危险）。
     */
    suspend fun syncRun(name: String, direction: String): Map<String, Any?> = withContext(IO) {
        require(name.isNotBlank()) { "name 不能为空" }
        when (direction) {
            "download" -> {
                val target = File(cachePackageDir(), File(name).name)
                AppCloudStorage.downloadCachePackage(name, target)
                mapOf(
                    "direction" to direction,
                    "name" to name,
                    "targetFile" to target.absolutePath,
                    "bytes" to target.length(),
                )
            }

            "restore" -> {
                AppCloudStorage.restore(name)
                mapOf("direction" to direction, "name" to name, "restored" to true)
            }

            "delete" -> {
                AppCloudStorage.deleteCachePackage(name)
                mapOf("direction" to direction, "name" to name, "deleted" to true)
            }

            else -> throw IllegalArgumentException(
                "未知 direction：$direction（支持 download/restore/delete）"
            )
        }
    }
}