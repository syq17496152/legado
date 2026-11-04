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

    /** 有模型时，上一段前缀里的角色名不得被字典 cue 抢判（跨段归属交给模型窗口） */
    @Test
    fun `模型就绪时跨段名字不参与字典归因`() {
        val paragraphs = listOf("张三转身看向李四。", "“你是谁？”")
        val signals: (String) -> NerTextEngine.ParagraphSignals = {
            NerTextEngine.ParagraphSignals(modelReady = true)
        }
        val result = OfflineRoleResolver.resolve(
            paragraphs,
            listOf(unit("u1", 1, 0, 6, cueBefore = "张三转身看向李四。\n")),
            characters,
            emptyMap(),
            signals
        )
        assertTrue("跨段名字不得抢判", result.segments[0].characterName.isBlank())
        assertEquals("unknown", result.segments[0].reason)
    }

    /** 无模型时保留跨段字典兜底（老设备/未下载模型功能不断档） */
    @Test
    fun `无模型时跨段名字仍可兜底`() {
        val paragraphs = listOf("张三转身看向李四。", "“你是谁？”")
        val signals: (String) -> NerTextEngine.ParagraphSignals = {
            NerTextEngine.ParagraphSignals(modelReady = false)
        }
        val result = OfflineRoleResolver.resolve(
            paragraphs,
            listOf(unit("u1", 1, 0, 6, cueBefore = "张三转身看向李四。\n")),
            characters,
            emptyMap(),
            signals
        )
        assertEquals("李四", result.segments[0].characterName)
        assertEquals("cue-hit", result.segments[0].reason)
    }

    /** 跨段前缀的合法 cue（上一段以「张三说：」结尾）由模型窗口判定，不因收紧字典而丢失 */
    @Test
    fun `跨段落 cue 由模型窗口判定`() {
        val paragraphs = listOf("张三说：", "“我们走吧。”")
        val signals: (String) -> NerTextEngine.ParagraphSignals = { text ->
            val at = text.indexOf("张三")
            if (at >= 0 && text.contains("说")) {
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
            listOf(unit("u1", 1, 0, 6, cueBefore = "张三说：\n")),
            characters,
            emptyMap(),
            signals
        )
        assertEquals("张三", result.segments[0].characterName)
        assertEquals("ner-cue", result.segments[0].reason)
    }

    /** 模型 cue 距引号 25~40 字仍要命中（旧 25 字窗口会漏，退化到字典匹配） */
    @Test
    fun `模型 cue 距引号三十字仍能命中`() {
        val prefix = "张三在原地站了很久，又反复整理衣袖，才用沙哑的声音缓缓说道："
        assertTrue("构造的前缀必须超过旧窗口 25 字", prefix.length > 25)
        val paragraph = prefix + "“走吧。”"
        val signals: (String) -> NerTextEngine.ParagraphSignals = { text ->
            val at = text.indexOf("张三")
            if (at >= 0) {
                NerTextEngine.ParagraphSignals(
                    modelReady = true,
                    cues = listOf(NerTextEngine.Mention("张三", at, at + 2))
                )
            } else {
                NerTextEngine.ParagraphSignals(modelReady = true)
            }
        }
        val result = OfflineRoleResolver.resolve(
            listOf(paragraph),
            listOf(unit("u1", 0, prefix.length, prefix.length + 7, cueBefore = prefix)),
            characters,
            emptyMap(),
            signals
        )
        assertEquals("张三", result.segments[0].characterName)
        assertEquals("ner-cue", result.segments[0].reason)
    }

    /** 引号紧挨人名时，模型 PER 必须优先于字典匹配（否则模型占比被字典吃掉） */
    @Test
    fun `引号紧邻模型 PER 时优先判 ner`() {
        val paragraph = "张三在旁边冷笑。“走吧。”"
        val quoteAt = paragraph.indexOf('“')
        val signals: (String) -> NerTextEngine.ParagraphSignals = { text ->
            val at = text.indexOf("张三")
            if (at >= 0) {
                NerTextEngine.ParagraphSignals(
                    modelReady = true,
                    persons = listOf(NerTextEngine.Mention("张三", at, at + 2))
                )
            } else {
                NerTextEngine.ParagraphSignals(modelReady = true)
            }
        }
        val result = OfflineRoleResolver.resolve(
            listOf(paragraph),
            listOf(unit("u1", 0, quoteAt, paragraph.length, cueBefore = paragraph.substring(0, quoteAt))),
            characters,
            emptyMap(),
            signals
        )
        assertEquals("张三", result.segments[0].characterName)
        assertEquals("ner", result.segments[0].reason)
    }

    /** 模型 cue 带称谓后缀（"张三大人"）应剥壳后命中角色，而不是当未识别 */
    @Test
    fun `模型 cue 带称谓后缀仍能命中`() {
        val paragraphs = listOf("“退下吧。”")
        val signals: (String) -> NerTextEngine.ParagraphSignals = { text ->
            val at = text.indexOf("张三大人")
            if (at >= 0) {
                NerTextEngine.ParagraphSignals(
                    modelReady = true,
                    cues = listOf(NerTextEngine.Mention("张三大人", at, at + 4))
                )
            } else {
                NerTextEngine.ParagraphSignals(modelReady = true)
            }
        }
        val result = OfflineRoleResolver.resolve(
            paragraphs,
            listOf(unit("u1", 0, 0, 6, cueBefore = "张三大人说：")),
            characters,
            emptyMap(),
            signals
        )
        assertEquals("张三", result.segments[0].characterName)
        assertEquals("ner-cue", result.segments[0].reason)
    }

    /** 模型 cue 命中角色别名（称谓以外的同义称呼） */
    @Test
    fun `模型 cue 命中角色别名`() {
        val withAlias = listOf(
            OfflineRoleResolver.RoleCharacter(id = 3L, name = "萧宏律", aliases = listOf("宏律"), gender = "male")
        )
        val paragraphs = listOf("“走吧。”")
        val signals: (String) -> NerTextEngine.ParagraphSignals = { text ->
            val at = text.indexOf("宏律")
            if (at >= 0) {
                NerTextEngine.ParagraphSignals(
                    modelReady = true,
                    cues = listOf(NerTextEngine.Mention("宏律", at, at + 2))
                )
            } else {
                NerTextEngine.ParagraphSignals(modelReady = true)
            }
        }
        val result = OfflineRoleResolver.resolve(
            paragraphs,
            listOf(unit("u1", 0, 0, 6, cueBefore = "宏律道：")),
            withAlias,
            emptyMap(),
            signals
        )
        assertEquals("萧宏律", result.segments[0].characterName)
        assertEquals("ner-cue", result.segments[0].reason)
    }

    /** 不做"任意包含"：张三丰不得被错配给张三 */
    @Test
    fun `近似长名不错配给短名`() {
        val paragraphs = listOf("“你说什么？”")
        val signals: (String) -> NerTextEngine.ParagraphSignals = { text ->
            val at = text.indexOf("张三丰")
            if (at >= 0) {
                NerTextEngine.ParagraphSignals(
                    modelReady = true,
                    cues = listOf(NerTextEngine.Mention("张三丰", at, at + 3))
                )
            } else {
                NerTextEngine.ParagraphSignals(modelReady = true)
            }
        }
        val result = OfflineRoleResolver.resolve(
            paragraphs,
            listOf(unit("u1", 0, 0, 7, cueBefore = "张三丰道：")),
            characters,
            emptyMap(),
            signals
        )
        assertTrue("张三丰不是张三", result.segments[0].characterName.isBlank())
    }

    /** 有模型时，同段只有名字、没有"人名+说话动词"结构 ⇒ 一律不猜（消除正则时代的邻近猜测） */
    @Test
    fun `有模型时同段裸名字不参与字典归因`() {
        val paragraphs = listOf("张三站在门口。", "“你是谁？”")
        val signals: (String) -> NerTextEngine.ParagraphSignals = {
            NerTextEngine.ParagraphSignals(modelReady = true)
        }
        val result = OfflineRoleResolver.resolve(
            paragraphs,
            listOf(unit("u1", 1, 0, 6, cueBefore = "张三站在门口。")),
            characters,
            emptyMap(),
            signals
        )
        assertTrue("没有说话动词不得猜说话人", result.segments[0].characterName.isBlank())
        assertEquals("unknown", result.segments[0].reason)
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

    /**
     * 整段只有引号、无说话动词，线索在相邻旁白段落：
     * 跨段近场窗口的模型 PER 应给出归属（ner-window），而不是退化为未识别。
     */
    @Test
    fun `跨段近场模型 PER 判定无 cue 对白`() {
        val paragraphs = listOf("张三靠墙站着，没有回头。", "“你终于来了。”")
        val signals: (String) -> NerTextEngine.ParagraphSignals = { text ->
            val at = text.indexOf("张三")
            if (at >= 0) {
                NerTextEngine.ParagraphSignals(
                    modelReady = true,
                    persons = listOf(NerTextEngine.Mention("张三", at, at + 2))
                )
            } else {
                NerTextEngine.ParagraphSignals(modelReady = true)
            }
        }
        val result = OfflineRoleResolver.resolve(
            paragraphs,
            listOf(unit("u1", 1, 0, 8, cueBefore = "张三靠墙站着，没有回头。\n", roleType = "character")),
            characters,
            emptyMap(),
            signals
        )
        assertEquals("张三", result.segments[0].characterName)
        assertEquals("ner-window", result.segments[0].reason)
    }

    /** 跨段近场窗口内的名字若不是已登记角色，不得猜测（仍然是"不猜"原则） */
    @Test
    fun `跨段近场未登记名字不猜`() {
        val paragraphs = listOf("王五靠墙站着。", "“你终于来了。”")
        val signals: (String) -> NerTextEngine.ParagraphSignals = { text ->
            val at = text.indexOf("王五")
            if (at >= 0) {
                NerTextEngine.ParagraphSignals(
                    modelReady = true,
                    persons = listOf(NerTextEngine.Mention("王五", at, at + 2))
                )
            } else {
                NerTextEngine.ParagraphSignals(modelReady = true)
            }
        }
        val result = OfflineRoleResolver.resolve(
            paragraphs,
            listOf(unit("u1", 1, 0, 8, cueBefore = "王五靠墙站着。\n", roleType = "character")),
            characters,
            emptyMap(),
            signals
        )
        assertTrue("王五未登记不得归因", result.segments[0].characterName.isBlank())
    }

    /** 同段 cue 已命中时走 ner-cue，不被跨段 ner-window 抢判（置信更高者优先） */
    @Test
    fun `同段 cue 优先于跨段近场`() {
        val paragraphs = listOf("张三在旁边。", "李四说：“走吧。”")
        val signals: (String) -> NerTextEngine.ParagraphSignals = { text ->
            NerTextEngine.ParagraphSignals(
                modelReady = true,
                cues = if (text.contains("李四")) {
                    val at = text.indexOf("李四")
                    listOf(NerTextEngine.Mention("李四", at, at + 2))
                } else emptyList(),
                persons = if (text.contains("张三")) {
                    val at = text.indexOf("张三")
                    listOf(NerTextEngine.Mention("张三", at, at + 2))
                } else emptyList()
            )
        }
        val result = OfflineRoleResolver.resolve(
            paragraphs,
            listOf(unit("u1", 1, 3, 9, cueBefore = "张三在旁边。\n李四说：")),
            characters,
            emptyMap(),
            signals
        )
        assertEquals("李四", result.segments[0].characterName)
        assertEquals("ner-cue", result.segments[0].reason)
    }

    /**
     * 音色稳定性（2026-11-03 用户反馈"多个音色乱跳"）：
     * 近场窗口里同时出现两个已登记角色时，最近的 PER 可能只是"被提到的人"，
     * 此时不得按最近名字归因，宁可不猜（交给锁/轮转保持对话连贯）。
     */
    @Test
    fun `窗口内多个已登记角色时不按最近名字归因`() {
        val paragraphs = listOf("张三看着李四，没有说话。", "“你先走吧。”")
        val signals: (String) -> NerTextEngine.ParagraphSignals = { text ->
            val atZhang = text.indexOf("张三")
            val atLi = text.indexOf("李四")
            NerTextEngine.ParagraphSignals(
                modelReady = true,
                persons = buildList {
                    if (atZhang >= 0) add(NerTextEngine.Mention("张三", atZhang, atZhang + 2))
                    if (atLi >= 0) add(NerTextEngine.Mention("李四", atLi, atLi + 2))
                }
            )
        }
        val result = OfflineRoleResolver.resolve(
            paragraphs,
            listOf(unit("u1", 1, 0, 7, cueBefore = "张三看着李四，没有说话。\n", roleType = "character")),
            characters,
            emptyMap(),
            signals
        )
        assertTrue("多角色窗口不得按最近名字抢判", result.segments[0].characterName.isBlank())
    }

    /**
     * 3.6：剧本 → 播报的转换口径。`ensure` 第一层就是读剧本，
     * 这里锁死「剧本段能原样变成归因段」以及「剧本与正文不对齐时必须回落」。
     */
    @Test
    fun `剧本段映射为归因段 不对齐时回落`() {
        val paragraphs = listOf("“走吧。”张三说。", "他推开门。")
        val script = ChapterScriptBuilder.build(
            bookKey = "b",
            chapterIndex = 1,
            paragraphs = paragraphs,
            segments = listOf(
                OfflineRoleSegment(
                    paragraphIndex = 0,
                    start = 1,
                    end = 5,
                    roleType = "character",
                    characterName = "张三",
                    characterId = 1L,
                    confidence = 0.9,
                    reason = "ner-cue"
                ),
                OfflineRoleSegment(
                    paragraphIndex = 1,
                    start = 0,
                    end = 3,
                    roleType = "narrator",
                    confidence = 0.5,
                    reason = "unknown"
                )
            ),
            ruleVersion = "r1",
            source = "mixed"
        )
        val aligned = ChapterScriptPlayback.align(script, paragraphs)
        assertNotNull(aligned)
        val segments = ChapterScriptPlayback.toOfflineSegments(aligned!!)
        assertEquals(2, segments.size)
        assertEquals("张三", segments[0].characterName)
        assertEquals("character", segments[0].roleType)
        // 剧本层把细分 reason 收敛为可诊断来源（ner-cue ⇒ ner），播报侧拿到同口径
        assertEquals("ner", segments[0].reason)
        assertEquals("narrator", segments[1].roleType)
        // 段数变化（正文新增一段）⇒ 剧本不可用，必须回落缓存/规则
        assertNull(ChapterScriptPlayback.align(script, paragraphs + "第三段"))
        // 段区间越界（正文改动后残留）⇒ 越界段剔除
        val alignedOutOfRange = ChapterScriptPlayback.align(
            script.copy(segments = script.segments + script.segments.first().copy(start = 90, end = 120)),
            paragraphs
        )
        assertEquals(2, alignedOutOfRange!!.segments.size)
    }

    /**
     * A2：角色画像新增「年龄带」只是叠加信息，不参与归因打分 ——
     * 带上年龄带前后，同一段文本的归属结果必须完全一致（防"为了语速把角色配错"）。
     */
    @Test
    fun `角色年龄带不改变归因结果`() {
        val paragraphs = listOf("张三说：“跟我走。”")
        val units = listOf(unit("u1", 0, 4, paragraphs[0].length, cueBefore = "张三说："))
        val base = OfflineRoleResolver.resolve(paragraphs, units, characters, emptyMap())
        val aged = OfflineRoleResolver.resolve(
            paragraphs,
            units,
            characters.map { it.copy(ageBand = "old") },
            emptyMap()
        )
        assertEquals(
            base.segments.map { it.characterName to it.roleType },
            aged.segments.map { it.characterName to it.roleType }
        )
    }

    /**
     * 4.9「清除 AI 数据」：剧本文件删掉后，内存里的状态也必须一起清空 ——
     * 否则听书中心仍显示「已生成」，用户会以为没清干净。
     */
    @Test
    fun `清除AI数据时状态总线一起清空`() {
        ScriptStatusBus.done("book-9", 1, segments = 3, source = "mixed", now = 100L)
        assertEquals(ScriptPhase.READY, ScriptStatusBus.statusOf("book-9", 1).phase)
        ScriptStatusBus.clearAll()
        assertEquals(ScriptPhase.NONE, ScriptStatusBus.statusOf("book-9", 1).phase)
    }
}
