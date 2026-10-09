package io.legado.app.model.localBook.epubcore.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EPUB 压缩包访问与路径工具单测（epub-md-rich-rendering 阶段 1.1/1.2 配对）
 *
 * 覆盖：EpubPath 归一化/相对解析/片段编解码；ZipEpubArchive 精确与大小写不敏感命中、
 * entrySize/openStream；EpubArchiveLeaseGate 在途租约期间不真正关闭底层 zip。
 */
class EpubPathAndArchiveTest {

    // === EpubPath ===

    @Test
    fun `normalize 折叠点段并剥离查询与片段`() {
        assertEquals("a/b", EpubPath.normalize("a/./b"))
        assertEquals("b/c", EpubPath.normalize("a/../b/c"))
        assertEquals("a/b", EpubPath.normalize("a/b?x=1"))
        assertEquals("a/b", EpubPath.normalize("a\\b#frag"))
    }

    @Test
    fun `resolve 相对路径按基目录解析`() {
        assertEquals(
            "OEBPS/images/img.png",
            EpubPath.resolve("OEBPS/text/ch1.xhtml", "../images/img.png")
        )
        assertEquals("ch2.xhtml#frag", EpubPath.resolve("ch1.xhtml", "ch2.xhtml#frag"))
    }

    @Test
    fun `resolve 对含 scheme 或协议相对的 href 原样返回`() {
        assertEquals("//host/a.xhtml", EpubPath.resolve("OEBPS/ch1.xhtml", "//host/a.xhtml"))
        assertEquals("urn:isbn:123", EpubPath.resolve("OEBPS/ch1.xhtml", "urn:isbn:123"))
    }

    @Test
    fun `片段编解码与取值`() {
        assertEquals("frag", EpubPath.fragment("a.xhtml#frag"))
        assertNull(EpubPath.fragment("a.xhtml"))
        assertEquals("f g", EpubPath.decodedFragment("a.xhtml#f%20g"))
        assertEquals("f%20g", EpubPath.encodeFragment("f g"))
        assertEquals("a/b", EpubPath.stripFragment("a/b#c"))
    }

    // === ZipEpubArchive ===

    @Test
    fun `zip 读取精确命中和大小写不敏感命中`() {
        val file = zipOf(mapOf("OEBPS/Text/Ch1.xhtml" to "<p>hi</p>"))
        ZipEpubArchive(file).use { archive ->
            assertTrue(archive.exists("OEBPS/Text/Ch1.xhtml"))
            assertTrue(archive.exists("oebps/text/ch1.xhtml"))
            assertEquals("<p>hi</p>", archive.readText("OEBPS/Text/Ch1.xhtml"))
            assertEquals("OEBPS/Text/Ch1.xhtml", archive.canonicalPath("./OEBPS/Text/Ch1.xhtml"))
            assertEquals(9L, archive.entrySize("OEBPS/Text/Ch1.xhtml"))
            assertEquals(
                "<p>hi</p>",
                archive.openStream("OEBPS/Text/Ch1.xhtml").use { it.readBytes().toString(Charsets.UTF_8) }
            )
            assertNull(archive.canonicalPath("missing.xhtml"))
            assertFalse(archive.exists("missing.xhtml"))
        }
        file.delete()
    }

    // === EpubArchiveLeaseGate ===

    @Test
    fun `在途租约期间不关闭底层 zip 释放后才关闭`() {
        val file = zipOf(mapOf("a.txt" to "hello"))
        val gate = EpubArchiveLeaseGate(ZipEpubArchive(file))
        val stream = gate.openStream("a.txt")
        var drained = false
        gate.closeWhenDrained { drained = true }
        assertFalse("存在在途流租约时不得立即关闭", drained)
        assertEquals("hello", stream.readBytes().toString(Charsets.UTF_8))
        stream.close()
        assertTrue("租约释放后应完成关闭", drained)
        file.delete()
    }

    private fun zipOf(entries: Map<String, String>): File {
        val file = File.createTempFile("epub-test", ".zip")
        ZipOutputStream(FileOutputStream(file)).use { out ->
            entries.forEach { (name, body) ->
                out.putNextEntry(ZipEntry(name))
                out.write(body.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
        return file
    }
}