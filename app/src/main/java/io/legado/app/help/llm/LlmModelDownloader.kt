package io.legado.app.help.llm

import android.content.Context
import cn.hutool.crypto.digest.DigestUtil
import io.legado.app.constant.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 2.4 / 4.10 端侧 LLM 下载器（公共资源二次下载）。
 *
 * 与 TTS 模型下载（`BuiltinModelManager.download`）同一套治理口径：
 * - `.part` + `.part.url`：**只有同一个源**的残片才能续传（换源续上必坏）；
 * - `Range` + 206 才追加，服务端忽略 Range 返回 200 则从头写；
 * - 416（残片超出服务端长度）⇒ 残片作废重下；
 * - 下载完成先校验大小与 sha256，通过才原子 rename 成 `model.gguf`，坏文件绝不进清单。
 *
 * 与 TTS 的差异：模型是**单文件**，不需要解包；落点必须是
 * `files/models/llm/<id>/model.gguf`（`LlmModelManager.scan` 的唯一识别路径）。
 */
object LlmModelDownloader {

    data class Progress(val bytes: Long, val total: Long) {
        /** 0~100；总长未知（服务端未给 Content-Length）时为 0，UI 应只显示已下载量 */
        val percent: Int get() = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0
    }

    sealed class Result {
        data class Success(val file: File, val bytes: Long) : Result()

        /** 可执行失败原因（网络 / 校验 / 空间），不吞成"成功" */
        data class Failure(val message: String) : Result()
    }

    private const val PART_SUFFIX = ".part"
    private const val PART_URL_SUFFIX = ".part.url"
    private const val MODEL_FILE = "model.gguf"
    private const val DISPLAY_NAME_FILE = "display_name.txt"

    suspend fun download(
        context: Context,
        profile: LlmModelCatalog.Profile,
        onProgress: (Progress) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        if (!profile.downloadable) {
            return@withContext Result.Failure("该档位不支持二次下载（需要自备 .gguf 导入）")
        }
        val targetDir = File(context.filesDir, "models/llm/${profile.id}")
        val target = File(targetDir, MODEL_FILE)
        // 已装且校验通过 ⇒ 幂等成功（重复点下载不会做无用功）
        if (target.isFile && target.length() == profile.sizeBytes && sha256(target) == profile.sha256) {
            return@withContext Result.Success(target, target.length())
        }
        targetDir.mkdirs()
        val part = File(targetDir, MODEL_FILE + PART_SUFFIX)
        val partUrl = File(targetDir, MODEL_FILE + PART_URL_SUFFIX)
        val url = profile.sourceUrl
        val offset = resumeOffset(
            partLength = if (part.isFile) part.length() else 0L,
            savedUrl = partUrl.takeIf { it.isFile }?.readText(),
            url = url
        )
        if (offset == 0L) {
            part.delete()
            partUrl.delete()
        }
        val result = runCatching {
            var failure: String? = null
            val builder = Request.Builder().url(url)
            if (offset > 0) builder.header("Range", "bytes=$offset-")
            client.newCall(builder.build()).execute().use { response ->
                when {
                    response.code == 416 -> {
                        part.delete()
                        partUrl.delete()
                        failure = "下载源与残片不一致（HTTP 416），已清理，请重试"
                    }
                    !response.isSuccessful -> failure = "下载失败：HTTP ${response.code}"
                    else -> {
                        val body = response.body
                        val appending = offset > 0 && response.code == 206
                        val remaining = body.contentLength()
                        val total = if (appending) offset + remaining else remaining
                        partUrl.writeText(url)
                        var written = if (appending) offset else 0L
                        onProgress(Progress(written, total))
                        body.byteStream().use { stream ->
                            FileOutputStream(part, appending).use { out ->
                                copyWithProgress(stream, out) { read ->
                                    written += read
                                    onProgress(Progress(written, total))
                                }
                            }
                        }
                    }
                }
            }
            failure?.let { Result.Failure(it) } ?: verify(profile, part)
        }.getOrElse { e ->
            AppLog.put("LLM 模型下载异常：${profile.id}", e)
            Result.Failure("下载异常：${e.message ?: "未知原因"}")
        }
        if (result is Result.Failure) {
            // 保留 .part 供下次续传；但校验失败（内容已坏）必须删掉，否则续传永远坏
            if (result.message.startsWith("校验失败")) {
                part.delete()
                partUrl.delete()
            }
            return@withContext result
        }
        target.delete()
        val renamed = part.renameTo(target)
        if (!renamed) return@withContext Result.Failure("落盘失败：无法重命名模型文件")
        partUrl.delete()
        File(targetDir, DISPLAY_NAME_FILE).writeText(profile.title.ifBlank { profile.id })
        AppLog.put("LLM 模型下载完成：${profile.id} ${target.length()}B")
        Result.Success(target, target.length())
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** 校验大小 + sha256（sha256 空则只校验大小，但 downloadable 已保证非空） */
    private fun verify(profile: LlmModelCatalog.Profile, file: File): Result {
        if (!file.isFile) return Result.Failure("下载未产生文件")
        if (profile.sizeBytes > 0 && file.length() != profile.sizeBytes) {
            return Result.Failure("校验失败：大小 ${file.length()} ≠ ${profile.sizeBytes}")
        }
        val actual = sha256(file)
        if (actual != profile.sha256) {
            return Result.Failure("校验失败：sha256 不一致，文件可能损坏")
        }
        return Result.Success(file, file.length())
    }

    private fun sha256(file: File): String = runCatching {
        file.inputStream().use { DigestUtil.digester("SHA-256").digestHex(it) }
    }.getOrDefault("")

    /**
     * 断点续传判据（纯函数）：长度 >0 **且** 记录的是同一个 URL 才可续；
     * 换源/空白记录一律从 0 开始（否则会把不同源的残片拼成坏文件）。
     */
    fun resumeOffset(partLength: Long, savedUrl: String?, url: String): Long =
        if (partLength > 0 && savedUrl?.trim() == url.trim()) partLength else 0L

    private inline fun copyWithProgress(
        input: InputStream,
        output: FileOutputStream,
        onChunk: (Int) -> Unit
    ) {
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            onChunk(read)
        }
    }
}
