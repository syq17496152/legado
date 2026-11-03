package io.legado.app.help.readaloud.role

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离线归因（AD-13 / AD-15 / AD-17 / AD-21 / T5.3）：cue 命中、不猜、对话锁、区间映射。
 */
class OfflineRoleResolverTest {

    private val characters = listOf(
        OfflineRoleResolver.RoleCharacter(id = 1L, name = "张三", gender = "male", roleLevel = 2),
        OfflineRoleResolver.RoleCharacter(id = 2L, name = "李四", gender = "female", roleLevel = 1)
    )

    private fun unit(
        id: String,
        paragraphIndex: Int,
        start: Int,
        end: Int,
        cueBefore: String = "",
        roleType: String = "character",
        kind: String = "dialogue"
    ) = ReadAloudRoleUnit(
        id = id,
        kind = kind,
        roleType = roleType,
        characterName = "",
        ranges = listOf(ReadAloudRoleRange(paragraphIndex, start, end)),
        text = "",
        cueBefore = cueBefore,
        confidence = 0.46
    )

    @Test
    fun `cue 命中角色`() {
        val paragraphs = listOf("张三说：“我们走吧。”")
        val result = OfflineRoleResolver.resolve(
            paragraphs,
            listOf(unit("u1", 0, 4, 10, cueBefore = "张三说：")),
            characters
        )
        assertEquals(1, result.segments.size)
        assertEquals("张三", result.segments[0].characterName)
        assertEquals(1L, result.segments[0].characterId)
        assertEquals("cue-hit", result.segments[0].reason)
    }

    @Test
    fun `已学 cue 在后续章自动命中（T7-5 分片）`() {
        // 场景：上一章把"淡淡道"教给李四（BookAliasStore.learnCue）；本章同模式 cue 应直接命中李四
        val paragraphs = listOf("她淡淡道：“随你。”")
        val learned = mapOf("淡淡道" to "李四")
        val result = OfflineRoleResolver.resolve(
            paragraphs,
            listOf(unit("u1", 0, 3, 9, cueBefore = "她淡淡道：")),
            characters,
            learned
        )
        assertEquals(1, result.segments.size)
        assertEquals("李四", result.segments[0].characterName)
        assertEquals(2L, result.segments[0].characterId)
    }

    @Test
    fun `张山不等于张三 未识别不猜`() {
        val paragraphs = listOf("张山说：“好。”")
        val result = OfflineRoleResolver.resolve(
            paragraphs,
            listOf(unit("u1", 0, 4, 8, cueBefore = "张山说：")),
            characters
        )
        assertEquals(1, result.segments.size)
        assertTrue("不应张冠李戴", result.segments[0].characterName.isBlank())
        assertEquals("unknown", result.segments[0].reason)
        assertEquals(0.0, result.segments[0].confidence, 0.001)
    }

    @Test
    fun `跨段 cue 上下文取最近角色名不张冠李戴`() {
        // preprocessor 的 cueBefore 会带上上一段原文：上一段出现"张三"，本段真正的 cue 是"李四说："
        val paragraphs = listOf(
            "张三说：“你终于来了，我等了很久。”",
            "李四说：“路上耽搁了，别急，先喝口茶。”"
        )
        val units = listOf(
            unit("u1", 0, 4, 18, cueBefore = "张三说："),
            unit(
                "u2", 1, 4, 22,
                cueBefore = "张三说：“你终于来了，我等了很久。”\n李四说："
            )
        )
        val result = OfflineRoleResolver.resolve(paragraphs, units, characters)
        assertEquals("张三", result.segments[0].characterName)
        assertEquals("李四", result.segments[1].characterName)
        assertEquals("cue-hit", result.segments[1].reason)
    }

    @Test
    fun `同段连续对白对话锁继承`() {
        val paragraphs = listOf("张三说：“一。”他顿了顿，又说：“二。”")
        val units = listOf(
            unit("u1", 0, 4, 5, cueBefore = "张三说："),
            unit("u2", 0, 16, 18, cueBefore = "又说：")
        )
        val result = OfflineRoleResolver.resolve(paragraphs, units, characters)
        assertEquals("张三", result.segments[0].characterName)
        assertEquals("张三", result.segments[1].characterName)
        // 锁内也可能由 NER 同段人名命中，二者都是"同一角色"的合法证据
        assertTrue(result.segments[1].reason in setOf("lock", "ner"))
    }

