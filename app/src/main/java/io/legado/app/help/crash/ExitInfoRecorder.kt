package io.legado.app.help.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import io.legado.app.constant.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * crash-hardening AD-06：崩溃/ANR 取证双通道里的"应用侧通道"。
 *
 * 为什么需要：设备不可 root、`logcat` 主缓冲仅 2MiB（实测只能留 2~4 分钟）、
 * `/data/anr` 与 `/data/tombstones` 只在系统侧且崩溃后很快轮转。
 * `ApplicationExitInfo`（API 30+）能在下次冷启动时告诉我们"上次是怎么死的"，
 * 这里把它连同 trace 摘要转存到应用自有目录，形成不依赖 adb 的取证链。
 *
 * 约束（design §8.2 / R5.5）：
 * - 只读系统记录，不写用户业务数据；trace 截断 256KB；
 * - 异步执行，失败静默（不得影响启动）；
 * - API < 30 设备写一条 unsupported 标记，保留 Java 崩溃文件通道。
 */
object ExitInfoRecorder {

    private const val DIR_NAME = "crash"
    private const val MAX_TRACE_BYTES = 256 * 1024
    private const val MAX_KEEP = 10
    private const val MAX_EXIT_RECORDS = 32
    private val RETAIN_DAYS = TimeUnit.DAYS.toMillis(7)

    /** 关心（且可解释）的退出原因：被信号杀 / 崩溃 / native 崩溃 / ANR / 初始化失败 / 低内存 */
    private val REASONS = setOf(
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_LOW_MEMORY
    )

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /** 本次冷启动转存到的异常退出条数（供启动提示判断"上次是否异常退出"） */
    @Volatile
    private var lastRecordedCount = 0

    /** 消费式读取：只在首次调用返回 true，避免主页重建时反复弹"检测到崩溃" */
    fun consumeRecentCrashOnStartup(): Boolean {
        val recent = lastRecordedCount > 0
        lastRecordedCount = 0
        return recent
    }

    fun dir(context: Context = appCtx): File =
        File(context.externalCacheDir ?: context.cacheDir, DIR_NAME).apply { mkdirs() }

    /**
     * 冷启动转存。返回本次新写入的条数（0 也可能表示"没有新的异常退出"）。
     */
    suspend fun recordOnStartup(context: Context = appCtx): Int = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            writeUnsupported(context)
            lastRecordedCount = 0
            return@withContext 0
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return@withContext 0
        // API 30：getHistoricalProcessExitReasons(packageName, userId, maxNum)（不是 ...ExitInfos）
        val exits = kotlin.runCatching {
            am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_RECORDS)
        }
            .onFailure { AppLog.put("读取 HistoricalProcessExitReasons 失败", it) }
            .getOrNull()
            .orEmpty()
        var written = 0
        exits.asSequence()
            .filter { it.reason in REASONS }
            .filter { it.processName.startsWith(context.packageName) }
            .forEach { info ->
                val target = File(dir(context), fileNameOf(info))
                if (target.exists()) return@forEach
                val text = kotlin.runCatching { buildText(context, info) }.getOrNull() ?: return@forEach
                if (kotlin.runCatching { target.writeText(text) }.isSuccess) written++
            }
        prune(context)
        lastRecordedCount = written
        written
    }

    /** 最近 [withinMs] 内 `:llm` 推理进程的异常退出次数（供熔断判定，AD-07） */
    fun recentInferenceDeaths(
        context: Context = appCtx,
        withinMs: Long = TimeUnit.MINUTES.toMillis(10),
        now: Long = System.currentTimeMillis()
    ): Int = kotlin.runCatching {
        countDeaths(dir(context).listFiles()?.toList().orEmpty(), now, withinMs, INFERENCE_PROCESS_SUFFIX)
    }.getOrDefault(0)

    /** 纯函数：统计窗口内指定进程后缀的死亡条数（可单测） */
    internal fun countDeaths(
        files: List<File>,
        now: Long,
        withinMs: Long,
        processSuffix: String
    ): Int = files.count { file ->
        if (!file.name.startsWith(FILE_PREFIX)) return@count false
        val ts = file.name.removePrefix(FILE_PREFIX).substringBefore('-').toLongOrNull()
            ?: return@count false
        now - ts <= withinMs && file.readLines().any {
            it.startsWith("process=") && it.contains(processSuffix)
        }
    }

    /** 导出全部取证文件到指定目录（本地，不上传） */
    fun exportAll(context: Context, targetDir: File): Int {
        val target = targetDir.apply { mkdirs() }
        var count = 0
        dir(context).listFiles()?.forEach { file ->
            if (file.isFile && kotlin.runCatching { file.copyTo(File(target, file.name), true) }.isSuccess) {
                count++
            }
        }
        return count
    }

    private fun buildText(context: Context, info: ApplicationExitInfo): String = buildString {
        appendLine("package=${context.packageName}")
        appendLine("process=${info.processName}")
        appendLine("timestamp=${timeFormat.format(Date(info.timestamp))} (${info.timestamp})")
        appendLine("reason=${reasonName(info.reason)}(${info.reason})")
        appendLine("pid=${info.pid}")
        appendLine("importance=${info.importance}")
        appendLine("pssKb=${info.pss}")
        appendLine("rssKb=${info.rss}")
        appendLine("description=${info.description ?: ""}")
        appendLine("status=${info.status}")
        val trace = readTrace(info)
        if (trace.isNotBlank()) {
            appendLine("----- trace (max ${MAX_TRACE_BYTES} bytes) -----")
            append(trace)
        }
    }

    private fun readTrace(info: ApplicationExitInfo): String = kotlin.runCatching {
        info.traceInputStream?.use { input ->
            val buffer = ByteArray(MAX_TRACE_BYTES)
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n <= 0) break
                read += n
            }
            if (read <= 0) "" else String(buffer, 0, read)
        } ?: ""
    }.getOrDefault("")

    private fun fileNameOf(info: ApplicationExitInfo): String =
        exitFileName(info.timestamp, info.pid)

    /** 纯函数：取证文件名（可单测） */
    internal fun exitFileName(timestamp: Long, pid: Int): String =
        "$FILE_PREFIX$timestamp-$pid.txt"

    private fun reasonName(reason: Int): String = reasonLabel(reason)

    /** 纯函数：退出原因可读名（可单测） */
    internal fun reasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        else -> "UNKNOWN"
    }

    /** 环形保留最近 [MAX_KEEP] 条 + 超过 [RETAIN_DAYS] 清理（不影响 Java 崩溃文件） */
    private fun prune(context: Context) {
        kotlin.runCatching {
            val files = dir(context).listFiles()
                ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) }
                ?.sortedByDescending { it.lastModified() }
                ?: return
            val deadline = System.currentTimeMillis() - RETAIN_DAYS
            files.forEachIndexed { index, file ->
                if (index >= MAX_KEEP || file.lastModified() < deadline) file.delete()
            }
        }
    }

    private fun writeUnsupported(context: Context) {
        kotlin.runCatching {
            File(dir(context), "exit-unsupported.txt")
                .takeIf { !it.exists() }
                ?.writeText("sdk=${Build.VERSION.SDK_INT} 不支持 ApplicationExitInfo，仅保留 Java 崩溃文件\n")
        }
    }

    private const val FILE_PREFIX = "exit-"
    private const val INFERENCE_PROCESS_SUFFIX = ":llm"
}
