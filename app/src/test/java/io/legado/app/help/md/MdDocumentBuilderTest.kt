package io.legado.app.help.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown → 归一化章节 HTML 单测（epub-md-rich-rendering 阶段 3.5 配对）。
 *
 * 覆盖【验证标准】：产物进入文本渲染模式（GFM 片段 HTML）、章节与目录同源；
 * 外加安全契约（raw HTML 默认转义 / 显式放开）、边界（单章超限显式报错）、
 * 富渲染构建期标注（mermaid / 公式）与 mermaid 保形、裸 URL 自动链接。
 */
class MdDocumentBuilderTest {

    @Test
    fun `GFM 表格与删除线渲染为片段 HTML`() {
        val built = MdDocumentBuilder.build(
            """
            | 列一 | 列二 |
            | --- | --- |
            | 甲 | 乙 |

            ~~废弃~~
            """.trimIndent()
        )
        assertTrue("表格须渲染为 table", built.html.contains("<table>"))
        assertTrue("表头须渲染 th", built.html.contains("<th>"))
        assertTrue("删除线须渲染 del", built.html.contains("<del>"))
        assertFalse("产物应是片段而非整页", built.html.contains("<html"))
    }

    @Test
    fun `安全模式默认转义 raw HTML`() {
        val built = MdDocumentBuilder.build("前文 <script>alert(1)</script> 后文")
        assertFalse("默认不得把 script 放进 DOM", built.html.contains("<script>"))
        assertTrue("必须转义为文本", built.html.contains("&lt;script&gt;"))
        assertTrue("纯文本保留原字符", built.plainText.contains("<script>"))
    }

    @Test
    fun `显式放开后 raw HTML 进入 DOM`() {
        val built = MdDocumentBuilder.build(
            "<div class=\"box\">内容</div>",
            MdDocumentBuilder.Options(allowRawHtml = true)
        )
        assertTrue(built.html.contains("<div class=\"box\">"))
    }

    @Test
    fun `单章超限抛显式错误不截断`() {
        val huge = "字".repeat(MdDocumentBuilder.MaxChapterChars + 1)
        val error = assertThrows(MdDocumentBuilder.MdChapterTooLargeException::class.java) {
            MdDocumentBuilder.build(huge)
        }
        assertTrue("错误信息需含上限", error.message!!.contains(MdDocumentBuilder.MaxChapterChars.toString()))
    }

    @Test
    fun `mermaid 围栏块保形为 pre class mermaid 并标注富渲染`() {
        val built = MdDocumentBuilder.build(
            """
            # 图

            ```mermaid
            graph TD; A-->B;
            ```
            """.trimIndent()
        )
        assertTrue("须产出 mermaid 容器", built.html.contains("<pre class=\"mermaid\">"))
        assertFalse("不得残留语言类 code 块", built.html.contains("language-mermaid"))
        assertTrue("构建期须标注 mermaid", built.hasMermaid)
        assertTrue(built.hasRichRender)
        assertFalse("无公式不应误判", built.hasMath)
    }

    @Test
    fun `非 mermaid 代码块保持普通代码块`() {
        val built = MdDocumentBuilder.build(
            """
            ```kotlin
            val a = 1
            ```
            """.trimIndent()
        )
        assertTrue(built.html.contains("<pre><code class=\"language-kotlin\">"))
        assertFalse(built.hasMermaid)
        assertFalse(built.hasRichRender)
    }

    @Test
    fun `公式在构建期被标注且文本原样保留`() {
        val built = MdDocumentBuilder.build("行内 \$E=mc^2\$ 与块级\n\n\$\$\n\\int_0^1 x dx\n\$\$")
        assertTrue("须标注公式", built.hasMath)
        assertTrue("定界符须原样保留供 KaTeX 处理", built.html.contains("\$E=mc^2\$"))
        assertTrue(built.html.contains("\$\$"))
    }

    @Test
    fun `货币符号不被误判为公式`() {
        val built = MdDocumentBuilder.build("定价 \$5 与 \$10 两档")
        assertFalse("纯货币不应触发 KaTeX 注入", built.hasMath)
    }

    @Test
    fun `代码块内的井号与公式不触发富渲染标注`() {
        val mathDollar = "$"
        val built = MdDocumentBuilder.build(
            """
            ```
            # 注释
            ${mathDollar}x$mathDollar
            ```
            """.trimIndent()
        )
        assertFalse(built.hasMermaid)
        assertFalse("代码块内公式不算富渲染", built.hasMath)
    }

    @Test
    fun `裸 URL 自动链接且不重复包裹已有链接`() {
        val built = MdDocumentBuilder.build("见 https://example.com/a 与 [已有](https://example.com/b)")
        assertTrue("裸 URL 须成为链接", built.html.contains("href=\"https://example.com/a\""))
        assertEquals(
            "已有链接不得被再包一层",
            1,
            Regex("<a [^>]*href=\"https://example.com/b\"").findAll(built.html).count()
        )
    }

    @Test
    fun `关闭自动链接时裸 URL 保持文本`() {
        val built = MdDocumentBuilder.build(
            "见 https://example.com/a",
            MdDocumentBuilder.Options(autolink = false)
        )
        assertFalse(built.html.contains("<a "))
        assertTrue(built.html.contains("https://example.com/a"))
    }

    @Test
    fun `纯文本与 HTML 同源`() {
        val built = MdDocumentBuilder.build("# 标题\n\n段落一\n\n段落二")
        assertTrue(built.plainText.contains("标题"))
        assertTrue(built.plainText.contains("段落一"))
        assertFalse("纯文本不得含标签", built.plainText.contains("<"))
        assertTrue(built.html.contains("<h1>"))
    }

    @Test
    fun `空输入产出空产物`() {
        val built = MdDocumentBuilder.build("")
        assertTrue(built.html.isBlank())
        assertFalse(built.hasRichRender)
    }
}