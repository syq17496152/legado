package io.legado.app.model.localBook.epubcore.web

import io.legado.app.model.localBook.epubcore.archive.EpubArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩包回源单源单测（epub-md-rich-rendering 阶段 2.3 配对）。
 *
 * 口径：只测**纯 JVM** 部分（URL→路径映射、baseUrl 构造、MIME 判定、大小写/转义/越界）。
 * `WebResourceResponse` 的构造在 JVM stub 下无法回读，故不对响应体断言——该部分由真机 L2 覆盖
 * （见 tasks.md 2.5 的 L2 对照清单）。
 */
class EpubArchiveWebResponseTest {

    private class FakeArchive(private val entries: Map<String, String>) : EpubArchive {
        override fun exists(path: String): Boolean = entries.keys.any { it.equals(path, true) }
        override fun list(): List<String> = entries.keys.toList()
        override fun readBytes(path: String, maxBytes: Long): ByteArray {
            val hit = entries.entries.first { it.key.equals(path, true) }
            return hit.value.toByteArray(Charsets.UTF_8)
        }
        override fun close() = Unit
    }

    private fun response(entries: Map<String, String> = emptyMap()) =
        EpubArchiveWebResponse(FakeArchive(entries))

    @Test
    fun `baseUrl 逐段编码且与虚拟主机一致`() {
        val component = response()
        assertEquals(
            "https://epub.local/OEBPS/text/ch1.xhtml",
            component.baseUrl("OEBPS/text/ch1.xhtml")
        )
        assertEquals(
            "带空格的段必须编码",
            "https://epub.local/OEBPS/my%20text/ch%201.xhtml",
            component.baseUrl("OEBPS/my text/ch 1.xhtml")
        )
        assertEquals(
            "路径归一（折叠点段）后再编码",
            "https://epub.local/a/b.xhtml",
            component.baseUrl("./a/./b.xhtml")
        )
    }

    @Test
    fun `archivePath 命中本主机并归一化路径`() {
        val component = response()
        val base = "https://epub.local"
        assertEquals("OEBPS/text/ch1.xhtml", component.archivePath("$base/OEBPS/text/ch1.xhtml"))
        assertEquals(
            "点段折叠",
            "OEBPS/ch.xhtml",
            component.archivePath("$base/OEBPS/../OEBPS/ch.xhtml")
        )
        assertEquals("OEBPS/style.css", component.archivePath("$base/OEBPS/style.css"))
    }

    @Test
    fun `archivePath 对转义与大小写主机容错`() {
        val component = response()
        assertEquals(
            "百分号转义需还原",
            "OEBPS/我的 文本/ch1.xhtml",
            component.archivePath("https://epub.local/OEBPS/%E6%88%91%E7%9A%84%20%E6%96%87%E6%9C%AC/ch1.xhtml")
        )
        assertEquals(
            "主机名不区分大小写",
            "a.xhtml",
            component.archivePath("https://EPUB.Local/a.xhtml")
        )
        assertEquals(
            "协议相对 URL 亦命中",
            "a.xhtml",
            component.archivePath("//epub.local/a.xhtml")
        )
    }

    @Test
    fun `archivePath 拒绝非本主机与空路径`() {
        val component = response()
        assertNull(component.archivePath("https://example.com/a.xhtml"))
        assertNull(component.archivePath("https://epub.local/"))
        assertNull(component.archivePath("file:///sdcard/a.xhtml"))
        assertNull(component.archivePath(null))
        assertNull(component.archivePath(""))
        assertFalse(component.isLocalUrl("https://example.com/a.xhtml"))
        assertTrue(component.isLocalUrl("https://epub.local/a.xhtml"))
    }

    @Test
    fun `mimeTypeFor 覆盖常见 EPUB 资源类型`() {
        val component = response()
        assertEquals("text/css", component.mimeTypeFor("OEBPS/style.css"))
        assertEquals("application/xhtml+xml", component.mimeTypeFor("OEBPS/ch1.xhtml"))
        assertEquals("image/svg+xml", component.mimeTypeFor("OEBPS/cover.svg"))
        assertEquals("image/jpeg", component.mimeTypeFor("OEBPS/images/pic.JPG"))
        assertEquals("font/woff2", component.mimeTypeFor("OEBPS/fonts/a.woff2"))
    }

    @Test
    fun `字体虚拟路径常量与宿主 URL 约定一致`() {
        assertEquals(
            "宿主 readerFontUrl 的虚拟路径段",
            "https://epub.local/__legado_reader_font__",
            "https://epub.local/${EpubArchiveWebResponse.ReaderFontPath}"
        )
    }

    @Test
    fun `字体绑定流读取器返回 null 时由调用方转空响应`() {
        val binding = EpubArchiveWebResponse.ReaderFontBinding(
            entityPath = "/definitely/not/exists/font.ttf",
            streamProvider = { null }
        )
        assertNull(binding.streamProvider(binding.entityPath))
    }
}