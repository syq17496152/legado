package io.legado.app.ui.book.read

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 沉浸舞台文字取色（四态验收）：文字色与舞台背景明暗**同侧**（白字压白底 / 黑字压黑底）
 * 必须换成主题推导墨色，明暗相反时保留原文字色。
 */
class ReadAloudPlayerColorsTest {

    @Test
    fun `白字压白底要换墨色`() {
        assertTrue(shouldSwapInk(textIsLight = true, backgroundIsLight = true))
    }

    @Test
    fun `黑字压黑底要换墨色`() {
        assertTrue(shouldSwapInk(textIsLight = false, backgroundIsLight = false))
    }

    @Test
    fun `明暗相反时保留原文字色`() {
        assertFalse(shouldSwapInk(textIsLight = true, backgroundIsLight = false))
        assertFalse(shouldSwapInk(textIsLight = false, backgroundIsLight = true))
    }
}
