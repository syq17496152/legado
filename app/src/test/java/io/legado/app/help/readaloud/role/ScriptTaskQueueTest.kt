package io.legado.app.help.readaloud.role

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.5 剧本任务队列：P0>P1>P2、同优先级 FIFO、同章合并升级、取消/清理。
 * 这些语义一旦漂移，会出现「正在读的章排在后台补全后面」这类用户可感知的卡顿。
 */
class ScriptTaskQueueTest {

    private fun newQueue() = ScriptTaskQueue()

    @Test
    fun `高优先级先出队`() {
        val queue = newQueue()
        queue.enqueue("book", 10, ScriptPriority.P2)
        queue.enqueue("book", 11, ScriptPriority.P1)
        queue.enqueue("book", 12, ScriptPriority.P0)

        val first = queue.poll()
        assertEquals(12, first?.chapterIndex)
        assertEquals(ScriptPriority.P0, first?.priority)
        assertEquals(11, queue.poll()?.chapterIndex)
        assertEquals(10, queue.poll()?.chapterIndex)
        assertNull(queue.poll())
    }

    @Test
    fun `同优先级按入队顺序`() {
        val queue = newQueue()
        queue.enqueue("book", 1)
        queue.enqueue("book", 2)
        queue.enqueue("book", 3)
        assertEquals(1, queue.poll()?.chapterIndex)
        assertEquals(2, queue.poll()?.chapterIndex)
        assertEquals(3, queue.poll()?.chapterIndex)
    }

    @Test
    fun `同一章重复入队只保留一条并升级优先级`() {
        val queue = newQueue()
        assertTrue(queue.enqueue("book", 7, ScriptPriority.P2))
        assertFalse("重复入队应合并而不是新增", queue.enqueue("book", 7, ScriptPriority.P0))
        assertEquals(1, queue.size)

        val task = queue.poll()
        assertEquals(ScriptPriority.P0, task?.priority)
        assertEquals(0, queue.size)
    }

    @Test
    fun `不同书的同章号互不覆盖`() {
        val queue = newQueue()
        queue.enqueue("bookA", 5)
        queue.enqueue("bookB", 5)
        assertEquals(2, queue.size)
        assertTrue(queue.contains("bookA", 5))
        assertTrue(queue.contains("bookB", 5))
        queue.cancel("bookA", 5)
        assertFalse(queue.contains("bookA", 5))
        assertTrue(queue.contains("bookB", 5))
    }

    @Test
    fun `取消指定章与整本书`() {
        val queue = newQueue()
        queue.enqueue("bookA", 1)
        queue.enqueue("bookA", 2)
        queue.enqueue("bookB", 3)

        assertTrue(queue.cancel("bookA", 1))
        assertFalse(queue.cancel("bookA", 1))
        assertEquals(2, queue.size)

        assertEquals(1, queue.cancelBook("bookA"))
        assertEquals(1, queue.size)
        assertTrue(queue.contains("bookB", 3))
    }

    @Test
    fun `清空返回条数且可继续使用`() {
        val queue = newQueue()
        queue.enqueue("book", 1)
        queue.enqueue("book", 2)
        assertEquals(2, queue.cancelAll())
        assertTrue(queue.isEmpty)

        assertTrue(queue.enqueue("book", 3, ScriptPriority.P0))
        assertEquals(3, queue.poll()?.chapterIndex)
    }

    @Test
    fun `非法参数不入队`() {
        val queue = newQueue()
        assertFalse(queue.enqueue("", 1))
        assertFalse(queue.enqueue("book", -1))
        assertTrue(queue.isEmpty)
    }

    @Test
    fun `peek 不消费且与 poll 同序`() {
        val queue = newQueue()
        queue.enqueue("book", 10, ScriptPriority.P1)
        queue.enqueue("book", 11, ScriptPriority.P0)
        assertEquals(11, queue.peek()?.chapterIndex)
        assertEquals(2, queue.size)
        assertEquals(11, queue.poll()?.chapterIndex)
        assertEquals(10, queue.peek()?.chapterIndex)
    }
}
