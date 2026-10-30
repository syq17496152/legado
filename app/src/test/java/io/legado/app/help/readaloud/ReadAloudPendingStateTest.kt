package io.legado.app.help.readaloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R15：冷启动朗读待播状态机契约。
 *
 * 关键防回归点：
 * 1. 章节未就绪时必须**保留**标记（否则正文永远等不到补播）；
 * 2. 补播只发生**一次**（同一回调重入不得双播）；
 * 3. 章节槽清空（切书/重载）后标记作废（否则切书回来会莫名开读）。
 */
class ReadAloudPendingStateTest {

    @Test
    fun initialNotPending() {
        val state = ReadAloudPendingState()
        assertFalse(state.isPending)
        assertFalse(state.consumeIfPending(chapterReady = true))
    }

    @Test
    fun markPending_thenConsumeOnce() {
        val state = ReadAloudPendingState()
        state.markPending()
        assertTrue(state.isPending)
        assertTrue("章节就绪时应补播一次", state.consumeIfPending(chapterReady = true))
        assertFalse("补播后必须清标记，防重入双播", state.isPending)
        assertFalse("同一标记不得再次补播", state.consumeIfPending(chapterReady = true))
    }

    @Test
    fun consumeBeforeChapterReady_keepsPending() {
        val state = ReadAloudPendingState()
        state.markPending()
        assertFalse("正文仍为空时不得消耗标记", state.consumeIfPending(chapterReady = false))
        assertTrue("标记必须保留，等下一次排版完成", state.isPending)
        assertTrue(state.consumeIfPending(chapterReady = true))
    }

    @Test
    fun cancel_dropsPending() {
        val state = ReadAloudPendingState()
        state.markPending()
        state.cancel()
        assertFalse(state.isPending)
        assertFalse(state.consumeIfPending(chapterReady = true))
    }
}
