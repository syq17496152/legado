package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Direct 缓存/索引/文档工具单测（epub-md-rich-rendering 阶段 1.1 配对）
 *
 * 覆盖：有界文本 LRU（单条超限不入）、资源缓存并发去重、片段索引归属判定、章节索引对齐。
 */
class EpubDirectCacheAndIndexTest {

    // === EpubDirectTextCache ===

    @Test
    fun `文本缓存超出总预算时按 LRU 淘汰`() {
        val cache = EpubDirectTextCache(maxEntries = 3, maxEntryChars = 10, maxTotalChars = 20)
        assertTrue(cache.put("a", "1234567890"))
        assertTrue(cache.put("b", "1234567890"))
        cache.get("a")
        assertTrue(cache.put("c", "1234567890"))
        // b 最久未用 ⇒ 被淘汰；a 因刚被访问而保留
        assertNull(cache.get("b"))
        assertEquals("1234567890", cache.get("a"))
    }

    @Test
    fun `单条超限不入缓存并清掉同键旧值`() {
        val cache = EpubDirectTextCache(maxEntries = 2, maxEntryChars = 4, maxTotalChars = 8)
        assertTrue(cache.put("k", "abcd"))
        assertFalse(cache.put("k", "abcde"))
        assertNull(cache.get("k"))
    }

    // === EpubDirectResourceCache ===

    @Test
    fun `资源缓存并发去重仅加载一次`() {
        val cache = EpubDirectResourceCache(maxEntryBytes = 64, maxTotalBytes = 128)
        val loads = AtomicInteger(0)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        val futures = (1..4).map {
            pool.submit {
                start.await()
                cache.getOrLoad("p", 4L) {
                    loads.incrementAndGet()
                    Thread.sleep(30)
                    byteArrayOf(1, 2, 3, 4)
                }
            }
        }
        start.countDown()
        futures.forEach { it.get(5, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(1, loads.get())
        assertEquals(4, cache.getOrLoad("p", 4L) { error("命中缓存不应再加载") }?.size)
    }

    @Test
    fun `超出单条上限的资源不缓存`() {
        val cache = EpubDirectResourceCache(maxEntryBytes = 4, maxTotalBytes = 8)
        assertNull(cache.getOrLoad("big", 100L) { byteArrayOf(1) })
        assertNull(cache.getOrLoad("unknown", null) { byteArrayOf(1) })
    }

    // === EpubDirectFragmentIndex ===

    @Test
    fun `片段索引按起点和区间判定所属逻辑章节`() {
        val index = EpubDirectFragmentIndex.parse(
            "<html><body><h1 id=\"c1\">A</h1><p id=\"c2\">B</p></body></html>"
        )
        val candidates = listOf(
            EpubDirectFragmentBoundary(1, "c1", "c2"),
            EpubDirectFragmentBoundary(2, "c2", null)
        )
        assertEquals(1, index.owner("c1", candidates, 0))
        assertEquals(2, index.owner("c2", candidates, 0))
        assertEquals(2, index.owner("unknown", candidates, 2))
        assertEquals(1, index.owner(null, candidates, 0))
    }

    @Test
    fun `片段索引对空候选返回空`() {
        val index = EpubDirectFragmentIndex.parse("<p id=\"x\">a</p>")
        assertNull(index.owner("x", emptyList(), 0))
    }

    // === TextReaderChapterIndex ===

    private fun chapter(index: Int, url: String = "u$index") =
        BookChapter(index = index, bookUrl = "book", url = url)

    @Test
    fun `章节索引解析对齐与相邻`() {
        val index = TextReaderChapterIndex(listOf(chapter(1), chapter(2), chapter(5)))
        assertEquals(2, index.resolve(2))
        assertNull(index.resolve(3))
        assertEquals(5, index.adjacent(2, 1))
        assertEquals(1, index.adjacent(2, -1))
        assertNull(index.adjacent(5, 1))
        assertTrue(index.matches(2, chapter(2)))
        assertFalse(index.matches(2, chapter(2, url = "changed")))
        assertFalse(index.matches(2, null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `章节索引拒绝重复序号`() {
        TextReaderChapterIndex(listOf(chapter(1), chapter(1, "other")))
    }
}