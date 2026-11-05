package io.legado.app.ui.book.read.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 听书场景卡状态机（T5.1）：四卡映射 + 主按钮三态 + 未开书/未就绪兜底。
 */
class CastSceneStateTest {

    /**
     * 3.5 剧本生成入口：`canGenerateScript` 必须原样透传（缺省 false）。
     * 语义是「本地 AI 可用才出现按钮」——不能出现"按钮在但点了没反应"或"可用却找不到入口"。
     */
    @Test
    fun `剧本生成入口按可用性透传且默认关闭`() {
        val base = CastSceneState.evaluate(
            engineReady = true,
            characterCount = 1,
            assignedCount = 1,
            storyboardSegments = 2,
            activeTemplateId = CastSceneState.TEMPLATE_MULTI
        )
        assertFalse(base.canGenerateScript)

        val enabled = CastSceneState.evaluate(
            engineReady = true,
            characterCount = 1,
            assignedCount = 1,
            storyboardSegments = 2,
            activeTemplateId = CastSceneState.TEMPLATE_MULTI,
            canGenerateScript = true
        )
        assertTrue(enabled.canGenerateScript)
    }

    /**
     * 1.7 书级覆盖徽标：只表达"本书改过配置"，未开书或没有覆盖时不能出现
     * （否则用户会以为每本书都被单独改过，去全局设置里找不到来源）。
     */
    @Test
    fun `书级覆盖徽标只在有书且有覆盖时出现`() {
        assertEquals(null, CastSceneState.bookOverrideBadge(false))
        assertEquals("本书已覆盖", CastSceneState.bookOverrideBadge(true))

        val noBook = CastSceneState.evaluate(
            engineReady = true,
            characterCount = 1,
            assignedCount = 1,
            storyboardSegments = 2,
            activeTemplateId = CastSceneState.TEMPLATE_MULTI,
            hasBook = false,
            bookOverride = true
        )
        assertEquals(null, noBook.bookOverrideBadge)

        val overridden = CastSceneState.evaluate(
            engineReady = true,
            characterCount = 1,
            assignedCount = 1,
            storyboardSegments = 2,
            activeTemplateId = CastSceneState.TEMPLATE_MULTI,
            bookOverride = true
        )
        assertEquals("本书已覆盖", overridden.bookOverrideBadge)

        val plain = CastSceneState.evaluate(
            engineReady = true,
            characterCount = 0,
            assignedCount = 0,
            storyboardSegments = 0,
            activeTemplateId = null
        )
        assertEquals(null, plain.bookOverrideBadge)
    }

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
     * 4.6 二级页职责收敛：听书中心高级区的入口 key 必须能深链命中真实页签，
     * 否则点了无反应（两套字符串漂移是这类"入口消失"故障的根因）。
     */
    @Test
    fun `高级区入口 key 与二级页页签单源`() {
        val entries = CastSceneState.advancedPageEntries
        assertEquals("高级区只保留角色配音与开书向导两个入口", 2, entries.size)
        entries.forEach { (tabKey, label) ->
            assertTrue("入口 $tabKey 必须能落在二级页", PageTab.ofKey(tabKey) != null)
            assertTrue("入口文案不能为空", label.isNotBlank())
        }
        // 制作/调试类页签不在这里（留在「引擎与音色」页），避免听书中心变成第二个设置页
        assertEquals(listOf("roles", "wizard"), entries.map { it.first })
        assertEquals(PageTab.ROLES, PageTab.ofKey(entries[0].first))
        assertEquals(PageTab.WIZARD, PageTab.ofKey(entries[1].first))
    }

