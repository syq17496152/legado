package io.legado.app.help.tts.builtin

import kotlin.math.abs

/**
 * 参考音频加工（T2.2）：外部录音 / 备份包音频入库前的统一口径。
 * 流程：解码（立体声下混）→ 能量 VAD 裁首尾静音 → 重采样到模型采样率 → 峰值归一 → 时长校验（3~15s）。
 *
 * 约束：
 * - 全静音音频**不静默通过**（克隆没有参考音频等于没声音），直接判失败让 UI 提示重录；
 * - 峰值归一**只向下压**（放大噪声会让克隆音色更差）；
 * - 时长判定用加工后的音频，避免末尾长静音把短音频"撑"过 3 秒。
 */
object ReferenceAudioProcessor {

    const val MIN_DURATION_MS = 3_000L
    const val MAX_DURATION_MS = 15_000L
    const val PEAK_TARGET = 0.92f

    sealed class Result {
        /** 加工成功：可直接写盘为 `<id>/prompt.wav` */
        data class Success(
            val wavBytes: ByteArray,
            val sampleRate: Int,
            val durationMs: Long,
            /** 是否发生过静音裁剪（UI 可提示"已自动去掉首尾静音"） */
            val trimmed: Boolean,
            val sourceDurationMs: Long
        ) : Result()

        data class Failure(val reason: String) : Result()
    }

    /** 加工 WAV 字节（16bit 单声道/立体声） */
    fun process(wavBytes: ByteArray, targetSampleRate: Int): Result {
        return runCatching {
            val audio = PcmAudioUtils.readWavDownmix(wavBytes.inputStream())
            process(audio.pcm, audio.sampleRate, targetSampleRate)
        }.getOrElse { error ->
            Result.Failure("音频无法解析：${error.message ?: "格式不受支持"}")
        }
    }

    /** 加工 PCM16 字节（已下混为单声道） */
    fun process(pcm16: ByteArray, sourceSampleRate: Int, targetSampleRate: Int): Result {
        if (sourceSampleRate <= 0) return Result.Failure("采样率非法")
        if (pcm16.size < 2) return Result.Failure("音频为空")
        val target = targetSampleRate.takeIf { it > 0 } ?: sourceSampleRate
        val sourceDurationMs = pcm16.size.toLong() * 1000L / (sourceSampleRate.toLong() * 2L)

        val samples = PcmAudioUtils.pcm16ToFloat(pcm16)
        val peak = maxAbs(samples)
        // 全静音保护：没有可用信号时直接失败（否则克隆音色不可用但用户不知情）
        if (peak < SILENT_PEAK) return Result.Failure("音频几乎无声音，请重新录制")

        val trimmed = PcmAudioUtils.trimSilence(samples, sourceSampleRate)
        val resampled = PcmAudioUtils.resample(trimmed, sourceSampleRate, target)
        val normalized = PcmAudioUtils.normalizePeak(resampled, PEAK_TARGET)
        val durationMs = normalized.size.toLong() * 1000L / target
        return when {
            durationMs < MIN_DURATION_MS -> Result.Failure(
                "参考音频过短（${durationMs / 1000}s，至少 ${MIN_DURATION_MS / 1000}s）"
            )
            durationMs > MAX_DURATION_MS -> Result.Failure(
                "参考音频过长（${durationMs / 1000}s，最长 ${MAX_DURATION_MS / 1000}s）"
            )
            else -> Result.Success(
                wavBytes = PcmAudioUtils.wavBytes(PcmAudioUtils.floatToPcm16(normalized), target),
                sampleRate = target,
                durationMs = durationMs,
                trimmed = trimmed.size != samples.size,
                sourceDurationMs = sourceDurationMs
            )
        }
    }

    private fun maxAbs(samples: FloatArray): Float = samples.maxOfOrNull { abs(it) } ?: 0f

    private const val SILENT_PEAK = 1e-4f
}
