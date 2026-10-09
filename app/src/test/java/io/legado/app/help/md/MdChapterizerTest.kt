package io.legado.app.help.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 切章单测（epub-md-rich-rendering 阶段 3.4 配对）。
 *
 * 覆盖【验证标准】：多级标题 / 无标题 / 超长软切 / setext / 未闭合代码块，
 * 外加三条不变量：区间首尾相接可拼回原文、不越界、围栏内不切章。
 */
class MdChapterizerTest {

    /** 不变量：各章区间首尾相接，拼回 == 原文。 */
    private fun assertContiguous(markdown: String, sections: List<MdChapterizer.Section>) {
        var cursor = 0
        sections.forEach { section ->
            assertEquals("章节区间必须首尾相接", cursor, section.startOffset)
            assertTrue("区间不得倒序", section.endOffset >= section.startOffset)
            cursor = section.endOffset
        }
        assertEquals("拼接后必须等于原文长度", markdown.length, cursor)
        assertEquals(
            "拼接后必须等于原文",
            markdown,
            sections.joinToString("") { markdown.substring(it.startOffset, it.endOffset) }
        )
    }

    @Test
    fun `ATX 多级标题按默认层级切章`() {
        val md = """
            # 第一章
            正文一

            ## 第一节
            正文二

            ### 小节（不切章）
            正文三

            # 第二章
            正文四
        """.trimIndent()
        val sections = MdChapterizer.chapterize(md)
        assertEquals(listOf("第一章", "第一节", "第二章"), sections.map { it.title })
        assertEquals(listOf(1, 2, 1), sections.map { it.level })
        assertTrue("三级标题不得切章", sections.none { it.title.startsWith("小节") })
        assertContiguous(md, sections)
    }

    @Test
    fun `无标题时整本一章且标题取首个非空行`() {
        val md = "这是首个非空行\n\n后续内容"
        val sections = MdChapterizer.chapterize(md)
        assertEquals(1, sections.size)
        assertEquals("这是首个非空行", sections.single().title)
        assertEquals(0, sections.single().level)
        assertContiguous(md, sections)
    }

    @Test
    fun `空文档返回单章且区间为空`() {
        val sections = MdChapterizer.chapterize("")
        assertEquals(1, sections.size)
        assertEquals(0, sections.single().length)
    }

    @Test
    fun `围栏代码块内的井号与下划线不切章`() {
        val md = """
            # 真章节

            ```markdown
            # 伪章节
            正文
            ```
            剩余内容
        """.trimIndent()
        val sections = MdChapterizer.chapterize(md)
        assertEquals(1, sections.size)
        assertEquals("真章节", sections.single().title)
        assertContiguous(md, sections)
    }

    @Test
    fun `未闭合围栏延至文末不切章`() {
        val md = """
            # 首章

            ```
            # 未闭合后的伪标题
            ```
        """.trimIndent()
        val sections = MdChapterizer.chapterize(md)
        assertEquals(listOf("首章"), sections.map { it.title })
        assertContiguous(md, sections)
    }

    @Test
    fun `setext 标题按等号与连字符分一二级`() {
        val md = """
            一级标题
            ========

            正文一

            二级标题
            --------

            正文二
        """.trimIndent()
        val sections = MdChapterizer.chapterize(md)
        assertEquals(listOf("一级标题", "二级标题"), sections.map { it.title })
        assertEquals(listOf(1, 2), sections.map { it.level })
        assertContiguous(md, sections)
    }

    @Test
    fun `空行后的连字符是分隔线而非 setext 标题`() {
        val md = """
            正文段落

            ---

            后续段落
        """.trimIndent()
        val sections = MdChapterizer.chapterize(md)
        assertEquals("分隔线不得被当成标题", 1, sections.size)
        assertContiguous(md, sections)
    }

    @Test
    fun `首个标题前的前置内容成为独立首章`() {
        val md = """
            书名页
            作者：某人

            # 第一章
            正文
        """.trimIndent()
        val sections = MdChapterizer.chapterize(md)
        assertEquals(listOf("书名页", "第一章"), sections.map { it.title })
        assertEquals(0, sections.first().level)
        assertContiguous(md, sections)
    }

    @Test
    fun `超长章节按空行软切且字符总量不变`() {
        val paragraph = "段落内容占位\n\n"
        val body = paragraph.repeat(200)
        val md = "# 长章节\n\n$body"
        val sections = MdChapterizer.chapterize(md, softLimitChars = 300)
        assertTrue("必须发生软切", sections.size > 1)
        assertTrue("续块需标记", sections.drop(1).all { it.continued })
        assertEquals("首块保留原标题", "长章节", sections.first().title)
        assertTrue("续块标题带续号", sections[1].title.contains("续2"))
        assertContiguous(md, sections)
        assertEquals(
            "软切不得丢字符",
            md.length,
            sections.sumOf { it.length }
        )
    }

    @Test
    fun `无空行的超长章节走硬切仍不丢字符`() {
        val md = "无空行内容".repeat(200)
        val sections = MdChapterizer.chapterize(md, softLimitChars = 100)
        assertTrue(sections.size > 1)
        assertContiguous(md, sections)
    }

    @Test
    fun `关闭软切时按标题原样分章`() {
        val md = "# 甲\n\n内容\n\n# 乙\n\n内容"
        val sections = MdChapterizer.chapterize(md, softLimitChars = 0)
        assertEquals(listOf("甲", "乙"), sections.map { it.title })
        assertContiguous(md, sections)
    }

    @Test
    fun `CRLF 换行下偏移仍与原文一致`() {
        val md = "# 甲\r\n\r\n正文\r\n\r\n# 乙\r\n\r\n正文"
        val sections = MdChapterizer.chapterize(md)
        assertEquals(listOf("甲", "乙"), sections.map { it.title })
        assertContiguous(md, sections)
    }

    @Test
    fun `超长标题被截断`() {
        val longTitle = "标".repeat(200)
        val md = "# $longTitle\n\n正文"
        val sections = MdChapterizer.chapterize(md)
        assertEquals(
            "标题须截断到上限 + 省略号",
            MdChapterizer.MaxTitleChars + 1,
            sections.single().title.length
        )
    }
}