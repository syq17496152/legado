package io.legado.app.help.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * add-dlna-cast：代理白名单注册表单测（tasks 5.2 验证标准，AD-08 安全模型）。
 *
 * 验证点：
 * 1. token 长度符合常量，且每次生成都不同
 * 2. 未注册 key → null（**调用方据此回 404 且不得发起上游请求**）
 * 3. `clear()` 后旧 token 立即失效（残留请求拿不到条目）
 * 4. 路径段卫生：路径穿越、超长、空白都被处理成安全短名
 * 5. 短 ID 惰性登记可用
 * 6. **并发**：多线程同时登记/查找不抛异常（NanoHTTPD 每请求一线程，HLS 分片并发是常态）
 *
 * 已知上限：只覆盖注册表本身；"非法 token 时上游零请求"是集成行为，靠 L2 用假渲染器核对。
 */
class CastProxyRegistryTest {

    private fun httpSource(url: String = "https://cdn.example.com/a.mp4") =
        CastSource.Http(mime = "video/mp4", url = url, headers = mapOf("Referer" to "https://site/"))

    private fun fileSource(path: String = "/sdcard/movie.mp4") =
        CastSource.LocalFile(mime = "video/mp4", path = path)

    // ============ token ============

    @Test
    fun newToken_hasConfiguredLengthAndIsRandom() {
        val a = CastProxyRegistry.newToken()
        val b = CastProxyRegistry.newToken()
        assertEquals(DlnaConstants.PROXY_TOKEN_LENGTH, a.length)
        assertEquals(DlnaConstants.PROXY_TOKEN_LENGTH, b.length)
        assertFalse("两次生成的 token 不应相同", a == b)
        assertTrue("token 应为 URL 安全字符", a.all { it.isLetterOrDigit() })
    }

    // ============ 会话查找 ============

    @Test
    fun createSession_registersBaseEntryUnderSanitizedName() {
        val session = CastProxyRegistry.createSession("a.mp4", httpSource())
        assertEquals("a.mp4", session.baseKey)
        assertEquals(1, session.size)
        val found = CastProxyRegistry.find(session.token)
        assertNotNull("用正确 token 应能找到会话", found)
        assertTrue(found!!.lookup("a.mp4") is CastSource.Http)
    }

    @Test
    fun find_returnsNullForWrongOrBlankToken() {
        CastProxyRegistry.createSession("a.mp4", httpSource())
        assertNull("错误 token 必须查不到（白名单模型）", CastProxyRegistry.find("not-a-real-token"))
        assertNull("空 token", CastProxyRegistry.find(""))
        assertNull("null token", CastProxyRegistry.find(null))
    }

    @Test
    fun lookup_returnsNullForUnregisteredKey() {
        val session = CastProxyRegistry.createSession("a.mp4", httpSource())
        assertNull("未登记的路径 key 必须为 null → 调用方回 404", session.lookup("b.mp4"))
        assertNull("null key", session.lookup(null))
        assertNull("空白 key", session.lookup("   "))
    }

    @Test
    fun clear_invalidatesEverything() {
        val session = CastProxyRegistry.createSession("a.mp4", httpSource())
        assertNotNull(CastProxyRegistry.find(session.token))
        CastProxyRegistry.clear()
        assertNull("会话结束后残留 token 必须失效", CastProxyRegistry.find(session.token))
    }

    // ============ 短 ID 惰性登记 ============

    @Test
    fun registerShort_createsLookupableKey() {
        val session = CastProxyRegistry.createSession("index.m3u8", httpSource())
        val key1 = session.registerShort(httpSource("https://cdn.example.com/seg1.ts"))
        val key2 = session.registerShort(httpSource("https://cdn.example.com/seg2.ts"))
        assertTrue("短 key 应带约定前缀", key1.startsWith(DlnaConstants.PROXY_SHORT_ID_SEGMENT))
        assertFalse("不同 URI 应得到不同 key", key1 == key2)
        assertNotNull(session.lookup(key1))
        assertNotNull(session.lookup(key2))
        assertEquals("基础条目 + 2 个分片", 3, session.size)
    }

