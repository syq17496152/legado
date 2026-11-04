package io.legado.app.help.llm

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.util.UUID

/**
 * 2.5 大文本走文件（design AD-25）：Binder 只传 jobId 与路径，正文落盘。
 *
 * 布局：
 * - 请求正文：`files/llm_jobs/<jobId>.txt`
 * - 仲裁结果：`files/llm_out/<jobId>.json`（[LlmJobResult]，Gson）
 *
 * 三条硬约束：
 * - **jobId 白名单**：只允许 `[0-9a-f-]`。`:llm` 侧按 id 拼路径，不校验就是路径穿越；
 * - **原子落盘**：结果先写 `.part` 再 rename，读取方永远不会读到半截 JSON；
 * - **过期清理**：jobId 前缀是毫秒时间戳，超过 [RETENTION_MS] 才删；解析不出时间的一律跳过
 *   （宁可留垃圾文件，也不误删别人正在读的结果）。
 */
object LlmJobs {

    /** 10 分钟（AD-25：超过 10 分钟自动过期清理） */
    const val RETENTION_MS = 10 * 60 * 1000L

    private const val SAFE_ID_REGEX = "[0-9a-f-]{6,64}"

    private val gson by lazy { Gson() }

    fun jobsDir(context: Context): File = File(context.filesDir, "llm_jobs")

    fun outDir(context: Context): File = File(context.filesDir, "llm_out")

    /** jobId = `<now>-<uuid 前 8 位>`：前缀保证可判过期，后缀保证并发不撞号 */
    fun newJobId(now: Long = System.currentTimeMillis()): String =
        "$now-${UUID.randomUUID().toString().take(8)}"

    fun isSafeJobId(jobId: String): Boolean = jobId.matches(Regex(SAFE_ID_REGEX))

    fun inputFile(dir: File, jobId: String): File = File(dir, "$jobId.txt")

    fun outputFile(dir: File, jobId: String): File = File(dir, "$jobId.json")

    fun writeInput(dir: File, jobId: String, text: String): File {
        require(isSafeJobId(jobId)) { "非法 jobId" }
        dir.mkdirs()
        val file = inputFile(dir, jobId)
        file.writeText(text, Charsets.UTF_8)
        return file
    }

    /** 读取请求正文；非法 id / 文件缺失 / 读失败统一返回 null（调用方按"无输入"降级） */
    fun readInput(dir: File, jobId: String): String? {
        if (!isSafeJobId(jobId)) return null
        val file = inputFile(dir, jobId)
        return runCatching {
            if (file.isFile) file.readText(Charsets.UTF_8) else null
        }.getOrNull()
    }

    fun writeResult(dir: File, jobId: String, result: LlmJobResult): File {
        require(isSafeJobId(jobId)) { "非法 jobId" }
        dir.mkdirs()
        val target = outputFile(dir, jobId)
        val tmp = File(dir, "$jobId.json.part")
        tmp.writeText(gson.toJson(result), Charsets.UTF_8)
        if (target.exists()) target.delete()
        tmp.renameTo(target)
        return target
    }

    fun readResult(dir: File, jobId: String): LlmJobResult? {
        if (!isSafeJobId(jobId)) return null
        val file = outputFile(dir, jobId)
        if (!file.isFile) return null
        return runCatching {
            gson.fromJson(file.readText(Charsets.UTF_8), LlmJobResult::class.java)
        }.getOrNull()
    }

    /** jobId 前缀时间戳（毫秒）；不是本模块命名的文件返回 null */
    fun jobTimeOf(fileName: String): Long? =
        fileName.substringBefore('-').toLongOrNull()

    /**
     * 清理过期文件。
     * @return 实际删除的文件数
     */
    fun prune(
        jobsDir: File,
        outDir: File,
        now: Long = System.currentTimeMillis(),
        retentionMs: Long = RETENTION_MS
    ): Int {
        var removed = 0
        for (dir in listOf(jobsDir, outDir)) {
            dir.listFiles()?.forEach { file ->
                val created = jobTimeOf(file.name) ?: return@forEach
                if (now - created > retentionMs && file.delete()) removed++
            }
        }
        return removed
    }
}
