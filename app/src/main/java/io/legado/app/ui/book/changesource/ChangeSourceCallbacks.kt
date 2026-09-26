package io.legado.app.ui.book.changesource

import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.SearchBook

/**
 * 换源列表的回调契约（CF 6.2 死件清理）。
 *
 * 背景：`ChangeBookSourceAdapter` / `ChangeChapterSourceAdapter` / `ChangeChapterTocAdapter`
 * 三个 View 层 Adapter 的宿主界面（`ChangeBookSourceDialog` / `ChangeChapterSourceDialog`）
 * 早已整体 Compose 化（`LazyColumn` + 行内 `@Composable`），Adapter 本身**从未被实例化**，
 * 仅残留其回调接口被对话框 `implement` ⇒ 三个 Adapter 及其 inflate 的
 * `item_change_source` / `item_chapter_list` 布局成为死件却无法退役。
 *
 * 故将回调用到的接口独立到本文件（成员签名与退役前逐字一致，仅改名以脱离 Adapter 命名空间），
 * 使死 Adapter 与死布局可被删除。
 */
interface ChangeBookSourceCallback {
    val oldBookUrl: String?

    fun changeTo(searchBook: SearchBook)

    fun topSource(searchBook: SearchBook)

    fun bottomSource(searchBook: SearchBook)

    fun editSource(searchBook: SearchBook)

    fun disableSource(searchBook: SearchBook)

    fun deleteSource(searchBook: SearchBook)

    fun setBookScore(searchBook: SearchBook, score: Int)

    fun getBookScore(searchBook: SearchBook): Int
}

/** 单章换源列表行回调（原 `ChangeChapterSourceAdapter.CallBack`）。 */
interface ChangeChapterSourceCallback {
    val oldBookUrl: String?

    fun openToc(searchBook: SearchBook)

    fun topSource(searchBook: SearchBook)

    fun bottomSource(searchBook: SearchBook)

    fun editSource(searchBook: SearchBook)

    fun disableSource(searchBook: SearchBook)

    fun deleteSource(searchBook: SearchBook)

    fun setBookScore(searchBook: SearchBook, score: Int)

    fun getBookScore(searchBook: SearchBook): Int
}

/** 单章换源目录（TOC）面板回调（原 `ChangeChapterTocAdapter.Callback`）。 */
interface ChangeChapterTocCallback {
    fun clickChapter(bookChapter: BookChapter, nextChapterUrl: String?)
}