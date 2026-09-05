package io.legado.app.ui.book

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.constant.SourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.book.info.BookInfoNavigator

object SearchBookOpenHelper {

    fun open(context: Context, book: SearchBook, isVideo: Boolean) {
        // dual-layout W4（用户反馈③）：视频书与其他类型书源统一走详情页
        // （此前 isVideo 直进播放器且经 initSource save() 自动入架；
        //   现由用户在详情页点「加入书架/开始播放」主动操作，播放即入架与文本书一致）
        AppLog.put("SBOPEN_ROUTE: isVideo=$isVideo, name=${book.name.take(6)}")
        context.startActivity(BookInfoNavigator.intent(context, book).apply {
            putExtra("videoTitle", book.name)
        })
    }

    fun isVideoResult(book: SearchBook, sourceTypeHint: Int? = null): Boolean {
        return book.type and BookType.video > 0 ||
                sourceTypeHint == BookSourceType.video ||
                appDb.bookSourceDao.getBookSource(book.origin)?.bookSourceType == BookSourceType.video
    }
}