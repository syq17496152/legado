package io.legado.app.ui.config

import io.legado.app.testkit.SourceFileProbe
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「我的 → 工具 → AI 设置」必须能直达端侧文本模型（用户裁定 2026-11-10）。
 *
 * 背景：本地 AI 设置页此前只能从听书设置深处的「本地 AI」进入，
 * 用户在 AI 设置里找不到文本模型入口 ⇒ 模型下载完也不知道去哪配。
 */
class AiConfigLocalModelEntryTest {

    private val aiConfig by lazy { SourceFileProbe.sourceText("ui/config/AiConfigFragment.kt") }

    @Test
    fun aiSettingsPageCarriesLocalModelEntry() {
        assertTrue(
            "AI 设置页缺「本地模型（端侧）」常量键",
            aiConfig.contains("const val KEY_LOCAL_MODEL")
        )
        assertTrue(
            "缺 SettingActionSpec 入口（用户仍找不到文本模型）",
            aiConfig.contains("key = KEY_LOCAL_MODEL")
        )
        assertTrue(
            "入口未跳本地 AI 设置页",
            aiConfig.contains("LlmSettingsActivity::class.java")
        )
        assertTrue(
            "标题必须走 strings（双语言资源）",
            aiConfig.contains("R.string.local_ai_model")
        )
    }
}
