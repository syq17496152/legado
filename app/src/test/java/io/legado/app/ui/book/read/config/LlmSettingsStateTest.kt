package io.legado.app.ui.book.read.config

import io.legado.app.help.config.LocalLlmPrefs
import io.legado.app.help.llm.LocalLlmFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.4/2.5 本地 AI 设置页状态：三层判定（引擎/设备/模型）+ 开关可拨性 + 策略与速度文案。
 * 关键红线：低端机或引擎缺失时**整块隐藏**而不是灰色开关；没有模型时不能误开开关。
 */
class LlmSettingsStateTest {

    private fun facts(
        engine: Boolean = true,
        model: Boolean = true,
        ramMb: Long = 12_000L,
        enabled: Boolean = true,
        policy: String = LocalLlmPrefs.POLICY_LOCAL,
        speed: Float = 0f,
        models: List<Triple<String, String, Long>> = listOf(
            Triple("qwen3-0.6b", "Qwen3-0.6B", 396_705_472L)
        )
    ) = LlmSettingsState.Facts(engine, model, ramMb, enabled, policy, speed, models, models.firstOrNull()?.first.orEmpty())

    @Test
    fun `低端机整块隐藏且给出内存原因`() {
        val ui = LlmSettingsState.build(facts(ramMb = 4000L))
        assertFalse("4GB 设备不显示本地 AI 入口", ui.showEntry)
        assertFalse(ui.switchEnabled)
        assertFalse(ui.switchChecked)
        assertTrue(ui.reason.contains("内存不足"))
        assertTrue(ui.reason.contains("3.9GB"))
    }

    @Test
    fun `引擎缺失整块隐藏`() {
        val ui = LlmSettingsState.build(facts(engine = false, model = false))
        assertFalse(ui.showEntry)
        assertTrue(ui.reason.contains("未包含本地 AI 引擎"))
    }

    @Test
    fun `没有模型时入口可见但开关不可拨`() {
        val ui = LlmSettingsState.build(facts(model = false, enabled = false, models = emptyList()))
        assertTrue(ui.showEntry)
        assertFalse(ui.switchEnabled)
        assertFalse(ui.switchChecked)
        assertTrue(ui.reason.contains("还没有模型"))
        assertTrue(ui.importHint.contains(".gguf"))
    }

    @Test
    fun `三层齐备时可开启并显示模型列表`() {
        val ui = LlmSettingsState.build(facts())
        assertTrue(ui.showEntry)
        assertTrue(ui.switchEnabled)
        assertTrue(ui.switchChecked)
        assertEquals("本地 AI 已就绪", ui.reason)
        assertEquals(1, ui.models.size)
        assertEquals("378MB", ui.models.first().sizeText)
        assertTrue("首个模型默认标使用中", ui.models.first().active)
    }

    @Test
    fun `策略与速度文案三态`() {
        assertEquals("仅本地（不出端）", LlmSettingsState.policyLabel(LocalLlmPrefs.POLICY_LOCAL))
        assertEquals("云端优先", LlmSettingsState.policyLabel(LocalLlmPrefs.POLICY_ONLINE))
        assertEquals("关闭 AI 任务", LlmSettingsState.policyLabel(LocalLlmPrefs.POLICY_OFF))
        // 未知策略回落 local（LocalLlmPrefs 单源）
        assertEquals("仅本地（不出端）", LlmSettingsState.policyLabel("weird"))

        assertEquals("未自检", LlmSettingsState.speedText(0f))
        assertEquals("12.3 tok/s", LlmSettingsState.speedText(12.3f))
    }

    @Test
    fun `LocalLlmFeature 判定顺序与隐藏口径`() {
        val weak = LocalLlmFeature.evaluate(
            LocalLlmFeature.Facts(engineAvailable = true, modelInstalled = true, totalRamMb = 4000, enabled = true)
        )
        assertEquals(LocalLlmFeature.Reason.DEVICE_TOO_WEAK, weak.reason)
        assertTrue(weak.hideEntry)

        val noEngine = LocalLlmFeature.evaluate(
            LocalLlmFeature.Facts(engineAvailable = false, modelInstalled = true, totalRamMb = 12000, enabled = true)
        )
        assertEquals(LocalLlmFeature.Reason.ENGINE_MISSING, noEngine.reason)
        assertTrue(noEngine.hideEntry)

        val noModel = LocalLlmFeature.evaluate(
            LocalLlmFeature.Facts(engineAvailable = true, modelInstalled = false, totalRamMb = 12000, enabled = false)
        )
        assertEquals(LocalLlmFeature.Reason.MODEL_MISSING, noModel.reason)
        assertFalse("缺模型属于可修复，入口仍应可见", noModel.hideEntry)

        val ready = LocalLlmFeature.evaluate(
            LocalLlmFeature.Facts(engineAvailable = true, modelInstalled = true, totalRamMb = 12000, enabled = true)
        )
        assertTrue(ready.usable)
        assertEquals(LocalLlmFeature.Reason.READY, ready.reason)
        assertEquals("11.7GB", LocalLlmFeature.ramText(12000))
    }
}
