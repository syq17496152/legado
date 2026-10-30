package io.legado.app.ui.book.read.config

import io.legado.app.help.tts.builtin.BuiltinVoice
import io.legado.app.help.tts.builtin.BuiltinVoiceStore
import io.legado.app.help.tts.builtin.ReferenceAudioProcessor
import io.legado.app.help.tts.builtin.ReferenceAudioRecorder
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * 自录音色控件（T2.4）：把"录音 → 加工 → 入库"从 Activity 里拆出来，Activity 只处理权限与页面状态。
 * - 录音线程回调直接在 UI 线程刷状态（Compose 状态线程安全）；
 * - 保存走 IO 线程（读 WAV/加工/写盘），完成后回调显示名；
 * - 加工失败给出可读原因（太短/太长/无声音），不静默。
 */
class BuiltinVoiceRecordController(
    private val scope: CoroutineScope,
    private val cacheDir: File,
    private val sampleRateProvider: () -> Int,
    private val voiceStore: () -> BuiltinVoiceStore,
    private val profileIdProvider: () -> String,
    private val onState: (recording: Boolean, hint: String) -> Unit,
    private val onSaved: (BuiltinVoice, durationMs: Long) -> Unit,
    private val onError: (String) -> Unit
) {

    private var recorder: ReferenceAudioRecorder? = null

    fun start(sampleText: String) {
        val target = ReferenceAudioRecorder(File(cacheDir, "voice-record"))
        target.listener = object : ReferenceAudioRecorder.Listener {
            override fun onStarted() {
                onState(true, ReferenceAudioRecorder.statusText(true, 0))
            }

            override fun onProgress(durationMs: Long) {
                onState(true, ReferenceAudioRecorder.statusText(true, durationMs))
            }

            override fun onStopped(wavFile: File?, durationMs: Long) {
                onState(
                    false,
                    ReferenceAudioRecorder.durationHint(durationMs) +
                            if (wavFile == null) "，请重录" else ""
                )
                if (wavFile != null) save(wavFile, sampleText, durationMs)
            }

            override fun onError(message: String) {
                onState(false, message)
            }
        }
        recorder = target
        val started = target.start()
        if (!started) onState(false, "录音启动失败")
    }

    fun stop() {
        val target = recorder
        recorder = null
        if (target == null) return
        onState(false, "正在保存录音…")
        // stop() 会 join 录音线程（最多 1s），放 IO 避免卡 UI
        scope.launch(Dispatchers.IO) { target.stop() }
    }

    fun cancel() {
        runCatching { recorder?.cancel() }
        recorder = null
    }

    private fun save(wavFile: File, refText: String, durationMs: Long) {
        scope.launch(Dispatchers.IO) {
            val processed = runCatching {
                ReferenceAudioProcessor.process(wavFile.readBytes(), sampleRateProvider())
            }.getOrNull()
            val success = processed as? ReferenceAudioProcessor.Result.Success
            if (success == null) {
                val reason = (processed as? ReferenceAudioProcessor.Result.Failure)?.reason ?: "加工失败"
                runCatching { wavFile.delete() }
                onError("录音不可用：$reason")
                return@launch
            }
            val store = voiceStore()
            val name = "我的音色 ${store.list().count { it.origin == BuiltinVoice.ORIGIN_SELF } + 1}"
            val voice = runCatching {
                store.addVoice(
                    name = name,
                    pcmWavBytes = success.wavBytes,
                    refText = refText.trim(),
                    tags = listOf("self"),
                    origin = BuiltinVoice.ORIGIN_SELF,
                    profileId = profileIdProvider(),
                    durationMs = success.durationMs
                )
            }.onFailure {
                LogUtils.e(TAG, "自录音色入库失败：${it.stackTraceToString()}")
            }.getOrNull()
            runCatching { wavFile.delete() }
            if (voice == null) {
                onError("保存失败（请确认示范文本非空）")
            } else {
                onSaved(voice, durationMs)
            }
        }
    }

    private companion object {
        const val TAG = "BuiltinVoiceRecord"
    }
}
