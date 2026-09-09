package io.legado.app.help.readaloud.prebuild

import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.readaloud.script.TtsScriptEngineClient
import io.legado.app.model.analyzeRule.AnalyzeUrl
import kotlinx.coroutines.currentCoroutineContext
import java.io.File

/**
 * 批量端合成纯函数（§3.7.1-5，契约 6）：
 * - 进度无关、不读播放状态（synthesizeToFile(book, chapterIndex, unitText, engineParams) 语义落点）
 * - 服务状态剥离：无 downloadErrorNo 累计（重试由 TtsPrebuildManager 负责）、无 speechRate 实例字段（参数注入）、
 *   错误只返回值不落静音产物（防批量端把无声固化进保留名单）
 * - script 引擎全链封装：synthesize 描述符 → AnalyzeUrl 请求转换 → 取流（对调用方透明）
 * - 播放端合成链（HttpReadAloudService.getEngineSpeakStream）保留原状：错误恢复三分类/累计计数语义为播放专属，
 *   播放端迁移登记后续；两端共享 TtsCacheKeys 键单源（键才是命中判据）
 */
object TtsSynthesizer {

    /** 合成结果：错误只返回值（不落静音产物） */
    sealed class Result {
        data class Success(val file: File) : Result()
        data class Failure(val reason: String, val retryable: Boolean) : Result()
    }

    /**
     * 合成单元文本并原子落盘（temp+rename，契约 5）
     * @param engineParams 语速（播放链 speechRate 同域注入）
     * @param voiceId 音色 id（toneID，空=引擎默认）
     */
    suspend fun synthesizeToFile(
        httpTts: HttpTTS,
        unitText: String,
        voiceKey: String,
        speechRate: Int,
        targetFile: File
    ): Result {
        return try {
            val stream = if (httpTts.type == 2) {
                val request = TtsScriptEngineClient.synthesize(
                    httpTts,
                    unitText,
                    voiceKey.ifBlank { null },
                    null, null, null
                )
                if (request.url.isBlank()) {
                    return Result.Failure("脚本 synthesize 返回空 url", retryable = false)
                }
                val optionJson = org.json.JSONObject().apply {
                    put("method", request.method)
                    if (request.headers.isNotEmpty()) {
                        put("headers", org.json.JSONObject(request.headers))
                    }
                    request.body?.let { put("body", it) }
                }.toString()
                AnalyzeUrl(
                    request.url + "," + optionJson,
                    speakText = unitText,
                    speakSpeed = speechRate,
                    source = httpTts,
                    readTimeout = 120_000L,
                    coroutineContext = currentCoroutineContext()
                ).getResponseAwait().body.byteStream()
            } else {
                AnalyzeUrl(
                    httpTts.url,
                    speakText = unitText,
                    speakSpeed = speechRate,
                    source = httpTts,
                    readTimeout = 120_000L,
                    coroutineContext = currentCoroutineContext()
                ).getResponseAwait().body.byteStream()
            }
            // 单一原子提交：temp + rename（防播放端读到半成品，契约 5）
            val temp = File(targetFile.parentFile, targetFile.name + ".part")
            temp.outputStream().use { out ->
                stream.use { it.copyTo(out) }
            }
            if (temp.length() <= 0L) {
                temp.delete()
                return Result.Failure("合成产物为空", retryable = true)
            }
            if (!temp.renameTo(targetFile)) {
                temp.copyTo(targetFile, overwrite = true)
                temp.delete()
            }
            Result.Success(targetFile)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            Result.Failure("合成超时：${e.message}", retryable = true)
        } catch (e: java.io.IOException) {
            Result.Failure("IO 错误：${e.message}", retryable = false)
        } catch (e: Exception) {
            Result.Failure("合成失败：${e.message}", retryable = true)
        }
    }
}
