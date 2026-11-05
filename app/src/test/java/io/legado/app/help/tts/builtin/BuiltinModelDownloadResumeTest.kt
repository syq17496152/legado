package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 断点续传判据（4.10 下载治理，2026-11-05）：
 * 只有同一来源的残片才可续传；换源续传会把两份包拼成坏归档。
 */
class BuiltinModelDownloadResumeTest {

    private val urlA = "https://example.invalid/models/a.tar.bz2"
    private val urlB = "https://example.invalid/models/b.tar.bz2"

    @Test
    fun `同源残片可续传`() {
        assertEquals(1024L, BuiltinModelManager.resumeOffset(1024L, urlA, urlA))
    }

    @Test
    fun `换源残片不可续传`() {
        assertEquals(0L, BuiltinModelManager.resumeOffset(1024L, urlA, urlB))
    }

    @Test
    fun `无残片或空记录一律从头下`() {
        assertEquals(0L, BuiltinModelManager.resumeOffset(0L, urlA, urlA))
        assertEquals(0L, BuiltinModelManager.resumeOffset(1024L, null, urlA))
        assertEquals(0L, BuiltinModelManager.resumeOffset(1024L, "  ", urlA))
    }

    @Test
    fun `记录 URL 首尾空白不影响判定`() {
        assertEquals(2048L, BuiltinModelManager.resumeOffset(2048L, " $urlA\n", urlA))
    }
}
