package io.legado.app.help

import io.legado.app.testkit.SourceFileProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R 批 §3.1.3（Q3）：富文本图片源参数解析与显示矩形求解
 *
 * 覆盖移植后的 `ImageSourceOptions`（HTML 实体 / 非字符串参数 / 大小写不敏感 / 转义引号回退）
 * 与原 `GlideImageGetter.getDrawableRect` 的几何语义（百分比、像素、非法宽度、超宽收敛、对齐、等比高）。
 */
class ImageSourceOptionsTest {

    // ---------- 解析 ----------

    @Test
    fun parseBlankReturnsNull() {
        assertNull(ImageSourceOptions.parse(null))
        assertNull(ImageSourceOptions.parse(""))
        assertNull(ImageSourceOptions.parse("   "))
    }

    @Test
    fun parseWithoutParamKeepsSourceUntouched() {
        val parsed = ImageSourceOptions.parse("https://a/b.png")
        assertNotNull(parsed)
        assertEquals("https://a/b.png", parsed!!.source)
        assertTrue(parsed.options.isEmpty())
        assertNull(parsed.width)
        assertNull(parsed.style)
    }

    @Test
    fun parseSplitsUrlAndOptions() {
        val parsed = ImageSourceOptions.parse("""https://a/b.png, {"width":"80%","style":"center"}""")
        assertEquals("https://a/b.png", parsed!!.source)
        assertEquals("80%", parsed.width)
        assertEquals("center", parsed.style)
    }

    @Test
    fun parseSupportsHtmlEscapedBraces() {
        // 正文经 HTML 转义后参数段形如 `,&#123;...&#125;`——原内联实现完全解不出
        val parsed = ImageSourceOptions.parse("https://a/b.png,&#123;\"width\":\"300\"&#125;")
        assertEquals("https://a/b.png", parsed!!.source)
        assertEquals("300", parsed.width)
    }

    @Test
    fun parseIsCaseInsensitiveForOptionKeys() {
        val parsed = ImageSourceOptions.parse("""https://a/b.png,{"Width":"300","Style":"right"}""")
        assertEquals("300", parsed!!.width)
        assertEquals("right", parsed.style)
    }

    @Test
    fun parseKeepsNonStringOptionValues() {
        val parsed = ImageSourceOptions.parse("""https://a/b.png,{"width":300,"style":{"a":1}}""")
        assertEquals("数字参数按字符串取用", "300", parsed!!.width)
        assertEquals("嵌套对象保留为 JSON 文本而非整体解析失败", """{"a":1}""", parsed.style)
    }

    @Test
    fun parseRetriesEscapedQuoteForm() {
        val parsed = ImageSourceOptions.parse("""https://a/b.png, {\"width\":\"300\"}""")
        assertEquals("https://a/b.png", parsed!!.source)
        assertEquals("300", parsed.width)
    }

    @Test
    fun parsePrefersLastParseableSeparator() {
        val parsed = ImageSourceOptions.parse("""https://a/b.png, {bogus}, {"width":"300"}""")
        assertEquals("https://a/b.png, {bogus}", parsed!!.source)
        assertEquals("300", parsed.width)
    }

    @Test
    fun parseBlankOptionValueIsTreatedAsUnset() {
        val parsed = ImageSourceOptions.parse("""https://a/b.png,{"width":"   ","style":"center"}""")
        assertNull(parsed!!.width)
        assertEquals("center", parsed.style)
    }

    @Test
    fun clickPrefersClickThenOnclick() {
        assertEquals(
            "java.toast('ok')",
            ImageSourceOptions.parse("""https://a/b.png,{"click":"java.toast('ok')"}""")!!.click
        )
        assertEquals(
            "onclick()",
            ImageSourceOptions.parse("""https://a/b.png,{"onclick":"onclick()"}""")!!.click
        )
        assertNull(ImageSourceOptions.parse("https://a/b.png")!!.click)
    }

    // ---------- 几何 ----------

