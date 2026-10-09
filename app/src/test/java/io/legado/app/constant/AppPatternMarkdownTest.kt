package io.legado.app.constant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地书白名单正则单测（epub-md-rich-rendering 阶段 3.3 配对）。
 *
 * 覆盖【验证标准】「现有格式分支不受影响」的白名单面：**只增不删**——
 * 既有 7 种后缀必须继续命中，新增 md/markdown 命中，压缩包不得混入。
 */
class AppPatternMarkdownTest {

    private val legacyTypes = listOf("a.txt", "a.epub", "a.umd", "a.pdf", "a.mobi", "a.azw3", "a.azw")

    @Test
    fun `既有本地书类型仍全部命中`() {
        legacyTypes.forEach { name ->
            assertTrue("$name 的白名单匹配不得回退", AppPattern.bookFileRegex.matches(name))
        }
    }

    @Test
    fun `Markdown 两种后缀命中且大小写不敏感`() {
        listOf("a.md", "a.MD", "a.Md", "a.markdown", "a.MARKDOWN", "a.Markdown").forEach { name ->
            assertTrue("$name 应在白名单内", AppPattern.bookFileRegex.matches(name))
        }
    }

    @Test
    fun `完整路径形式亦命中`() {
        assertTrue(AppPattern.bookFileRegex.matches("/sdcard/Download/说明.md"))
        assertTrue(AppPattern.bookFileRegex.matches("content://x/y/readme.markdown"))
    }

    @Test
    fun `压缩包与未知后缀不混入本地书白名单`() {
        listOf("a.zip", "a.rar", "a.7z", "a.json", "a.md.bak").forEach { name ->
            assertFalse("$name 不应命中原生书白名单", AppPattern.bookFileRegex.matches(name))
        }
    }

    @Test
    fun `Markdown 与既有类型的命中互斥`() {
        assertEquals(
            "同一后缀不得同时被多类判据命中",
            0,
            legacyTypes.count { AppPattern.bookFileRegex.matches(it) && it.endsWith(".md") }
        )
    }
}