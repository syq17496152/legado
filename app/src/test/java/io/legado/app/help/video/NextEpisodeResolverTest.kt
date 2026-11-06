package io.legado.app.help.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 下一集预解析缓存单测（design §1.6 / tasks 6.1）
 */
class NextEpisodeResolverTest {

    @Before
    fun setUp() {
        NextEpisodeResolver.clear()
    }

    private fun pre(url: String, at: Long = 1000L) =
        NextEpisodeResolver.PreResolved(finalUrl = url, headers = mapOf("A" to "1"), resolvedAt = at)

    @Test
    fun `命中 未过期返回缓存结果`() {
        NextEpisodeResolver.put("k1", pre("http://x/1.m3u8", at = 1000L))
        val hit = NextEpisodeResolver.get("k1", now = 1000L + NextEpisodeResolver.TTL_MS - 1)
        assertEquals("http://x/1.m3u8", hit?.finalUrl)
    }

    @Test
    fun `过期 返回 null 并移除`() {
        NextEpisodeResolver.put("k1", pre("http://x/1.m3u8", at = 1000L))
        assertNull(NextEpisodeResolver.get("k1", now = 1000L + NextEpisodeResolver.TTL_MS))
        assertEquals(0, NextEpisodeResolver.sizeForTest())
    }

    @Test
    fun `未命中返回 null`() {
        assertNull(NextEpisodeResolver.get("nope", now = 1L))
    }

    @Test
    fun `空地址不缓存`() {
        NextEpisodeResolver.put("k1", pre(""))
        assertEquals(0, NextEpisodeResolver.sizeForTest())
    }

    @Test
    fun `容量淘汰最久未使用`() {
        repeat(NextEpisodeResolver.CAPACITY) { i ->
            NextEpisodeResolver.put("k$i", pre("http://x/$i.m3u8"))
        }
        // 访问 k0 使其变为最近使用
        NextEpisodeResolver.get("k0", now = 1000L)
        NextEpisodeResolver.put("kNew", pre("http://x/new.m3u8"))
        assertEquals(NextEpisodeResolver.CAPACITY, NextEpisodeResolver.sizeForTest())
        assertEquals("http://x/0.m3u8", NextEpisodeResolver.get("k0", now = 1000L)?.finalUrl)
        assertNull(NextEpisodeResolver.get("k1", now = 1000L))
    }

    @Test
    fun `失效后不再命中`() {
        NextEpisodeResolver.put("k1", pre("http://x/1.m3u8"))
        NextEpisodeResolver.invalidate("k1")
        assertNull(NextEpisodeResolver.get("k1", now = 1000L))
    }

    @Test
    fun `键随集地址或源或文章变化`() {
        val base = NextEpisodeResolver.key("http://e/1", "srcA", "http://a/1")
        assertEquals(base, NextEpisodeResolver.key("http://e/1", "srcA", "http://a/1"))
        assertNotEquals(base, NextEpisodeResolver.key("http://e/2", "srcA", "http://a/1"))
        assertNotEquals(base, NextEpisodeResolver.key("http://e/1", "srcB", "http://a/1"))
        assertNotEquals(base, NextEpisodeResolver.key("http://e/1", "srcA", "http://a/2"))
    }
}
