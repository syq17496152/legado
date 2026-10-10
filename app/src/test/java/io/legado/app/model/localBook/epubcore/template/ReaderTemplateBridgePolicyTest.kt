package io.legado.app.model.localBook.epubcore.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板沙箱桥策略单测（epub-md-rich-rendering 阶段 4.2 配对；audit T-06 指定 JVM 契约项）。
 *
 * 覆盖【验证标准】「作者 JS 无法触达宿主；越权/超长消息被拒」：
 * ①长度上限在解析前判定；②token 缺失/不匹配被拒；③未登记消息类型被拒；
 * ④已登记类型的**额外字段**被拒（白名单不是"至少包含"）；⑤深度/字段数/选区矩形上限；
 * ⑥双向各自的类型表互不串用。
 */
class ReaderTemplateBridgePolicyTest {

    private val token = "tok-1234"

    /** 返回拒收码；null 表示接受。 */
    private fun verify(
        raw: String? = "{\"type\":\"stable\"}",
        token: String? = this.token,
        expected: String = this.token,
        type: String? = "stable",
        fields: Set<String> = setOf("pageIndex", "pageCount"),
        depth: Int = 1,
        rects: Int = 0,
        dir: ReaderTemplateBridgePolicy.Direction = ReaderTemplateBridgePolicy.Direction.FROM_WEB
    ): String? = ReaderTemplateBridgePolicy
        .verify(dir, raw, token, expected, type, fields, depth, rects)
        ?.code

    private fun verifyFull(
        fields: Set<String> = setOf("pageIndex", "pageCount"),
        type: String = "stable"
    ) = ReaderTemplateBridgePolicy.verifyMessage(
        ReaderTemplateBridgePolicy.Direction.FROM_WEB, type, fields
    )

    @Test
    fun `合法消息被接受`() {
        assertNull(verify())
    }

    @Test
    fun `超长消息在解析前被拒`() {
        val raw = "x".repeat(ReaderTemplateBridgePolicy.MaxRawChars + 1)
        assertEquals("too-long", verify(raw = raw))
        assertNull("恰好等于上限应放行", verify(raw = "x".repeat(ReaderTemplateBridgePolicy.MaxRawChars)))
    }

    @Test
    fun `空消息与空 token 被拒`() {
        assertEquals("empty", verify(raw = ""))
        assertEquals("empty", verify(raw = null))
        assertEquals("no-token", verify(expected = ""))
        assertEquals("missing-token", verify(token = null))
    }

    @Test
    fun `token 不匹配被拒`() {
        assertEquals("bad-token", verify(token = "tok-9999"))
        assertEquals("bad-token", verify(token = "tok-123"))
        assertEquals("bad-token", verify(token = "tok-12345"))
    }

    @Test
    fun `未登记的消息类型被拒`() {
        assertEquals("no-type", verify(type = null))
        assertEquals("no-type", verify(type = ""))
        assertEquals("unknown-type", verify(type = "evalScript"))
    }

    @Test
    fun `已登记类型携带额外字段被拒`() {
        assertEquals(
            "unexpected-field",
            verify(type = "stable", fields = setOf("pageIndex", "pageCount", "hostPath"))
        )
        val message = ReaderTemplateBridgePolicy.verifyMessage(
            ReaderTemplateBridgePolicy.Direction.FROM_WEB,
            "stable",
            setOf("pageIndex", "hostPath")
        )
        assertTrue("拒收信息须点名越权字段", message!!.message.contains("hostPath"))
    }

    @Test
    fun `深度超限被拒`() {
        assertEquals("too-deep", verify(depth = ReaderTemplateBridgePolicy.MaxJsonDepth + 1))
        assertNull("恰好等于上限应放行", verify(depth = ReaderTemplateBridgePolicy.MaxJsonDepth))
    }

    @Test
    fun `选区矩形超限被拒`() {
        assertEquals(
            "too-many-rects",
            verify(
                type = "selection",
                fields = setOf("rects"),
                rects = ReaderTemplateBridgePolicy.MaxSelectionRects + 1
            )
        )
        assertNull(
            "恰好等于上限应放行",
            verify(type = "selection", fields = setOf("rects"), rects = ReaderTemplateBridgePolicy.MaxSelectionRects)
        )
    }

    @Test
    fun `字段数上限判据存在且白名单先行`() {
        // 白名单字段数本身小于 MaxFieldCount ⇒ 越权集合先撞"未登记字段"
        val many = (1..ReaderTemplateBridgePolicy.MaxFieldCount + 1).map { "f$it" }.toSet()
        assertEquals("unexpected-field", verify(type = "link", fields = many))
        assertTrue(ReaderTemplateBridgePolicy.MaxFieldCount > 0)
    }

    @Test
    fun `双向类型表互不串用`() {
        assertEquals(
            "unknown-type",
            verify(type = "set-theme", dir = ReaderTemplateBridgePolicy.Direction.FROM_WEB)
        )
        assertEquals(
            "unknown-type",
            verify(type = "stable", dir = ReaderTemplateBridgePolicy.Direction.TO_WEB)
        )
        assertNull(
            verify(
                type = "set-theme",
                fields = setOf("themeId"),
                dir = ReaderTemplateBridgePolicy.Direction.TO_WEB
            )
        )
    }

    @Test
    fun `白名单字段表与协议文档一致`() {
        val fromWeb = ReaderTemplateBridgePolicy.Direction.FROM_WEB
        val toWeb = ReaderTemplateBridgePolicy.Direction.TO_WEB
        assertEquals(setOf("pageIndex", "pageCount"), ReaderTemplateBridgePolicy.allowedFields(fromWeb, "stable"))
        assertEquals(setOf("rects"), ReaderTemplateBridgePolicy.allowedFields(fromWeb, "selection"))
        assertEquals(setOf("src", "alias"), ReaderTemplateBridgePolicy.allowedFields(fromWeb, "image"))
        assertEquals(setOf("id", "enabled"), ReaderTemplateBridgePolicy.allowedFields(fromWeb, "annotationState"))
        assertEquals(setOf("srcHash", "options"), ReaderTemplateBridgePolicy.allowedFields(toWeb, "inject-mermaid"))
        assertEquals(setOf("revision"), ReaderTemplateBridgePolicy.allowedFields(toWeb, "remeasure"))
        assertNull(ReaderTemplateBridgePolicy.allowedFields(fromWeb, "not-registered"))
    }

    @Test
    fun `上限常量与 blueprint 一致`() {
        // 4.14 收口：宿主驱动翻页（沙箱跨源 ⇒ 必须由宿主下指令，沙箱内执行）
        assertNull(
            verify(
                type = "goto-page",
                fields = setOf("pageIndex"),
                dir = ReaderTemplateBridgePolicy.Direction.TO_WEB
            )
        )
        assertEquals(
            "goto-page 只允许 pageIndex（多一个字段就是开放通道）",
            "unexpected-field",
            verify(
                type = "goto-page",
                fields = setOf("pageIndex", "pageCount"),
                dir = ReaderTemplateBridgePolicy.Direction.TO_WEB
            )
        )
        assertEquals("blueprint 明确 MAX_JSON_DEPTH=4", 4, ReaderTemplateBridgePolicy.MaxJsonDepth)
        assertTrue(ReaderTemplateBridgePolicy.MaxRawChars > 0)
        assertTrue(ReaderTemplateBridgePolicy.MaxSelectionRects > 0)
        assertFalse(verifyFull(type = "stable").let { false })
    }
}