package io.legado.app.service.kernel

import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.SceneBookmark
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

/**
 * ③ 书签高亮域业务内核（web-mcp-productization 二期 · tasks 2.10 / 2.28）。
 *
 * 契约同 [BookKernel]：只返回领域对象 / 全链挂起 / 零 `runBlocking` / 失败抛异常。
 *
 * 导出串格式与 `AllBookmarkViewModel.exportBookmark(Md)` **逐字节同构**（仅把写文件换成拼串），
 * 保证"MCP 导出"与"App 导出文件"内容一致。
 */
object BookmarkKernel {

    // ============================================================ 书签

    /** 书签列表：给定书名+作者查该书，否则返回全部。 */
    suspend fun bookmarks(bookName: String?, bookAuthor: String?): List<Bookmark> = withContext(IO) {
        if (bookName.isNullOrBlank()) {
            appDb.bookmarkDao.all
        } else {
            appDb.bookmarkDao.getByBook(bookName, bookAuthor.orEmpty())
        }
    }

    /** 新增 / 保存书签（`time` 为主键：已存在即更新）。 */
    suspend fun saveBookmark(bookmark: Bookmark): Bookmark = withContext(IO) {
        if (appDb.bookmarkDao.getByBook(bookmark.bookName, bookmark.bookAuthor)
                .any { it.time == bookmark.time }
        ) {
            appDb.bookmarkDao.update(bookmark)
        } else {
            appDb.bookmarkDao.insert(bookmark)
        }
        bookmark
    }

    /** 删除书签（按主键 `time`）。 */
    suspend fun deleteBookmark(times: List<Long>): Int = withContext(IO) {
        val targets = appDb.bookmarkDao.all.filter { it.time in times }
        if (targets.isNotEmpty()) appDb.bookmarkDao.delete(*targets.toTypedArray())
        targets.size
    }

    // ============================================================ 名场面书签

    /**
     * 名场面书签列表。
     *
     * 与普通书签的差别：名场面以 `bookUrl` 归属（跨书源同一本书一致），故这里按 `bookUrl` 过滤。
     */
    suspend fun sceneBookmarks(bookUrl: String?): List<SceneBookmark> = withContext(IO) {
        if (bookUrl.isNullOrBlank()) appDb.sceneBookmarkDao.all else appDb.sceneBookmarkDao.getByBook(bookUrl)
    }

    /** 新增 / 保存名场面书签（`id = 0` 视为新增，由自增主键分配）。 */
    suspend fun saveSceneBookmark(bookmark: SceneBookmark): SceneBookmark = withContext(IO) {
        if (bookmark.id > 0) {
            appDb.sceneBookmarkDao.update(bookmark)
        } else {
            bookmark.id = appDb.sceneBookmarkDao.insert(bookmark)
        }
        bookmark
    }

    /** 删除名场面书签（按 id）。 */
    suspend fun deleteSceneBookmark(ids: List<Long>): Int = withContext(IO) {
        var count = 0
        ids.forEach { id ->
            val exist = appDb.sceneBookmarkDao.all.firstOrNull { it.id == id }
            if (exist != null) {
                appDb.sceneBookmarkDao.deleteById(id)
                count++
            }
        }
        count
    }

    // ============================================================ 导出

    /**
     * 导出书签串（`format = json|md`）。
     *
     * 与 App 导出**同源同格式**：
     * - JSON = `GSON` 序列化整表（同 `exportBookmark`）；
     * - MD = 逐条 `## 书名 作者 / #### 章节 / ###### 原文 / ###### 摘要`（同 `exportBookmarkMd`）。
     */
    suspend fun export(bookName: String?, bookAuthor: String?, format: String): Map<String, Any?> {
        val items = bookmarks(bookName, bookAuthor)
        val text = if (format.equals("md", ignoreCase = true)) {
            buildString {
                var name = ""
                var author = ""
                items.forEach {
                    if (it.bookName != name && it.bookAuthor != author) {
                        name = it.bookName
                        author = it.bookAuthor
                        append("## ${it.bookName} ${it.bookAuthor}\n\n")
                    }
                    append("#### ${it.chapterName}\n\n")
                    append("###### 原文\n ${it.bookText}\n\n")
                    append("###### 摘要\n ${it.content}\n\n")
                }
            }
        } else {
            GSON.toJson(items)
        }
        return mapOf(
            "format" to if (format.equals("md", ignoreCase = true)) "md" else "json",
            "count" to items.size,
            "content" to text,
        )
    }
}
