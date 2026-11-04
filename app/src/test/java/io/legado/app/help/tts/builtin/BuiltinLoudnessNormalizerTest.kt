package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 跨音色响度归一（T8.1）：阈值内不动 / 过小抬升 / 过大压低 / 音色增益叠加 / 峰值限制。
 */
class BuiltinLoudnessNormalizerTest {

    private fun constant(value: Float, size: Int = 1024): FloatArray = FloatArray(size) { value }

    @Test
    fun `偏差在3dB内不调整且返回原数组`() {
        // RMS = 0.12，相对目标 0.10 为 +1.6dB → 阈值内
        val samples = constant(0.12f)
        assertSame(samples, BuiltinLoudnessNormalizer.apply(samples))
        assertEquals(1.0f, BuiltinLoudnessNormalizer.normalizeGain(samples), 0.0001f)
    }

    @Test
    fun `过小音频抬升但不超过上限`() {
        // RMS = 0.01 → 需要 10 倍，夹紧到 MAX_GAIN=2.0
        val samples = constant(0.01f)
        val gain = BuiltinLoudnessNormalizer.normalizeGain(samples)
        assertEquals(BuiltinLoudnessNormalizer.MAX_GAIN, gain, 0.0001f)
        val out = BuiltinLoudnessNormalizer.apply(samples)
        assertEquals(0.02f, out[0], 0.001f)
    }

    @Test
    fun `过大音频被压低`() {
        // RMS = 0.8 → 目标增益 0.125，夹紧到 MIN_GAIN=0.5
        val samples = constant(0.8f)
        val gain = BuiltinLoudnessNormalizer.normalizeGain(samples)
        assertEquals(BuiltinLoudnessNormalizer.MIN_GAIN, gain, 0.0001f)
    }

    @Test
    fun `音色增益叠加后生效`() {
        val samples = constant(0.1f)
        val out = BuiltinLoudnessNormalizer.apply(samples, gainMultiplier = 1.5f)
        // 归一增益 1.0 → 仅音色增益 1.5
        assertEquals(0.15f, out[0], 0.001f)
    }

    @Test
    fun `峰值限制防削波`() {
        val samples = constant(0.9f)
        val out = BuiltinLoudnessNormalizer.apply(samples, gainMultiplier = 1.5f)
        val peak = out.maxOfOrNull { abs(it) } ?: 0f
        assertTrue("峰值应被压到限制内：$peak", peak <= BuiltinLoudnessNormalizer.PEAK_LIMIT + 0.0001f)
    }

    @Test
    fun `静音不调整`() {
        val samples = FloatArray(512)
        val out = BuiltinLoudnessNormalizer.apply(samples)
        assertSame(samples, out)
    }

    @Test
    fun `增益文案按dB换算`() {
        assertEquals("0.0dB", BuiltinLoudnessNormalizer.gainDbText(1.0f))
        assertEquals("-6.0dB", BuiltinLoudnessNormalizer.gainDbText(0.5f))
        assertEquals("6.0dB", BuiltinLoudnessNormalizer.gainDbText(2.0f))
    }

    /**
     * A3 验收：跨音色平均响度差 ≤2dB（设计 §1.8）。
     * 两个音色原始能量差近 9dB，归一后必须落在同一目标附近；接近目标的音色保持原样（不抖动）。
     */
    @Test
    fun `跨音色归一后平均响度差不超过2dB`() {
        val quiet = BuiltinLoudnessNormalizer.apply(constant(0.06f))
        val loud = BuiltinLoudnessNormalizer.apply(constant(0.16f))
        val near = BuiltinLoudnessNormalizer.apply(constant(0.12f))

        val db = { value: Float ->
            20f * kotlin.math.log10(value / BuiltinLoudnessNormalizer.TARGET_RMS)
        }
        val quietRms = BuiltinLoudnessNormalizer.rms(quiet)
        val loudRms = BuiltinLoudnessNormalizer.rms(loud)
        val nearRms = BuiltinLoudnessNormalizer.rms(near)
        assertTrue(
            "两个偏远音色归一后应接近目标：$quietRms / $loudRms",
            abs(db(quietRms)) <= 2f && abs(db(loudRms)) <= 2f
        )
        // 阈值内（+1.6dB）不调整：避免为每个音色引入无意义增益抖动
        assertEquals(0.12f, nearRms, 0.001f)
    }
}
