package io.legado.app.help.readaloud.http

import android.content.Context
import com.script.ScriptException
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.toastOnUi
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Response
import org.mozilla.javascript.WrappedException

/**
 * HTTP TTS 取流器（从 `HttpReadAloudService` 抽出，供 G-38 行数门禁达标 + T8.6 段级换声复用）。
 *
 * 保持不变的行为（抽离时逐行对照原实现）：
 * - URL 模板 / 脚本引擎两套入口同一个取流面；
 * - 连续错误 >5 次抛错（交给服务侧暂停朗读）；
 * - 非超时类错误 ⇒ 返回 null + 每会话仅一次"本段静音替代"提示（`silentFallbackToasted`）。
 *
 * 新增（T8.6）：`toneID` 透传 —— URL 模板可用 `{{currentToneID}}`，脚本引擎直接收 toneID，
 * 从而让同一段正文在不同角色下走不同音色，而不是恒用全局路由音色。
 */
class HttpTtsStreamFetcher(private val context: Context) {

    /** 连续失败计数（成功即清零） */
    var downloadErrorNo: Int = 0
        private set

    /** 静音替代提示去重（服务会话级，一次朗读会话至多提示一次） */
    private var silentFallbackToasted = false

    fun resetSession() {
        downloadErrorNo = 0
        silentFallbackToasted = false
    }

    /**
     * 取合成音频流。
     * @param toneID 段级音色（空 = 沿用全局路由音色）
     */
    suspend fun fetch(
        httpTts: HttpTTS,
        speakText: String,
        speechRate: Int,
        toneID: String = ""
    ): InputStream? {
        while (true) {
            try {
                val analyzeUrl = AnalyzeUrl(
                    httpTts.url,
                    speakText = speakText,
                    speakSpeed = speechRate,
                    source = httpTts,
                    readTimeout = 300 * 1000L,
                    coroutineContext = currentCoroutineContext(),
                    // T8.6：段级音色（URL 模板 {{currentToneID}} / 脚本 JS currentToneID）
                    currentToneID = toneID.ifBlank { null },
                )
                val checkJs = httpTts.loginCheckJs
                val response = kotlin.runCatching {
                    analyzeUrl.getResponseAwait().let {
                        currentCoroutineContext().ensureActive()
                        if (!checkJs.isNullOrBlank()) {
                            analyzeUrl.evalJS(checkJs, it) as Response
                        } else {
                            it
                        }
                    }
                }.getOrElse { throwable ->
                    currentCoroutineContext().ensureActive()
                    if (!checkJs.isNullOrBlank()) {
                        val errResponse = analyzeUrl.getErrResponse(throwable)
                        try {
                            (analyzeUrl.evalJS(checkJs, errResponse) as Response).also {
                                if (it.code == 500) {
                                    throw throwable
                                }
                            }
                        } catch (_: Throwable) {
                            throw throwable
                        }
                    } else {
                        throw throwable
                    }
                }
                response.headers["Content-Type"]?.let { contentType ->
                    val contentType = contentType.substringBefore(";")
                    val ct = httpTts.contentType
                    if (contentType == "application/json" || contentType.startsWith("text/")) {
                        throw NoStackTraceException(response.body.string())
                    } else if (ct?.isNotBlank() == true) {
                        if (!contentType.matches(ct.toRegex())) {
                            throw NoStackTraceException(
                                "TTS服务器返回错误：" + response.body.string()
                            )
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                response.body.byteStream().let { stream ->
                    downloadErrorNo = 0
                    // 下载成功重置静音提示去重（新会话再次失败时可再提示）
                    silentFallbackToasted = false
                    return stream
                }
            } catch (e: Exception) {
                when (e) {
                    is CancellationException -> throw e
                    is ScriptException, is WrappedException -> {
                        AppLog.put("js错误\n${e.localizedMessage}", e, true)
                        e.printOnDebug()
                        throw e
                    }

                    is SocketTimeoutException, is ConnectException -> {
                        downloadErrorNo++
                        if (downloadErrorNo > MAX_ERROR_NO) {
                            val msg = "tts超时或连接错误超过${MAX_ERROR_NO}次\n${e.localizedMessage}"
                            AppLog.put(msg, e, true)
                            throw e
                        }
                    }

                    else -> {
                        downloadErrorNo++
                        val msg = "tts下载错误\n${e.localizedMessage}"
                        AppLog.put(msg, e)
                        e.printOnDebug()
                        if (downloadErrorNo > MAX_ERROR_NO) {
                            val msg1 = "TTS服务器连续${MAX_ERROR_NO}次错误，已暂停阅读。"
                            AppLog.put(msg1, e, true)
                            throw e
                        } else {
                            AppLog.put("TTS下载音频出错，使用无声音频代替。")
                            // 静默替代不再无感：每次服务会话仅提示一次（防逐句刷 toast）
                            if (!silentFallbackToasted) {
                                silentFallbackToasted = true
                                context.toastOnUi("TTS 音频下载出错，本段将以静音代替（详情见日志）")
                            }
                            break
                        }
                    }
                }
            }
        }
        return null
    }

    companion object {
        /** 连续错误阈值（与原实现一致：>5 次暂停朗读） */
        const val MAX_ERROR_NO = 5
    }
}
