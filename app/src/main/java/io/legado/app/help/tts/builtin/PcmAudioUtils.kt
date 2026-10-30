package io.legado.app.help.tts.builtin

import java.io.File
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * PCM / WAV 工具（纯 JVM，可单测）。
 * 约定：引擎内部一律 float 样本（-1f~1f），交付系统 TTS 与缓存一律 16bit 单声道 PCM。
 */
object PcmAudioUtils {

    const val WAV_HEADER_BYTES = 44

    fun floatToPcm16(samples: FloatArray, offset: Int = 0, length: Int = samples.size): ByteArray {
        val out = ByteArray(length * 2)
        var index = 0
        for (i in offset until offset + length) {
            val v = samples.getOrElse(i) { 0f }.coerceIn(-1f, 1f)
            val s = (v * 32767f).roundToInt().coerceIn(-32768, 32767)
            out[index++] = (s and 0xFF).toByte()
            out[index++] = (s shr 8 and 0xFF).toByte()
        }
        return out
    }

    fun pcm16ToFloat(pcm: ByteArray): FloatArray {
        val count = pcm.size / 2
        val out = FloatArray(count)
        for (i in 0 until count) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt() and 0xFF
            val s = (hi shl 8) or lo
            out[i] = s.toFloat() / 32768f
        }
        return out
    }

    /** 生成标准 16bit 单声道 WAV 字节 */
    fun wavBytes(pcm: ByteArray, sampleRate: Int): ByteArray {
        val byteRate = sampleRate * 2
        val dataLen = pcm.size
        return ByteArray(WAV_HEADER_BYTES + dataLen).also { out ->
            out.putAscii(0, "RIFF")
            out.putIntLe(4, 36 + dataLen)
            out.putAscii(8, "WAVE")
            out.putAscii(12, "fmt ")
            out.putIntLe(16, 16)
            out.putShortLe(20, 1)
            out.putShortLe(22, 1)
            out.putIntLe(24, sampleRate)
            out.putIntLe(28, byteRate)
            out.putShortLe(32, 2)
            out.putShortLe(34, 16)
            out.putAscii(36, "data")
            out.putIntLe(40, dataLen)
            System.arraycopy(pcm, 0, out, WAV_HEADER_BYTES, dataLen)
        }
    }

    fun readWav(file: File): CachedAudio = file.inputStream().use { readWav(it) }

    /** 解析 WAV（PCM16 单声道；非 16bit/非单声道抛 IllegalArgument） */
    fun readWav(input: InputStream): CachedAudio {
        val bytes = input.readBytes()
        require(bytes.size > WAV_HEADER_BYTES) { "WAV 文件过小" }
        var pos = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var dataOffset = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val chunkId = bytes.copyOfRange(pos, pos + 4).joinToString("") { b -> (b.toInt() and 0xFF).toChar().toString() }
            val chunkSize = bytes.readIntLe(pos + 4)
            when (chunkId) {
                "fmt " -> {
                    channels = bytes.readShortLe(pos + 10)
                    sampleRate = bytes.readIntLe(pos + 12)
                    bits = bytes.readShortLe(pos + 22)
                }
                "data" -> {
                    dataOffset = pos + 8
                    dataLen = minOf(chunkSize, bytes.size - dataOffset)
                }
            }
            if (dataOffset >= 0 && sampleRate > 0) break
            pos += 8 + chunkSize + (chunkSize % 2)
        }
        require(sampleRate > 0 && dataOffset > 0) { "WAV 头非法" }
        require(channels == 1 && bits == 16) { "仅支持 16bit 单声道 WAV（实际 $bits bit / $channels 声道）" }
        val pcm = bytes.copyOfRange(dataOffset, dataOffset + dataLen)
        return CachedAudio(sampleRate, pcm)
    }

    /**
     * 能量 VAD 裁剪首尾静音（帧长 20ms，阈值 = 最大帧 RMS 的 8%，至少保留 0.6s）。
     * 用于参考音频加工（ReferenceAudioProcessor）。
     */
    fun trimSilence(samples: FloatArray, sampleRate: Int): FloatArray {
        val frame = (sampleRate * 0.02f).roundToInt().coerceAtLeast(1)
        if (samples.size <= frame * 4) return samples
        val frameCount = samples.size / frame
        val rms = FloatArray(frameCount) { index ->
            var sum = 0.0
            val start = index * frame
            for (i in start until start + frame) {
                val v = samples[i].toDouble()
                sum += v * v
            }
            (sum / frame).let { if (it <= 0) 0f else kotlin.math.sqrt(it).toFloat() }
        }
        val maxRms = rms.maxOrNull() ?: 0f
        if (maxRms <= 1e-6f) return samples
        val threshold = maxRms * 0.08f
        val first = rms.indexOfFirst { it >= threshold }.takeIf { it >= 0 } ?: 0
        val last = rms.indexOfLast { it >= threshold }.takeIf { it >= 0 } ?: frameCount - 1
        val start = (first - 2).coerceAtLeast(0) * frame
        val end = minOf(samples.size, (last + 3) * frame)
        return if (end - start < sampleRate * 0.6f) samples else samples.copyOfRange(start, end)
    }

    /** 峰值归一（仅向下压，避免放大噪声）；返回新数组 */
    fun normalizePeak(samples: FloatArray, target: Float = 0.92f): FloatArray {
        val peak = samples.maxOfOrNull { abs(it) } ?: 0f
        if (peak <= 1e-6f || peak <= target) return samples
        val scale = target / peak
        return FloatArray(samples.size) { samples[it] * scale }
    }

    /** 线性插值重采样（参考音频加工用；质量足够用于克隆参考） */
    fun resample(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate <= 0 || toRate <= 0 || fromRate == toRate) return samples
        val outLen = (samples.size.toLong() * toRate / fromRate).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val ratio = fromRate.toDouble() / toRate
        for (i in 0 until outLen) {
            val src = i * ratio
            val i0 = src.toInt().coerceIn(0, samples.size - 1)
            val i1 = (i0 + 1).coerceAtMost(samples.size - 1)
            val frac = (src - i0).toFloat()
            out[i] = samples[i0] * (1f - frac) + samples[i1] * frac
        }
        return out
    }

    /** 毫秒静音（拼接停顿用） */
    fun silence(ms: Int, sampleRate: Int): FloatArray {
        val count = (sampleRate * ms / 1000).coerceAtLeast(0)
        return FloatArray(count)
    }

    /**
     * 基频（F0）中位数估计：分帧 + 归一化自相关峰值（60~400Hz 搜索范围）。
     * 用于音色自动打标（AD-12）：男 85~165 / 女 165~300。
     */
    fun estimateF0(samples: FloatArray, sampleRate: Int, maxFrames: Int = 30): Float {
        if (samples.size < sampleRate * 0.1f) return 0f
        val frameLen = (sampleRate * 0.04f).roundToInt()
        val hop = (samples.size - frameLen).coerceAtLeast(1) / maxFrames.coerceAtLeast(1)
        val minLag = (sampleRate / 400f).roundToInt().coerceAtLeast(2)
        val maxLag = (sampleRate / 60f).roundToInt()
        val results = ArrayList<Float>(maxFrames)
        var frameStart = 0
        while (frameStart + frameLen < samples.size && results.size < maxFrames) {
            var energy = 0.0
            for (i in frameStart until frameStart + frameLen) {
                val v = samples[i].toDouble()
                energy += v * v
            }
            if (energy / frameLen < 1e-5) {
                frameStart += hop.coerceAtLeast(frameLen)
                continue
            }
            // 归一化自相关（避免高 lag 虚高），取"第一个接近峰值的 lag"防倍频
            val scores = DoubleArray(maxLag + 1)
            for (lag in minLag..minOf(maxLag, frameLen - 1)) {
                var numerator = 0.0
                var energyA = 0.0
                var energyB = 0.0
                for (i in frameStart until frameStart + frameLen - lag) {
                    val a = samples[i].toDouble()
                    val b = samples[i + lag].toDouble()
                    numerator += a * b
                    energyA += a * a
                    energyB += b * b
                }
                val denom = sqrt(energyA * energyB)
                scores[lag] = if (denom > 1e-9) numerator / denom else 0.0
            }
            val maxScore = scores.maxOrNull() ?: 0.0
            var bestLag = 0
            if (maxScore >= 0.5) {
                for (lag in minLag..maxLag) {
                    if (scores[lag] >= maxScore * 0.85) {
                        bestLag = lag
                        break
                    }
                }
            }
            if (bestLag > 0) results.add(sampleRate.toFloat() / bestLag)
            frameStart += hop.coerceAtLeast(frameLen)
        }
        if (results.isEmpty()) return 0f
        results.sort()
        return results[results.size / 2]
    }

    private fun ByteArray.putAscii(offset: Int, text: String) {
        text.forEachIndexed { i, c -> this[offset + i] = c.code.toByte() }
    }

    private fun ByteArray.putIntLe(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = (value shr 8 and 0xFF).toByte()
        this[offset + 2] = (value shr 16 and 0xFF).toByte()
        this[offset + 3] = (value shr 24 and 0xFF).toByte()
    }

    private fun ByteArray.putShortLe(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = (value shr 8 and 0xFF).toByte()
    }

    private fun ByteArray.readIntLe(offset: Int): Int {
        return (this[offset].toInt() and 0xFF) or
                ((this[offset + 1].toInt() and 0xFF) shl 8) or
                ((this[offset + 2].toInt() and 0xFF) shl 16) or
                ((this[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun ByteArray.readShortLe(offset: Int): Int {
        return (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
    }
}
