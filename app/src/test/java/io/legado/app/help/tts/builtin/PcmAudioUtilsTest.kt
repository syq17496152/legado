package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * PCM/WAV 工具（T9.3 基础件）：WAV 往返、静音裁剪、F0 估计。
 */
class PcmAudioUtilsTest {

    private fun sine(freq: Double, seconds: Double, sampleRate: Int): FloatArray {
        val count = (seconds * sampleRate).toInt()
        return FloatArray(count) { i ->
            sin(2.0 * Math.PI * freq * i / sampleRate).toFloat() * 0.8f
        }
    }

    @Test
    fun `wav 字节往返一致`() {
        val samples = floatArrayOf(0.5f, -0.25f, 0.125f)
        val pcm = PcmAudioUtils.floatToPcm16(samples)
        val wav = PcmAudioUtils.wavBytes(pcm, 24000)
        val parsed = PcmAudioUtils.readWav(wav.inputStream())
        assertEquals(24000, parsed.sampleRate)
        assertEquals(pcm.size, parsed.pcm.size)
        assertEquals(0.5f, PcmAudioUtils.pcm16ToFloat(parsed.pcm)[0], 0.01f)
    }

    @Test
    fun `截止低频正弦的基频可被估计`() {
        val f0 = PcmAudioUtils.estimateF0(sine(200.0, 0.4, 16000), 16000)
        assertTrue("F0 估计偏差过大：$f0", f0 in 175f..235f)
    }

    @Test
    fun `全静音返回 0 且不崩溃`() {
        val f0 = PcmAudioUtils.estimateF0(FloatArray(8000), 16000)
        assertEquals(0f, f0, 0.001f)
    }

    @Test
    fun `首尾静音被裁掉`() {
        val speech = sine(180.0, 1.0, 16000)
        val silence = FloatArray(16000) { 0f }
        val padded = silence + speech + silence
        val trimmed = PcmAudioUtils.trimSilence(padded, 16000)
        assertTrue("未裁剪：${trimmed.size} / ${padded.size}", trimmed.size < padded.size)
        assertTrue(trimmed.size > speech.size / 2)
    }

    @Test
    fun `峰值归一不放大`() {
        val quiet = floatArrayOf(0.1f, -0.2f)
        val normalized = PcmAudioUtils.normalizePeak(quiet, 0.9f)
        assertEquals(0.2f, normalized.maxOf { kotlin.math.abs(it) }, 0.0001f)
    }

    /**
     * A4：段间 80ms 增益包络 —— 段首尾必须被压到 0（消除拼接爆音），中段保持原样；
     * 极短段（不足两倍淡入长度）原样返回，避免把语气词削没。
     */
    @Test
    fun `段间包络压平头尾且不改中段`() {
        val sampleRate = 16000
        val samples = FloatArray(sampleRate / 2) { 0.5f } // 500ms 恒定信号
        val out = PcmAudioUtils.applyEdgeEnvelope(samples, sampleRate)
        val fade = sampleRate * PcmAudioUtils.EDGE_FADE_MS / 1000
        assertEquals(0f, out[0], 0.0001f)
        assertEquals(0f, out[out.lastIndex], 0.0001f)
        assertEquals("中段不得衰减", 0.5f, out[fade + 100], 0.0001f)
        // 淡入段线性递增（第一个采样之后开始变响）
        assertTrue(out[1] < out[fade - 1])
    }

    @Test
    fun `极短音频不做包络`() {
        val samples = floatArrayOf(0.5f, 0.5f, 0.5f)
        assertSame(samples, PcmAudioUtils.applyEdgeEnvelope(samples, 16000))
        assertSame(samples, PcmAudioUtils.applyEdgeEnvelope(samples, 0))
    }
}
