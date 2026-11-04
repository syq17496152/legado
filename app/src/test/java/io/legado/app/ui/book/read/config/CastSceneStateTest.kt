package io.legado.app.ui.book.read.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 听书场景卡状态机（T5.1）：四卡映射 + 主按钮三态 + 未开书/未就绪兜底。
 */
class CastSceneStateTest {

    @Test
    fun `卡与内置模板一一映射`() {
        assertEquals(CastSceneState.TEMPLATE_MONO, CastSceneState.templateOf(CastSceneState.CARD_MONO))
        assertEquals(CastSceneState.TEMPLATE_DUAL, CastSceneState.templateOf(CastSceneState.CARD_DUAL))
        assertEquals(CastSceneState.TEMPLATE_MULTI, CastSceneState.templateOf(CastSceneState.CARD_MULTI))
        assertEquals(null, CastSceneState.templateOf(CastSceneState.CARD_CUSTOM))
        assertEquals(CastSceneState.CARD_MONO, CastSceneState.cardOf("builtin_mono"))
        assertEquals(CastSceneState.CARD_CUSTOM, CastSceneState.cardOf("my_custom_template"))
        // 未设置模板 = 默认单声，应落在「普通朗读」而不是「自定义」
        assertEquals(CastSceneState.CARD_MONO, CastSceneState.cardOf(null))
    }

    @Test
    fun `引擎未就绪主按钮变成启用离线朗读`() {
        val snapshot = CastSceneState.evaluate(
            engineReady = false,
            characterCount = 0,
            assignedCount = 0,
            storyboardSegments = 0,
            activeTemplateId = CastSceneState.TEMPLATE_MULTI
        )
        // 4.11 开箱即用：未就绪不再把主按钮变成"去启用"，而是直接可听（回落系统语音）
        assertEquals("开始听书（系统语音）", snapshot.primaryLabel)
        assertTrue(snapshot.statusLine.contains("未就绪"))
        assertFalse(snapshot.engineReady)
        // 多角色卡始终标"推荐"，即使引擎未就绪（引导用户去启用）
        assertTrue(snapshot.cards.first { it.id == CastSceneState.CARD_MULTI }.recommended)
    }

    @Test
    fun `多角色未识别角色时主按钮是识别角色并开始`() {
        val snapshot = CastSceneState.evaluate(
            engineReady = true,
            characterCount = 0,
            assignedCount = 0,
            storyboardSegments = 0,
            activeTemplateId = CastSceneState.TEMPLATE_MULTI
        )
        assertEquals("识别角色并开始", snapshot.primaryLabel)
        assertEquals(CastSceneState.CARD_MULTI, snapshot.selectedCard)
        assertEquals("新书，未识别角色", snapshot.cards.first { it.id == CastSceneState.CARD_MULTI }.subtitle)
        assertTrue(snapshot.statusLine.contains("未开始"))
    }

    @Test
    fun `角色与配声与分镜齐备后主按钮是开始听书`() {
        val snapshot = CastSceneState.evaluate(
            engineReady = true,
            characterCount = 2,
            assignedCount = 2,
            storyboardSegments = 13,
            activeTemplateId = CastSceneState.TEMPLATE_MULTI
        )
        assertEquals("开始听书", snapshot.primaryLabel)
        assertTrue(snapshot.cards.first { it.id == CastSceneState.CARD_MULTI }.subtitle.contains("音色已配 2 个"))
        assertTrue(snapshot.statusLine.contains("13 段分镜"))
        assertTrue(snapshot.cards.first { it.id == CastSceneState.CARD_MULTI }.selected)
    }

    @Test
    fun `卡片收敛为三张且自定义不再占卡位`() {
        val snapshot = CastSceneState.evaluate(
            engineReady = true,
            characterCount = 0,
            assignedCount = 0,
            storyboardSegments = 0,
            activeTemplateId = null
        )
        assertEquals(3, snapshot.cards.size)
        assertEquals(
            listOf(CastSceneState.CARD_MONO, CastSceneState.CARD_DUAL, CastSceneState.CARD_MULTI),
            snapshot.cards.map { it.id }
        )
        assertTrue(snapshot.cards.none { it.id == CastSceneState.CARD_CUSTOM })
    }

