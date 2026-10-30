package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * 参考音频加工（T2.2）：立体声下混 / 静音裁剪 / 重采样 / 时长校验 / 全静音保护。
 */
class ReferenceAudioProcessorTest {

    private fun sine(sampleRate: Int, durationMs: Int, freq: Float = 220f, amp: Float = 0.6f): FloatArray {
        val count = sampleRate * durationMs / 1000
        return FloatArray(count) { i -> amp * sin(2.0 * Math.PI * freq * i / sampleRate).toFloat() }
    }

    private fun monoWav(samples: FloatArray, sampleRate: Int): ByteArray =
        PcmAudioUtils.wavBytes(PcmAudioUtils.floatToPcm16(samples), sampleRate)

    /** 手工拼立体声 WAV（PcmAudioUtils 只写单声道，这里补一个带 fmt 头的最小实现） */
    private fun stereoWav(left: FloatArray, right: FloatArray, sampleRate: Int): ByteArray {
        val frames = minOf(left.size, right.size)
        val data = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val l = (left[i] * 32767f).toInt()
            val r = (right[i] * 32767f).toInt()
            data[i * 4] = (l and 0xFF).toByte()
            data[i * 4 + 1] = (l shr 8 and 0xFF).toByte()
            data[i * 4 + 2] = (r and 0xFF).toByte()
            data[i * 4 + 3] = (r shr 8 and 0xFF).toByte()
        }
        val header = ByteArray(44)
        "RIFF".toByteArray().copyInto(header, 0)
        putInt(header, 4, 36 + data.size)
        "WAVE".toByteArray().copyInto(header, 8)
        "fmt ".toByteArray().copyInto(header, 12)
        putInt(header, 16, 16)
        putShort(header, 20, 1)
        putShort(header, 22, 2) // 双声道
        putInt(header, 24, sampleRate)
        putInt(header, 28, sampleRate * 4)
        putShort(header, 32, 4)
        putShort(header, 34, 16)
        "data".toByteArray().copyInto(header, 36)
        putInt(header, 40, data.size)
        return header + data
    }

    private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = (value shr 8 and 0xFF).toByte()
        bytes[offset + 2] = (value shr 16 and 0xFF).toByte()
        bytes[offset + 3] = (value shr 24 and 0xFF).toByte()
    }

    private fun putShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = (value shr 8 and 0xFF).toByte()
    }

    @Test
    fun `立体声下混为单声道且帧数减半`() {
        val left = sine(16000, 1000)
        val right = sine(16000, 1000)
        val audio = PcmAudioUtils.readWavDownmix(stereoWav(left, right, 16000).inputStream())
        assertEquals(16000, audio.sampleRate)
        // 每帧 4 字节 → 下混后每帧 2 字节
        assertEquals(left.size * 2, audio.pcm.size)
    }

    @Test
    fun `首尾长静音被裁掉`() {
        val speech = sine(16000, 4000)
        val silence = FloatArray(16000 * 2)
        val source = silence + speech + silence
        val result = ReferenceAudioProcessor.process(monoWav(source, 16000), 16000)
        assertTrue("应加工成功：$result", result is ReferenceAudioProcessor.Result.Success)
        result as ReferenceAudioProcessor.Result.Success
        assertTrue("应发生裁剪", result.trimmed)
        assertTrue("裁剪后应明显变短：${result.durationMs}", result.durationMs < 5000)
    }

    @Test
    fun `重采样到目标采样率`() {
        val result = ReferenceAudioProcessor.process(monoWav(sine(16000, 5000), 16000), 24000)
        assertTrue(result is ReferenceAudioProcessor.Result.Success)
        result as ReferenceAudioProcessor.Result.Success
        assertEquals(24000, result.sampleRate)
        val check = PcmAudioUtils.readWavDownmix(result.wavBytes.inputStream())
        assertEquals(24000, check.sampleRate)
    }

    @Test
    fun `过短音频被拒绝`() {
        val result = ReferenceAudioProcessor.process(monoWav(sine(16000, 1200), 16000), 24000)
        assertTrue(result is ReferenceAudioProcessor.Result.Failure)
        assertTrue((result as ReferenceAudioProcessor.Result.Failure).reason.contains("过短"))
    }

    @Test
    fun `过长音频被拒绝`() {
        val result = ReferenceAudioProcessor.process(monoWav(sine(16000, 20000), 16000), 24000)
        assertTrue(result is ReferenceAudioProcessor.Result.Failure)
        assertTrue((result as ReferenceAudioProcessor.Result.Failure).reason.contains("过长"))
    }

    @Test
    fun `全静音直接拒绝`() {
        val result = ReferenceAudioProcessor.process(monoWav(FloatArray(16000 * 4), 16000), 16000)
        assertTrue(result is ReferenceAudioProcessor.Result.Failure)
        assertTrue((result as ReferenceAudioProcessor.Result.Failure).reason.contains("无声音"))
    }

    @Test
    fun `峰值归一后不超过目标峰值`() {
        // 输入峰值 0.2（很小声但非静音）→ 归一后峰值应接近 0.92 的量化上限
        val result = ReferenceAudioProcessor.process(monoWav(sine(16000, 5000, amp = 0.2f), 16000), 16000)
        assertTrue(result is ReferenceAudioProcessor.Result.Success)
        val processed = PcmAudioUtils.readWavDownmix(
            (result as ReferenceAudioProcessor.Result.Success).wavBytes.inputStream()
        )
        val peak = PcmAudioUtils.pcm16ToFloat(processed.pcm).maxOfOrNull { kotlin.math.abs(it) } ?: 0f
        assertTrue("峰值应被抬到接近 0.92：$peak", peak > 0.8f)
        assertTrue("峰值不得超过 1.0：$peak", peak <= 1.0f)
    }
}
