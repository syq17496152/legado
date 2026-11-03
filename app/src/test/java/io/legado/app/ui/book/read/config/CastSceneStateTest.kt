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
        assertEquals("启用离线朗读", snapshot.primaryLabel)
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
}