    @Test
    fun `轮转在已见角色间切换`() {
        val paragraphs = listOf(
            "张三说：“一。”",
            "李四说：“二。”",
            "她说：“三。”"
        )
        val units = listOf(
            unit("u1", 0, 4, 5, cueBefore = "张三说："),
            unit("u2", 1, 4, 5, cueBefore = "李四说："),
            unit("u3", 2, 3, 5, cueBefore = "")
        )
        val result = OfflineRoleResolver.resolve(paragraphs, units, characters)
        assertEquals("张三", result.segments[0].characterName)
        assertEquals("李四", result.segments[1].characterName)
        // 第三句无 cue，代词"她"指女性，上一句是李四（女）→ 只能落未识别（不猜同一人）
        assertEquals("unknown", result.segments[2].reason)
    }

    @Test
    fun `段与角色按区间交集映射`() {
        val segments = listOf(
            OfflineRoleSegment(paragraphIndex = 0, start = 10, end = 30, roleType = "character",
                characterName = "张三", characterId = 1L, confidence = 0.9, reason = "cue-hit")
        )
        val hit = OfflineRoleResolver.lookup(segments, 0, 12, 8)
        assertNotNull(hit)
        assertEquals("张三", hit!!.characterName)

        assertNull(OfflineRoleResolver.lookup(segments, 0, 0, 8))
    }

    @Test
    fun `旁白段不参与角色匹配`() {
        val paragraphs = listOf("风很大，夜里没有月亮。")
        val result = OfflineRoleResolver.resolve(
            paragraphs,
            listOf(unit("u1", 0, 0, 11, roleType = "narrator", kind = "narrator")),
            characters
        )
        assertEquals(1, result.narration)
        assertEquals("narrator", result.segments[0].roleType)
        assertTrue(result.segments[0].characterName.isBlank())
    }

    /** AD-16 主路径：模型词性 cue（名词性词元 + 说话动词 + 冒号）优先于正则/字典抽取 */
    @Test
    fun `模型 cue 命中走 ner-cue 而不是正则`() {
        val paragraphs = listOf("零点道：“别动。”")
        val withNickname = characters + OfflineRoleResolver.RoleCharacter(id = 3L, name = "零点")
        val signals: (String) -> NerTextEngine.ParagraphSignals = {
            NerTextEngine.ParagraphSignals(
                modelReady = true,
                cues = listOf(NerTextEngine.Mention("零点", 0, 2)),
                persons = emptyList()
            )
        }
        val result = OfflineRoleResolver.resolve(
            paragraphs,
            listOf(unit("u1", 0, 3, 8, cueBefore = "零点道：")),
            withNickname,
            emptyMap(),
            signals
        )
        assertEquals("零点", result.segments[0].characterName)
        assertEquals("ner-cue", result.segments[0].reason)
        assertEquals(0.90, result.segments[0].confidence, 0.001)
    }

    /** AD-16 ③ 第二级：模型 PER 实体 + 引号邻接（无显式 cue 时也能定位说话人） */
    @Test
    fun `模型 PER 引号邻接走 ner`() {
        val paragraphs = listOf("“我们走吧。”李四点点头。")
        val signals: (String) -> NerTextEngine.ParagraphSignals = {
            NerTextEngine.ParagraphSignals(
                modelReady = true,
                cues = emptyList(),
                persons = listOf(NerTextEngine.Mention("李四", 7, 9))
            )
        }
        val result = OfflineRoleResolver.resolve(
            paragraphs,
            listOf(unit("u1", 0, 0, 6)),
            characters,
            emptyMap(),
            signals
        )
        assertEquals("李四", result.segments[0].characterName)
        assertEquals("ner", result.segments[0].reason)
    }

