package io.legado.app.help.readaloud.casting

import android.content.Context
import android.speech.tts.TextToSpeech
import io.legado.app.constant.AppLog
import io.legado.app.help.readaloud.speech.TtsEngineParamsStore
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * utterance 进度回收接口（P1-9 监听器所有权归一）：
 * 服务级唯一 UtteranceProgressListener 按 utteranceId 查分发表回收完成信号，
 * 声源实现不再逐句 setOnUtteranceProgressListener 覆盖服务监听器（覆盖不恢复=legacy 推进失效卡死）
 */
interface UtteranceProgressSink {
    fun registerUtterance(utteranceId: String, cont: CancellableContinuation<Boolean>)
    fun unregisterUtterance(utteranceId: String)
}

/**
 * 适配层统一声源接口（§3.5.3/AD-09 L-b）
 * 三实现：SystemEngineSource（系统引擎）/HttpForwarderSource（MultiTTS:8774/CloneTTS:8080，期2）/ScriptSource（脚本引擎，期2）
 * UtteranceResult sealed 消除抽象漏损：系统引擎=SpeakSubmitted（speak 命令+挂起 onDone）；HTTP=AudioFile（缓存文件→ExoPlayer）
 */
sealed class UtteranceResult {
    /** 系统引擎：已提交 speak，挂起至 onDone/onStop/onError（三路收推进闸） */
    data class SpeakSubmitted(
        val utteranceId: String,
        val completeSignal: suspend () -> Boolean
    ) : UtteranceResult()

    /** HTTP 合成：落盘缓存文件，交 ExoPlayer 消费 */
    data class AudioFile(val file: java.io.File) : UtteranceResult()
}

data class TtsVoiceRef(
    val voiceId: String = "",
    val displayName: String = "",
    // system/multitts/clonetts/script
    val channel: String = CHANNEL_SYSTEM
) {
    companion object {
        const val CHANNEL_SYSTEM = "system"
        const val CHANNEL_HTTP = "http"
        const val CHANNEL_SCRIPT = "script"
    }
}

interface TtsVoiceSource {
    /** 音色枚举（引擎级实现可为空表→按引擎级降级；结果经缓存，play 链不直连引擎） */
    suspend fun voices(): List<TtsVoiceRef>

    /**
     * 单句合成/播报（句级轮换）。
     * 系统实现：speak 并挂起至该句完成（onDone/onError），onStop 返回 false（推进闸语义）。
     */
    suspend fun utterance(
        text: String,
        voice: TtsVoiceRef?,
        prosody: CastingProsody,
        utteranceId: String
    ): Boolean

    fun release()
}

/**
 * 系统引擎声源实现：包裹 TextToSpeech 实例，逐句 setVoice/韵律（§3.5.2②）
 * - per-utterance voice 无官方 Bundle 支持（§1.8-G-4）→ setVoice 实例级+逐句调用（官方主路线）
 * - getVoices 预判（缓存快照）：空集/不含目标 → 降级引擎默认音+首次明示
 * - 完成信号经 UtteranceProgressSink 分发表回收（P1-9 归一），不覆盖服务级监听器
 * - 句级超时兜底（P2-8）：引擎丢回调时挂起防永久卡死
 * - 多实例登记后续（官方未背书）
 */
