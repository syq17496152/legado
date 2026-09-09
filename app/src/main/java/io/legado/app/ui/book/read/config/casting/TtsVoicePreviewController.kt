package io.legado.app.ui.book.read.config.casting

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.readaloud.casting.CastingProsody
import io.legado.app.help.readaloud.casting.ReadAloudDelegate
import io.legado.app.help.readaloud.casting.TtsCastingStore
import io.legado.app.utils.toastOnUi
import io.legado.app.help.readaloud.script.TtsScriptEngineClient
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.model.analyzeRule.AnalyzeUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * 试听状态（编辑器声源行按钮渲染依据）
 */
data class TtsPreviewState(
    val key: String? = null,
    val phase: Phase = Phase.IDLE
) {
    enum class Phase { IDLE, LOADING, PLAYING }
}

/**
 * 试听目标（宿主由声源行 SpeechRoute 构造；default/current 哨兵由控制器内部解析为当前生效路由）
 */
data class TtsPreviewTarget(
    val previewKey: String,
    val engineType: String,
    val engineValue: String = "",
    val voiceId: String? = null,
    val prosody: CastingProsody? = null
)

/**
 * 试听控制器（AD-12 契约移植+依赖适配）：
 * - 600ms 防抖按 previewKey（窗口内连点合并为最后一次），同 key 再点=停止切换
 * - 双 token：脚本/HTTP 链 requestToken + 系统链 systemPreviewToken，后到响应一律丢弃
 * - 临时文件 cacheDir/voice_preview/voice_preview_{engineId}_{ts}.audio（独立命名空间，不入朗读缓存键体系）
 * - 脚本/HTTP 声源走合成取流落盘→ExoPlayer；系统声源走自建临时 TextToSpeech 实例（shutdown 释放，天然通道隔离）
 * - beforePreview 先暂停朗读；恢复由 stopActivePreview/finish 统一路径执行（成功/失败/dismiss 同路）
 * - 宿主 dismiss/onDestroyView 必须调 release
 */
