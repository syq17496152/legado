package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * IF-14-L 配对用例：章末朗读续播的「待续播索引」判定（真机 2026-11-01 断流修复）。
 */
class ReadAloudChapterAdvanceTest {

    @Test
    fun `朗读驱动且目标章未排版完成时登记待续播`() {
        assertEquals(2, ReadAloudChapterAdvance.pendingIndex(true, false, 2))
    }

    @Test
    fun `目标章已完成排版时无需登记`() {
        assertEquals(-1, ReadAloudChapterAdvance.pendingIndex(true, true, 3))
    }

    @Test
    fun `非朗读驱动不登记`() {
        assertEquals(-1, ReadAloudChapterAdvance.pendingIndex(false, false, 1))
    }

    @Test
    fun `非法章索引不登记`() {
        assertEquals(-1, ReadAloudChapterAdvance.pendingIndex(true, false, -1))
    }

    @Test
    fun `章节未排版完成时暂缓起播`() {
        assertEquals(true, ReadAloudChapterAdvance.shouldDeferUntilCompleted(false))
    }

    @Test
    fun `章节排版完成时可立即起播`() {
        assertEquals(false, ReadAloudChapterAdvance.shouldDeferUntilCompleted(true))
    }

    @Test
    fun `朗读服务运行中的跨章按朗读推进处理`() {
        assertEquals(true, ReadAloudChapterAdvance.fromReadAloudForService(true))
    }

    @Test
    fun `朗读服务已停时的跨章按手动导航处理`() {
        assertEquals(false, ReadAloudChapterAdvance.fromReadAloudForService(false))
    }

    @Test
    fun `服务跨章标记必须能触发待续播登记`() {
        // IF-14-M 回归锁：服务续章时 fromReadAloud 若退化为 false，pendingIndex 恒为 -1 ⇒ 章末断流
        val fromReadAloud = ReadAloudChapterAdvance.fromReadAloudForService(true)
        assertEquals(5, ReadAloudChapterAdvance.pendingIndex(fromReadAloud, false, 5))
    }
}
