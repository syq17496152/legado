package io.legado.app.help.readaloud.prebuild

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TTS 缓存键纯函数单测（§3.7.1-1，tasks 2.10）：
 * 确定性/维度敏感性（引擎/语速/音色/章节 index 任一变化即键变）
 */
class TtsCacheKeysTest {

    @Test
    fun `同输入同输出（确定性）`() {
        val a = TtsCacheKeys.ttsSpeakFileName("1", "10", "", 3, "第三章", "你好世界")
        val b = TtsCacheKeys.ttsSpeakFileName("1", "10", "", 3, "第三章", "你好世界")
        assertEquals(a, b)
    }

    @Test
    fun `引擎 id 变化即键变`() {
        val a = TtsCacheKeys.ttsSpeakFileName("1", "10", "", 3, "t", "x")
        val b = TtsCacheKeys.ttsSpeakFileName("2", "10", "", 3, "t", "x")
        assertNotEquals(a, b)
    }

    @Test
    fun `空引擎 id 与不同引擎不碰撞（空 url type=2 防碰撞）`() {
        val a = TtsCacheKeys.ttsSpeakFileName("", "10", "", 3, "t", "x")
        val b = TtsCacheKeys.ttsSpeakFileName("2", "10", "", 3, "t", "x")
        assertNotEquals(a, b)
    }

    @Test
    fun `语速变化即键变`() {
        val a = TtsCacheKeys.ttsSpeakFileName("1", "10", "", 3, "t", "x")
        val b = TtsCacheKeys.ttsSpeakFileName("1", "20", "", 3, "t", "x")
        assertNotEquals(a, b)
    }

    @Test
    fun `章节 index 变化即键变（同章名防碰撞）`() {
        val a = TtsCacheKeys.ttsSpeakFileName("1", "10", "", 3, "第一章", "x")
        val b = TtsCacheKeys.ttsSpeakFileName("1", "10", "", 7, "第一章", "x")
        assertNotEquals(a, b)
    }

    @Test
    fun `音色变化即键变`() {
        val a = TtsCacheKeys.ttsSpeakFileName("1", "10", "", 3, "t", "x")
        val b = TtsCacheKeys.ttsSpeakFileName("1", "10", "female_01", 3, "t", "x")
        assertNotEquals(a, b)
    }

    @Test
    fun `键结构保持双段 stem_unit 同构`() {
        val key = TtsCacheKeys.ttsSpeakFileName("1", "10", "", 3, "第三章", "你好")
        assertTrue("键应含分隔下划线：$key", key.contains("_"))
        val parts = key.split("_")
        assertTrue("键应为两段：$key", parts.size >= 2)
        assertEquals(16, parts[0].length)
    }
}
