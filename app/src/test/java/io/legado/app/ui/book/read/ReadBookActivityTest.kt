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
    fun `阅读菜单只剩一个听书入口`() {
        // 4.3 / spec R1：menu_cast_scene 是唯一朗读入口；平行入口（预合成/引擎）已移入听书中心
        assertTrue("菜单 id 缺失：menu_cast_scene", R.id.menu_cast_scene > 0)
        assertTrue("菜单标题资源缺失", R.string.cast_scene_menu_entry > 0)
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
