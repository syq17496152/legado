package io.legado.app.model.localBook.epubcore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EpubRegex 单测（epub-md-rich-rendering 阶段 1.1 配对）
 *
 * 覆盖：正常编译命中、非法模式降级为"永不匹配"（不抛异常，避免毒化解析链）。
 */
class EpubRegexTest {

    @Test
    fun `正常模式可编译并可匹配`() {
        val regex = EpubRegex.compile("a\\s+b")
        assertTrue(regex.containsMatchIn("xa  by"))
        assertFalse(regex.containsMatchIn("ab"))
    }

    @Test
    fun `带选项编译生效`() {
        val regex = EpubRegex.compile("abc", RegexOption.IGNORE_CASE)
        assertTrue(regex.containsMatchIn("xxABCxx"))
    }

    @Test
    fun `非法模式降级为永不匹配而不抛异常`() {
        // 未闭合字符组：标准 Regex 构造会抛异常
        val regex = EpubRegex.compile("[unclosed")
        assertFalse(regex.containsMatchIn("[unclosed"))
        assertFalse(regex.containsMatchIn(""))
    }
}