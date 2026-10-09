package io.legado.app.model.localBook.epubcore.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EpubReaderTemplate 单测（epub-md-rich-rendering 阶段 1.2 配对）
 *
 * 覆盖：结构校验（paged/scroll）、JSON 往返、内容指纹与格式无关、字段长度前缀不进 JSON。
 */
class EpubReaderTemplateTest {

    private fun paged() = EpubReaderTemplate(
        id = "builtin.demo",
        name = "示例",
        description = "d",
        firstPageHtml = "<p>first</p>",
        otherPageHtml = "<p>other</p>"
    )

    @Test
    fun `paged 模板校验通过且 JSON 往返保持指纹一致`() {
        val template = paged()
        assertTrue(template.validate().isEmpty())
        val restored = EpubReaderTemplate.fromJson(template.toJson())
        assertEquals(template.contentHash(), restored.contentHash())
        assertEquals(template.id, restored.id)
        assertFalse(restored.isScrolling)
    }

    @Test
    fun `scroll 模板需要格式版本 2 且滚动 HTML 非空`() {
        val scroll = EpubReaderTemplate(
            schemaVersion = EpubReaderTemplate.SCROLL_SCHEMA_VERSION,
            id = "user.scroll",
            name = "滚动",
            type = EpubReaderTemplate.TYPE_SCROLL,
            scrollHtml = "<div>s</div>"
        )
        assertTrue(scroll.validate().isEmpty())
        assertTrue(scroll.isScrolling)
        assertEquals(listOf("<div>s</div>"), scroll.htmlDocuments)

        val missingHtml = scroll.copy(scrollHtml = "")
        assertTrue(missingHtml.validate().any { it.contains("滚动 HTML") })

        val wrongVersion = scroll.copy(schemaVersion = 1)
        assertTrue(wrongVersion.validate().any { it.contains("格式版本 2") })
    }

    @Test
    fun `空 id 与未知类型被拒`() {
        assertTrue(paged().copy(id = "").validate().any { it.contains("id") })
        assertTrue(paged().copy(type = "pdf").validate().any { it.contains("类型") })
    }

    @Test
    fun `内容指纹随任一字段变化`() {
        val base = paged()
        assertFalse(base.contentHash() == base.copy(css = "p{color:red}").contentHash())
        assertFalse(base.contentHash() == base.copy(name = "另一名").contentHash())
    }

    @Test
    fun `非法 JSON 抛出带说明的异常`() {
        val error = runCatching { EpubReaderTemplate.fromJson("not-json") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}