package io.legado.app.ui.book

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.SourceType
import io.legado.app.help.source.isVideoSource
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
        // video-source-dual-track AD-02：静态源类型与运行时 book.type 的判定收口为统一 helper；
        // sourceTypeHint 为调用方显式提示，仍作为独立真值保留
        return sourceTypeHint == BookSourceType.video ||
                appDb.bookSourceDao.getBookSource(book.origin).isVideoSource(book.type)
    }
}