class SystemEngineSource(
    private val context: Context,
    private val ttsProvider: () -> TextToSpeech?,
    private val isInitFinish: () -> Boolean,
    private val engineValue: String,
    private val sink: UtteranceProgressSink
) : TtsVoiceSource {

    companion object {
        /** 句级超时兜底（P2-8）：正常句朗读远小于该上限，超时视为引擎丢回调 */
        private const val UTTERANCE_TIMEOUT_MS = 120_000L
    }

    private var degradedNotified = false

    override suspend fun voices(): List<TtsVoiceRef> {
        val tts = ttsProvider() ?: return emptyList()
        if (!isInitFinish()) return emptyList()
        return runCatching {
            tts.voices.orEmpty().map { voice ->
                TtsVoiceRef(
                    voiceId = voice.name,
                    displayName = voice.name,
                    channel = TtsVoiceRef.CHANNEL_SYSTEM
                )
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun utterance(
        text: String,
        voice: TtsVoiceRef?,
        prosody: CastingProsody,
        utteranceId: String
    ): Boolean {
        val tts = ttsProvider() ?: return false
        if (!isInitFinish()) return false
        return try {
            withTimeout(UTTERANCE_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    // P1-9 归一：完成信号由服务级监听器按 utteranceId 分发到 sink，不再自挂监听器
                    sink.registerUtterance(utteranceId, cont)
                    runCatching {
                        applyProsody(tts, prosody)
                        applyVoice(tts, voice)
                        // TextToSpeech 无 setVolume API：volume 走 per-utterance Bundle（官方 KEY_PARAM_VOLUME）
                        val bundle = android.os.Bundle().apply {
                            if (prosody.volume > 0f) {
                                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, prosody.volume.coerceIn(0f, 1.0f))
                            }
                        }
                        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, bundle, utteranceId)
                        if (result == TextToSpeech.ERROR) {
                            sink.unregisterUtterance(utteranceId)
                            if (cont.isActive) cont.resume(false)
                        }
                    }.onFailure {
                        sink.unregisterUtterance(utteranceId)
                        if (cont.isActive) cont.resume(false)
                    }
                    cont.invokeOnCancellation {
                        sink.unregisterUtterance(utteranceId)
                        runCatching { tts.stop() }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            // P2-8 句级超时兜底：引擎丢回调，stop 当前播报并按完成推进（防整链卡死）
            AppLog.put("TTS 句级朗读超时（120s），跳过该段继续：len=${text.length}")
            runCatching { tts.stop() }
            true
        }
        // 外层协程取消（用户停止/换章）原样抛出，不吞 CancellationException
    }

    private fun applyProsody(tts: TextToSpeech, prosody: CastingProsody) {
        runCatching {
            // 0=跟随全局（§1.8-G-2 语义）；非 0 clamp 0.5~2.0（§1.8-F 限幅）
            if (prosody.rate > 0f) tts.setSpeechRate(prosody.rate.coerceIn(0.5f, 2.0f))
            if (prosody.pitch > 0f) tts.setPitch(prosody.pitch.coerceIn(0.5f, 2.0f))
        }
    }

    private fun applyVoice(tts: TextToSpeech, voice: TtsVoiceRef?) {
        if (voice?.voiceId.isNullOrBlank()) return
        val target = tts.voices.orEmpty().firstOrNull { it.name == voice.voiceId }
        if (target == null) {
            // getVoices 预判降级（§3.5.2②）：空集/不含目标 → 引擎默认音+首次明示
            if (!degradedNotified) {
                degradedNotified = true
                io.legado.app.constant.AppLog.put("TTS 引擎音色不可用，已回退引擎默认音：${voice?.voiceId}")
            }
            return
        }
        val result = tts.setVoice(target)
        if (result == TextToSpeech.ERROR && !degradedNotified) {
            degradedNotified = true
            io.legado.app.constant.AppLog.put("TTS setVoice 失败，已回退引擎默认音：${voice?.voiceId}")
        }
    }

    /** 供试听/预览复用的单句播报（§1.8-D-3 定型项 6 签名语义） */
    suspend fun preview(text: String, voice: TtsVoiceRef?): Boolean {
        return utterance(text, voice, CastingProsody(), "preview_${System.currentTimeMillis()}")
    }

    override fun release() {
        // TextToSpeech 实例归 TTSReadAloudService 生命周期管理，此处无独立资源
    }

    /** 每引擎独立参数存取入口（AD-05，TtsEngineParamsStore 承载） */
    fun saveEngineParams(speechRate: Float, pitch: Float, volume: Float) {
        TtsEngineParamsStore.save(engineValue, speechRate, pitch, volume)
    }
}
