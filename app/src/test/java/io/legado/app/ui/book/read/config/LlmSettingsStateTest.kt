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

    /**
     * 「下载推荐档」入口：已装同 id 必须隐藏（否则会把 396MB 重复下载一遍），
     * 未装才显示，目录缺档/坏数据一律不显示。
     */
    @Test
    fun `下载入口只在未安装推荐档时出现`() {
        assertEquals(
            "Qwen3 0.6B（推荐）",
            LlmSettingsState.downloadEntryTitle(
                "qwen3-0.6b-q4", "Qwen3 0.6B（推荐）", emptyList()
            )
        )
        assertEquals(
            "",
            LlmSettingsState.downloadEntryTitle(
                "qwen3-0.6b-q4", "Qwen3 0.6B（推荐）", listOf("qwen3-0.6b-q4")
            )
        )
        // 装的是"别的档位"时仍应显示：用户没装推荐档，就该能看到下载入口
        assertEquals(
            "Qwen3 0.6B（推荐）",
            LlmSettingsState.downloadEntryTitle(
                "qwen3-0.6b-q4", "Qwen3 0.6B（推荐）", listOf("qwen")
            )
        )
        assertEquals(
            "",
            LlmSettingsState.downloadEntryTitle(null, null, emptyList())
        )
        assertEquals(
            "",
            LlmSettingsState.downloadEntryTitle("id", " ", emptyList())
        )
    }

    /**
     * 自检文案：成功要给出加载耗时/ tok/s（用户判断"这台机跑不跑得动"的唯一依据），
     * 失败必须带原因（不能只说"自检失败"）。
     */
    @Test
    fun `自检文案成功与失败两种形态`() {
        val ok = LlmSettingsState.benchMessage(
            ok = true, loadMs = 32_870L, tokenCount = 64, tokPerSec = 9.48f, failureMessage = ""
        )
        assertTrue(ok.contains("自检完成"))
        assertTrue(ok.contains("32870ms"))
        assertTrue(ok.contains("64 token"))
        assertTrue(ok.contains("9.5 tok/s"))

        val failed = LlmSettingsState.benchMessage(
            ok = false, loadMs = 0L, tokenCount = 0, tokPerSec = 0f, failureMessage = "加载超时"
        )
        assertTrue(failed.contains("自检失败"))
        assertTrue("失败原因必须带出", failed.contains("加载超时"))

        val noReason = LlmSettingsState.benchMessage(
            ok = false, loadMs = 0L, tokenCount = 0, tokPerSec = 0f, failureMessage = "  "
        )
        assertTrue("原因为空要有兜底文案", noReason.contains("未知原因"))
    }

    /**
     * 调试注入的 IPC 自检文案：L3 脚本按「连接推理进程 成功」判断链路是否成立，
     * 文案即契约 —— 这里锁死，防止后续改字把 7.2 的判据悄悄改坏。
     */
    @Test
    fun `IPC 自检文案是 L3 脚本契约`() {
        val ok = LlmSettingsState.ipcSelfCheckMessage(connected = true, phase = "READY")
        assertTrue("必须含脚本断言的关键词", ok.contains("连接推理进程 成功"))
        assertTrue("必须带出推理进程状态", ok.contains("READY"))

        val unknown = LlmSettingsState.ipcSelfCheckMessage(connected = true, phase = "")
        assertTrue("状态为空要有兜底文案，不能出现空括号", unknown.contains("状态=未知"))

        val failed = LlmSettingsState.ipcSelfCheckMessage(connected = false)
        assertFalse("失败文案不得包含「成功」，否则脚本会误判 PASS", failed.contains("成功"))
        assertTrue(failed.contains("连接推理进程 失败"))
    }
}
