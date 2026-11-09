package io.legado.app.ui.book.read

import io.legado.app.R
import io.legado.app.help.readaloud.ReadAloudRoleBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读页菜单入口（T5.1/T5.2）+ 角色徽章浮层契约（T8.7）：
 * 菜单项 id / 标题与离线引擎管理页契约存在（防被误删导致入口消失）。
 */
class ReadBookActivityTest {

    @Test
    fun `阅读菜单不再提供听书入口`() {
        // offline-tts-ux-consolidation AD-01：菜单入口已移除，唯一入口是阅读页「朗读」按钮
        val menu = io.legado.app.testkit.SourceFileProbe.sourceTextByPath("src/main/res/menu/book_read.xml")
        org.junit.Assert.assertFalse("menu_cast_scene 不应再出现在菜单", menu.contains("menu_cast_scene"))
        assertTrue("入口文案资源保留（面板/引导仍可复用）", R.string.cast_scene_menu_entry > 0)
    }

    @Test
    fun `引擎标签与标题资源存在`() {
        assertTrue(R.string.builtin_tts_engine_label > 0)
        assertTrue(R.string.builtin_tts_title > 0)
    }

    @Test
    fun `角色徽章浮层文案与显隐契约`() {
        assertEquals("当前：张三", ReadAloudRoleBadge.text("张三"))
        assertEquals("当前：旁白", ReadAloudRoleBadge.text("旁白"))
        // 空标签=隐藏（未识别段不冒充旁白）
        assertEquals("", ReadAloudRoleBadge.text(""))
        assertEquals("", ReadAloudRoleBadge.text("   "))
    }
}
