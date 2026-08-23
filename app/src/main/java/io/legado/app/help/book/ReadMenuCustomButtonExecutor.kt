package io.legado.app.help.book

import androidx.appcompat.app.AppCompatActivity
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReadMenuCustomButton
import io.legado.app.ui.login.SourceLoginJsExtensions
import kotlinx.coroutines.withTimeout

object ReadMenuCustomButtonExecutor {

    suspend fun execute(
        activity: AppCompatActivity,
        button: ReadMenuCustomButton,
        book: Book,
        chapter: BookChapter,
        content: String,
        bookSource: BookSource?
    ): Any? {
        val source = ReadMenuCustomButtonSource(button)
        val java = SourceLoginJsExtensions(activity, source, BookType.text)
        val baseUrl = chapterRequestUrl(chapter)
        return withTimeout(button.validTimeout()) {
            source.evalJS(buildScript(button)) {
                put("java", java)
                put("book", book)
                put("chapter", chapter)
                put("content", content)
                put("src", content)
                put("result", content)
                put("title", chapter.title)
                put("baseUrl", baseUrl)
                put("bookSource", bookSource)
                put("readSource", bookSource)
                put("button", button)
            }
        }
    }

    private fun buildScript(button: ReadMenuCustomButton): String {
        return buildString {
            append(button.script).append('\n')
            append(
                """
                ;(function(){
                    if (typeof run === 'function') return run();
                    throw '自定义按键未定义 function run()';
                })();
                """.trimIndent()
            )
        }
    }

    private fun chapterRequestUrl(chapter: BookChapter): String {
        val absoluteUrl = runCatching { chapter.getAbsoluteURL() }.getOrNull().orEmpty()
        return when {
            absoluteUrl.startsWith("http://", true) || absoluteUrl.startsWith("https://", true) -> absoluteUrl
            chapter.url.startsWith("http://", true) || chapter.url.startsWith("https://", true) -> chapter.url
            chapter.baseUrl.startsWith("http://", true) || chapter.baseUrl.startsWith("https://", true) -> chapter.baseUrl
            else -> ""
        }
    }

}
