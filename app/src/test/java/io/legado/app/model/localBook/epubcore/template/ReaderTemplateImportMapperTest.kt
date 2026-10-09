package io.legado.app.model.localBook.epubcore.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * archive 模板导入兼容层单测（epub-md-rich-rendering 阶段 4.17 配对）。
 *
 * 逐条覆盖 `archive-theme-compat-test.md` §四 映射表与 §五 边界用例：
 * ①v1→v2 升级并记入报告；②`builtin.*` 改名 + originId 入报告；③`type` 缺省默认 paged；
 * ④`scrollHtml=""` 忽略不判错；⑤未知字段忽略并降级；⑥内联 `data:` 照收 + 尺寸/数量校验；
 * ⑦巨大 css 性能提示；⑧archive 桥字段提示；⑨边界：损坏 JSON / 缺必填 / 版本过高。
 */
class ReaderTemplateImportMapperTest {

    /** 复刻 archive 导出样本的字段集（`archive-theme-compat-test.md` §一）。 */
    private fun archiveV1Json(
        id: String = "builtin.asuka",
        type: String = "paged",
        scrollHtml: String = "",
        css: String = "body{color:#c33}",
        javascript: String = "readerBridge.send('stable',{pageIndex:0})",
        extra: String = "",
        firstPageHtml: String = "<article data-reader-flow=\"body\"></article>",
        otherPageHtml: String = "<article data-reader-flow=\"body\"></article>"
    ): String = """
        {
          "schemaVersion": 1,
          "type": "$type",
          "id": "$id",
          "name": "测试模板",
          "description": "来自 archive 的导出",
          "firstPageHtml": ${quote(firstPageHtml)},
          "otherPageHtml": ${quote(otherPageHtml)},
          "scrollHtml": ${quote(scrollHtml)},
          "css": ${quote(css)},
          "javascript": ${quote(javascript)}$extra
        }
    """.trimIndent()

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun codes(result: ReaderTemplateImportMapper.ImportResult): List<String> =
        result.report.map { it.code }

    @Test
    fun `archive v1 模板可导入且升级到 v2`() {
        val result = ReaderTemplateImportMapper.importTemplateJson(archiveV1Json())
        assertTrue("应导入成功", result.succeeded)
        assertEquals(2, result.template!!.schemaVersion)
        assertEquals(EpubReaderTemplate.TYPE_PAGED, result.template.type)
        assertTrue("须记录版本升级", "schema-upgraded" in codes(result))
        assertEquals("无错误项", 0, result.errors.size)
    }

    @Test
    fun `builtin 前缀 id 被改名且原名入报告`() {
        val result = ReaderTemplateImportMapper.importTemplateJson(archiveV1Json(id = "builtin.asuka"))
        val template = result.template!!
        assertTrue("须改名为 user.*", template.id.startsWith("user."))
        assertFalse(template.id.startsWith("builtin."))
        val entry = result.report.first { it.code == "id-renamed" }
        assertTrue("报告须保留 originId 供展示", entry.message.contains("builtin.asuka"))
    }

    @Test
    fun `非内置 id 保持原样`() {
        val result = ReaderTemplateImportMapper.importTemplateJson(archiveV1Json(id = "user.custom"))
        assertEquals("user.custom", result.template!!.id)
        assertFalse("id-renamed" in codes(result))
    }

    @Test
    fun `空 id 也改名避免落成空标识`() {
        val result = ReaderTemplateImportMapper.importTemplateJson(archiveV1Json(id = ""))
        assertTrue(result.template!!.id.startsWith("user."))
        assertTrue("id-renamed" in codes(result))
    }

    @Test
    fun `缺省 type 默认 paged 并记录`() {
        val json = """
            {
              "schemaVersion": 1,
              "id": "user.a",
              "name": "无类型",
              "firstPageHtml": "<div data-reader-flow=\"body\"></div>",
              "otherPageHtml": "<div data-reader-flow=\"body\"></div>"
            }
        """.trimIndent()
        val result = ReaderTemplateImportMapper.importTemplateJson(json)
        assertEquals(EpubReaderTemplate.TYPE_PAGED, result.template!!.type)
        assertTrue("type-defaulted" in codes(result))
    }

    @Test
    fun `paged 模板的空 scrollHtml 被忽略不判错`() {
        val result = ReaderTemplateImportMapper.importTemplateJson(archiveV1Json(scrollHtml = ""))
        assertTrue(result.succeeded)
        assertTrue("scroll-html-ignored" in codes(result))
        assertEquals(0, result.errors.size)
    }

    @Test
    fun `未知字段被忽略并降级且列名`() {
        val result = ReaderTemplateImportMapper.importTemplateJson(
            archiveV1Json(extra = ", \"archivePrivateKnob\": 1, \"ankiMode\": true")
        )
        assertTrue(result.succeeded)
        val entry = result.report.first { it.code == "unknown-fields-ignored" }
        assertTrue(entry.message.contains("ankiMode"))
        assertTrue(entry.message.contains("archivePrivateKnob"))
        assertEquals(ReaderTemplateImportMapper.Level.INFO, entry.level)
    }

