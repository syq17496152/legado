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
        var temp: File? = null
        try {
            val response: okhttp3.Response
            val stream: java.io.InputStream = if (httpTts.type == 2) {
                val request = TtsScriptEngineClient.synthesize(
                    httpTts,
                    unitText,
                    voiceKey.ifBlank { null },
                    // P1-14 修复：全局语速送达脚本引擎（倍率=speechRate/10，与播放链同口径）
                    speechRate / 10f, null, null
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
                response = AnalyzeUrl(
                    request.url + "," + optionJson,
                    speakText = unitText,
                    speakSpeed = speechRate,
                    source = httpTts,
                    readTimeout = 120_000L,
                    coroutineContext = currentCoroutineContext()
                ).getResponseAwait()
                response.body.byteStream()
            } else {
                response = AnalyzeUrl(
                    httpTts.url,
                    speakText = unitText,
                    speakSpeed = speechRate,
                    source = httpTts,
                    readTimeout = 120_000L,
                    coroutineContext = currentCoroutineContext()
                ).getResponseAwait()
                response.body.byteStream()
            }
            // P1-4 修复：响应校验（对齐播放端 getSpeakStream）——JSON/text 错误页不得当音频落盘
            //（否则错误内容固化进保留名单形成缓存毒化）；脚本引擎 loginCheckJs 恢复链登记后续
            val contentType = response.headers["Content-Type"]?.substringBefore(";")?.trim()
            if (contentType == "application/json" || contentType?.startsWith("text/") == true) {
                runCatching { response.body.close() }
                return Result.Failure("合成返回错误页（$contentType）", retryable = true)
            }
            // 单一原子提交：temp + rename（防播放端读到半成品，契约 5）；失败/取消路径统一清理 .part（P2-15）
            val t = File(targetFile.parentFile, targetFile.name + ".part")
            temp = t
            t.outputStream().use { out ->
                stream.use { it.copyTo(out) }
            }
            if (t.length() <= 0L) {
                t.delete()
                return Result.Failure("合成产物为空", retryable = true)
            }
            if (!t.renameTo(targetFile)) {
                t.copyTo(targetFile, overwrite = true)
                t.delete()
            }
            temp = null
            return Result.Success(targetFile)
        } catch (e: kotlinx.coroutines.CancellationException) {
            temp?.let { runCatching { it.delete() } }
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            temp?.let { runCatching { it.delete() } }
            return Result.Failure("合成超时：${e.message}", retryable = true)
        } catch (e: java.io.IOException) {
            temp?.let { runCatching { it.delete() } }
            return Result.Failure("IO 错误：${e.message}", retryable = false)
        } catch (e: Exception) {
            temp?.let { runCatching { it.delete() } }
            return Result.Failure("合成失败：${e.message}", retryable = true)
        }
    }
}
