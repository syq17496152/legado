package io.legado.app.service.kernel

import com.shuyu.gsyvideoplayer.GSYVideoManager
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.UrlRecord
import io.legado.app.help.CrashHandler
import io.legado.app.help.book.BookHelp
import io.legado.app.service.AudioPlayService
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.FileUtils
import io.legado.app.utils.externalCache
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

/**
 * ⑬ 诊断域（release 5）业务内核（web-mcp-productization 二期 · tasks 2.19 / 2.28）。
 *
 * 契约同 [BookKernel]：只返回结构化 Map、全链挂起、零 `runBlocking`、失败抛异常。
 *
 * 缓存类别与路径**与 `ui/book/cache/CacheManageViewModel` 一字不差**（同源）：
 * 书籍文本 / 视频（exoplayer）/ 音频（httpTTS）/ WebView（webview + hws_webview）。
 * 删除保护同款口径：视频播放中拒删视频目录、朗读/有声书运行中拒删音频目录。
 *
 * 输出安全：URL / 错误文本里的敏感 query 参数（token/key/secret/…）一律打码为 `***`
 * —— 本内核**不 import `web/utils/AuditSanitizer`**（G-22 禁止 Kernel 依赖 web 层），故自带等价实现。
 */
object DiagKernel {

    const val KIND_BOOKS = "books"
    const val KIND_VIDEO = "video"
    const val KIND_AUDIO = "audio"
    const val KIND_WEBVIEW = "webview"
    const val KIND_ALL = "all"

    private const val CRASH_DIR_NAME = "crash"
    private const val HEAP_DUMP_DIR_NAME = "heapDump"

    /** URL 敏感 query 参数键（打码用；不区分大小写）。 */
    private val SENSITIVE_KEYS = listOf(
        "token", "key", "apikey", "api_key", "password", "passwd", "secret", "auth", "sign", "signature", "cookie"
    )
    private val SENSITIVE_QUERY = Regex(
        "(?i)([?&](?:${SENSITIVE_KEYS.joinToString("|")})=)([^&#\\s]*)"
    )

    /** 打码 URL 中敏感 query 参数（保留路径与结构，便于 AI 判断请求走向）。 */
    fun maskUrl(raw: String?): String? = raw?.replace(SENSITIVE_QUERY, "$1***")

