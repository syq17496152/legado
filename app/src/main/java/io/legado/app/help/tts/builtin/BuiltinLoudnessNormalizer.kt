package io.legado.app.help.tts.builtin

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 跨音色响度归一（T8.1）：多角色朗读时不同参考音频的音色自带音量差异很大，
 * 不归一会出现"主角震耳、配角听不见"。口径：
 * - 目标 RMS 0.10（≈-20 dBFS，对齐系统 TTS 实测均值）；
 * - **偏差 ≤3 dB 不调整**（避免为每个音色引入无意义的增益抖动）；
 * - 调整上限 ±6 dB（超出说明音频本身有问题，硬拉会失真/放大底噪）；
 * - 音色自带 `gainMultiplier` 叠加后统一做峰值限制（≤0.98），防削波。
 */
object BuiltinLoudnessNormalizer {

    const val TARGET_RMS = 0.10f
    const val ADJUST_THRESHOLD_DB = 3f
    const val MAX_GAIN = 2.0f
    const val MIN_GAIN = 0.5f
    const val PEAK_LIMIT = 0.98f

    /** 短音频/静音片段不参与统计（避免把停顿当成"很小声"而猛拉增益） */
    private const val SILENT_RMS = 1e-4f

    fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        samples.forEach { sum += it.toDouble() * it.toDouble() }
        return sqrt(sum / samples.size).toFloat()
    }

    /** 归一化增益（未叠加音色 gainMultiplier；偏差在阈值内返回 1.0） */
    fun normalizeGain(samples: FloatArray): Float {
        val current = rms(samples)
        if (current < SILENT_RMS) return 1.0f
        val targetGain = TARGET_RMS / current
        val gainDb = 20f * log10(targetGain)
        if (abs(gainDb) <= ADJUST_THRESHOLD_DB) return 1.0f
        return targetGain.coerceIn(MIN_GAIN, MAX_GAIN)
    }

    /** 应用归一 + 音色增益 + 峰值限制；返回新数组（不修改入参） */
    fun apply(samples: FloatArray, gainMultiplier: Float = 1.0f): FloatArray {
        if (samples.isEmpty()) return samples
        val voiceGain = gainMultiplier.takeIf { it > 0f && it < 10f } ?: 1.0f
        var gain = normalizeGain(samples) * voiceGain
        val peak = samples.maxOfOrNull { abs(it) } ?: 0f
        if (peak > 0f && peak * gain > PEAK_LIMIT) {
            gain = PEAK_LIMIT / peak
        }
        if (gain == 1.0f) return samples
        return FloatArray(samples.size) { index ->
            val value = samples[index] * gain
            if (value > 1f) 1f else if (value < -1f) -1f else value
        }
    }

    /** 供 UI 展示的增益文案（dB，一位小数；1.0 → 0.0dB） */
    fun gainDbText(gain: Float): String {
        val db = 20f * log10(max(gain, 1e-3f))
        return String.format(java.util.Locale.US, "%.1fdB", (db * 10f).roundToInt() / 10f)
    }
}
