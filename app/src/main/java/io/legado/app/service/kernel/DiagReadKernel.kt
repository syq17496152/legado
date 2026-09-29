package io.legado.app.service.kernel

import android.os.Build
import android.os.SystemClock
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.help.CrashHandler
import io.legado.app.help.MemoryPressure
import io.legado.app.help.http.NetworkLog
import io.legado.app.utils.externalCache
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.util.Locale

/**
 * ⑬ 诊断读出层业务内核（web-mcp-productization 二期 · L3 调试读出，仅 debug 包消费）。
 *
 * 契约同 [DiagKernel]：**只返回结构化 Map**、全链挂起（`withContext(IO)`）、零 `runBlocking`、
 * 参数非法抛异常。本内核**不 import** `web` 层 / `api.controller` / `ReturnData`（门禁 G-22）。
 *
 * 读源（全部复用既有单源，不自建 Store）：
 * - 应用日志 [AppLog]（内存环形缓冲 `logs`，最新在前，单条已在写入侧截断）；
 * - 崩溃日志 [CrashHandler.readLatestCrashLog]（externalCache/crash 下最新一份）；
 * - 网络痕迹 [NetworkLog]（内存 `logs`；为空时退回读 externalCache/logs 下持久化文本）；
 * - 内存压力 [MemoryPressure] + `Runtime`。
 *
 * 输出安全：message / URL / 响应片段 / 崩溃全文一律经 [mask] 打码 ——
 * URL 敏感 query 走 [DiagKernel.maskUrl]，凭据（JSON 引号值 / 表单 / Bearer / Basic）走
 * [NetworkLog.redactCredentialsForLog]，二者串联后再按需截断；凭据原文**绝不回传**。
 *
 * 已知上限：`AppLog.LogEntry` 不保存模块 tag（tag 只在 logcat / 日志文件出现），
 * 故 `log_query` 条目的 `tag` 字段恒为 `null`；升级路径：写入侧扩展 `LogEntry` 增存 `tag`。
 */
object DiagReadKernel {

    const val DEFAULT_LOG_TAIL = 100
    const val DEFAULT_CRASH_LIMIT = 4000
    const val DEFAULT_TRACE_TAIL = 50
    const val DEFAULT_TRACE_LIMIT = 20000

    /** externalCache 下网络日志目录与文件名前缀（与 [NetworkLog.persist] 一字不差）。 */
    private const val NETWORK_LOG_DIR = "logs"
    private const val NETWORK_LOG_PREFIX = "network-log-"
    private const val NETWORK_LOG_SUFFIX = ".txt"

    /** 单次 `network_trace_query` 全部条目文本的字符总预算（自限，避免撑爆 1MB 出参信封）。 */
    private const val MAX_TRACE_CHARS = 200_000

    /**
     * 持久化网络日志的条目分隔点：每条以 `[HH:mm:ss.SSS] ` 起头（见 [NetworkLog.Entry.summary]）。
     * 已知上限：响应体正文若恰好出现同形行会被误切；仅作用于"内存为空"的降级读取路径。
     */
    private val NETWORK_ENTRY_SPLIT = Regex("(?m)(?=\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\])")

    /**
     * 打码：先按凭据模式脱敏（JSON 引号值 / 表单 / Bearer / Basic），再打码 URL 敏感 query。
     * 顺序无关紧要，串联覆盖两类泄漏面。
     */
    private fun mask(text: String): String =
        NetworkLog.redactCredentialsForLog(DiagKernel.maskUrl(text) ?: text)

    private fun truncate(text: String, maxChars: Int): String =
        if (maxChars <= 0 || text.length <= maxChars) {
            text
        } else {
            text.take(maxChars) + "...(已截断，总长${text.length}字符)"
        }

    /** 解析日志级别入参；省略 / 空串 → `null`（不过滤）；未知取值 → 抛异常（参数非法）。 */
    private fun parseLevel(raw: String?): AppLog.Level? {
        val value = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return when (value) {
            "error" -> AppLog.Level.ERROR
            "warn", "warning" -> AppLog.Level.WARN
            "info" -> AppLog.Level.INFO
            "debug" -> AppLog.Level.DEBUG
            else -> throw IllegalArgumentException("未知日志级别：$raw（支持 error/warn/info/debug）")
        }
    }

