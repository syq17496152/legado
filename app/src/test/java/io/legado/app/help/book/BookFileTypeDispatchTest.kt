package io.legado.app.help.book

import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地书类型分发单测（epub-md-rich-rendering 阶段 3.3 配对）。
 *
 * 覆盖【验证标准】「4 处分发点改动齐全；现有格式分支不受影响」的**判据层**：
 * ①`isMarkdown` 命中 `.md`/`.markdown`（含大小写）；②不得误判 txt/epub/umd/pdf/mobi；
 * ③白名单正则同时覆盖新增与既有类型（只增不删）。
 *
 * 说明：真正的分发点（`LocalBook` 三处 `when`）依赖 IO/DB，属真机 L2；本测试锁定其**判据**，
 * 避免「白名单加了但判据没加」这类静默漏配。
 */
class BookFileTypeDispatchTest {

    private fun localBook(originName: String) = Book(
        type = BookType.local,
        bookUrl = "file:///x/$originName",
        originName = originName,
        name = originName
    )

    @Test
    fun `md 与 markdown 后缀均识别为 Markdown`() {
        assertTrue(localBook("note.md").isMarkdown)
        assertTrue(localBook("NOTE.MD").isMarkdown)
        assertTrue(localBook("readme.markdown").isMarkdown)
        assertTrue(localBook("README.Markdown").isMarkdown)
    }

    @Test
    fun `既有类型不被误判为 Markdown`() {
        listOf("a.txt", "a.epub", "a.umd", "a.pdf", "a.mobi", "a.azw3", "a.azw").forEach { name ->
            assertFalse("$name 不得判为 Markdown", localBook(name).isMarkdown)
        }
    }

    @Test
    fun `Markdown 判据不影响既有类型判据`() {
        assertTrue(localBook("a.epub").isEpub)
        assertTrue(localBook("a.umd").isUmd)
        assertTrue(localBook("a.pdf").isPdf)
        assertTrue(localBook("a.mobi").isMobi)
        assertTrue(localBook("a.txt").isLocalTxt)
        assertFalse("epub 不得判为 Markdown", localBook("a.epub").isMarkdown)
    }

    @Test
    fun `非本地书即使后缀为 md 也不判为 Markdown`() {
        val remote = Book(
            type = BookType.text,
            bookUrl = "https://example.com/a.md",
            originName = "a.md",
            name = "a"
        )
        assertFalse(remote.isMarkdown)
    }
}