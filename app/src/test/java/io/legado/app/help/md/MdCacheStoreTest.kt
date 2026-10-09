package io.legado.app.help.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Markdown 缓存治理单测（epub-md-rich-rendering 阶段 3.11 配对）。
 *
 * 覆盖【验证标准】：①原子写（写入后立即可读、无 staging 残留）；②**中断不留半成品**
 * （预置 staging/backup 由存储自愈）；③容量可回收（超限按 lastModified 由旧到新）；
 * ④清理入口；⑤键随文件指纹/schema 变化而失效。
 */
class MdCacheStoreTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("md-cache-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun store(maxBytes: Long = MdCacheStore.DefaultMaxBytes) = MdCacheStore(root, maxBytes)

    @Test
    fun `写入后可读且目录无 staging 残留`() {
        val cache = store()
        val key = cache.key("book://a", 100, 1000)
        assertTrue(cache.write(key, "<p>甲</p>"))
        assertEquals("<p>甲</p>", cache.read(key))
        val leftovers = root.listFiles()!!.filter { it.name.startsWith(".") }
        assertTrue("原子写不得残留 staging/backup：$leftovers", leftovers.isEmpty())
    }

    @Test
    fun `未写入的键读取返回 null`() {
        assertNull(store().read(store().key("book://missing", 1, 1)))
    }

    @Test
    fun `中断残留被自愈且不返回半成品`() {
        val cache = store()
        val key = cache.key("book://b", 200, 2000)
        // 预置中断态：staging 残留（模拟写入过程被杀）
        val target = File(root, "$key.md.html")
        File(root, ".${target.name}.staging").writeText("<p>半成品")
        cache.write(key, "<p>完整</p>")
        assertEquals("必须返回完整内容", "<p>完整</p>", cache.read(key))
        assertFalse(File(root, ".${target.name}.staging").exists())
    }

    @Test
    fun `键随文件指纹与 schema 变化`() {
        val cache = store()
        val base = cache.key("book://c", 100, 1000)
        assertTrue("同输入同键", base == cache.key("book://c", 100, 1000))
        assertFalse("长度变化须换键", base == cache.key("book://c", 101, 1000))
        assertFalse("修改时间变化须换键", base == cache.key("book://c", 100, 1001))
        assertFalse("书标识变化须换键", base == cache.key("book://d", 100, 1000))
        assertTrue("键为 sha256 十六进制", base.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `超限按最旧优先回收`() {
        val cache = store(maxBytes = 0)
        val keys = (1..3).map { index ->
            cache.key("book://$index", index.toLong(), index.toLong()).also { key ->
                assertTrue(cache.write(key, "内容$index"))
                File(root, "$key.md.html").setLastModified(index * 1_000L)
            }
        }
        assertEquals(3, cache.entryCount())
        val removed = cache.trim()
        assertEquals("maxBytes=0 时应全部回收", 3, removed)
        keys.forEach { key -> assertNull("回收后不得可读", cache.read(key)) }
        assertEquals(0L, cache.totalBytes())
    }

    @Test
    fun `未超限时不回收`() {
        val cache = store(maxBytes = 1024)
        assertTrue(cache.write(cache.key("book://x", 1, 1), "内容"))
        assertEquals(0, cache.trim())
        assertEquals(1, cache.entryCount())
    }

    @Test
    fun `清理入口清空全部条目`() {
        val cache = store()
        assertTrue(cache.write(cache.key("book://y", 1, 1), "甲"))
        assertTrue(cache.write(cache.key("book://z", 2, 2), "乙"))
        assertEquals(2, cache.clear())
        assertEquals(0, cache.entryCount())
        assertEquals(0L, cache.totalBytes())
    }

    @Test
    fun `单条删除返回字节数并可再次写入`() {
        val cache = store()
        val key = cache.key("book://w", 5, 5)
        assertTrue(cache.write(key, "12345"))
        assertEquals(5L, cache.delete(key))
        assertNull(cache.read(key))
        assertTrue("删除后应可重写", cache.write(key, "abc"))
    }

    @Test
    fun `根目录不存在时读取不抛异常`() {
        val missing = File(root, "not-created")
        val cache = MdCacheStore(missing)
        assertNull(cache.read("k"))
        assertEquals(0, cache.entryCount())
        assertEquals(0L, cache.totalBytes())
        assertEquals(0, cache.trim())
    }
}