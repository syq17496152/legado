package io.legado.app.help.tts.builtin

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.legado.app.utils.LogUtils
import java.io.File
import java.io.FileOutputStream

/**
 * 克隆向导录音（T2.4）：麦克风 → 16bit 单声道 PCM → WAV。
 *
 * 为什么用 `AudioRecord` 而不是 `MediaRecorder`：克隆参考音频必须是 **PCM WAV**（引擎直接读样本），
 * MediaRecorder 只能出 AMR/AAC，还要再解码一遍，多一层 Android 版本差异风险。
 *
 * 约束：
 * - 采样率 16kHz（参考音频最终会重采样到模型采样率，16k 足够且体积小）；
 * - 达到 [MAX_DURATION_MS] 自动停止（防用户一直录导致内存/体验问题）；
 * - 录制中异常（无麦克风/被占用/读失败）返回明确错误，不静默失败；
 * - 收尾（停录音机 + PCM 转 WAV + 回调）只在 [finalizeRecording] 里做一次（线程安全）。
 */
class ReferenceAudioRecorder(
    private val outputDir: File,
    private val sampleRate: Int = 16_000
) {

    interface Listener {
        fun onStarted()
        fun onProgress(durationMs: Long)
        fun onStopped(wavFile: File?, durationMs: Long)
        fun onError(message: String)
    }

    var listener: Listener? = null

    @Volatile
    private var recording = false
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var pcmFile: File? = null
    private var finished = false

    fun isRecording(): Boolean = recording

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (recording) return false
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            listener?.onError("设备不支持 16kHz 录音")
            return false
        }
        val bufferSize = minBuffer * 2
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }.getOrNull()
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder?.release() }
            listener?.onError("麦克风不可用（请检查权限或是否被其他应用占用）")
            return false
        }
        outputDir.mkdirs()
        val pcm = File(outputDir, "record_${System.currentTimeMillis()}.pcm")
        pcmFile = pcm
        record = recorder
        recording = true
        finished = false
        listener?.onStarted()
        worker = Thread({
            var failure: String? = null
            runCatching {
                recorder.startRecording()
                FileOutputStream(pcm).use { out ->
                    val buffer = ShortArray(bufferSize / 2)
                    var writtenSamples = 0L
                    while (recording) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read < 0) {
                            failure = "录音读取失败（code=$read）"
                            break
                        }
                        if (read == 0) continue
                        val samples = FloatArray(read) { buffer[it] / 32768f }
                        out.write(PcmAudioUtils.floatToPcm16(samples))
                        writtenSamples += read
                        val durationMs = writtenSamples * 1000L / sampleRate
                        listener?.onProgress(durationMs)
                        if (durationMs >= MAX_DURATION_MS) {
                            recording = false
                            break
                        }
                    }
                }
            }.onFailure {
                LogUtils.e(TAG, "录音失败：${it.stackTraceToString()}")
                recording = false
                failure = "录音失败：${it.message}"
            }
            val error = failure
            if (error != null) {
                recording = false
                finished = true
                runCatching { pcmFile?.delete() }
                pcmFile = null
                listener?.onError(error)
            } else {
                finalizeRecording()
            }
        }, "builtin-tts-record")
        worker?.start()
        return true
    }

    /** UI 停止：等录音线程收尾（线程内会产出 WAV 并回调），超时兜底自行收尾 */
    fun stop(): File? {
        if (!recording && finished) return lastWav
        recording = false
        worker?.let { thread ->
            runCatching { thread.join(1_000) }
        }
        finalizeRecording()
        return lastWav
    }

    fun cancel() {
        recording = false
        worker?.let { runCatching { it.join(500) } }
        worker = null
        finished = true
        runCatching {
            record?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
            record?.release()
        }
        record = null
        pcmFile?.delete()
        pcmFile = null
    }

    @Volatile
    private var lastWav: File? = null
    private var lastDurationMs = 0L

    /** 收尾只做一次：停录音机 → PCM 转 WAV → 时长校验 → 回调 */
    @Synchronized
    private fun finalizeRecording() {
        if (finished) return
        finished = true
        runCatching {
            record?.let { recorder ->
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                recorder.release()
            }
        }
        record = null
        val pcm = pcmFile
        pcmFile = null
        val durationMs = if (pcm?.isFile == true) {
            pcm.length() * 1000L / (sampleRate.toLong() * 2L)
        } else 0L
        val wav = pcm?.let { convertToWav(it) }
        lastDurationMs = durationMs
        lastWav = wav?.takeIf { durationMs >= MIN_DURATION_MS }
        listener?.onStopped(lastWav, durationMs)
    }

    /** PCM → 标准 WAV（写盘后直接交给 ReferenceAudioProcessor 加工） */
    private fun convertToWav(pcm: File): File? {
        return runCatching {
            val target = File(pcm.parentFile, pcm.nameWithoutExtension + ".wav")
            target.writeBytes(PcmAudioUtils.wavBytes(pcm.readBytes(), sampleRate))
            pcm.delete()
            target
        }.onFailure { LogUtils.e(TAG, "PCM 转 WAV 失败：${it.message}") }.getOrNull()
    }

    companion object {
        const val MIN_DURATION_MS = 3_000L
        const val MAX_DURATION_MS = 15_000L

        /** 自录音色的示范文本（必须与实际朗读一致，否则克隆参考文本对不上） */
        const val DEFAULT_SAMPLE_TEXT = "今天天气很好，我们一起去公园散步吧。"

        /** 录音状态提示（纯函数可单测） */
        fun statusText(recording: Boolean, durationMs: Long): String = when {
            recording -> "录制中 ${durationMs / 1000}s（建议 5~10 秒）"
            else -> "点击开始录音，至少 ${MIN_DURATION_MS / 1000} 秒"
        }

        fun durationValid(durationMs: Long): Boolean = durationMs in MIN_DURATION_MS..MAX_DURATION_MS

        fun durationHint(durationMs: Long): String = when {
            durationMs <= 0L -> ""
            durationMs < MIN_DURATION_MS -> "太短了（${durationMs / 1000}s），请重录"
            durationMs > MAX_DURATION_MS -> "太长了（${durationMs / 1000}s），请控制在 ${MAX_DURATION_MS / 1000} 秒内"
            else -> "时长合适（${durationMs / 1000}s）"
        }

        private const val TAG = "BuiltinTtsRecorder"
    }
}