    @Test
    fun `已激活自定义模板时用状态行说明并保留主按钮`() {
        val snapshot = CastSceneState.evaluate(
            engineReady = true,
            characterCount = 3,
            assignedCount = 3,
            storyboardSegments = 5,
            activeTemplateId = "user_template_x",
            customName = "我的模板"
        )
        // 自定义模板不再有卡位，但选中态要能从状态行读出来，否则用户不知道当前用的是哪个模板
        assertEquals(CastSceneState.CARD_CUSTOM, snapshot.selectedCard)
        assertTrue(snapshot.statusLine.contains("我的模板"))
        assertTrue(snapshot.cards.none { it.selected })
        assertEquals("开始听书", snapshot.primaryLabel)
    }

    @Test
    fun `未开书时禁用主按钮`() {
        val snapshot = CastSceneState.evaluate(
            engineReady = true,
            characterCount = 0,
            assignedCount = 0,
            storyboardSegments = 0,
            activeTemplateId = CastSceneState.TEMPLATE_MONO,
            hasBook = false
        )
        assertFalse(snapshot.primaryEnabled)
        assertEquals("请先打开一本书", snapshot.primaryLabel)
        assertTrue(snapshot.statusLine.contains("请先打开一本书"))
    }

    @Test
    fun `卡圆标按序号 ABCD 且越界安全`() {
        // 两列网格用 A/B/C/D 指认四卡（第三轮视觉重做）
        assertEquals("A", CastSceneState.badgeOf(0))
        assertEquals("B", CastSceneState.badgeOf(1))
        assertEquals("C", CastSceneState.badgeOf(2))
        assertEquals("D", CastSceneState.badgeOf(3))
        // 越界不抛异常（夹紧到 0..25）
        assertEquals("A", CastSceneState.badgeOf(-3))
        assertEquals("Z", CastSceneState.badgeOf(30))
    }

    @Test
    fun `使用提示三条覆盖三种卡与回入口`() {
        val tips = CastSceneState.usageTips()
        assertEquals(3, tips.size)
        // 每条都要对应一个真实卡/入口，避免提示文案与页面选项脱节
        assertTrue(tips[0].contains("普通朗读"))
        assertTrue(tips[1].contains("沉浸多角色"))
        assertTrue(tips[2].contains("侧拉菜单"))
    }

    @Test
    fun `三步引导按引擎音色与开书推进且不阻塞`() {
        val none = CastSceneState.guideSteps(engineReady = false, voiceCount = 0, hasBook = false)
        assertEquals(3, none.size)
        assertTrue(none.none { it.done })

        val noVoice = CastSceneState.guideSteps(engineReady = true, voiceCount = 0, hasBook = true)
        assertTrue(noVoice[0].done)
        assertFalse(noVoice[1].done)
        assertFalse(noVoice[2].done)

        val allReady = CastSceneState.guideSteps(engineReady = true, voiceCount = 174, hasBook = true)
        assertTrue(allReady.all { it.done })
    }

    @Test
    fun `资源概览行包含音色模板配乐与占用`() {
        assertEquals(
            "音色 12 · 模板 3 · 配乐 2 · 离线资产 45MB",
            CastSceneState.resourceLine(12, 3, 2, 45L * 1024 * 1024)
        )
    }

    @Test
    fun `快照按入参产出引导与资源行`() {
        val snapshot = CastSceneState.evaluate(
            engineReady = true,
            characterCount = 2,
            assignedCount = 2,
            storyboardSegments = 8,
            activeTemplateId = CastSceneState.TEMPLATE_MULTI,
            voiceCount = 6,
            templateCount = 4,
            bgmCount = 1,
            storageBytes = 30L * 1024 * 1024
        )
        assertTrue(snapshot.guideSteps.all { it.done })
        assertEquals(
            "音色 6 · 模板 4 · 配乐 1 · 离线资产 30MB",
            snapshot.resourceLine
        )
    }

