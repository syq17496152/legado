package io.legado.app.constant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 偏好键稳定性契约测试（epub-md-rich-rendering 阶段 3.2 配对）。
 *
 * 为什么钉死字面量：偏好键是**用户数据的寻址地址**，一旦改名，用户既有设置会静默丢失
 * （读不到旧键 ⇒ 回落默认值），属于不可静默发生的兼容性破坏。
 */
class PreferKeyMarkdownTest {

    @Test
    fun `文本富渲染键名稳定`() {
        assertEquals("mdRichRender", PreferKey.mdRichRender)
    }

    @Test
    fun `既有 epub 引擎键未被连带改名`() {
        assertEquals("epubReadEngine", PreferKey.epubReadEngine)
        assertEquals("epubCoreScheduleMode", PreferKey.epubCoreScheduleMode)
    }

    @Test
    fun `新增键与既有键不冲突`() {
        val keys = listOf(
            PreferKey.mdRichRender,
            PreferKey.epubReadEngine,
            PreferKey.epubCoreScheduleMode
        )
        assertTrue("同一键名不得重复登记", keys.size == keys.toSet().size)
    }
}