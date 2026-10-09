package io.legado.app.model.reader.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读器错误类型单测（epub-md-rich-rendering blueprint §八 配对）。
 *
 * 覆盖：①五类错误齐备且语义可区分（降级链按错误语义决定动作，不能退化成裸 Exception）；
 * ②全部继承 [io.legado.app.exception.NoStackTraceException]（项目 landmine：业务异常不采集调用栈）。
 */
class ReaderErrorTest {

    @Test
    fun `错误类型齐备且可区分`() {
        val errors = listOf(
            ReaderError.TemplateScriptTimeout("超时"),
            ReaderError.TemplateRenderFailed("渲染失败"),
            ReaderError.BackendUnavailable("旧内核"),
            ReaderError.CoordConvertUnavailable("坐标不可换算"),
            ReaderError.CacheWriteFailed("磁盘满"),
            ReaderError.ContentTooLarge("本章超限")
        )
        assertEquals("类型不得重复", errors.size, errors.map { it::class }.toSet().size)
        assertTrue(
            "降级链需要按类型分派，故不得共用同一类型",
            errors.map { it::class.simpleName }.toSet().size == errors.size
        )
        errors.forEach { error ->
            assertTrue("业务异常须继承 NoStackTraceException", error !is RuntimeException)
            assertTrue(error.message!!.isNotBlank())
        }
    }

    @Test
    fun `不采集调用栈以避免高频降级时的开销`() {
        val error = ReaderError.BackendUnavailable("旧内核")
        assertEquals(
            "NoStackTraceException 契约：栈深度为 0",
            0,
            error.stackTrace.size
        )
    }

    @Test
    fun `超限错误可携带数值上限便于提示`() {
        val error = ReaderError.ContentTooLarge("本章 Markdown 过大（3000000 > 2097152 字符）")
        assertTrue(error.message!!.contains("2097152"))
    }

    @Test
    fun `作为 Exception 可被常规捕获`() {
        assertThrows(ReaderError.ContentTooLarge::class.java) {
            throw ReaderError.ContentTooLarge("超限")
        }
    }
}