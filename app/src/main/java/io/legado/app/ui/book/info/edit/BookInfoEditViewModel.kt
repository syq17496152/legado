package io.legado.app.ui.book.info.edit

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.model.ReadBook
import io.legado.app.R

class BookInfoEditViewModel(application: Application) : BaseViewModel(application) {
    var book: Book? = null
    val bookData = MutableLiveData<Book>()

    fun loadBook(bookUrl: String) {
        execute {
            book = appDb.bookDao.getBook(bookUrl)
            book?.let {
                bookData.postValue(it)
            }
        }
    }

    fun saveBook(book: Book, success: (() -> Unit)?) {
        execute {
            if (ReadBook.book?.bookUrl == book.bookUrl) {
                ReadBook.book = book
            }
            appDb.bookDao.update(book)
        }.onSuccess {
            success?.invoke()
        }.onError {
            if (it is SQLiteConstraintException) {
                AppLog.put(getApplication<Application>().getString(R.string.book_info_save_fail_duplicate, it), it, true)
            } else {
                AppLog.put(getApplication<Application>().getString(R.string.book_info_save_fail, it), it, true)
            }
        }
    }
}