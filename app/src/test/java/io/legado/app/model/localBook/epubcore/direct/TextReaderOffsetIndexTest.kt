package io.legado.app.model.localBook.epubcore.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 显示/朗读同源偏移索引单测（epub-md-rich-rendering 阶段 2.7 配对）。
 *
 * 【验证标准】「显示与朗读文本一致；气泡位置对应」的可验证化落地：
 * ①`spans` 覆盖的字符序列与 `plainText()` **逐字符一致**（同源）；
 * ②`spans` 的偏移与 `html()` 写入的 `data-legado-text-offset` **一致**（气泡挂点正确）；
 * ③字符位置 ↔ 段落双向映射可往返。
 */
class TextReaderOffsetIndexTest {

    private fun content(
        title: String = "第一章",
        blocks: List<TextReaderDocument.Block>
    ) = TextReaderDocument.Content(title = title, blocks = blocks)

    private fun textBlock(text: String) = TextReaderDocument.Block(text = text)

    /** 从 html 中抽取 data-legado-text-offset，验证与索引一致。 */
    private fun htmlOffsets(html: String): List<Int> {
        return Regex("data-legado-text-offset=\"(\\d+)\"").findAll(html)
            .map { it.groupValues[1].toInt() }
            .toList()
    }

    private fun htmlText(html: String): String {
        // 先剔除 <head>（含内联样式表），再剥标签与空白——样式文本不属于可见正文。
        val bodyOnly = html.replace(Regex("(?is)<head\\b.*?</head\\s*>"), "")
        return bodyOnly.replace(Regex("<[^>]+>"), "")
            .replace(Regex("\\s+"), "")
    }

    @Test
    fun `索引与纯文本逐字符一致`() {
        val doc = content(blocks = listOf(textBlock("第一段"), textBlock("第二段")))
        val spans = TextReaderOffsetIndex.spans(doc, includeTitle = true)
        val plainText = doc.plainText(includeTitle = true)

        assertEquals("索引总长与纯文本一致", plainText.length, TextReaderOffsetIndex.totalChars(spans))
        // 按索引切片重组，必须与 plainText 完全一致。
        val rebuilt = buildString {
            spans.forEachIndexed { index, span ->
                val source = if (span.isTitle) doc.title else doc.blocks[span.blockIndex].text
                append(source)
                if (index != spans.lastIndex) append('\n')
            }
            append('\n')
        }
        assertEquals("同源：索引切片重组 == plainText", plainText, rebuilt)
    }

    @Test
    fun `索引偏移与 html 写入的偏移标注一致`() {
        val doc = content(blocks = listOf(textBlock("第一段"), textBlock("第二段")))
        val spans = TextReaderOffsetIndex.spans(doc, includeTitle = true)
        val html = doc.html(includeTitle = true) { "" }
        assertEquals(
            "气泡挂点偏移必须与索引一致",
            spans.map { it.startOffset },
            htmlOffsets(html)
        )
    }

    @Test
    fun `图片块与空文本块不推进光标`() {
        val image = TextReaderImage(
            id = "i1",
            source = "x.png",
            renderSource = "x.png",
            click = null,
            inline = false,
            width = null,
            height = null,
            alignment = null
        )
        val doc = content(
            blocks = listOf(
                textBlock("前文"),
                TextReaderDocument.Block(image = image),
                textBlock(""),
                textBlock("后文")
            )
        )
        val spans = TextReaderOffsetIndex.spans(doc, includeTitle = false)
        assertEquals(
            "仅「非图片块且文本非空」的段落入索引，实际=${spans.map { it.blockIndex }}",
            2,
            spans.size
        )
        assertEquals(0, spans[0].startOffset)
        // 段间分隔换行占 1 字符，故后段起始 = 前段长度 + 1（与 plainText 的 "\n" 连接一致）。
        assertEquals("前文".length + 1, spans[1].startOffset)
        assertEquals(doc.plainText(includeTitle = false).length, TextReaderOffsetIndex.totalChars(spans))
    }

    @Test
    fun `不含标题时索引与纯文本仍一致`() {
        val doc = content(blocks = listOf(textBlock("甲"), textBlock("乙")))
        val spans = TextReaderOffsetIndex.spans(doc, includeTitle = false)
        assertTrue(spans.none { it.isTitle })
        assertEquals(doc.plainText(includeTitle = false).length, TextReaderOffsetIndex.totalChars(spans))
        assertEquals(0, spans.first().startOffset)
    }

    @Test
    fun `字符位置与段落双向映射可往返`() {
        val doc = content(title = "标题", blocks = listOf(textBlock("第一段"), textBlock("第二段")))
        val spans = TextReaderOffsetIndex.spans(doc, includeTitle = true)
        val second = spans.first { it.blockIndex == 1 }
        val inside = second.startOffset + 1
        assertEquals(second, TextReaderOffsetIndex.blockAt(spans, inside))
        assertEquals(second.startOffset, TextReaderOffsetIndex.offsetOf(spans, blockIndex = 1))
        assertEquals(
            TextReaderOffsetIndex.TitleBlockIndex,
            TextReaderOffsetIndex.blockAt(spans, 0)?.blockIndex
        )
    }

    @Test
    fun `段末偏移归入该段且越界返回空`() {
        val doc = content(blocks = listOf(textBlock("唯一段落")))
        val spans = TextReaderOffsetIndex.spans(doc, includeTitle = false)
        val only = spans.single()
        assertEquals(only, TextReaderOffsetIndex.blockAt(spans, only.endOffset))
        assertNull(TextReaderOffsetIndex.blockAt(spans, -1))
        assertNull(TextReaderOffsetIndex.blockAt(emptyList(), 0))
    }

    @Test
    fun `html 可见文本与纯文本同源`() {
        val doc = content(blocks = listOf(textBlock("段落一"), textBlock("段落二")))
        val html = doc.html(includeTitle = true) { "" }
        assertEquals(
            "同源：去掉标签与空白后必须等于纯文本去掉空白",
            doc.plainText(includeTitle = true).replace(Regex("\\s+"), ""),
            htmlText(html)
        )
    }
}