package io.legado.app.ui.book.read.textweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动边界判据契约测试（阶段 3.1/3.2 接线配对）。
 *
 * 守护："到底了却切不了章"（严格相等会被取整误差卡住）与"未测量完成就乱切章"两类问题。
 */
class TextRichScrollPolicyTest {

    @Test
    fun `顶部与中段与底部判定`() {
        assertTrue(TextRichScrollPolicy.edge(0, 3000, 1000).atTop)
        val middle = TextRichScrollPolicy.edge(1000, 3000, 1000)
        assertFalse(middle.atTop)
        assertFalse(middle.atBottom)
        assertTrue(TextRichScrollPolicy.edge(2000, 3000, 1000).atBottom)
    }

    @Test
    fun `容差吸收取整误差`() {
        // 距底部 10px 仍算到底（亚像素/取整常见误差）
        assertTrue(TextRichScrollPolicy.edge(3000 - 1000 - 10, 3000, 1000).atBottom)
        assertTrue(TextRichScrollPolicy.edge(5, 3000, 1000).atTop)
        // 超出容差不认
        assertFalse(TextRichScrollPolicy.edge(3000 - 1000 - 100, 3000, 1000).atBottom)
    }

    @Test
    fun `内容不足一屏时两端同时到但不得因此切章`() {
        val edge = TextRichScrollPolicy.edge(0, 400, 1000)
        assertEquals(TextRichScrollPolicy.Edge(atTop = true, atBottom = true), edge)
    }

    @Test
    fun `未测量完成时两端同时到交由宿主兜底`() {
        assertTrue(TextRichScrollPolicy.edge(0, 0, 0).atBottom)
        assertTrue(TextRichScrollPolicy.edge(0, 3000, 0).atBottom)
    }

    @Test
    fun `越界滚动值被夹取`() {
        // 负偏移（过度回弹）按顶部处理
        assertTrue(TextRichScrollPolicy.edge(-50, 3000, 1000).atTop)
        // 超出最大偏移按底部处理
        assertTrue(TextRichScrollPolicy.edge(99999, 3000, 1000).atBottom)
    }

    @Test
    fun `自定义容差生效`() {
        val edge = TextRichScrollPolicy.edge(2000 - 50, 3000, 1000, tolerancePx = 100)
        assertTrue("容差放大后应判定到底", edge.atBottom)
    }
}