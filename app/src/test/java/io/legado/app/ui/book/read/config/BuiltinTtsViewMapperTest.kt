package io.legado.app.ui.book.read.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 管理页视图映射（T5.5）：无书/无章节时的提示文案（不依赖 Android 运行态）。
 */
class BuiltinTtsViewMapperTest {

    @Test
    fun `无书时提示先打开一本书`() {
        assertEquals("请先打开一本书", BuiltinTtsViewMapper.storyboardHint(null))
        assertEquals("本章测试文案", BuiltinTtsViewMapper.roleSummary(null, "本章测试文案"))
    }

    @Test
    fun `无书时分镜为空`() {
        assertEquals(0, BuiltinTtsViewMapper.storyboard(null).size)
    }
}