    @Test
    fun `内联 data 素材照收并记录数量`() {
        val dataUri = "data:image/webp;base64," + "A".repeat(4000)
        val result = ReaderTemplateImportMapper.importTemplateJson(
            archiveV1Json(css = ".bg{background:url($dataUri)}")
        )
        assertTrue(result.succeeded)
        assertTrue("须记录内联素材数", "inline-data-count" in codes(result))
        assertFalse("未超限不应告警", "oversized-inline-data" in codes(result))
    }

    @Test
    fun `超标内联素材产生告警但不阻断导入`() {
        // 约 12MB 的有效载荷（base64 字符数 ≈ bytes * 4/3）
        val payload = "A".repeat((ReaderTemplateImportMapper.MaxInlineDataBytes * 4 / 3).toInt() + 100)
        val result = ReaderTemplateImportMapper.importTemplateJson(
            archiveV1Json(css = ".bg{background:url(data:image/png;base64,$payload)}")
        )
        assertTrue("超限仅告警，不阻断", result.succeeded)
        assertTrue("oversized-inline-data" in codes(result))
    }

    @Test
    fun `巨大 css 给出性能提示`() {
        val result = ReaderTemplateImportMapper.importTemplateJson(
            archiveV1Json(css = "/*" + "x".repeat(ReaderTemplateImportMapper.LargeCssWarnChars) + "*/")
        )
        val entry = result.report.first { it.code == "large-css" }
        assertEquals(ReaderTemplateImportMapper.Level.WARNING, entry.level)
        assertTrue("提示须给出体量", entry.message.contains("KB"))
    }

    @Test
    fun `archive 桥字段引用被提示（由桥白名单覆盖）`() {
        val result = ReaderTemplateImportMapper.importTemplateJson(archiveV1Json())
        val entry = result.report.first { it.code == "legacy-bridge-fields" }
        assertTrue(entry.message.contains("readerBridge"))
    }

    @Test
    fun `滚动模板缺 scrollHtml 判错`() {
        val result = ReaderTemplateImportMapper.importTemplateJson(
            archiveV1Json(type = "scroll", scrollHtml = "")
        )
        assertFalse(result.succeeded)
        assertTrue(codes(result).contains("missing-required-html"))
    }

    @Test
    fun `paged 模板缺必填 HTML 判错`() {
        val result = ReaderTemplateImportMapper.importTemplateJson(
            archiveV1Json(firstPageHtml = "")
        )
        assertFalse(result.succeeded)
        assertTrue(codes(result).contains("missing-required-html"))
    }

    @Test
    fun `损坏 JSON 优雅报错不抛异常`() {
        val result = ReaderTemplateImportMapper.importTemplateJson("{ not json")
        assertFalse(result.succeeded)
        assertNull(result.template)
        assertEquals("invalid-json", result.errors.single().code)
    }

    @Test
    fun `非对象 JSON 被拒`() {
        val result = ReaderTemplateImportMapper.importTemplateJson("[1,2,3]")
        assertFalse(result.succeeded)
        assertEquals("not-object", result.errors.single().code)
    }

    @Test
    fun `缺 schemaVersion 被拒`() {
        val result = ReaderTemplateImportMapper.importTemplateJson("{\"id\":\"user.a\",\"name\":\"n\"}")
        assertFalse(result.succeeded)
        assertEquals("missing-schema-version", result.errors.single().code)
    }

    @Test
    fun `高于支持的版本给出升级提示而非静默失败`() {
        val json = archiveV1Json().replace("\"schemaVersion\": 1", "\"schemaVersion\": 9")
        val result = ReaderTemplateImportMapper.importTemplateJson(json)
        assertFalse(result.succeeded)
        val error = result.errors.single()
        assertEquals("unsupported-schema-version", error.code)
        assertTrue("提示须含版本号", error.message.contains("9"))
    }

    @Test
    fun `v2 模板原样导入不产生升级记录`() {
        val json = archiveV1Json().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        val result = ReaderTemplateImportMapper.importTemplateJson(json)
        assertTrue(result.succeeded)
        assertFalse("不应记录版本升级", "schema-upgraded" in codes(result))
    }

    @Test
    fun `库内旧版本模板读入时按需升级`() {
        val legacy = EpubReaderTemplate(
            schemaVersion = 1,
            id = "user.old",
            name = "旧模板",
            firstPageHtml = "<div></div>",
            otherPageHtml = "<div></div>"
        )
        assertEquals(2, ReaderTemplateImportMapper.upgradeIfNeeded(legacy).schemaVersion)
        val current = legacy.copy(schemaVersion = 2)
        assertEquals("已是最新则不变", 2, ReaderTemplateImportMapper.upgradeIfNeeded(current).schemaVersion)
    }

    @Test
    fun `报告摘要可直接展示`() {
        val result = ReaderTemplateImportMapper.importTemplateJson(archiveV1Json())
        val summary = result.summary()
        assertTrue(summary.contains("[INFO]"))
        assertNotNull(result.template)
    }
}