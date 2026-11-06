package io.legado.app.help.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VideoTrace 纯函数单测（video-player-redguo-alignment design §1.16 / tasks 15.1）
 *
 * 只测可脱离 Android 运行时执行的部分：格式拼装、脱敏、限频判定、时钟差。
 */
class VideoTraceTest {

    @Test
    fun `format 输出固定格式并包含阶段与键值`() {
        val line = VideoTrace.format(
            "sniff_hit", 1000L, 120L,
            arrayOf("engine" to "dom", "costMs" to 88L, "finalHost" to "h1234abcd")
        )
        assertTrue(line.startsWith("VbsTrace | stage=sniff_hit"))
        assertTrue(line.contains("t=1000"))
        assertTrue(line.contains("dt=120"))
        assertTrue(line.contains("engine=dom"))
        assertTrue(line.contains("costMs=88"))
        assertTrue(line.contains("finalHost=h1234abcd"))
    }

    @Test
    fun `format 忽略空键且清理换行`() {
        val line = VideoTrace.format("x", 1L, 0L, arrayOf("" to "bad", "msg" to "a\nb"))
        assertFalse(line.contains("bad"))
        assertTrue(line.contains("msg=a b"))
    }

    @Test
    fun `format 超长时截断并加省略号`() {
        val long = "v".repeat(800)
        val line = VideoTrace.format("x", 1L, 0L, arrayOf("k" to long))
        assertTrue(line.length <= 513)
        assertTrue(line.endsWith("…"))
    }

    @Test
    fun `urlPath 去掉域名与查询串只留路径尾段`() {
        val out = VideoTrace.urlPath("https://secret-host.example.com/a/b/c.m3u8?token=abc&x=1")
        assertFalse(out.contains("secret-host"))
        assertFalse(out.contains("token"))
        assertFalse(out.contains("?") )
        assertTrue(out.startsWith("h"))
        assertTrue(out.endsWith("b/c.m3u8"))
    }

    @Test
    fun `urlPath 空值返回占位符`() {
        assertEquals("-", VideoTrace.urlPath(null))
        assertEquals("-", VideoTrace.urlPath("   "))
    }

    @Test
    fun `hash8 稳定且固定八位`() {
        val a = VideoTrace.hash8("host-a")
        val b = VideoTrace.hash8("host-a")
        val c = VideoTrace.hash8("host-b")
        assertEquals(a, b)
        assertTrue(a.length == 8)
        assertFalse(a == c)
    }

    @Test
    fun `shouldSample 首条放行且间隔内丢弃`() {
        assertTrue(VideoTrace.shouldSample(0L, 100L, 1000L))
        assertFalse(VideoTrace.shouldSample(1000L, 1500L, 1000L))
        assertTrue(VideoTrace.shouldSample(1000L, 2000L, 1000L))
    }

    @Test
    fun `delta 首次为零且负值归零`() {
        assertEquals(0L, VideoTrace.delta(null, 500L))
        assertEquals(0L, VideoTrace.delta(0L, 500L))
        assertEquals(200L, VideoTrace.delta(500L, 700L))
        assertEquals(0L, VideoTrace.delta(700L, 500L))
    }
}
