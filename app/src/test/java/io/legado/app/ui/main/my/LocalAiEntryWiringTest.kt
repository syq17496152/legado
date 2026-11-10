package io.legado.app.ui.main.my

import io.legado.app.testkit.SourceFileProbe
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公共离线能力入口不变量（用户裁定 2026-11-10）：
 * 端侧文本模型与声音资产（音色/离线语音/理解模型/配乐/缓存）是**全局能力**，
 * 必须能从「我的 → 工具」直达；按书朗读时仍走听书设置内的同一页面（不做第二套实现）。
 *
 * 入口链两段都必须在位：① 工具分区 actionRow；② handleSettingsRowClick 跳转分支。
 * 另锁 values / values-zh 双 strings 齐备（缺 zh 会显示成资源名）。
 */
class LocalAiEntryWiringTest {

    private val data by lazy { SourceFileProbe.sourceText("ui/main/my/MySettingsData.kt") }
    private val strings by lazy { SourceFileProbe.resValuesText("strings.xml") }
    private val stringsZh by lazy {
        SourceFileProbe.sourceTextByPath("src/main/res/values-zh/strings.xml")
    }

    @Test
    fun toolsSectionCarriesLocalModelAndVoiceAssets() {
        val tools = data.substringAfter("R.string.config_category_tools")
            .substringBefore("R.string.read_record_summary")
        assertTrue(
            "工具分区缺「本地模型（端侧）」入口",
            tools.contains("actionRow(\"localAiModel\", R.string.local_ai_model")
        )
        assertTrue(
            "工具分区缺「声音与离线资源」入口",
            tools.contains("actionRow(\"voiceAssets\", R.string.voice_asset_center")
        )
    }

    @Test
    fun clickRoutesOpenSharedPages() {
        assertTrue(
            "localAiModel 未跳本地 AI 设置页（点了没反应）",
            data.contains("\"localAiModel\" -> startActivity<LlmSettingsActivity>()")
        )
        assertTrue(
            "voiceAssets 未跳声音资源中心（与听书设置复用同一页面）",
            data.contains("\"voiceAssets\" -> startActivity<VoiceAssetCenterActivity>()")
        )
    }

    @Test
    fun bothStringSetsHaveTheEntryTexts() {
        listOf(
            "local_ai_model", "local_ai_model_summary",
            "voice_asset_center", "voice_asset_center_summary"
        ).forEach { key ->
            assertTrue("默认 values 缺 $key", strings.contains("name=\"$key\""))
            assertTrue("values-zh 缺 $key", stringsZh.contains("name=\"$key\""))
        }
    }
}
