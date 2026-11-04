package io.legado.app.help.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.5 三层可用性判定：判定顺序（引擎 → 设备 → 模型 → 开关）与「隐藏入口」口径。
 */
class LocalLlmFeatureTest {

    private fun facts(
        engine: Boolean = true,
        model: Boolean = true,
        ramMb: Long = 12_000L,
        enabled: Boolean = true
    ) = LocalLlmFeature.Facts(engine, model, ramMb, enabled)

    @Test
    fun `引擎缺失优先于设备与模型判定`() {
        val verdict = LocalLlmFeature.evaluate(facts(engine = false, model = false, ramMb = 4000, enabled = false))
        assertEquals(LocalLlmFeature.Reason.ENGINE_MISSING, verdict.reason)
        assertTrue(verdict.hideEntry)
        assertFalse(verdict.usable)
    }

    @Test
    fun `低端机不支持且隐藏入口`() {
        val verdict = LocalLlmFeature.evaluate(facts(ramMb = 4096))
        assertEquals(LocalLlmFeature.Reason.DEVICE_TOO_WEAK, verdict.reason)
        assertTrue(verdict.hideEntry)
        assertTrue(verdict.message.contains("4.0GB"))
    }

    @Test
    fun `模型缺失可见但不可用 开关关闭也可用入口`() {
        val noModel = LocalLlmFeature.evaluate(facts(model = false))
        assertEquals(LocalLlmFeature.Reason.MODEL_MISSING, noModel.reason)
        assertFalse("缺模型是用户可修复项，不应隐藏入口", noModel.hideEntry)

        val disabled = LocalLlmFeature.evaluate(facts(enabled = false))
        assertEquals(LocalLlmFeature.Reason.DISABLED, disabled.reason)
        assertFalse(disabled.hideEntry)
    }

    @Test
    fun `三层齐备且开启才可用`() {
        val verdict = LocalLlmFeature.evaluate(facts())
        assertTrue(verdict.usable)
        assertEquals(LocalLlmFeature.Reason.READY, verdict.reason)
        assertEquals("11.7GB", LocalLlmFeature.ramText(12_000L))
    }
}
