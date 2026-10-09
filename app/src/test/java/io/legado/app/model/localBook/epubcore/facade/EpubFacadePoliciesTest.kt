package io.legado.app.model.localBook.epubcore.facade

import io.legado.app.data.entities.BookChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * facade 层 EPUB 纯策略单测（epub-md-rich-rendering 阶段 1.2/1.4 配对）
 *
 * 覆盖：续页结束片段判定、章节身份选择（fragment/owner/最近序号）、物理续页元数据标记、
 * 起始片段策略、片段窗口裁剪 [start, end)、可读/逻辑相邻章节、spine 归属、TOC 父级同资源判定。
 */
class EpubFacadePoliciesTest {

    private fun chapter(index: Int, url: String, startFragmentId: String? = null, variable: String? = null) =
        BookChapter(
            index = index,
            bookUrl = "book",
            url = url,
            startFragmentId = startFragmentId,
            variable = variable
        )

    // === EpubChapterFragmentRangePolicy ===

    @Test
    fun `同一资源内续页才补结束片段`() {
        assertEquals(
            "ch2",
            EpubChapterFragmentRangePolicy.endFragmentId("a.xhtml#ch1", "a.xhtml#ch2", "ch2")
        )
        assertNull(
            "跨资源不得补结束片段",
            EpubChapterFragmentRangePolicy.endFragmentId("a.xhtml#ch1", "b.xhtml#ch2", "ch2")
        )
        assertNull(EpubChapterFragmentRangePolicy.endFragmentId("a.xhtml", "a.xhtml", null))
    }

    // === EpubChapterIdentityPolicy ===

    @Test
    fun `章节身份取起始片段优先 其次 URL 解码片段`() {
        assertEquals("explicit", EpubChapterIdentityPolicy.fragment(chapter(1, "a.xhtml#url", "explicit")))
        assertEquals("f g", EpubChapterIdentityPolicy.fragment(chapter(1, "a.xhtml#f%20g")))
        assertNull(EpubChapterIdentityPolicy.fragment(chapter(1, "a.xhtml")))
    }

    @Test
    fun `章节身份选择按片段 归属和最近序号`() {
        val candidates = listOf(chapter(1, "a.xhtml#f1"), chapter(2, "a.xhtml#f2"), chapter(5, "a.xhtml"))

        assertEquals(1, EpubChapterIdentityPolicy.select(candidates, "f1", 99)?.index)
        assertEquals(2, EpubChapterIdentityPolicy.select(candidates, "unknown", 99, ownerIndex = 2)?.index)
        assertEquals(1, EpubChapterIdentityPolicy.select(listOf(candidates[0]), null, 42)?.index)
        assertEquals("请求序号不存在时取最近序号", 5, EpubChapterIdentityPolicy.select(candidates, null, 6)?.index)
        assertNull(EpubChapterIdentityPolicy.select(emptyList(), null, 1))
    }

    // === EpubChapterMetadata ===

    @Test
    fun `物理续页被标记为目录隐藏并继承归属标题`() {
        val marked = EpubChapterMetadata.markPhysicalContinuation(chapter(3, "a.xhtml#part2"), "a.xhtml", "第一章")
        assertTrue(EpubChapterMetadata.isHiddenFromToc(marked))
        assertEquals("第一章", marked.title)
        assertTrue(marked.variableMap[EpubChapterMetadata.LogicalOwnerUrlKey] == "a.xhtml")
        assertFalse(EpubChapterMetadata.isHiddenFromToc(chapter(4, "a.xhtml")))
    }

    @Test
    fun `缺少归属信息时原样返回章节`() {
        val original = chapter(3, "a.xhtml#part2")
        assertEquals(original, EpubChapterMetadata.markPhysicalContinuation(original, null, "标题"))
        assertEquals(original, EpubChapterMetadata.markPhysicalContinuation(original, "a.xhtml", null))
    }

    // === EpubDirectChapterStartFragmentPolicy ===

    @Test
    fun `起始片段策略优先保留请求片段`() {
        assertEquals(
            "req",
            EpubDirectChapterStartFragmentPolicy.resolve(
                requestedUrl = "a.xhtml#req",
                resolvedUrl = "a.xhtml#other",
                requestedHref = "a.xhtml",
                resolvedHref = "a.xhtml",
                requestedFragment = "req",
                normalizedStartFragmentId = "other"
            )
        )
        assertEquals(
            "other",
            EpubDirectChapterStartFragmentPolicy.resolve(
                requestedUrl = "a.xhtml#same",
                resolvedUrl = "a.xhtml#same",
                requestedHref = "a.xhtml",
                resolvedHref = "a.xhtml",
                requestedFragment = "req",
                normalizedStartFragmentId = "other"
            )
        )
        assertNull(
            EpubDirectChapterStartFragmentPolicy.resolve(
                "a.xhtml", "b.xhtml", "a.xhtml", "b.xhtml", null, null
            )
        )
    }

    // === EpubDirectFragmentWindowPolicy ===

