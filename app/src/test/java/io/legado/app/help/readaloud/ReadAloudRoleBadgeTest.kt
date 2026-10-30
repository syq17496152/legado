package io.legado.app.help.readaloud

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 角色徽章展示态（T8.7，G-01 配对 ReadAloudRoleBadge）：文案契约 + update/clear 状态语义。
 */
class ReadAloudRoleBadgeTest {

    @After
    fun tearDown() {
        ReadAloudRoleBadge.clear()
    }

    @Test
    fun `文案契约`() {
        assertEquals("当前：张三", ReadAloudRoleBadge.text("张三"))
        assertEquals("", ReadAloudRoleBadge.text(""))
        assertEquals("", ReadAloudRoleBadge.text("  "))
        // 前后空白被裁掉，避免浮层出现双空格
        assertEquals("当前：李四", ReadAloudRoleBadge.text(" 李四 "))
    }

    @Test
    fun `update 写入状态 clear 归零`() {
        ReadAloudRoleBadge.update("张三")
        assertEquals("张三", ReadAloudRoleBadge.state.value)
        ReadAloudRoleBadge.update("   ")
        assertEquals("", ReadAloudRoleBadge.state.value)
        ReadAloudRoleBadge.clear()
        assertEquals("", ReadAloudRoleBadge.state.value)
    }
}