    @Test
    fun `资源行与声音资源中心的 MB 口径一致`() {
        // 两个页面（听书中心资源行 / 声音资源页头部）对同一份占用必须显示同一个数字，
        // 否则用户会看到"45MB"与"46MB"两种答案（口径漂移是资源类页面最容易被投诉的点）
        val bytes = 45L * 1024 * 1024
        assertEquals(45L, VoiceAssetCenterState.mb(bytes))
        assertTrue(CastSceneState.resourceLine(1, 1, 1, bytes).contains("离线资产 45MB"))
    }

    @Test
    fun `声音资源页音色分区显示画像覆盖率`() {
        // 5.1：用户要能看出"这份音色库的性别/年龄标注够不够"，否则会误以为性别硬约束 100% 生效
        val ui = VoiceAssetCenterState.build(
            AssetFacts(
                voiceCount = 10,
                importedVoiceCount = 10,
                voiceGenderPercent = 95,
                voiceAgePercent = 60
            )
        )
        val voiceGroup = ui.groups.first { it.id == "voice" }
        assertTrue(voiceGroup.summary.contains("共 10 个"))
        assertTrue(voiceGroup.summary.contains("性别标注 95%"))
        assertTrue(voiceGroup.summary.contains("年龄标注 60%"))
    }

    @Test
    fun `配乐开关摘要三态与开关前置条件`() {
        // 关：说明打开后会播放（不承诺已有配乐）
        assertEquals("未开启 · 打开后朗读时播放配乐", CastSceneState.bgmSummary(false, 0))
        assertEquals("未开启 · 打开后朗读时播放配乐", CastSceneState.bgmSummary(false, 3))
        // 开但没有配乐：明确提示去添加（与真实播放条件一致：无配乐=不出声）
        assertEquals("已开启 · 暂无配乐，点此去添加", CastSceneState.bgmSummary(true, 0))
        // 开且有配乐：给出可用数量，便于判断是否真的会响
        assertEquals("已开启 · 可用配乐 3 首（与朗读混音）", CastSceneState.bgmSummary(true, 3))
        // 前置条件：无已启用配乐时不允许打开开关（避免"开了却没声音"）
        assertFalse(CastSceneState.canEnableBgm(0))
        assertTrue(CastSceneState.canEnableBgm(1))
    }

    @Test
    fun `一键编排触发条件与主按钮文案同源`() {
        // 多角色卡且未识别/未配声 ⇒ 主按钮是「识别角色并开始」，点击必须走一键编排
        assertTrue(CastSceneState.needsOneTapPrepare(CastSceneState.CARD_MULTI, 0, 0))
        assertTrue(CastSceneState.needsOneTapPrepare(CastSceneState.CARD_MULTI, 3, 0))
        assertFalse(CastSceneState.needsOneTapPrepare(CastSceneState.CARD_MULTI, 3, 3))
        // 单声/双声卡不需要编排（直接用当前引擎朗读）
        assertFalse(CastSceneState.needsOneTapPrepare(CastSceneState.CARD_MONO, 0, 0))
        assertFalse(CastSceneState.needsOneTapPrepare(CastSceneState.CARD_DUAL, 3, 0))
    }

    @Test
    fun `剧本状态在多角色页可见且不干扰其它卡`() {
        // 3.7：状态行与「本章准备」的剧本行都必须给出同一句状态
        val multi = CastSceneState.evaluate(
            engineReady = true,
            characterCount = 2,
            assignedCount = 2,
            storyboardSegments = 13,
            activeTemplateId = CastSceneState.TEMPLATE_MULTI,
            scriptStatusLabel = "生成中…"
        )
        assertTrue(multi.statusLine.contains("本章剧本：生成中…"))
        assertEquals("本章剧本：生成中…", multi.scriptLine)

        // 剧本未生成时不空口承诺（明确写"未生成"，而不是留白让人误以为已生效）
        val none = CastSceneState.evaluate(
            engineReady = true,
            characterCount = 2,
            assignedCount = 2,
            storyboardSegments = 0,
            activeTemplateId = CastSceneState.TEMPLATE_MULTI,
            scriptStatusLabel = "未生成"
        )
        assertTrue(none.statusLine.contains("本章剧本：未生成"))
        assertEquals("本章剧本：未生成", none.scriptLine)

        // 单声/双声卡不展示剧本行（剧本只影响多角色配声，避免无关信息干扰）
        val mono = CastSceneState.evaluate(
            engineReady = true,
            characterCount = 2,
            assignedCount = 2,
            storyboardSegments = 13,
            activeTemplateId = CastSceneState.TEMPLATE_MONO,
            scriptStatusLabel = "已生成（AI 增强）· 12 段"
        )
        assertEquals("", mono.scriptLine)
        assertFalse(mono.statusLine.contains("本章剧本："))

        // 缺省调用方（未传状态）行为不变
        val legacy = CastSceneState.evaluate(
            engineReady = true,
            characterCount = 2,
            assignedCount = 2,
            storyboardSegments = 13,
            activeTemplateId = CastSceneState.TEMPLATE_MULTI
        )
        assertEquals("", legacy.scriptLine)
    }

