package io.legado.app.ui.book.read

import io.legado.app.R
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读页菜单入口（T5.1/T5.2）：菜单项 id / 标题与离线引擎管理页契约存在（防被误删导致入口消失）。
 */
class ReadBookActivityTest {

    @Test
    fun `内置离线引擎菜单入口存在`() {
        assertTrue("菜单 id 缺失：menu_builtin_tts", R.id.menu_builtin_tts > 0)
        assertTrue("菜单标题资源缺失", R.string.builtin_tts_menu_entry > 0)
    }

    @Test
    fun `引擎标签与标题资源存在`() {
        assertTrue(R.string.builtin_tts_engine_label > 0)
        assertTrue(R.string.builtin_tts_title > 0)
    }
}
