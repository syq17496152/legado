package io.legado.app.service

import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeech
import io.legado.app.help.tts.builtin.BuiltinEngineState
import io.legado.app.help.tts.builtin.BuiltinTtsRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置 TTS 引擎服务（T1.5）纯逻辑：语速百分比换算 + 回调分块（G-01 配对内置 TTS 服务与朗读服务改动）。
 */
class BuiltinTtsServiceTest {

    @Test
    fun `语速百分比换算为倍率`() {
        assertEquals(1.0f, BuiltinTtsSynthesisMath.speedFromPercent(100), 0.001f)
        assertEquals(1.5f, BuiltinTtsSynthesisMath.speedFromPercent(150), 0.001f)
        assertEquals(0.5f, BuiltinTtsSynthesisMath.speedFromPercent(50), 0.001f)
    }

    @Test
    fun `缺省或非法语速回落 1x`() {
        assertEquals(1.0f, BuiltinTtsSynthesisMath.speedFromPercent(0), 0.001f)
        assertEquals(1.0f, BuiltinTtsSynthesisMath.speedFromPercent(-1), 0.001f)
    }

    @Test
    fun `超范围语速被夹紧`() {
        assertEquals(2.0f, BuiltinTtsSynthesisMath.speedFromPercent(300), 0.001f)
    }

    @Test
    fun `回调分块约 200ms 且有下限`() {
        assertEquals(9600, BuiltinTtsSynthesisMath.chunkBytes(24000, 200))
        assertEquals(8820, BuiltinTtsSynthesisMath.chunkBytes(22050, 200))
        assertEquals(2048, BuiltinTtsSynthesisMath.chunkBytes(1000, 200))
    }

    /**
     * 未初始化时状态查询安全返回 NONE
     */
    @Test
    fun `未初始化时状态查询安全返回 NONE`() {
        assertFalse(BuiltinTtsRuntime.isInitialized())
        assertEquals(BuiltinEngineState.NONE, BuiltinTtsRuntime.state())
        assertFalse(BuiltinTtsRuntime.isReady())
    }

    @Test
    fun `角色徽章文案`() {
        assertEquals("正在朗读", BuiltinNotificationText.content("正在朗读", null))
        assertEquals("正在朗读", BuiltinNotificationText.content("正在朗读", "  "))
        assertEquals("正在朗读 · 正在朗读：张三", BuiltinNotificationText.content("正在朗读", "张三"))
    }

    // ---- BuiltinTtsCallbackWriter（真机「合成完成但无声」修复的配对用例） ----

    @Test
    fun `分块不得超过框架单次缓冲上限`() {
        val writer = BuiltinTtsCallbackWriter(FakeCallback(maxBufferSize = 4096))
        assertEquals(4096, writer.chunkBytes(22050))
        assertEquals(4096, writer.chunkBytes(24000))
    }

    @Test
    fun `上限大于期望时保持 200ms 分块`() {
        val writer = BuiltinTtsCallbackWriter(FakeCallback(maxBufferSize = 65536))
        assertEquals(8820, writer.chunkBytes(22050))
        assertEquals(9600, writer.chunkBytes(24000))
    }

    @Test
    fun `上限为奇数字节时按帧对齐到偶数`() {
        val writer = BuiltinTtsCallbackWriter(FakeCallback(maxBufferSize = 4095))
        assertEquals(4094, writer.chunkBytes(24000))
    }

    @Test
    fun `上限非法时回落 200ms 分块`() {
        val writer = BuiltinTtsCallbackWriter(FakeCallback(maxBufferSize = 0))
        assertEquals(9600, writer.chunkBytes(24000))
    }

    @Test
    fun `写入按块推进且全部成功时报告完成`() {
        val fake = FakeCallback(maxBufferSize = 4096)
        val writer = BuiltinTtsCallbackWriter(fake)
        val pcm = ByteArray(10000)
        val report = writer.write(pcm, 2048)
        assertEquals(10000, report.writtenBytes)
        assertEquals(10000, report.totalBytes)
        assertEquals(-1, report.failedAt)
        assertTrue(report.completed)
        assertEquals(listOf(0, 2048, 4096, 6144, 8192), fake.offsets)
    }

    @Test
    fun `首块被拒时立即停止并报告偏移与返回码`() {
        val fake = FakeCallback(maxBufferSize = 4096).apply { failAtOffset = 2048 }
        val writer = BuiltinTtsCallbackWriter(fake)
        val report = writer.write(ByteArray(10000), 2048)
        assertEquals(2048, report.writtenBytes)
        assertEquals(2048, report.failedAt)
        assertEquals(TextToSpeech.ERROR, report.errorCode)
        assertFalse(report.completed)
        assertEquals(listOf(0, 2048), fake.offsets)
    }

    @Test
    fun `回调抛出异常时按失败处理`() {
        val fake = FakeCallback(maxBufferSize = 4096).apply { throwOnAudioAvailable = true }
        val writer = BuiltinTtsCallbackWriter(fake)
        val report = writer.write(ByteArray(4096), 2048)
        assertEquals(0, report.writtenBytes)
        assertEquals(0, report.failedAt)
        assertFalse(report.completed)
    }

    @Test
    fun `start 抛出异常时回落 ERROR`() {
        val fake = FakeCallback(maxBufferSize = 4096).apply { throwOnStart = true }
        val writer = BuiltinTtsCallbackWriter(fake)
        assertEquals(TextToSpeech.ERROR, writer.start(22050, 1))
    }

    private class FakeCallback(private val maxBufferSize: Int) : SynthesisCallback {

        val offsets = mutableListOf<Int>()
        var failAtOffset: Int = -1
        var throwOnAudioAvailable: Boolean = false
        var throwOnStart: Boolean = false

        override fun getMaxBufferSize(): Int = maxBufferSize

        override fun start(sampleRateInHz: Int, audioFormat: Int, channelCount: Int): Int {
            if (throwOnStart) throw IllegalStateException("start 不可用")
            return TextToSpeech.SUCCESS
        }

        override fun audioAvailable(buffer: ByteArray, offset: Int, length: Int): Int {
            if (throwOnAudioAvailable) throw IllegalStateException("audioAvailable 不可用")
            offsets.add(offset)
            if (failAtOffset >= 0 && offset == failAtOffset) return TextToSpeech.ERROR
            return TextToSpeech.SUCCESS
        }

        override fun done(): Int = TextToSpeech.SUCCESS

        override fun error() = Unit

        override fun error(errorCode: Int) = Unit

        override fun hasFinished(): Boolean = true

        override fun hasStarted(): Boolean = true
    }
}
