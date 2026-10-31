package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 缓存（AD-04 / C-4 / T1.4）：键单源、命中、LRU 淘汰、原子写。
 */
class BuiltinTtsCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `同输入同键 不同音色或语速不同键`() {
        val base = BuiltinCacheKeys.key("clone", "b6_m_youth|内置·男·少年", 1.0f, "你好")
        assertEquals(base, BuiltinCacheKeys.key("clone", "b6_m_youth|内置·男·少年", 1.0f, "  你好 "))
        assertNotEquals(base, BuiltinCacheKeys.key("clone", "b6_f_youth|内置·女·少年", 1.0f, "你好"))
        assertNotEquals(base, BuiltinCacheKeys.key("clone", "b6_m_youth|内置·男·少年", 1.5f, "你好"))
    }

    @Test
    fun `语速键保留两位小数`() {
        assertEquals("1.00", BuiltinCacheKeys.rateKey(1.0f))
        assertEquals("1.00", BuiltinCacheKeys.rateKey(1.001f))
        assertEquals("0.75", BuiltinCacheKeys.rateKey(0.75f))
    }

    @Test
    fun `音色增益不同则缓存键不同`() {
        val base = BuiltinCacheKeys.key("clone", "b6_m_youth|内置·男·少年", 1.0f, "你好")
        assertNotEquals(base, BuiltinCacheKeys.key("clone", "b6_m_youth|内置·男·少年", 1.0f, "你好", 1.5f))
        assertEquals("1.00", BuiltinCacheKeys.gainKey(1.0f))
        assertEquals("1.50", BuiltinCacheKeys.gainKey(1.5f))
    }

    @Test
    fun `缓存水位上限可取 默认 2GB T3-4`() {
        // 管理页需要显示「用量 / 上限」：默认上限与构造参数都必须能读出来
        val cache = BuiltinTtsCache(temp.newFolder("cache"))
        assertEquals(BuiltinTtsCache.DEFAULT_MAX_DISK_BYTES, cache.diskLimitBytes())
        val custom = BuiltinTtsCache(temp.newFolder("cache2"), maxDiskBytes = 64L * 1024 * 1024)
        assertEquals(64L * 1024 * 1024, custom.diskLimitBytes())
        assertTrue(custom.diskLimitBytes() < BuiltinTtsCache.DEFAULT_MAX_DISK_BYTES)
    }

    @Test
    fun `写盘后可读回且采样率自描述`() {
        val cache = BuiltinTtsCache(temp.newFolder("cache"))
        val pcm = PcmAudioUtils.floatToPcm16(floatArrayOf(0.1f, -0.2f, 0.3f))
        cache.put("k1", CachedAudio(24000, pcm))
        val loaded = cache.get("k1")
        assertTrue(cache.contains("k1"))
        assertEquals(24000, loaded!!.sampleRate)
        assertEquals(pcm.size, loaded.pcm.size)
    }

    @Test
    fun `磁盘超水位按最近使用淘汰`() {
        val dir = temp.newFolder("lru")
        val cache = BuiltinTtsCache(dir, maxDiskBytes = 900)
        repeat(6) { index ->
            val pcm = ByteArray(200) { 1 }
            cache.put("k$index", CachedAudio(16000, pcm))
        }
        val remaining = dir.listFiles()?.size ?: 0
        assertTrue("未按水位淘汰：$remaining", remaining < 6)
    }

    @Test
    fun `清空后目录为空`() {
        val cache = BuiltinTtsCache(temp.newFolder("clear"))
        cache.put("k", CachedAudio(16000, ByteArray(10)))
        cache.clear()
        assertTrue(cache.sizeBytes() == 0L)
        assertTrue(!cache.contains("k"))
    }
}
