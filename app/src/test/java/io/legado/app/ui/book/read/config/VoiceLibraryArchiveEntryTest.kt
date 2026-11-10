package io.legado.app.ui.book.read.config

import io.legado.app.help.tts.builtin.BuiltinVoice
import io.legado.app.help.tts.builtin.BuiltinVoiceFilter
import io.legado.app.help.tts.builtin.VoiceLibraryCurator
import io.legado.app.help.tts.builtin.VoiceTagNormalizer
import io.legado.app.testkit.SourceFileProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // 序号名先按名称族归（珂珂/艾舍尔），再按性别/年龄
        assertEquals("本档可用 · 珂珂 · 女声 · 青年（1）", sections.first().title)
        assertEquals("其他档位 · 艾舍尔 · 男声 · 中年（1）", sections.last().title)
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

    /**
     * 真机铁证 2026-11-10：年龄 chips 放在 horizontalScroll 里，1200px 屏把「未标年龄」裁掉半边
     * （与「管理角色」被挤出可视区同一类失守）⇒ 必须自动换行。
     */
    @Test
    fun ageFilterChipsWrapInsteadOfHorizontalScroll() {
        // SourceFileProbe.sourceText 会剥掉行注释 ⇒ 锚点必须用代码标识；
        // 且 AGE_ORDER 在 FlowRow 内部，取它**之前**的一段才是容器声明处
        val ageBlock = panel.substringAfter("\"未打标\"")
            .substringBefore("VoiceTagNormalizer.AGE_ORDER")
        assertTrue("年龄 chips 应使用 FlowRow 自动换行", ageBlock.contains("FlowRow"))
        assertFalse(
            "年龄 chips 不得放 horizontalScroll（会把「未标年龄」裁掉）",
            ageBlock.contains("horizontalScroll")
        )
    }

    /** NER 入口必须真的开选择器：只 startActivity 跳页会让用户以为"点了没反应" */
    @Test
    fun assetCenterNerImportOpensFilePicker() {
        assertTrue("缺 NER 文件选择器", assetCenter.contains("pickNerZip"))
        assertTrue("IMPORT_NER 未接选择器", assetCenter.contains("AssetAction.IMPORT_NER ->"))
        assertTrue("未调用模型导入", assetCenter.contains("NerModelManager.importZip"))
    }

    /** 性格是唯一无法自动推断的维度 ⇒ 必须能从编辑弹窗多选标注并参与筛选 */
    @Test
    fun voiceMetaDialogCarriesMoodPicker() {
        assertTrue("编辑弹窗缺性格多选", panel.contains("MOOD_ORDER.forEach"))
        assertTrue("保存动作未携带性格", screens.contains("val moods: List<String>"))
        assertTrue("Activity 未透传性格", activity.contains("action.moods"))
        assertTrue("性格写入未走统一口径", activity.contains("BuiltinVoiceOps.saveMeta"))
    }
}
