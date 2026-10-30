package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自录音色（T2.4）纯逻辑：状态文案 / 时长边界 / 超限提示。
 */
class ReferenceAudioRecorderTest {

    @Test
    fun `录制中显示秒数与建议时长`() {
        assertEquals("录制中 7s（建议 5~10 秒）", ReferenceAudioRecorder.statusText(true, 7000))
        assertTrue(ReferenceAudioRecorder.statusText(false, 0).contains("至少 3 秒"))
    }

    @Test
    fun `时长边界为3到15秒`() {
        assertFalse(ReferenceAudioRecorder.durationValid(2999))
        assertTrue(ReferenceAudioRecorder.durationValid(3000))
        assertTrue(ReferenceAudioRecorder.durationValid(15000))
        assertFalse(ReferenceAudioRecorder.durationValid(15001))
    }

    @Test
    fun `超限与过短给出可操作提示`() {
        assertTrue(ReferenceAudioRecorder.durationHint(2000).contains("太短"))
        assertTrue(ReferenceAudioRecorder.durationHint(16000).contains("太长"))
        assertTrue(ReferenceAudioRecorder.durationHint(6000).contains("合适"))
        assertEquals("", ReferenceAudioRecorder.durationHint(0))
    }
}
