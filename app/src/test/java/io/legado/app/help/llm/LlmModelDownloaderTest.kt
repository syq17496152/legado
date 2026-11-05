package io.legado.app.help.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LLM 断点续传的判据（纯函数）：**同源才可续**。
 * 换源续传会把两个不同文件的残片拼在一起，装上去必然坏 —— 这是比"下载失败"更糟的结果。
 */
class LlmModelDownloaderTest {

    @Test
    fun `同源残片可续传`() {
        assertEquals(
            1234L,
            LlmModelDownloader.resumeOffset(1234L, " https://a/m.gguf ", "https://a/m.gguf")
        )
    }

    @Test
    fun `换源不续传`() {
        assertEquals(
            0L,
            LlmModelDownloader.resumeOffset(1234L, "https://a/m.gguf", "https://b/m.gguf")
        )
    }

    @Test
    fun `无残片或空白记录从零开始`() {
        assertEquals(0L, LlmModelDownloader.resumeOffset(0L, "https://a/m.gguf", "https://a/m.gguf"))
        assertEquals(0L, LlmModelDownloader.resumeOffset(1234L, null, "https://a/m.gguf"))
        assertEquals(0L, LlmModelDownloader.resumeOffset(1234L, "", "https://a/m.gguf"))
    }

    @Test
    fun `进度在总长未知时不显示百分比`() {
        val known = LlmModelDownloader.Progress(50, 100)
        assertEquals(50, known.percent)

        val unknown = LlmModelDownloader.Progress(50, -1)
        assertEquals("总长未知只能显示已下载量", 0, unknown.percent)

        val done = LlmModelDownloader.Progress(3, 3)
        assertEquals(100, done.percent)

        val overflow = LlmModelDownloader.Progress(10, 3)
        assertEquals("溢出也要夹在 100", 100, overflow.percent)

        assertTrue(LlmModelDownloader.Progress(1, 2).percent in 0..100)
        assertFalse(LlmModelDownloader.Progress(0, 0).percent > 0)
    }
}