    /** 模型就绪但本段无 cue 时，仍可用字典 cue（证明不是"模型一开就废掉兜底"） */
    @Test
    fun `模型就绪但无 cue 时字典 cue 兜底`() {
        val paragraphs = listOf("张三说：“我们走吧。”")
        val signals: (String) -> NerTextEngine.ParagraphSignals = {
            NerTextEngine.ParagraphSignals(modelReady = true)
        }
        val result = OfflineRoleResolver.resolve(
            paragraphs,
            listOf(unit("u1", 0, 4, 10, cueBefore = "张三说：")),
            characters,
            emptyMap(),
            signals
        )
        assertEquals("张三", result.segments[0].characterName)
        assertEquals("cue-hit", result.segments[0].reason)
    }

    /** 诊断口径：同段多句只统计一次模型 cue 数（避免"模型没信号"被误判为"信号很多"） */
    @Test
    fun `同段多句只计一次模型 cue 总数`() {
        val paragraphs = listOf("张三说：“一。”李四说：“二。”")
        val signals: (String) -> NerTextEngine.ParagraphSignals = {
            NerTextEngine.ParagraphSignals(
                modelReady = true,
                cues = listOf(
                    NerTextEngine.Mention("张三", 0, 2),
                    NerTextEngine.Mention("李四", 7, 9)
                )
            )
        }
        val result = OfflineRoleResolver.resolve(
            paragraphs,
            listOf(unit("u1", 0, 3, 6), unit("u2", 0, 12, 15)),
            characters,
            emptyMap(),
            signals
        )
        assertEquals(2, result.modelCueSpans)
        assertEquals("ner-cue", result.segments[0].reason)
    }

    /** 跨段 cue：本段没有 cue、cue 落在上一段时，用近场窗口的模型推理判定（不是正则抽名） */
    @Test
    fun `跨段 cue 由模型窗口判定为 ner-cue`() {
        val paragraphs = listOf("“我们走吧。”")
        val signals: (String) -> NerTextEngine.ParagraphSignals = { text ->
            if (text.contains("张三说")) {
                val at = text.indexOf("张三")
                NerTextEngine.ParagraphSignals(
                    modelReady = true,
                    cues = listOf(NerTextEngine.Mention("张三", at, at + 2))
                )
            } else {
                NerTextEngine.ParagraphSignals(modelReady = true)
            }
        }
        val result = OfflineRoleResolver.resolve(
            paragraphs,
            listOf(unit("u1", 0, 0, 6, cueBefore = "上一段结尾……张三说：")),
            characters,
            emptyMap(),
            signals
        )
        assertEquals("张三", result.segments[0].characterName)
        assertEquals("ner-cue", result.segments[0].reason)
    }

    /** 长引导语（人名距引号 >24 字）必须落在 40 字近场窗口内，才能由模型 cue 判定 */
    @Test
    fun `长引导语由模型 cue 判定`() {
        val paragraphs = listOf("“好。”")
        val longCue = "张三站在原地沉默了很长时间，才用低沉的声音缓缓说道："
        val signals: (String) -> NerTextEngine.ParagraphSignals = { text ->
            if (text.contains("张三") && text.contains("说道")) {
                val at = text.indexOf("张三")
                NerTextEngine.ParagraphSignals(
                    modelReady = true,
                    cues = listOf(NerTextEngine.Mention("张三", at, at + 2))
                )
            } else {
                NerTextEngine.ParagraphSignals(modelReady = true)
            }
        }
        val result = OfflineRoleResolver.resolve(
            paragraphs,
            listOf(unit("u1", 0, 0, 4, cueBefore = longCue)),
            characters,
            emptyMap(),
            signals
        )
        assertEquals("张三", result.segments[0].characterName)
        assertEquals("ner-cue", result.segments[0].reason)
    }

    /** 无模型时正则兜底路径仍可用（老设备/未下载模型不得功能断档） */
    @Test
    fun `无模型时正则 cue 兜底`() {
        val paragraphs = listOf("张三说：“我们走吧。”")
        val signals: (String) -> NerTextEngine.ParagraphSignals = {
            NerTextEngine.ParagraphSignals(modelReady = false)
        }
        val result = OfflineRoleResolver.resolve(
            paragraphs,
            listOf(unit("u1", 0, 4, 10, cueBefore = "张三说：")),
            characters,
            emptyMap(),
            signals
        )
        assertEquals("张三", result.segments[0].characterName)
        assertEquals("cue-hit", result.segments[0].reason)
    }
}
