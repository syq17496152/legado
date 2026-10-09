package io.legado.app.model.localBook

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.md.MdChapterizer
import io.legado.app.help.md.MdDocumentBuilder
import io.legado.app.utils.EncodingDetect
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.StringUtils
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Markdown 本地书解析（epub-md-rich-rendering 阶段 3.6）。
 *
 * 结构照抄 [UmdFile]（companion 单例 + 懒加载 + `clear`），差异化在内容管线：
 * - **切章**：[MdChapterizer]（ATX/Setext + 软切），章偏移存 `BookChapter.start/end`（字符区间），
 *   `url` 用 md5 生成稳定标识（与 [TextFile] 同范式）；
 * - **内容**：`getContent` 返回**归一化章节 HTML**，并用既有 `<usehtml>` 约定包裹 ⇒
 *   直接落入「文本渲染模式」（canvas HTML 快路径可渲染；WebView 富渲染后端由阶段 3.1 接线）；
 * - **不打包 EPUB3**（AD-04 v1.1）⇒ 不经 `EpubCoreProvider`。
 *
 * 线程/并发：与其它 LocalBook 解析器一致，由 companion 的 `@Synchronized` 串行化。
 */
class MdFile(private var book: Book) {

    companion object : BaseLocalBookParse {
        private var mFile: MdFile? = null

        @Synchronized
        private fun getMFile(book: Book): MdFile {
            if (mFile == null || mFile?.book?.bookUrl != book.bookUrl) {
                mFile = MdFile(book)
                return mFile!!
            }
            mFile?.book = book
            return mFile!!
        }

        @Synchronized
        override fun getChapterList(book: Book): ArrayList<BookChapter> {
            return getMFile(book).getChapterList()
        }

        @Synchronized
        override fun getContent(book: Book, chapter: BookChapter): String? {
            return getMFile(book).getContent(chapter)
        }

        @Synchronized
        override fun getImage(book: Book, href: String): InputStream? {
            // md 无内嵌资源（图片按 md 相对路径由 WebView 加载，阶段 3.11 再议）
            return null
        }

        @Synchronized
        override fun upBookInfo(book: Book) {
            getMFile(book).upBookInfo()
        }

        @Synchronized
        fun clear() {
            mFile = null
        }

        @Synchronized
        fun clearBook(book: Book) {
            if (mFile?.book?.bookUrl == book.bookUrl) {
                mFile = null
            }
        }

        /** 全文读取上限（单文件 32Mi；单章另有 2Mi 上限，超限由构建器显式报错）。 */
        private const val MaxSourceBytes = 32 * 1024 * 1024
        private const val USE_HTML_OPEN = "<usehtml>"
        private const val USE_HTML_CLOSE = "</usehtml>"
    }

    /** 全文（懒加载；单次读取，避免逐章重复 IO）。 */
    private var source: String? = null

    /** 切章结果（懒加载）。 */
    private var sections: List<MdChapterizer.Section>? = null

    /** 构建产物缓存：章区间键 → 归一化 HTML（同一会话内避免重复转换）。 */
    private val builtCache = HashMap<String, MdDocumentBuilder.Built>()

    private fun text(): String {
        source?.let { return it }
        val loaded = readSource()
        source = loaded
        return loaded
    }

    private fun chapterSections(): List<MdChapterizer.Section> {
        sections?.let { return it }
        val result = MdChapterizer.chapterize(text())
        sections = result
        return result
    }

    // === BaseLocalBookParse ===

    private fun getChapterList(): ArrayList<BookChapter> {
        val doc = text()
        val list = ArrayList<BookChapter>()
        chapterSections().forEachIndexed { index, section ->
            val chapter = BookChapter(
                index = index,
                bookUrl = book.bookUrl,
                title = section.title,
                url = chapterUrl(index, section),
                start = section.startOffset.toLong(),
                end = section.endOffset.toLong()
            )
            chapter.wordCount = StringUtils.wordCountFormat(section.length)
            list.add(chapter)
        }
        if (list.isEmpty()) {
            list.add(
                BookChapter(
                    index = 0,
                    bookUrl = book.bookUrl,
                    title = MdChapterizer.chapterize("").first().title,
                    url = chapterUrl(0, null),
                    start = 0L,
                    end = doc.length.toLong()
                )
            )
        }
        return list
    }

    private fun getContent(chapter: BookChapter): String? {
        val doc = text()
        val start = chapter.start?.toInt()?.coerceIn(0, doc.length) ?: 0
        val end = chapter.end?.toInt()?.coerceIn(start, doc.length) ?: doc.length
        if (start == end && chapter.isVolume) return null
        val sectionMarkdown = doc.substring(start, end)
        val built = builtCache.getOrPut("$start-$end") {
            runCatching { MdDocumentBuilder.build(sectionMarkdown) }
                .onFailure {
                    AppLog.put("Markdown 章节渲染失败\n${it.localizedMessage}", it)
                }
                .getOrElse { MdDocumentBuilder.Built("", sectionMarkdown, false, false) }
        }
        if (built.html.isBlank()) return built.plainText.ifBlank { null }
        // 走既有 `<usehtml>` 约定 ⇒ 内容进入文本渲染模式（HTML 渲染路径）。
        return "$USE_HTML_OPEN${built.html}$USE_HTML_CLOSE"
    }

    private fun upBookInfo() {
        val doc = text()
        if (doc.isBlank()) {
            book.intro = "书籍导入异常"
            return
        }
        if (book.name.isEmpty()) {
            book.name = book.originName.substringBeforeLast('.').ifBlank { "未命名文档" }
        }
        if (book.intro.isNullOrBlank()) {
            // 简介取首章纯文本前 300 字（与 UmdFile 的"取内容做简介"同思路，截断更保守）。
            val head = chapterSections().firstOrNull()?.let { doc.substring(it.startOffset, it.endOffset) }.orEmpty()
            val plain = runCatching { MdDocumentBuilder.build(head).plainText }.getOrDefault("")
            book.intro = plain.replace(Regex("\\s+"), " ").trim().take(300)
        }
    }

    // === 内部 ===

    private fun chapterUrl(index: Int, section: MdChapterizer.Section?): String {
        val suffix = section?.let { "${it.startOffset}-${it.endOffset}" }.orEmpty()
        return MD5Utils.md5Encode16("${book.originName}|md|$index|$suffix")
    }

    private fun readSource(): String {
        val bytes = LocalBook.getBookInputStream(book).use { input ->
            input.readAllBytesBounded(MaxSourceBytes)
        }
        if (bytes.isEmpty()) return ""
        return decode(bytes)
    }

    private fun decode(bytes: ByteArray): String {
        // 先按 UTF-8 严格解码（md 绝大多数为 UTF-8）；失败再交给编码探测。
        val utf8 = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
            .getOrNull()
        if (utf8 != null) return utf8.removePrefix("\uFEFF")
        val charset: Charset = runCatching {
            Charset.forName(EncodingDetect.getEncode(bytes))
        }.getOrNull() ?: Charsets.UTF_8
        return bytes.toString(charset).removePrefix("\uFEFF")
    }

    private fun InputStream.readAllBytesBounded(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            total += read
            if (total >= limit) break
        }
        return out.toByteArray()
    }
}