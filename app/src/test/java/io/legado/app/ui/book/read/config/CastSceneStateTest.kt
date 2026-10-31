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
        assertEquals(CastSceneState.CARD_CUSTOM, CastSceneState.cardOf(null))
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
    fun `自定义模板选中自定义卡并显示模板名`() {
        val snapshot = CastSceneState.evaluate(
            engineReady = true,
            characterCount = 3,
            assignedCount = 3,
            storyboardSegments = 5,
            activeTemplateId = "user_template_x",
            customName = "我的模板"
        )
        assertEquals(CastSceneState.CARD_CUSTOM, snapshot.selectedCard)
        assertTrue(snapshot.cards.first { it.id == CastSceneState.CARD_CUSTOM }.subtitle.contains("我的模板"))
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
}