    /**
     * `log_query`：按级别 / 关键词 / 时间下限过滤应用日志，取**最近** [tail] 条（日志最新在前）。
     *
     * @param level 级别过滤：error/warn/info/debug（省略 = 不过滤）
     * @param keyword 关键词（对 message 不区分大小写；省略 = 不过滤）
     * @param since 时间下限（毫秒时间戳，`time >= since`；省略 = 不过滤）
     * @param tail 最多返回条数（≤0 = 不限）
     */
    suspend fun logQuery(
        level: String? = null,
        keyword: String? = null,
        since: Long? = null,
        tail: Int = DEFAULT_LOG_TAIL,
    ): Map<String, Any?> = withContext(IO) {
        val wanted = parseLevel(level)
        val key = keyword?.trim()?.takeIf { it.isNotEmpty() }
        val filtered = AppLog.logs.filter { entry ->
            (wanted == null || entry.level == wanted) &&
                (key == null || entry.message.contains(key, ignoreCase = true)) &&
                (since == null || entry.time >= since)
        }
        val cap = if (tail > 0) tail else filtered.size
        val picked = filtered.take(cap)
        mapOf(
            "total" to filtered.size,
            "returned" to picked.size,
            "entries" to picked.map { entry ->
                mapOf(
                    "time" to entry.time,
                    "level" to entry.level.name.lowercase(Locale.ROOT),
                    "message" to mask(entry.message),
                    // AppLog.LogEntry 不保存模块 tag（tag 只在 logcat / 日志文件），此处恒为 null
                    "tag" to null,
                )
            },
        )
    }

    /**
     * `crash_query`：读取最近一次崩溃日志全文（[CrashHandler.readLatestCrashLog]，只读静态方法，
     * **不构造 `CrashHandler(context)`** —— 构造会注册全局崩溃处理器）。
     *
     * @param limit 返回文本最大字符数（≤0 = 不限）；超限截断并置 `truncated = true`
     */
    suspend fun crashQuery(limit: Int = DEFAULT_CRASH_LIMIT): Map<String, Any?> = withContext(IO) {
        val full = CrashHandler.readLatestCrashLog()
        if (full.isNullOrBlank()) {
            mapOf("found" to false, "length" to 0, "truncated" to false, "log" to "")
        } else {
            val truncated = limit > 0 && full.length > limit
            mapOf(
                "found" to true,
                "length" to full.length,
                "truncated" to truncated,
                "log" to mask(if (truncated) full.take(limit) else full),
            )
        }
    }

    /** 关键词命中判定（对条目可见字段做不区分大小写匹配；未给关键词则全通过）。 */
    private fun matches(entry: NetworkLog.Entry, key: String?): Boolean {
        if (key == null) return true
        return entry.url.contains(key, true) ||
            entry.source.contains(key, true) ||
            entry.type.contains(key, true) ||
            entry.method.contains(key, true) ||
            entry.statusCode?.toString()?.contains(key) == true ||
            entry.responseBody?.contains(key, true) == true ||
            entry.error?.contains(key, true) == true
    }

    /** 持久化日志降级读取：externalCache/logs 下最新的 network-log 文本，按条目切块后取**末尾** [tail] 条。 */
    private fun fileTrace(key: String?, tail: Int, charCap: Int): Map<String, Any?> {
        val file = latestNetworkLogFile()
        if (file == null) {
            return mapOf(
                "enabled" to NetworkLog.isEnabled,
                "source" to "file",
                "total" to 0,
                "returned" to 0,
                "entries" to emptyList<Any>(),
                "note" to "内存网络日志为空，且 externalCache/logs 下无持久化日志文件（多为 recordNetworkLog 未开启）",
            )
        }
        val text = kotlin.runCatching { file.readText() }.getOrDefault("")
        val blocks = text.split(NETWORK_ENTRY_SPLIT)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { key == null || it.contains(key, true) }
        val cap = if (tail > 0) tail else blocks.size
        val picked = blocks.takeLast(cap)
        val entries = picked.map { mapOf("text" to truncate(mask(it), charCap)) }
        return mapOf(
            "enabled" to NetworkLog.isEnabled,
            "source" to "file",
            "file" to file.name,
            "total" to blocks.size,
            "returned" to entries.size,
            "entries" to entries,
        )
    }

    private fun latestNetworkLogFile(): File? {
        val dir = File(appCtx.externalCache, NETWORK_LOG_DIR)
        return kotlin.runCatching {
            dir.listFiles()
                ?.filter { it.name.startsWith(NETWORK_LOG_PREFIX) && it.name.endsWith(NETWORK_LOG_SUFFIX) }
                ?.maxByOrNull { it.lastModified() }
        }.getOrNull()
    }

