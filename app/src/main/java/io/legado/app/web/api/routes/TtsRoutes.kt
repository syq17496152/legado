package io.legado.app.web.api.routes

import fi.iki.elonen.NanoHTTPD.Method
import io.legado.app.api.ReturnData
import io.legado.app.service.kernel.AudioKernel
import io.legado.app.service.kernel.TtsKernel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.web.TokenManager.Level
import io.legado.app.web.api.ApiRoute

/**
 * D 组 · TTS 与朗读路由声明（web-mcp-productization 三期 · spec 4.3，共 6 端点）。
 *
 * 设计原则（AD-3-01 / AD-10）：每端点 = 一个二期 kernel 方法投影；本文件只做
 * 「声明 + 取参 + 委派」，不内联任何业务逻辑；`HttpServer.kt` 全程零改动（REQ-3-109）。
 *
 * 级别照 V2.5 施工卡：引擎/进度读出（D1/D6）与朗读参数读（D2）按施工卡
 * 「TTS 配置（D2/D3）= admin」定级；试听（D4）、听书控制（D5）为 MANAGE。
 * 归属（AD-11）：听书控制两侧的 kernel 是二期 [AudioKernel]（唯一归属二期），
 * 本期不重写；配置侧走二期 [TtsKernel]。
 *
 * 响应体由 [ReturnData] 包 `data` 后交 `ApiEnvelope` 装信封（与一期端点同构）。
 */
object TtsRoutes {

    val routes: Array<ApiRoute> = arrayOf(
        // ---- D1 引擎清单（readonly）----
        ApiRoute(Method.GET, "/getTtsEngines", Level.READONLY, mcpToolName = "tts_engines_get") { _ ->
            ReturnData().setData(TtsKernel.engines())
        },

        // ---- D2 朗读参数读取（admin）----
        ApiRoute(Method.GET, "/getTtsConfig", Level.ADMIN, mcpToolName = "tts_config_get") { _ ->
            ReturnData().setData(TtsKernel.configGet())
        },

        // ---- D3 朗读参数保存（admin）；保存后由 kernel 广播（REQ-3-303）----
        ApiRoute(Method.POST, "/saveTtsConfig", Level.ADMIN, mcpToolName = "tts_config_save") { ctx ->
            val body = GSON.fromJsonObject<Map<String, Any?>>(ctx.requirePostData()).getOrThrow()
            ReturnData().setData(
                TtsKernel.configSave(
                    engine = str(body, "engine"),
                    followSystem = bool(body, "followSystem"),
                    speechRate = int(body, "speechRate"),
                    timerMinutes = int(body, "timerMinutes"),
                    timerMode = int(body, "timerMode"),
                    timerChapters = int(body, "timerChapters"),
                    paragraphPauseMs = int(body, "paragraphPauseMs"),
                    engineSpeechRate = float(body, "engineSpeechRate"),
                    enginePitch = float(body, "enginePitch"),
                    engineVolume = float(body, "engineVolume"),
                )
            )
        },

        // ---- D4 试听（manage）；音频流能力缺失 ⇒ kernel 如实降级回告 ----
        ApiRoute(Method.POST, "/testTts", Level.MANAGE, mcpToolName = "tts_test") { ctx ->
            val body = GSON.fromJsonObject<Map<String, Any?>>(ctx.requirePostData()).getOrThrow()
            ReturnData().setData(
                TtsKernel.testTts(
                    ttsUrl = str(body, "ttsUrl"),
                    contentType = str(body, "contentType"),
                    text = str(body, "text"),
                )
            )
        },

        // ---- D5 听书控制（manage，端侧指令：下发后回读播放态）----
        ApiRoute(Method.POST, "/audioControl", Level.MANAGE, mcpToolName = "audio_control") { ctx ->
            val body = GSON.fromJsonObject<Map<String, Any?>>(ctx.requirePostData()).getOrThrow()
            ReturnData().setData(
                AudioKernel.audioControl(
                    action = str(body, "action")
                        ?: throw IllegalArgumentException("参数action不能为空"),
                    minute = int(body, "minute") ?: 0,
                    mode = int(body, "mode") ?: 0,
                    chapters = int(body, "chapters") ?: 0,
                    chapterIndex = int(body, "chapterIndex") ?: 0,
                )
            )
        },

        // ---- D6 听书进度（readonly）----
        ApiRoute(Method.GET, "/getAudioProgress", Level.READONLY, mcpToolName = "audio_progress_get") { _ ->
            ReturnData().setData(AudioKernel.audioProgress())
        },
    )

    // ---- 请求体取值（JSON 松解析：整数经 GSON 落为 Long，统一按 Number 收敛）----

    private fun str(body: Map<String, Any?>, key: String): String? = body[key] as? String

    private fun int(body: Map<String, Any?>, key: String): Int? = (body[key] as? Number)?.toInt()

    private fun float(body: Map<String, Any?>, key: String): Float? = (body[key] as? Number)?.toFloat()

    private fun bool(body: Map<String, Any?>, key: String): Boolean? = body[key] as? Boolean
}