    /**
     * 4.1 高级项可见性：入口只放"现在真能用"的项 —— 无书时不显示分镜/角色/向导，
     * 无离线引擎时不显示批量预合成（系统语音无法预合成）。
     */
    @Test
    fun `高级区入口按有无书与引擎状态收敛`() {
        val noBook = CastSceneState.advancedEntriesOf(hasBook = false, engineReady = false)
        assertEquals(
            listOf("voices", "templates", "bgm", "tuning", "diagnostics"),
            noBook.map { it.id }
        )
        // 有书但引擎未就绪：分镜/角色/向导可用（规则链路不依赖离线引擎），预合成仍不可用
        val bookNoEngine = CastSceneState.advancedEntriesOf(hasBook = true, engineReady = false)
        assertEquals(
            listOf("voices", "templates", "bgm", "storyboard", "tuning", "diagnostics", "roles", "wizard"),
            bookNoEngine.map { it.id }
        )
        // 有书且引擎就绪：全部 9 项可达，且文案都不为空（避免出现空白 chip）
        val all = CastSceneState.advancedEntriesOf(hasBook = true, engineReady = true)
        assertEquals(
            listOf(
                "voices", "templates", "prebuild", "bgm", "storyboard",
                "tuning", "diagnostics", "roles", "wizard"
            ),
            all.map { it.id }
        )
        assertTrue(all.all { it.label.isNotBlank() })
        // 深链 key 仍与二级页页签单源
        assertEquals(PageTab.ROLES, PageTab.ofKey(all.first { it.id == "roles" }.id))
        assertEquals(PageTab.WIZARD, PageTab.ofKey(all.first { it.id == "wizard" }.id))
    }

    /**
     * 1.7 顶部胶囊：引擎档位 + 剧本三态（未生成/就绪/降级）必须一眼说完，
     * 且未开书时不能出现「系统语音 · 剧本未生成」这种无意义胶囊。
     */
    @Test
    fun `顶部胶囊按引擎与剧本状态映射`() {
        assertEquals("极速单声 · 剧本就绪", CastSceneState.capsuleText("极速单声", true, true, false))
        assertEquals("离线引擎 · 剧本降级", CastSceneState.capsuleText("", true, true, true))
        assertEquals("系统语音 · 剧本未生成", CastSceneState.capsuleText("极速单声", false, false, false))

        val withBook = CastSceneState.evaluate(
            engineReady = true,
            characterCount = 2,
            assignedCount = 2,
            storyboardSegments = 10,
            activeTemplateId = CastSceneState.TEMPLATE_MULTI,
            engineTitle = "极速单声",
            scriptReady = true
        )
        assertEquals("极速单声 · 剧本就绪", withBook.capsule)

        val noBook = CastSceneState.evaluate(
            engineReady = true,
            characterCount = 0,
            assignedCount = 0,
            storyboardSegments = 0,
            activeTemplateId = null,
            hasBook = false,
            engineTitle = "极速单声"
        )
        assertEquals("未开书不展示剧本胶囊", "", noBook.capsule)
    }

    /**
     * 4.1/7.7 低端机与本地 AI 关闭：AI 入口「不出现」而不是灰色；已有 AI 数据例外，
     * 否则清完模型手机上也看不到清理入口，遗留数据永远占空间。
     */
    @Test
    fun `低端机与本地AI关闭不显示AI入口`() {
        assertTrue(CastSceneState.isLowEndDevice(4000))
        assertFalse(CastSceneState.isLowEndDevice(8000))
        assertFalse(CastSceneState.isLowEndDevice(12000))

        assertTrue(CastSceneState.aiEntryVisible(lowEndDevice = false, localAiEnabled = true, hasAiData = false))
        assertFalse(CastSceneState.aiEntryVisible(lowEndDevice = true, localAiEnabled = true, hasAiData = false))
        assertFalse(CastSceneState.aiEntryVisible(lowEndDevice = false, localAiEnabled = false, hasAiData = false))
        // 已有数据：即使低端机/未开启也显示，保证用户能清理
        assertTrue(CastSceneState.aiEntryVisible(lowEndDevice = true, localAiEnabled = false, hasAiData = true))

        val lowEnd = VoiceAssetCenterState.build(AssetFacts(lowEndDevice = true))
        assertTrue("低端机不显示 AI 分组", lowEnd.groups.none { it.id == "ai" })
        assertFalse(lowEnd.canClearAiData)

        val aiOff = VoiceAssetCenterState.build(AssetFacts(localAiEnabled = false))
        assertTrue("未开启本地 AI 不显示 AI 分组", aiOff.groups.none { it.id == "ai" })
        assertFalse(aiOff.canClearAiData)

        // 已有剧本草案：低端机也显示分组并可清除（否则遗留数据无法清理）
        val legacy = VoiceAssetCenterState.build(
            AssetFacts(lowEndDevice = true, scriptBytes = 2L * 1024 * 1024)
        )
        assertTrue(legacy.groups.any { it.id == "ai" })
        assertTrue(legacy.canClearAiData)
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
