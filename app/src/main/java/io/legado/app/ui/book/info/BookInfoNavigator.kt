package io.legado.app.ui.book.info

import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContract
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.BookInfoComponentConfig
import io.legado.app.help.config.BookInfoPageStyle

object BookInfoNavigator {

    fun open(context: Context, book: Book) {
        context.startActivity(intent(context, book))
    }

    fun open(
        context: Context,
        name: String,
        author: String,
        bookUrl: String? = null,
        origin: String? = null,
        originName: String? = null,
        coverUrl: String? = null
    ) {
        context.startActivity(
            baseIntent(context).apply {
                putBookExtras(name, author, bookUrl, origin, originName, coverUrl)
            }
        )
    }

    fun intent(context: Context, book: Book): Intent {
        return baseIntent(context).apply {
            putBookExtras(
                name = book.name,
                author = book.author,
                bookUrl = book.bookUrl,
                origin = book.origin,
                originName = book.originName,
                coverUrl = book.coverUrl
            )
        }
    }

    fun intent(context: Context, book: SearchBook): Intent {
        return baseIntent(context).apply {
            putBookExtras(
                name = book.name,
                author = book.author,
                bookUrl = book.bookUrl,
                origin = book.origin,
                originName = book.originName,
                coverUrl = book.coverUrl
            )
        }
    }

    fun targetClass(): Class<*> {
        return if (BookInfoComponentConfig.loadStyle() == BookInfoPageStyle.IMMERSIVE_COMPOSE) {
            BookInfoComposeActivity::class.java
        } else {
            BookInfoActivity::class.java
        }
    }

    private fun baseIntent(context: Context): Intent {
        return Intent(context, targetClass())
    }

    private fun Intent.putBookExtras(
        name: String,
        author: String,
        bookUrl: String?,
        origin: String?,
        originName: String?,
        coverUrl: String?
    ) {
        putExtra("name", name)
        putExtra("author", author)
        putExtra("bookUrl", bookUrl.orEmpty())
        putExtra("origin", origin.orEmpty())
        putExtra("originName", originName.orEmpty())
        putExtra(SearchBookOpenCoverExtra, coverUrl.orEmpty())
    }

    private const val SearchBookOpenCoverExtra = "coverUrl"
}

class BookInfoStartActivityContract :
    ActivityResultContract<(Intent.() -> Unit)?, ActivityResult>() {

    override fun createIntent(context: Context, input: (Intent.() -> Unit)?): Intent {
        return Intent(context, BookInfoNavigator.targetClass()).apply {
            input?.invoke(this)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): ActivityResult {
        return ActivityResult(resultCode, intent)
    }
}