    /**
     * `network_trace_query`：查询网络痕迹（优先内存 [NetworkLog.logs]；为空退回持久化文件，见 [fileTrace]）。
     *
     * 输出打码：URL 与响应片段 / 错误文本的敏感参数一律打码；请求/响应头**不回传**（减小载荷，
     * 且头部凭据已在记录侧脱敏，需要时可由 App 内网络日志界面查看）。
     *
     * @param keyword 关键词过滤（url / 来源 / 类型 / 方法 / 状态码 / 响应片段 / 错误，省略 = 不过滤）
     * @param tail 最多返回条数（≤0 = 不限）
     * @param limit 单条文本最大字符数（≤0 = 不限）；另受 [MAX_TRACE_CHARS] 总预算约束
     */
    suspend fun networkTraceQuery(
        keyword: String? = null,
        tail: Int = DEFAULT_TRACE_TAIL,
        limit: Int = DEFAULT_TRACE_LIMIT,
    ): Map<String, Any?> = withContext(IO) {
        val key = keyword?.trim()?.takeIf { it.isNotEmpty() }
        val perEntryCap = if (limit > 0) limit else Int.MAX_VALUE
        val memory = NetworkLog.logs
        if (memory.isEmpty()) {
            fileTrace(key, tail, perEntryCap)
        } else {
            val filtered = memory.filter { matches(it, key) }
            val cap = if (tail > 0) tail else filtered.size
            val entries = mutableListOf<Map<String, Any?>>()
            var budget = MAX_TRACE_CHARS
            var budgetExhausted = false
            for (entry in filtered.take(cap)) {
                if (budget <= 0) {
                    budgetExhausted = true
                    break
                }
                val textCap = minOf(perEntryCap, budget)
                val url = mask(entry.url)
                val body = truncate(mask(entry.responseBody.orEmpty()), textCap)
                val error = truncate(mask(entry.error.orEmpty()), textCap)
                entries.add(
                    mapOf(
                        "id" to entry.id,
                        "time" to entry.time,
                        "source" to entry.source,
                        "type" to entry.type,
                        "method" to entry.method,
                        "url" to url,
                        "statusCode" to entry.statusCode,
                        "tookMs" to entry.tookMs,
                        "responseBody" to body,
                        "error" to error,
                    )
                )
                budget -= url.length + body.length + error.length
            }
            mapOf(
                "enabled" to NetworkLog.isEnabled,
                "source" to "memory",
                "total" to filtered.size,
                "returned" to entries.size,
                "truncated" to budgetExhausted,
                "entries" to entries,
            )
        }
    }

    /** 设备信息（`android.os.Build` + 应用包信息；不含设备标识符，避免隐私面外泄）。 */
    private fun deviceInfo(): Map<String, Any?> = kotlin.runCatching {
        val info = AppConst.appInfo
        mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "androidRelease" to Build.VERSION.RELEASE,
            "sdkInt" to Build.VERSION.SDK_INT,
            "packageName" to appCtx.packageName,
            "versionName" to info.versionName,
            "versionCode" to info.versionCode,
            "availableProcessors" to Runtime.getRuntime().availableProcessors(),
        )
    }.getOrElse { error ->
        mapOf("error" to "设备信息读取失败：${error.localizedMessage}")
    }

    /** 性能指标（与 `perf_metrics_get` 同口径 + [MemoryPressure] 三级阈值判定）。 */
    private fun perfMetrics(): Map<String, Any?> = kotlin.runCatching {
        val runtime = Runtime.getRuntime()
        mapOf(
            "source" to "Runtime + MemoryPressure（阈值与 App 同源）",
            "heapUsed" to runtime.totalMemory() - runtime.freeMemory(),
            "heapTotal" to runtime.totalMemory(),
            "heapMax" to runtime.maxMemory(),
            "availableMemory" to MemoryPressure.availableMemory(),
            "isSmallHeap" to MemoryPressure.isSmallHeap,
            "shouldTrimNow" to MemoryPressure.shouldTrimNow(),
            "uptimeMs" to SystemClock.elapsedRealtime(),
        )
    }.getOrElse { error ->
        mapOf("error" to "性能指标读取失败：${error.localizedMessage}")
    }

    /**
     * `diagnostics_download`：组装诊断包**结构化清单**（applog 摘要 / crash 摘要 / device / perf），
     * 逐段说明来源与字节数。
     *
     * **不落盘、不回传全文**：正文分别由 `log_query` / `crash_query` / `network_trace_query` 按需取，
     * 故本方法输出恒为小载荷；若后续确需落盘，落 `appCtx.cacheDir` 下临时文件并在清单里返回路径。
     */
    suspend fun diagnosticsDownload(): Map<String, Any?> = withContext(IO) {
        val appLogs = AppLog.logs
        val crash = CrashHandler.readLatestCrashLog()
        mapOf(
            "appLog" to mapOf(
                "source" to "AppLog.logs（内存环形缓冲，最新在前）",
                "entries" to appLogs.size,
                "bytes" to appLogs.sumOf { it.message.length.toLong() },
            ),
            "crash" to mapOf(
                "source" to "CrashHandler.readLatestCrashLog（externalCache/crash 下最新 .log）",
                "found" to !crash.isNullOrBlank(),
                "bytes" to (crash?.length?.toLong() ?: 0L),
            ),
            "networkTrace" to mapOf(
                "source" to "NetworkLog.logs（内存）+ externalCache/logs 持久化文件",
                "enabled" to NetworkLog.isEnabled,
                "entries" to NetworkLog.logs.size,
            ),
            "device" to deviceInfo(),
            "perf" to perfMetrics(),
            "notes" to listOf(
                "本工具只返回来源与字节数清单，不落盘、不回传全文",
                "取正文请分别调用 log_query / crash_query / network_trace_query",
                "URL 与凭据类敏感参数已在各段输出侧打码",
            ),
        )
    }
}