    private val windowHtml =
        "<html><head><title>t</title></head><body><p id=\"a\">A</p><p id=\"b\">B</p><p id=\"c\">C</p></body></html>"

    @Test
    fun `无边界时不做裁剪并原样返回`() {
        val result = EpubDirectFragmentWindowPolicy.apply(windowHtml, null, null)
        assertFalse(result.applied)
        assertEquals(windowHtml, result.html)
        assertFalse(EpubDirectFragmentWindowPolicy.apply("", "a", null).applied)
    }

    @Test
    fun `起点边界裁掉其之前的内容`() {
        val result = EpubDirectFragmentWindowPolicy.apply(windowHtml, "b", null)
        assertTrue(result.applied)
        assertTrue(result.html.contains("B"))
        assertFalse("起点之前的内容应被移除", result.html.contains(">A<"))
        assertTrue("head 元数据必须保留", result.html.contains("<title>t</title>"))
    }

    @Test
    fun `区间边界裁掉终点及其之后的内容`() {
        val result = EpubDirectFragmentWindowPolicy.apply(windowHtml, "b", "c")
        assertTrue(result.applied)
        assertTrue(result.html.contains("B"))
        assertFalse("终点及其之后应被移除", result.html.contains(">C<"))
        assertFalse(result.html.contains(">A<"))
    }

    @Test
    fun `边界缺失或起止倒置时安全回退`() {
        val missing = EpubDirectFragmentWindowPolicy.apply(windowHtml, "not-exist", null)
        assertFalse(missing.applied)
        assertEquals(windowHtml, missing.html)

        val reversed = EpubDirectFragmentWindowPolicy.apply(windowHtml, "c", "a")
        assertFalse(reversed.applied)
        assertEquals(windowHtml, reversed.html)
    }

    // === EpubReadableChapterPolicy ===

    @Test
    fun `可读与逻辑相邻章节判定`() {
        val chapters = listOf(
            chapter(1, "a.xhtml"),
            chapter(2, "skip:volume"),
            chapter(3, "a.xhtml#part2", variable = "{\"${EpubChapterMetadata.TocHiddenKey}\":\"true\"}"),
            chapter(4, "b.xhtml")
        )
        assertTrue(EpubReadableChapterPolicy.isReadable(chapters[0]))
        assertFalse(EpubReadableChapterPolicy.isReadable(chapters[1]))
        assertFalse("隐藏的物理续页不是逻辑章节", EpubReadableChapterPolicy.isLogical(chapters[2]))

        assertEquals("可读相邻只排除 skip：，隐藏的物理续页仍算可读", 3, EpubReadableChapterPolicy.adjacent(chapters, 1, 1))
        assertEquals(3, EpubReadableChapterPolicy.adjacent(chapters, 4, -1))
        assertEquals("逻辑相邻跳过隐藏的物理续页", 4, EpubReadableChapterPolicy.adjacentLogical(chapters, 1, 1))

        assertEquals("不可读时按方向回退到相邻可读章节", 1, EpubReadableChapterPolicy.resolve(chapters, 2, -1))
        assertEquals(4, EpubReadableChapterPolicy.resolve(chapters, 4))
        assertNull(EpubReadableChapterPolicy.resolve(listOf(chapter(1, "skip:x")), 1))
    }

    // === EpubSpineOwnershipPolicy ===

    @Test
    fun `spine 归属取不大于序号的最大者并按目录序破平`() {
        val owners = listOf(
            EpubSpineOwnershipPolicy.Owner(spineOrder = 0, tocOrder = 0, url = "a.xhtml", title = "A"),
            EpubSpineOwnershipPolicy.Owner(spineOrder = 2, tocOrder = 1, url = "b.xhtml", title = "B"),
            EpubSpineOwnershipPolicy.Owner(spineOrder = 2, tocOrder = 2, url = "c.xhtml", title = "C")
        )
        assertEquals("A", EpubSpineOwnershipPolicy.ownerAt(1, owners)?.title)
        assertEquals("C", EpubSpineOwnershipPolicy.ownerAt(3, owners)?.title)
        assertNull(EpubSpineOwnershipPolicy.ownerAt(-1, owners))
    }

    // === EpubTocParentHrefPolicy ===

    @Test
    fun `TOC 父级同资源判定与结构化条件`() {
        assertTrue(EpubTocParentHrefPolicy.sharesResource("a.xhtml#x", "a.xhtml#y"))
        assertFalse(EpubTocParentHrefPolicy.sharesResource("a.xhtml#x", "b.xhtml#y"))
        assertFalse(EpubTocParentHrefPolicy.sharesResource("skip:v", "skip:v"))
        assertFalse(EpubTocParentHrefPolicy.sharesResource("a.xhtml", null))

        assertTrue(EpubTocParentHrefPolicy.shouldBecomeStructural(true, "a.xhtml", "a.xhtml#y"))
        assertFalse(EpubTocParentHrefPolicy.shouldBecomeStructural(false, "a.xhtml", "a.xhtml#y"))
    }
}