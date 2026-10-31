package io.legado.app.help.readaloud.export

import io.legado.app.help.readaloud.role.OfflineRoleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T8.5 章节音频导出计划：分镜 → (音色, 文本) 段表的纯逻辑。
 * 关键口径：旁白/未识别/未配音一律回落旁白音色（整章连续，不静默丢段）。
 */
class AudioExportPlanTest {

    private fun seg(
        paragraph: Int,
        start: Int,
        end: Int,
        roleType: String,
        name: String = "",
        id: Long = 0L
    ) = OfflineRoleSegment(
        paragraphIndex = paragraph,
        start = start,
        end = end,
        roleType = roleType,
        characterName = name,
        characterId = id
    )

    @Test
    fun `旁白与未识别段落回落旁白音色`() {
        val paragraphs = listOf("旁白一句。张三说：你好。")
        val segments = listOf(
            seg(0, 0, 5, "narrator"),
            // 已识别但未配音（characterId=0 / 无绑定）也必须回落旁白
            seg(0, 5, 10, "character", name = "张三", id = 99L)
        )
        val pieces = AudioExportPlan.build(
            segments = segments,
            paragraphs = paragraphs,
            characterVoices = emptyMap(),
            narratorVoice = "narrator_v"
        )
        assertEquals(2, pieces.size)
        assertTrue(pieces.all { it.voiceName == "narrator_v" })
        assertEquals("张三", pieces[1].roleName)
        assertEquals(AudioExportPlan.NARRATOR_LABEL, pieces[0].roleName)
    }

    @Test
    fun `已识别且已配音的角色使用绑定音色`() {
        val paragraphs = listOf("张三说：今天出发。")
        val segments = listOf(seg(0, 0, 10, "character", name = "张三", id = 7L))
        val pieces = AudioExportPlan.build(
            segments = segments,
            paragraphs = paragraphs,
            characterVoices = mapOf(7L to "b6_m_youth"),
            narratorVoice = "narrator_v"
        )
        assertEquals(1, pieces.size)
        assertEquals("b6_m_youth", pieces[0].voiceName)
        assertEquals("张三", pieces[0].roleName)
        assertEquals("张三说：今天出发。", pieces[0].text)
    }

    @Test
    fun `越界区间被夹紧且空文本被跳过`() {
        val paragraphs = listOf("短句")
        val segments = listOf(
            seg(0, 0, 999, "narrator"),
            seg(5, 0, 10, "narrator"), // 段落不存在
            seg(0, 3, 3, "narrator") // 零长度
        )
        val pieces = AudioExportPlan.build(segments, paragraphs, emptyMap(), "narrator_v")
        assertEquals(1, pieces.size)
        assertEquals("短句", pieces[0].text)
    }

    @Test
    fun `长段落按标点切块且拼回等价原文`() {
        val longText = "第一句。" + "字".repeat(120) + "。" + "第二句尾"
        val paragraphs = listOf(longText)
        val segments = listOf(seg(0, 0, longText.length, "narrator"))
        val pieces = AudioExportPlan.build(
            segments = segments,
            paragraphs = paragraphs,
            characterVoices = emptyMap(),
            narratorVoice = "narrator_v",
            maxChars = 60
        )
        assertTrue(pieces.size > 1)
        // 每块都不超过上限；拼回去（去空白）与原文一致
        pieces.forEach { assertTrue(it.text.length <= 60) }
        assertEquals(longText.replace(Regex("\\s+"), ""), pieces.joinToString("") { it.text }.replace(Regex("\\s+"), ""))
    }

    @Test
    fun `文件名清洗非法字符并兜底`() {
        assertEquals("斗破-第1章.wav", AudioExportPlan.fileName("斗破", "第1章"))
        assertEquals("a_b-章节_b.wav", AudioExportPlan.fileName("a/b", "章节:b"))
        assertEquals("book-chapter.wav", AudioExportPlan.fileName("   ", ""))
        // 超长名截断到 24 字，避免 SAF 拒绝
        val long = "字".repeat(40)
        assertTrue(AudioExportPlan.fileName(long, long).length <= 24 + 24 + 5)
    }

    @Test
    fun `结果文案含段数耗时体积与失败数`() {
        assertEquals("已导出 3/3 段 · 12s · 1 MB", AudioExportPlan.summary(3, 0, 12, 1024L * 1024))
        assertEquals("已导出 2/3 段 · 12s · 512 B · 失败 1", AudioExportPlan.summary(3, 1, 12, 512))
    }
}
