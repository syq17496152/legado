package io.legado.app.ui.book.read.config

import io.legado.app.help.tts.builtin.BuiltinVoice
import io.legado.app.help.tts.builtin.BuiltinVoiceFilter
import io.legado.app.help.tts.builtin.VoiceLibraryCurator
import io.legado.app.help.tts.builtin.VoiceTagNormalizer
import io.legado.app.testkit.SourceFileProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-10 用户裁定「282 个音色乱七八糟 + NER 包没导入」：
 * ① 音色页必须能按 性别/年龄/性格 归档筛选，并有「整理音色库」一键收口；
 * ② 音色行副文案不得再把内部英文池名暴露给用户；
 * ③ 声音资源中心的「导入 NER 模型」必须真的落文件选择器（原先只跳页 = 没有入口）。
 */
class VoiceLibraryArchiveEntryTest {

    private val panel by lazy { SourceFileProbe.sourceText("ui/book/read/config/BuiltinTtsVoicesPanel.kt") }
    private val screens by lazy { SourceFileProbe.sourceText("ui/book/read/config/BuiltinTtsScreens.kt") }
    private val activity by lazy { SourceFileProbe.sourceText("ui/book/read/config/BuiltinTtsActivity.kt") }
    private val assetCenter by lazy { SourceFileProbe.sourceText("ui/book/read/config/VoiceAssetCenterActivity.kt") }

    // ---- 行为断言：归档与筛选真的按归一值工作 ----

    @Test
    fun archiveGroupsByUsableThenGenderThenAge() {
        val voices = listOf(
            BuiltinVoice(id = "a", name = "珂珂 3", gender = "female", age = "青年", profileId = "hq"),
            BuiltinVoice(id = "b", name = "艾舍尔 1", gender = "男", age = "中年", profileId = "multi")
        )
        val sections = VoiceLibraryCurator.archive(voices, activeProfileId = "hq")
        assertEquals("本档可用 · 女声 · 青年（1）", sections.first().title)
        assertEquals("其他档位 · 男声 · 中年（1）", sections.last().title)
    }

    @Test
    fun metaTextShowsChinesePoolAndMoodLabels() {
        val voice = BuiltinVoice(
            id = "v1",
            name = "清冷女声",
            groupId = "narration",
            gender = "女",
            age = "中年",
            tags = listOf("清冷")
        )
        // 池名转中文、年龄转中文、性格补中文；名称已含「女」不再重复
        assertEquals("导入 · 池:旁白 · 中年 · 清冷", BuiltinTtsUiSupport.voiceMetaText(voice))
    }

    @Test
    fun ageAndMoodFiltersApply() {
        val voices = listOf(
            BuiltinVoice(id = "a", name = "A", gender = "female", age = "中年", tags = listOf("沉稳")),
            BuiltinVoice(id = "b", name = "B", gender = "male", age = "少年")
        )
        val middle = BuiltinVoiceFilter.apply(
            voices,
            BuiltinVoiceFilter.Criteria(age = VoiceTagNormalizer.AGE_MIDDLE)
        )
        assertEquals(listOf("a"), middle.map { it.id })
        val calm = BuiltinVoiceFilter.apply(voices, BuiltinVoiceFilter.Criteria(mood = "calm"))
        assertEquals(listOf("a"), calm.map { it.id })
    }

    // ---- 接线断言：入口必须真的在 ----

    @Test
    fun voicesPanelCarriesCurateEntryAndArchiveFilters() {
        assertTrue("缺「整理音色库」入口", panel.contains("PageAction.CurateVoices"))
        assertTrue("缺年龄归档筛选", panel.contains("VoiceTagNormalizer.AGE_ORDER"))
        assertTrue("缺性格归档筛选", panel.contains("VoiceTagNormalizer.MOOD_ORDER"))
        assertTrue("列表未按归档分组渲染", panel.contains("VoiceLibraryCurator.archive"))
        assertTrue("池名未做中文展示", panel.contains("VoiceTagNormalizer.poolLabel"))
    }

    @Test
    fun pageActionAndDispatchDeclared() {
        assertTrue(screens.contains("data object CurateVoices : PageAction"))
        assertTrue(activity.contains("PageAction.CurateVoices ->"))
        assertTrue(activity.contains("VoiceLibraryCurator.curate("))
    }

    /** NER 入口必须真的开选择器：只 startActivity 跳页会让用户以为"点了没反应" */
    @Test
    fun assetCenterNerImportOpensFilePicker() {
        assertTrue("缺 NER 文件选择器", assetCenter.contains("pickNerZip"))
        assertTrue("IMPORT_NER 未接选择器", assetCenter.contains("AssetAction.IMPORT_NER ->"))
        assertTrue("未调用模型导入", assetCenter.contains("NerModelManager.importZip"))
    }
}
