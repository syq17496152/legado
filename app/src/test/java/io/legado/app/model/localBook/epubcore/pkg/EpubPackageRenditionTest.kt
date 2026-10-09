package io.legado.app.model.localBook.epubcore.pkg

import io.legado.app.model.localBook.epubcore.archive.EpubPath
import io.legado.app.model.localBook.epubcore.archive.ZipEpubArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EPUB3 rendition / itemref 覆盖解析单测（epub-md-rich-rendering 阶段 1.4 配对）。
 *
 * 覆盖：①全书 `rendition:layout` 与视口（含 EPUB2 `original-resolution` 回退）；
 * ②itemref `rendition:layout-*` 覆盖全书声明；③itemref properties 小写化；
 * ④未声明时 rendition 缺省（分类改走内容结构推断）；⑤nav/cover 识别不受 properties 小写化影响。
 */
class EpubPackageRenditionTest {

    @Test
    fun `全书 rendition 与 original-resolution 视口被解析`() {
        val file = zipOf(
            mapOf(
                "META-INF/container.xml" to container("OEBPS/content.opf"),
                "OEBPS/content.opf" to opf(
                    metadata = """
                        <meta property="rendition:layout">pre-paginated</meta>
                        <meta property="rendition:viewport">width=1024, height=768</meta>
                    """.trimIndent(),
                    items = """<item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>""",
                    spine = """<itemref idref="c1"/>"""
                )
            )
        )
        ZipEpubArchive(file).use { archive ->
            val pkg = EpubPackageParser().parse(archive)
            assertEquals("pre-paginated", pkg.renditionLayout)
            assertEquals(1024f, pkg.rendition.viewportWidth!!, 0.001f)
            assertEquals(768f, pkg.rendition.viewportHeight!!, 0.001f)
            assertEquals("OEBPS/text/ch1.xhtml", pkg.spine.single().href)
        }
        file.delete()
    }

    @Test
    fun `epub2 original-resolution 作为视口回退`() {
        val file = zipOf(
            mapOf(
                "META-INF/container.xml" to container("OEBPS/content.opf"),
                "OEBPS/content.opf" to opf(
                    metadata = """<meta name="original-resolution" content="1200x1600"/>""",
                    items = """<item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>""",
                    spine = """<itemref idref="c1"/>"""
                )
            )
        )
        ZipEpubArchive(file).use { archive ->
            val pkg = EpubPackageParser().parse(archive)
            assertNull("EPUB2 无 rendition:layout 声明", pkg.renditionLayout)
            assertEquals(1200f, pkg.rendition.viewportWidth!!, 0.001f)
            assertEquals(1600f, pkg.rendition.viewportHeight!!, 0.001f)
        }
        file.delete()
    }

    @Test
    fun `itemref rendition layout 覆盖全书声明且 properties 小写化`() {
        val file = zipOf(
            mapOf(
                "META-INF/container.xml" to container("OEBPS/content.opf"),
                "OEBPS/content.opf" to opf(
                    metadata = """<meta property="rendition:layout">reflowable</meta>""",
                    items = """<item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>""",
                    spine = """<itemref idref="c1" properties="Rendition:Layout-Pre-Paginated Scripted"/>"""
                )
            )
        )
        ZipEpubArchive(file).use { archive ->
            val pkg = EpubPackageParser().parse(archive)
            val item = pkg.spine.single()
            assertEquals("pre-paginated", item.rendition.layout)
            assertEquals(setOf("rendition:layout-pre-paginated", "scripted"), item.properties)
        }
        file.delete()
    }

    @Test
    fun `未声明 rendition 时缺省且 nav 与 cover-image 识别保持`() {
        val file = zipOf(
            mapOf(
                "META-INF/container.xml" to container("OEBPS/content.opf"),
                "OEBPS/content.opf" to opf(
                    metadata = "",
                    items = """
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="cov" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                        <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                    """.trimIndent(),
                    spine = """<itemref idref="c1"/>"""
                )
            )
        )
        ZipEpubArchive(file).use { archive ->
            val pkg = EpubPackageParser().parse(archive)
            assertNull(pkg.renditionLayout)
            assertNull(pkg.rendition.viewportWidth)
            assertEquals("OEBPS/nav.xhtml", pkg.navHref)
            assertEquals("OEBPS/images/cover.jpg", pkg.coverHref)
            assertTrue("未声明 rendition 时章节 rendition 继承缺省", pkg.spine.single().rendition.layout == null)
            assertEquals(EpubPath.stripFragment(pkg.spine.single().href), "OEBPS/text/ch1.xhtml")
        }
        file.delete()
    }

    // 注意：模板不得依赖 trimIndent —— 插值进来的多行片段会把最小缩进拉回 0，
    // 导致 XML 声明不在第 0 列（Parser 直接拒绝）。故模板逐行从第 0 列书写。
    private fun container(opfPath: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

    private fun opf(metadata: String, items: String, spine: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="pub-id">urn:uuid:test</dc:identifier>
    <dc:title>Test</dc:title>
$metadata
  </metadata>
  <manifest>$items</manifest>
  <spine>$spine</spine>
</package>"""

    private fun zipOf(entries: Map<String, String>): File {
        val file = File.createTempFile("epub-pkg-test", ".zip")
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