    @Test
    fun noOptionsKeepsIntrinsicSize() {
        assertEquals(
            ImageBounds(0, 0, 400, 200),
            resolveImageBounds(parsed(), 400, 200, 600)
        )
        assertEquals(
            "未解析出参数时同样取原始尺寸",
            ImageBounds(0, 0, 400, 200),
            resolveImageBounds(null, 400, 200, 600)
        )
    }

    @Test
    fun percentWidthScalesWithAvailableWidth() {
        val bounds = resolveImageBounds(parsed(width = "50%"), 400, 200, 600)
        assertEquals(300, bounds.right - bounds.left)
        assertEquals("高度按原始宽高比等比换算", 150, bounds.bottom)
    }

    @Test
    fun absoluteWidthIsUsedAsIs() {
        val bounds = resolveImageBounds(parsed(width = "200"), 400, 200, 600)
        assertEquals(200, bounds.right - bounds.left)
        assertEquals(100, bounds.bottom)
    }

    @Test
    fun oversizedWidthIsClampedToAvailableWidth() {
        val bounds = resolveImageBounds(parsed(width = "900"), 900, 450, 600)
        assertEquals("超过可用宽度时收敛到可用宽度", 600, bounds.right - bounds.left)
        assertEquals(300, bounds.bottom)
    }

    @Test
    fun invalidPercentFallsBackToEightyPercent() {
        val bounds = resolveImageBounds(parsed(width = "abc%"), 400, 200, 600)
        assertEquals(480, bounds.right - bounds.left)
        assertEquals(240, bounds.bottom)
    }

    @Test
    fun invalidConvertibleWidthFallsBackToIntrinsicWidth() {
        val bounds = resolveImageBounds(parsed(width = "abc"), 400, 200, 600)
        assertEquals(400, bounds.right - bounds.left)
        assertEquals(200, bounds.bottom)
    }

    @Test
    fun zeroAvailableWidthFallsBackToIntrinsicWidth() {
        // 尚未完成布局时可用宽度为 0：不得据此算出负偏移（上游加固口径）
        val bounds = resolveImageBounds(parsed(width = "200", style = "center"), 400, 200, 0)
        assertEquals(100, bounds.left)
        assertEquals(300, bounds.right)
        assertEquals(100, bounds.bottom)
    }

    @Test
    fun styleCenterAndRightOffsetLeft() {
        val center = resolveImageBounds(parsed(width = "200", style = "center"), 400, 200, 600)
        assertEquals(200, center.left)
        assertEquals(400, center.right)
        val right = resolveImageBounds(parsed(width = "200", style = "right"), 400, 200, 600)
        assertEquals(400, right.left)
        assertEquals(600, right.right)
    }

    @Test
    fun unknownStyleAlignsLeft() {
        val bounds = resolveImageBounds(parsed(width = "200", style = "whatever"), 400, 200, 600)
        assertEquals(0, bounds.left)
        assertEquals(200, bounds.right)
    }

    @Test
    fun styledWidthFromParsedSourceReachesGeometry() {
        val parsed = ImageSourceOptions.parse("""https://a/b.png, {"width":"25%","style":"right"}""")
        val bounds = resolveImageBounds(parsed, 400, 200, 800)
        assertEquals(200, bounds.right - bounds.left)
        assertEquals(600, bounds.left)
        assertEquals(100, bounds.bottom)
    }

    private fun parsed(width: String? = null, style: String? = null): ParsedImageSource {
        val options = buildMap {
            width?.let { put("width", it) }
            style?.let { put("style", it) }
        }
        return ParsedImageSource("https://a/b.png", options)
    }

    // ---------- 3.3.3：data URI 解码收口到宽容解码单源 ----------

    @Test
    fun dataUriBase64UsesSharedTolerantDecoder() {
        val code = SourceFileProbe.sourceText("help/GlideImageGetter.kt")
        assertTrue(
            "base64 分支必须走 DataUrlUtils 单源宽容解码",
            code.contains("decodeTolerantBase64(payload)")
        )
        assertFalse(
            "不得残留本文件自带的严格 Base64 解码",
            code.contains("kotlin.io.encoding.Base64")
        )
    }
}