    /**
     * A7 情绪诚实：朗读设置页的引擎摘要只展示"真的会生效"的情绪（HTTP 且带 emotionTag）；
     * 内置/系统引擎即使角色卡带情绪字段，也不得在设置页摘要里出现。
     */
    @Test
    fun `情绪摘要只在支持情绪的引擎展示`() {
        val builtin = io.legado.app.help.readaloud.speech.SpeechRoute(
            engineType = io.legado.app.help.readaloud.speech.SpeechRoute.ENGINE_DEFAULT,
            speakerName = "内置女声",
            emotionName = "开心",
            emotionTag = "happy"
        )
        assertFalse("内置引擎情绪不生效，摘要不应展示", builtin.supportsEmotion)

        val http = io.legado.app.help.readaloud.speech.SpeechRoute(
            engineType = io.legado.app.help.readaloud.speech.SpeechRoute.ENGINE_HTTP,
            engineValue = "42",
            emotionName = "开心",
            emotionTag = "happy"
        )
        assertTrue("HTTP 且带 emotionTag 才允许展示", http.supportsEmotion)
    }

    /**
     * 4.9：音色分组带「连播试听」次要动作；新增「AI 剧本与本地模型」分组并可清除；
     * 两条删除路径都必须先给出「删什么/不影响什么」的影响提示。
     */
    @Test
    fun `资源页新增试听与清除AI数据`() {
        val ui = VoiceAssetCenterState.build(
            AssetFacts(
                engineReady = true,
                profileTitle = "极速单声",
                voiceCount = 6,
                importedVoiceCount = 6,
                templateCount = 3,
                cacheBytes = 12L * 1024 * 1024,
                scriptBytes = 5L * 1024 * 1024,
                aiModelBytes = 400L * 1024 * 1024
            )
        )
        val voice = ui.groups.first { it.id == "voice" }
        assertEquals(AssetAction.MANAGE_VOICE, voice.action)
        assertEquals(AssetAction.PREVIEW_VOICES, voice.secondaryAction)
        assertEquals("连播试听", voice.secondaryLabel)

        val ai = ui.groups.first { it.id == "ai" }
        assertEquals(AssetAction.CLEAR_AI_DATA, ai.action)
        assertEquals("清除", ai.actionLabel)
        assertTrue(ai.summary.contains("保留音色与角色绑定"))
        assertTrue(ui.canClearAiData)
        assertTrue(ui.canClearCache)

        // 删除前影响提示：必须写明"保留音色/模板"，避免用户以为一键清空全部配置
        assertTrue(
            VoiceAssetCenterState.clearCacheImpactText(12L * 1024 * 1024)
                .contains("音色、选角模板、配乐、章节剧本都不会被删除")
        )
        val aiImpact = VoiceAssetCenterState.clearAiImpactText(
            5L * 1024 * 1024,
            400L * 1024 * 1024
        )
        assertTrue(aiImpact.contains("音色、角色与音色的绑定"))
        assertTrue(aiImpact.contains("规则结果"))
        // 无 AI 数据时分组仍存在，但动作显示"已干净"且不提供清除
        val emptyAi = VoiceAssetCenterState.build(AssetFacts())
        assertEquals("已干净", emptyAi.groups.first { it.id == "ai" }.actionLabel)
        assertFalse(emptyAi.canClearAiData)
    }
}