class TtsVoicePreviewController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val beforePreview: (() -> Unit)? = null,
    private val onResumeReadAloud: (() -> Unit)? = null,
    private val onStateChanged: (TtsPreviewState) -> Unit
) {

    companion object {
        private const val DEBOUNCE_MS = 600L
        private const val SYS_TTS_INIT_TIMEOUT_MS = 8_000L
        private const val PREVIEW_GRACE_MS = 300L
        private const val SYNTH_TIMEOUT_MS = 60_000L
        private const val PREVIEW_PREFIX = "preview_"
    }

    /** 脚本/HTTP 链代际令牌（++ 后校验，不一致的后到响应一律丢弃） */
    private var requestToken = 0

    /** 系统链独立代际令牌 */
    private var systemPreviewToken = 0

    private var debounceJob: Job? = null
    private var executeJob: Job? = null
    private var player: ExoPlayer? = null
    private var tempFile: File? = null
    private var sysTts: TextToSpeech? = null
    private var sysTtsInitJob: Job? = null
    private var currentState = TtsPreviewState()
    private var resumedByPreview = false

    private fun setState(state: TtsPreviewState) {
        currentState = state
        onStateChanged(state)
    }

    val state: TtsPreviewState get() = currentState

    /**
     * 试听入口：同 key 再点=停止切换；否则防抖合并短连点后执行
     */
    fun preview(target: TtsPreviewTarget) {
        if (currentState.key == target.previewKey && currentState.phase != TtsPreviewState.Phase.IDLE) {
            stopActivePreview()
            return
        }
        stopActivePreview()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            executeResolved(resolveDefault(target))
        }
    }

    /** default/current 哨兵解析为当前实际生效路由（跟随引擎语义） */
    private fun resolveDefault(target: TtsPreviewTarget): TtsPreviewTarget {
        if (target.engineType != SpeechRoute.ENGINE_DEFAULT) return target
        val route = SpeechRoute.resolveSpeechRoute(ReadAloudDelegate.currentTtsEngineRaw())
        return when (route.engineType) {
            SpeechRoute.ENGINE_SYSTEM -> target.copy(
                previewKey = "system|${route.toneID}",
                engineType = SpeechRoute.ENGINE_SYSTEM,
                voiceId = route.toneID.ifBlank { null }
            )
            SpeechRoute.ENGINE_HTTP -> target.copy(
                previewKey = "${route.engineValue}|${route.toneID}",
                engineType = SpeechRoute.ENGINE_HTTP,
                engineValue = route.engineValue,
                voiceId = route.toneID.ifBlank { null }
            )
            else -> target
        }
    }

    private fun executeResolved(target: TtsPreviewTarget) {
        beforePreview?.invoke()
        resumedByPreview = true
        // TtsTrace 真机联调：试听请求入口（通道判定证据）
        AppLog.putDebugWithTag(
            AppLog.TAG_TTS_TRACE,
            "preview 请求 key=${target.previewKey} type=${target.engineType} voice=${target.voiceId ?: "默认"}",
            level = AppLog.Level.INFO
        )
        when (target.engineType) {
            SpeechRoute.ENGINE_SYSTEM -> executeSystemSpeak(target)
            else -> executeSynthesizeToFile(target)
        }
    }

    /**
     * 脚本/HTTP 声源链：synthesize/URL 模板 → 取流 → 临时文件 → ExoPlayer
     * requestToken 校验贯穿全链（取流后、落盘后、播放前三次校验）
     */
    private fun executeSynthesizeToFile(target: TtsPreviewTarget) {
        val token = ++requestToken
        setState(TtsPreviewState(target.previewKey, TtsPreviewState.Phase.LOADING))
        executeJob = scope.launch {
            val file = kotlin.runCatching {
                withTimeout(SYNTH_TIMEOUT_MS) {
                    val httpTts = loadHttpTts(target.engineValue)
                        ?: throw NoStackTraceException("试听引擎不存在或已删除")
                    synthesizeToTempFile(httpTts, target, token)
                }
            }.getOrElse { throwable ->
                currentCoroutineContext().ensureActive()
                AppLog.put("TTS 试听合成失败：${throwable.message}")
                if (token == requestToken) {
                    context.toastOnUi("试听失败：${throwable.message}")
                    stopActivePreview()
                }
                return@launch
            }
            if (token != requestToken) {
                file.delete()
                return@launch
            }
            startPlayer(file, target.previewKey, token)
        }
    }

    /** 描述符/URL 模板 → 取流 → 写临时文件（preview_ 独立命名空间，不入缓存键体系） */
    private suspend fun synthesizeToTempFile(
        httpTts: HttpTTS,
        target: TtsPreviewTarget,
        token: Int
    ): File {
        val prosody = target.prosody
        val stream = if (httpTts.type == 2) {
            val request = TtsScriptEngineClient.synthesize(
                httpTts,
                previewText(),
                target.voiceId,
                prosody?.rate?.takeIf { it > 0f },
                prosody?.pitch?.takeIf { it > 0f },
                prosody?.volume?.takeIf { it > 0f }
            )
            if (token != requestToken) return discardedPlaceholder()
            val optionJson = org.json.JSONObject().apply {
                put("method", request.method)
                if (request.headers.isNotEmpty()) {
                    put("headers", org.json.JSONObject(request.headers))
                }
                request.body?.let { put("body", it) }
            }.toString()
            AnalyzeUrl(
                request.url + "," + optionJson,
                speakText = previewText(),
                source = httpTts,
                readTimeout = 30_000L,
                coroutineContext = currentCoroutineContext()
            ).getResponseAwait().body.byteStream()
        } else {
            AnalyzeUrl(
                httpTts.url,
                speakText = previewText(),
                source = httpTts,
                readTimeout = 30_000L,
                coroutineContext = currentCoroutineContext()
            ).getResponseAwait().body.byteStream()
        }
        currentCoroutineContext().ensureActive()
        if (token != requestToken) {
            kotlin.runCatching { stream.close() }
            return discardedPlaceholder()
        }
        val dir = File(context.cacheDir, "voice_preview")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "voice_preview_${httpTts.id}_${System.currentTimeMillis()}.audio")
        file.outputStream().use { out -> stream.copyTo(out) }
        kotlin.runCatching { stream.close() }
        currentCoroutineContext().ensureActive()
        if (token != requestToken) {
            file.delete()
            return discardedPlaceholder()
        }
        tempFile = file
        return file
    }

    /** token 失效路径的空占位（不落盘） */
    private fun discardedPlaceholder(): File = File(context.cacheDir, "voice_preview_discarded.audio")

    /** ExoPlayer 播放临时文件（完成守护：STATE_ENDED/错误 → 统一路径回收） */
    private fun startPlayer(file: File, previewKey: String, token: Int) {
        if (token != requestToken) {
            file.delete()
            return
        }
        val exo = ExoPlayer.Builder(context).build()
        player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && token == requestToken) {
                    scope.launch {
                        delay(PREVIEW_GRACE_MS)
                        if (token == requestToken && currentState.key == previewKey) {
                            stopActivePreview()
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                AppLog.put("TTS 试听播放错误：${error.errorCodeName}")
                if (token == requestToken) {
                    context.toastOnUi("试听失败：播放错误")
                    stopActivePreview()
                }
            }
        })
        exo.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
        exo.prepare()
        if (token != requestToken) {
            exo.release()
            player = null
            return
        }
        setState(TtsPreviewState(previewKey, TtsPreviewState.Phase.PLAYING))
        exo.play()
    }

    /**
     * 系统声源链：自建临时 TextToSpeech 实例（shutdown 释放，天然通道隔离）
     * - 8s init 超时守护（onInit 失败/未回调→shutdown 防泄漏）
     * - speak 使用 preview_ 前缀 utteranceId
     */
    private fun executeSystemSpeak(target: TtsPreviewTarget) {
        val token = ++systemPreviewToken
        setState(TtsPreviewState(target.previewKey, TtsPreviewState.Phase.LOADING))
        var ready = false
        var localTts: TextToSpeech? = null
        val tts = TextToSpeech(context) { status ->
            if (token != systemPreviewToken) {
                localTts?.shutdown()
                return@TextToSpeech
            }
            if (status != TextToSpeech.SUCCESS) {
                AppLog.put("TTS 试听临时 TTS init 失败：status=$status")
                context.toastOnUi("试听失败：系统 TTS 初始化失败")
                stopActivePreview()
                return@TextToSpeech
            }
            ready = true
            val utteranceId = "${PREVIEW_PREFIX}system_${System.currentTimeMillis()}"
            sysTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (token == systemPreviewToken) {
                        setState(TtsPreviewState(target.previewKey, TtsPreviewState.Phase.PLAYING))
                    }
                }

                override fun onDone(utteranceId: String?) {
                    if (token == systemPreviewToken) {
                        scope.launch {
                            delay(PREVIEW_GRACE_MS)
                            stopActivePreview()
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (token == systemPreviewToken) {
                        AppLog.put("TTS 试听系统链 onError")
                        context.toastOnUi("试听失败：系统 TTS 合成错误")
                        stopActivePreview()
                    }
                }
            })
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            sysTts?.speak(previewText(), TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }
        localTts = tts
        sysTts = tts
        // 8s init 超时守护：未就绪即 shutdown（防 onInit 永不回调泄漏）
        sysTtsInitJob = scope.launch {
            delay(SYS_TTS_INIT_TIMEOUT_MS)
            if (token == systemPreviewToken && !ready) {
                AppLog.put("TTS 试听临时 TTS init 超时（8s），主动 shutdown")
                context.toastOnUi("试听失败：系统 TTS 初始化超时")
                stopActivePreview()
            }
        }
    }

    /** 试听文本：strings 默认试听句（本仓声源实体无 sampleText 字段，不加实体字段） */
    private fun previewText(): String = context.getString(R.string.tts_preview_default_text)

    private suspend fun loadHttpTts(engineValue: String): HttpTTS? {
        val id = engineValue.toLongOrNull() ?: return null
        return appDb.httpTTSDao.get(id)
    }

    /**
     * 统一回收（成功结束/失败/同 key 停止/宿主 dismiss 同路）：
     * 双 token++、cancel 执行 Job、release 播放器、shutdown 临时 TTS、删临时文件、恢复朗读、回 IDLE
     */
    fun stopActivePreview() {
        // TtsTrace 真机联调：试听停止+资源释放证据（防泄漏核对）
        AppLog.putDebugWithTag(
            AppLog.TAG_TTS_TRACE,
            "preview 停止+释放 释放前 player=${player != null} sysTts=${sysTts != null} tempFile=${tempFile != null}",
            level = AppLog.Level.INFO
        )
        requestToken++
        systemPreviewToken++
        debounceJob?.cancel()
        debounceJob = null
        executeJob?.cancel()
        executeJob = null
        sysTtsInitJob?.cancel()
        sysTtsInitJob = null
        player?.runCatching {
            stop()
            release()
        }
        player = null
        sysTts?.runCatching { shutdown() }
        sysTts = null
        tempFile?.let { file ->
            kotlin.runCatching { file.delete() }
        }
        tempFile = null
        if (resumedByPreview) {
            resumedByPreview = false
            onResumeReadAloud?.invoke()
        }
        setState(TtsPreviewState())
    }

    /**
     * 宿主 dismiss/onDestroyView 必须调用：stopActivePreview 兜底 + 状态归零
     */
    fun release() {
        stopActivePreview()
    }
}