    private fun directorySize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length().coerceAtLeast(0L)
        return runCatching { file.listFiles()?.sumOf { directorySize(it) } ?: 0L }.getOrDefault(0L)
    }

    /** 某缓存类别的删除路径（与 `CacheManageViewModel.buildStorageBreakdown` 同源）。 */
    private fun cachePaths(kind: String): List<String> = when (kind) {
        KIND_BOOKS -> listOf(BookHelp.cachePath)
        KIND_VIDEO -> listOf(File(appCtx.externalCache, "exoplayer").absolutePath)
        KIND_AUDIO -> listOf(File(appCtx.cacheDir, "httpTTS").absolutePath)
        KIND_WEBVIEW -> listOf(
            appCtx.getDir("webview", android.content.Context.MODE_PRIVATE).absolutePath,
            appCtx.getDir("hws_webview", android.content.Context.MODE_PRIVATE).absolutePath,
        )
        else -> throw IllegalArgumentException("未知缓存类别：$kind（支持 books/video/audio/webview/all）")
    }

    /** 视频播放中（与 App 同判据）。 */
    private fun isVideoPlaying(): Boolean =
        runCatching { GSYVideoManager.instance()?.isPlaying() == true }.getOrDefault(false)

    /** 朗读 / 有声书运行中（音频维度删除保护）。 */
    private fun isAudioBusy(): Boolean = runCatching {
        BaseReadAloudService.isRun || AudioPlayService.isRun
    }.getOrDefault(false)

    /** `cache_stats` 的底层数据（⑯ 存储域复用；此处供 ⑬ 的 `cache_clear` 结果对照）。 */
    suspend fun cacheStats(): Map<String, Any?> = withContext(IO) {
        val kinds = listOf(KIND_BOOKS, KIND_VIDEO, KIND_AUDIO, KIND_WEBVIEW)
        val items = kinds.map { kind ->
            val bytes = cachePaths(kind).sumOf { directorySize(File(it)) }
            mapOf("kind" to kind, "bytes" to bytes, "paths" to cachePaths(kind))
        }
        mapOf("total" to items.sumOf { it["bytes"] as Long }, "items" to items)
    }

    /**
     * `cache_clear`：清理**可重建缓存**（白名单四类；不触碰用户数据 —— 书架/书源/记录均不在其列）。
     *
     * 播放/朗读中命中对应目录时**跳过并在 `skipped` 里如实回告**（不静默删）。
     */
    suspend fun cacheClear(kind: String = KIND_ALL): Map<String, Any?> = withContext(IO) {
        val kinds = if (kind == KIND_ALL) {
            listOf(KIND_BOOKS, KIND_VIDEO, KIND_AUDIO, KIND_WEBVIEW)
        } else {
            listOf(kind)
        }
        val cleared = mutableListOf<Map<String, Any?>>()
        val skipped = mutableListOf<String>()
        var freed = 0L
        kinds.forEach { k ->
            when {
                k == KIND_VIDEO && isVideoPlaying() -> skipped.add(k)
                k == KIND_AUDIO && isAudioBusy() -> skipped.add(k)
                else -> {
                    val paths = cachePaths(k)
                    val before = paths.sumOf { directorySize(File(it)) }
                    paths.forEach { FileUtils.delete(it, deleteRootDir = true) }
                    freed += before
                    cleared.add(mapOf("kind" to k, "freedBytes" to before, "paths" to paths))
                }
            }
        }
        mapOf(
            "cleared" to cleared,
            "skipped" to skipped,
            "freedBytes" to freed,
            // WebView 数据清完需重启才完全生效（与 App 内提示同口径）
            "needRestart" to (KIND_WEBVIEW in cleared.map { it["kind"] }),
        )
    }

    private fun UrlRecord.toMasked(): Map<String, Any?> = mapOf(
        "id" to id,
        "url" to maskUrl(url),
        "domain" to domain,
        "method" to method,
        "sourceName" to sourceName,
        "timestamp" to timestamp,
        "responseCode" to responseCode,
        "durationMs" to duration,
        "errorMsg" to maskUrl(errorMsg),
    )

    /**
     * `url_record_query`：查询 URL 访问记录（URL 敏感参数打码）。
     *
     * 口径：库侧按 `keyword/domain` 过滤（`UrlRecordDao` 既有方法），再在**输出侧**打码与截断。
     */
    suspend fun urlRecordQuery(
        keyword: String? = null,
        domain: String? = null,
        limit: Int = 200,
    ): Map<String, Any?> = withContext(IO) {
        val dao = appDb.urlRecordDao
        val raw: List<UrlRecord> = when {
            !keyword.isNullOrBlank() -> dao.search(keyword)
            !domain.isNullOrBlank() -> dao.getByDomain(domain)
            else -> dao.getAll()
        }
        val cap = if (limit > 0) limit else raw.size
        mapOf(
            "total" to dao.getCount(),
            "returned" to minOf(raw.size, cap),
            "records" to raw.take(cap).map { it.toMasked() },
        )
    }

    /** `url_record_clear`：按天数清理历史记录（`days <= 0` 表示清空全部）。 */
    suspend fun urlRecordClear(days: Int = 7): Map<String, Any?> = withContext(IO) {
        val dao = appDb.urlRecordDao
        val before = dao.getCount()
        val removed = if (days <= 0) {
            dao.deleteAll()
        } else {
            val cutoff = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000
            dao.deleteOldRecords(cutoff)
        }
        mapOf("before" to before, "removed" to removed, "days" to days)
    }

    /** `log_dump`：建立堆转储（写入 externalCache 下 heapDump 目录的 .hprof 文件，可手动触发）。 */
    suspend fun logDump(): Map<String, Any?> = withContext(IO) {
        val dir = File(appCtx.externalCache, HEAP_DUMP_DIR_NAME)
        val before = listFiles(dir).size
        CrashHandler.doHeapDump(manually = true)
        mapOf(
            "triggered" to true,
            "dir" to dir.absolutePath,
            "before" to before,
            // 堆转储为异步写盘，落盘后 `log_delete(heap)` 可清理
            "note" to "堆转储已触发（异步写盘）",
        )
    }

    private fun listFiles(dir: File): List<File> =
        runCatching { dir.listFiles()?.toList() ?: emptyList() }.getOrDefault(emptyList())

    /**
     * `log_delete`：删除日志。
     *
     * targets 取值：`app`（应用运行日志，`AppLog.clear()`）/ `crash`（崩溃日志文件）/
     * `heap`（堆转储文件）。多项可串联，逐项返回删除条数。
     */
    suspend fun logDelete(targets: List<String>): Map<String, Any?> = withContext(IO) {
        require(targets.isNotEmpty()) { "targets 不能为空" }
        val results = targets.map { target ->
            when (target) {
                "app" -> {
                    val removed = AppLog.logs.size
                    AppLog.clear()
                    mapOf("target" to target, "removed" to removed)
                }

                "crash" -> {
                    val dir = File(appCtx.externalCache, CRASH_DIR_NAME)
                    val removed = listFiles(dir).size
                    FileUtils.delete(dir.absolutePath, deleteRootDir = true)
                    mapOf("target" to target, "removed" to removed, "dir" to dir.absolutePath)
                }

                "heap" -> {
                    val dir = File(appCtx.externalCache, HEAP_DUMP_DIR_NAME)
                    val removed = listFiles(dir).size
                    FileUtils.delete(dir.absolutePath, deleteRootDir = true)
                    mapOf("target" to target, "removed" to removed, "dir" to dir.absolutePath)
                }

                else -> throw IllegalArgumentException("未知日志目标：$target（支持 app/crash/heap）")
            }
        }
        mapOf("deleted" to results)
    }
}