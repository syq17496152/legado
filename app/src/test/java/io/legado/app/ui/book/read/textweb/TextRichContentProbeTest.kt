package io.legado.app.ui.book.read.textweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 富渲染内容探测契约测试（阶段 3.1/3.2 接线配对）。
 *
 * 守护两类真实风险：①口径与 `TextChapterLayout` 不一致导致"能渲染的书被判成不可渲染"；
 * ②宽判定导致"正文提到 mermaid 一词就注入 2.4MB 运行时"。
 */
class TextRichContentProbeTest {

    @Test
    fun `抽取 usehtml 内层且口径与 canvas 一致`() {
        val inner = "<p class=\"reader-paragraph\">正文</p>"
        assertEquals(inner, TextRichContentProbe.extractUseHtmlInner("<usehtml>$inner</usehtml>"))
        // canvas 判定是 startsWith("<usehtml") + 首个 '>' 到最后一个 '<'
        assertEquals(inner, TextRichContentProbe.extractUseHtmlInner("<usehtml  >$inner</usehtml >"))
    }

    @Test
    fun `非 usehtml 内容返回空以回落 canvas`() {
        assertNull(TextRichContentProbe.extractUseHtmlInner(null))
        assertNull(TextRichContentProbe.extractUseHtmlInner(""))
        assertNull(TextRichContentProbe.extractUseHtmlInner("普通纯文本正文"))
        assertNull("标签不完整时不得硬切", TextRichContentProbe.extractUseHtmlInner("<usehtml>只有开头"))
        assertNull("内层为空时视为不可用", TextRichContentProbe.extractUseHtmlInner("<usehtml></usehtml>"))
    }

    @Test
    fun `prepare 同时给出正文与富渲染标注`() {
        val prepared = TextRichContentProbe.prepare(
            "<usehtml><pre class=\"mermaid\">graph TD;A-->B</pre><p>公式 \$x^2\$</p></usehtml>"
        )
        requireNotNull(prepared)
        assertTrue(prepared.hasMermaid)
        assertTrue(prepared.hasMath)
        assertTrue(prepared.html.contains("graph TD"))
    }

    @Test
    fun `mermaid 需为类名而非任意出现该词`() {
        assertTrue(TextRichContentProbe.detectMermaid("<pre class=\"mermaid\">x</pre>"))
        assertTrue(TextRichContentProbe.detectMermaid("<div class='foo mermaid bar'>x</div>"))
        assertFalse(
            "正文提到 mermaid 一词不得触发 2.4MB 运行时注入",
            TextRichContentProbe.detectMermaid("<p>本章介绍 mermaid 语法</p>")
        )
        assertFalse(TextRichContentProbe.detectMermaid("<p class=\"mermaidx\">x</p>"))
    }

    @Test
    fun `公式需成对定界符`() {
        assertTrue(TextRichContentProbe.detectMath("<p>\$x^2 + y^2\$</p>"))
        assertTrue(TextRichContentProbe.detectMath("<p>\$\$\\int_0^1 x dx\$\$</p>"))
        assertFalse("孤立美元符（货币）不得判定为公式", TextRichContentProbe.detectMath("<p>价格 5\$ 与 8\$</p>"))
        assertFalse("定界符内侧为空白不判为公式", TextRichContentProbe.detectMath("<p>\$ 5 and 8 \$</p>"))
        assertFalse("空公式不判定", TextRichContentProbe.detectMath("<p>\$\$</p>"))
    }

    @Test
    fun `代码块内的美元符不算公式`() {
        assertFalse(
            TextRichContentProbe.detectMath("<pre><code>echo \$HOME \$PATH</code></pre>")
        )
        // 正文公式仍在代码块之外时正常判定
        assertTrue(
            TextRichContentProbe.detectMath("<pre><code>echo \$HOME</code></pre><p>\$a+b\$</p>")
        )
    }

    @Test
    fun `无富渲染元素时两项标注均为假`() {
        val prepared = TextRichContentProbe.prepare("<usehtml><p>纯文字段落</p></usehtml>")
        requireNotNull(prepared)
        assertFalse(prepared.hasMermaid)
        assertFalse(prepared.hasMath)
    }
}