    @Test
    fun shortKeyLengthStaysSmallForLongUpstreamUrls() {
        val session = CastProxyRegistry.createSession("index.m3u8", httpSource())
        val veryLong = "https://cdn.example.com/" + "segment/".repeat(200) + "s.ts?token=abcdef"
        val key = session.registerShort(httpSource(veryLong))
        assertTrue(
            "短 key 必须远小于原 URI（避免经 base64 塞进路径导致 URL 长度爆炸）",
            key.length < 16
        )
    }

    // ============ 路径卫生 ============

    @Test
    fun sanitizeName_stripsPathTraversal() {
        // 只取最后一段 → 路径穿越结构被天然消解
        assertEquals("passwd", CastProxyRegistry.sanitizeName("../../etc/passwd"))
        assertEquals("a.mp4", CastProxyRegistry.sanitizeName("/cast/token/a.mp4"))
        assertEquals("a.mp4", CastProxyRegistry.sanitizeName("..\\windows\\a.mp4"))
        assertFalse(
            "结果不得包含路径分隔符",
            CastProxyRegistry.sanitizeName("../../x").contains('/')
        )
        assertFalse(
            "结果不得含 ..（防穿越语义残留）",
            CastProxyRegistry.sanitizeName("../..").contains("..")
        )
    }

    @Test
    fun sanitizeName_truncatesAndFallsBack() {
        val long = "x".repeat(500) + ".mp4"
        val cleaned = CastProxyRegistry.sanitizeName(long)
        assertEquals(
            "超长名字应被截断到常量上限",
            DlnaConstants.PROXY_NAME_MAX_LENGTH,
            cleaned.length
        )
        assertEquals("空白回退", "media", CastProxyRegistry.sanitizeName("   "))
        assertEquals("null 回退", "media", CastProxyRegistry.sanitizeName(null))
        assertEquals("纯符号回退", "media", CastProxyRegistry.sanitizeName("///"))
    }

    @Test
    fun sanitizeName_keepsExtensionForRendererFriendliness() {
        assertEquals("movie.mp4", CastProxyRegistry.sanitizeName("movie.mp4"))
        assertEquals("index.m3u8", CastProxyRegistry.sanitizeName("index.m3u8"))
    }

    // ============ 会话可承载文件源 ============

    @Test
    fun session_supportsLocalFileSource() {
        val session = CastProxyRegistry.createSession("movie.mp4", fileSource())
        val source = session.lookup("movie.mp4")
        assertTrue("本地文件源应是 LocalFile 分支", source is CastSource.LocalFile)
        assertEquals("/sdcard/movie.mp4", (source as CastSource.LocalFile).path)
    }

    // ============ 并发 ============

    @Test
    fun session_isSafeUnderConcurrentRegistrationAndLookup() {
        val session = CastProxyRegistry.createSession("index.m3u8", httpSource())
        val threads = 8
        val perThread = 60
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val failures = AtomicInteger(0)
        try {
            repeat(threads) { t ->
                pool.submit {
                    start.await()
                    repeat(perThread) { i ->
                        try {
                            val key = session.registerShort(
                                httpSource("https://cdn.example.com/t$t/seg$i.ts")
                            )
                            // 登记后立刻查（模拟另一线程在读）
                            if (session.lookup(key) == null) failures.incrementAndGet()
                            session.lookup(session.baseKey)
                        } catch (e: Exception) {
                            failures.incrementAndGet()
                        }
                    }
                }
            }
            start.countDown()
            pool.shutdown()
            assertTrue("并发登记/查找应全部完成", pool.awaitTermination(20, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals("并发下不应有丢条目或异常", 0, failures.get())
        assertEquals("基础条目 + 全部登记", 1 + threads * perThread, session.size)
    }
}
