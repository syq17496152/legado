package io.legado.app.service.kernel

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.SourceCallBack
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * ① 书架与书籍域业务内核（web-mcp-productization 二期 · tasks 2.8 / 2.28）。
 *
 * 契约同 [BookKernel]：**只返回领域对象**、**全链挂起**、**零 `runBlocking`**、失败抛异常。
 *
 * 逻辑来源：分组 / 标签 / 排序 / 批量管理 / 换源均**照搬既有 UI 层同一份实现**
 * （`ui/book/manage/BookshelfManageViewModel` 与 `BookGroupDao` / `BookDao`），
 * 只把 `execute { }`（ViewModel 的协程包装）换成 `withContext(IO)` —— 业务规则零新增（AD-10）。
 */
object BookshelfKernel {

    // ============================================================ 分组

    /** 书架分组列表（含内置分组，`order` 升序由 DAO 保证）。 */
    suspend fun groups(): List<BookGroup> = withContext(IO) { appDb.bookGroupDao.all }

    /**
     * 新增 / 重命名分组。
     *
     * 口径与 `BookGroupDao` 一致：`groupId <= 0` 视为**新增**（分配 `getUnusedId()`），
     * 否则按主键 `update`；`groupName` 为空时抛异常（调用方语义上必须给名字）。
     */
    suspend fun saveGroup(groupId: Long, groupName: String, show: Boolean?, bookSort: Int?): BookGroup =
        withContext(IO) {
            val name = groupName.trim()
            require(name.isNotEmpty()) { "分组名不能为空" }
            if (groupId > 0) {
                val exist = appDb.bookGroupDao.getByID(groupId)
                    ?: throw NoSuchElementException("分组不存在：$groupId")
                exist.groupName = name
                show?.let { exist.show = it }
                bookSort?.let { exist.bookSort = it }
                appDb.bookGroupDao.update(exist)
                exist
            } else {
                val group = BookGroup(
                    groupId = appDb.bookGroupDao.getUnusedId(),
                    groupName = name,
                    show = show ?: true,
                    bookSort = bookSort ?: 0,
                    order = appDb.bookGroupDao.maxOrder + 1,
                )
                appDb.bookGroupDao.insert(group)
                group
            }
        }

    /**
     * 删除分组。
     *
     * 与 UI 同口径（`BookGroupViewModel.delGroup`）：**内置分组不可删**（`isInRules`），
     * 且删除前把组内书籍迁到"全部"（`BookDao.upGroup`），再删分组 —— 不产生"孤儿书"。
     */
    suspend fun deleteGroup(groupId: Long) {
        withContext(IO) {
            require(groupId > 0) { "内置分组不可删除" }
            require(!appDb.bookGroupDao.isInRules(groupId)) { "该分组被自动任务占用，不可删除" }
            appDb.bookDao.upGroup(groupId, BookGroup.IdAll)
            appDb.bookGroupDao.getByID(groupId)?.let { appDb.bookGroupDao.delete(it) }
        }
    }

    /** 设置书籍所属分组（单条）。 */
    suspend fun setBookGroup(bookUrl: String, groupId: Long): Book =
        withContext(IO) {
            val book = appDb.bookDao.getBook(bookUrl)
                ?: throw NoSuchElementException("书籍不存在：$bookUrl")
            require(groupId == 0L || appDb.bookGroupDao.getByID(groupId) != null) {
                "分组不存在：$groupId"
            }
            appDb.bookDao.upGroup(book.group, groupId)
            book.group = groupId
            appDb.bookDao.update(book)
            book
        }

    // ============================================================ 书籍详情 / 排序

    /** 书籍详情（含 author / intro / cover / 分组 / 进度字段）。 */
    suspend fun bookInfo(bookUrl: String): Book? = withContext(IO) { appDb.bookDao.getBook(bookUrl) }

    /**
     * 调整书籍排序 / 移动位置。
     *
     * - `order` 非空 ⇒ **绝对设置**（直接写该值）；
     * - `move` 非空 ⇒ **相对移动**（`top` / `up` / `down` / `bottom`），在同一分组内按 `order` 重排后回写两条记录，
     *   与书架长按拖拽的语义相同（只交换相邻两条的 `order`，不重排全表）。
     */
    suspend fun setBookOrder(bookUrl: String, order: Int?, move: String?): Book =
        withContext(IO) {
            val book = appDb.bookDao.getBook(bookUrl)
                ?: throw NoSuchElementException("书籍不存在：$bookUrl")
            when {
                order != null -> book.order = order
                !move.isNullOrBlank() -> {
                    val siblings = appDb.bookDao.getBooksByGroup(book.group)
                        .sortedBy { it.order }
                        .toMutableList()
                    val index = siblings.indexOfFirst { it.bookUrl == bookUrl }
                    if (index >= 0) {
                        val target = when (move.lowercase()) {
                            "top" -> 0
                            "up" -> (index - 1).coerceAtLeast(0)
                            "down" -> (index + 1).coerceAtMost(siblings.lastIndex)
                            "bottom" -> siblings.lastIndex
                            else -> throw IllegalArgumentException("move 仅支持 top/up/down/bottom")
                        }
                        if (target != index) {
                            val other = siblings.removeAt(index)
                            siblings.add(target, other)
                            siblings.forEachIndexed { i, item -> item.order = i }
                            appDb.bookDao.update(*siblings.toTypedArray())
                            return@withContext appDb.bookDao.getBook(bookUrl) ?: other
                        }
                    }
                }
                else -> throw IllegalArgumentException("order 与 move 至少给一个")
            }
            appDb.bookDao.update(book)
            book
        }

    // ============================================================ 本地书 / 在线导入

    /** 添加本地书（沿用一期 `BookKernel.addLocalBook`）。 */
    suspend fun addLocalBook(fileName: String, filePath: String) =
        BookKernel.addLocalBook(fileName, filePath)

    /**
     * 按 URL 导入在线书籍（txt/umd/epub 文件流）。
     *
     * 复用 [LocalBook.importFileOnLine]（下载 + 落盘 + 建书 + 生成目录，与 App 导入页同一条链）。
     */
    suspend fun importByUrl(name: String, url: String, sourceUrl: String?): Book =
        withContext(IO) {
            val source = sourceUrl?.takeIf { it.isNotBlank() }
                ?.let { appDb.bookSourceDao.getBookSource(it) }
            LocalBook.importFileOnLine(url, name, source)
        }

    // ============================================================ 跨书源搜书

    /**
     * 跨书源全局搜书（返回"可加入书架"的结果集）。
     *
     * @param sourceUrls 指定参与搜索的书源；为空 ⇒ 全部**启用**书源
     * @param maxSources 最多搜索的书源数（默认 8，避免一次打满全部源）
     */
    suspend fun searchBooks(
        key: String,
        page: Int = 1,
        sourceUrls: List<String> = emptyList(),
        maxSources: Int = 8,
    ): List<Map<String, Any?>> {
        if (key.isBlank()) throw IllegalArgumentException("搜索关键词不能为空")
        val sources = withContext(IO) {
            val all = if (sourceUrls.isEmpty()) {
                appDb.bookSourceDao.allEnabled
            } else {
                sourceUrls.mapNotNull { appDb.bookSourceDao.getBookSource(it) }
            }
            all.filter { !it.searchUrl.isNullOrBlank() }.take(maxSources.coerceIn(1, 64))
        }
        val result = ArrayList<Map<String, Any?>>()
        sources.forEach { source ->
            val books = kotlin.runCatching { WebBook.searchBookAwait(source, key, page) }
                .onFailure { AppLog.put("MCP 跨源搜书失败：${it.localizedMessage}", it) }
                .getOrNull().orEmpty()
            books.forEach { searchBook ->
                result.add(
                    mapOf(
                        "name" to searchBook.name,
                        "author" to searchBook.author,
                        "kind" to searchBook.kind,
                        "origin" to searchBook.origin,
                        "originName" to searchBook.originName,
                        "bookUrl" to searchBook.bookUrl,
                        "intro" to searchBook.intro,
                        "coverUrl" to searchBook.coverUrl,
                        "latestChapterTitle" to searchBook.latestChapterTitle,
                    )
                )
            }
        }
        return result
    }

    // ============================================================ 批量管理

    /**
     * 书架批量操作（spec §4.2① `bookshelf_batch_op`）。
     *
     * 支持动作（均复用既有实现，不自造规则）：
     * - `update_on` / `update_off` ⇒ `canUpdate` 开关（同 `BookshelfManageViewModel.upCanUpdate`）
     * - `clear_cache` ⇒ [BookHelp.clearCache(Book)]
     * - `set_group` ⇒ 批量改分组
     * - `change_source` ⇒ 批量换源（同 `BookshelfManageViewModel.changeSource`，含逐本搜索 + 迁移进度）
     */
    suspend fun batchOp(action: String, bookUrls: List<String>, targetGroup: Long?, targetSource: String?): Map<String, Any?> {
        if (bookUrls.isEmpty()) throw IllegalArgumentException("bookUrls 不能为空")
        val books = withContext(IO) { bookUrls.mapNotNull { appDb.bookDao.getBook(it) } }
        if (books.isEmpty()) throw IllegalArgumentException("没有匹配到的书籍")
        return when (action) {
            "update_on", "update_off" -> {
                val canUpdate = action == "update_on"
                val array = withContext(IO) {
                    Array(books.size) {
                        books[it].copy(canUpdate = canUpdate).apply {
                            if (!canUpdate) removeType(BookType.updateError)
                        }
                    }
                }
                withContext(IO) { appDb.bookDao.update(*array) }
                mapOf("action" to action, "affected" to array.size)
            }

            "clear_cache" -> withContext(IO) {
                books.forEach { BookHelp.clearCache(it) }
                mapOf("action" to action, "affected" to books.size)
            }

            "set_group" -> {
                val groupId = targetGroup ?: throw IllegalArgumentException("set_group 需要 targetGroup")
                withContext(IO) {
                    books.forEach { appDb.bookDao.upGroup(it.group, groupId) }
                    mapOf("action" to action, "affected" to books.size, "groupId" to groupId)
                }
            }

            "change_source" -> {
                val sourceUrl = targetSource?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("change_source 需要 targetSource")
                val source = withContext(IO) { appDb.bookSourceDao.getBookSource(sourceUrl) }
                    ?: throw NoSuchElementException("书源不存在：$sourceUrl")
                changeSource(books, source)
            }

            else -> throw IllegalArgumentException("不支持的动作：$action（可选 update_on/update_off/clear_cache/set_group/change_source）")
        }
    }

    /** 批量换源（逐本：跨源精确搜索 → 拉目录 → 迁移进度 → 落库）。 */
    private suspend fun changeSource(books: List<Book>, source: io.legado.app.data.entities.BookSource): Map<String, Any?> {
        var changed = 0
        var skipped = 0
        val delayMs = AppConfig.batchChangeSourceDelay * 1000L
        books.forEach { book ->
            if (book.isLocal || book.origin == source.bookSourceUrl) {
                skipped++
                return@forEach
            }
            val newBook = kotlin.runCatching { WebBook.preciseSearchAwait(source, book.name, book.author) }
                .onFailure { AppLog.put("MCP 批量换源搜索失败：${it.localizedMessage}", it) }
                .getOrNull()?.getOrNull()
            if (newBook == null) {
                skipped++
                return@forEach
            }
            kotlin.runCatching {
                if (newBook.tocUrl.isEmpty()) WebBook.getBookInfoAwait(source, newBook)
            }.onFailure { skipped++; return@forEach }
            val toc = kotlin.runCatching { WebBook.getChapterListAwait(source, newBook) }
                .onFailure { AppLog.put("MCP 批量换源取目录失败：${it.localizedMessage}", it) }
                .getOrNull()?.getOrNull()
            if (toc == null) {
                skipped++
                return@forEach
            }
            withContext(IO) {
                book.migrateTo(newBook, toc)
                book.removeType(BookType.updateError)
                appDb.bookDao.insert(newBook)
                appDb.bookChapterDao.insert(*toc.toTypedArray())
            }
            changed++
            if (delayMs > 0) delay(delayMs)
        }
        return mapOf("changed" to changed, "skipped" to skipped, "total" to books.size)
    }

    // ============================================================ 标签

    /** 标签卡数据（书 URL / 书名 / 作者 / 自定义标签 / 类型 / 分组）。 */
    suspend fun tags(): List<io.legado.app.data.dao.BookTagInfo> =
        withContext(IO) { appDb.bookDao.allTagInfos }

    /** 批量写自定义标签（传 null / 空串 = 清除标签）。 */
    suspend fun saveTag(bookUrls: List<String>, tag: String?): Map<String, Any?> {
        if (bookUrls.isEmpty()) throw IllegalArgumentException("bookUrls 不能为空")
        val value = tag?.takeIf { it.isNotBlank() }
        withContext(IO) { bookUrls.forEach { appDb.bookDao.updateCustomTag(it, value) } }
        return mapOf("affected" to bookUrls.size, "tag" to value)
    }

    // ============================================================ 删除

    /**
     * 从书架删除书籍（`book_delete`，admin）。
     *
     * 与 `BookshelfManageViewModel.deleteBook` **同一条链**：先删库记录，再对本地书删文件、
     * 对在线书回调书源的 `delBookShelf` 事件（源可用它记日志/更新状态）。
     *
     * @param deleteOriginal 本地书是否连同源文件一起删（默认 false = 仅移出书架）
     */
    suspend fun deleteBooks(bookUrls: List<String>, deleteOriginal: Boolean): Map<String, Any?> {
        if (bookUrls.isEmpty()) throw IllegalArgumentException("bookUrls 不能为空")
        val books = withContext(IO) { bookUrls.mapNotNull { appDb.bookDao.getBook(it) } }
        if (books.isEmpty()) throw IllegalArgumentException("没有匹配到的书籍")
        withContext(IO) {
            appDb.bookDao.delete(*books.toTypedArray())
            books.forEach { book ->
                if (book.isLocal) {
                    LocalBook.deleteBook(book, deleteOriginal)
                } else {
                    val source = appDb.bookSourceDao.getBookSource(book.origin)
                    SourceCallBack.callBackBook(SourceCallBack.DEL_BOOK_SHELF, source, book)
                }
            }
        }
        return mapOf("deleted" to books.size, "deleteOriginal" to deleteOriginal)
    }

    // ============================================================ 分享
    /**
     * 书籍系统分享文本。
     *
     * 与 `BookInfoModernActivity.shareBook()` **同格式**（`bookUrl#bookJson`）——
     * 该串是 App 内"分享给他人导入"的既有协议，AI 侧不应另造格式。
     */
    suspend fun shareText(bookUrl: String): Map<String, Any?> {
        val book = withContext(IO) { appDb.bookDao.getBook(bookUrl) }
            ?: throw NoSuchElementException("书籍不存在：$bookUrl")
        val json = GSON.toJson(book)
        return mapOf(
            "shareText" to "$bookUrl#$json",
            "size" to json.toByteArray(Charsets.UTF_8).size,
        )
    }

    // ============================================================ 三期 G 组（REST：书架搜索）

    /**
     * 书架内搜索（三期 REQ-3-501 `GET /searchBookshelf`）。
     *
     * 与二期 MCP 工具 `bookshelf_search` **同口径**：书名 / 作者不区分大小写包含，**0 网络请求**；
     * 复用 [BookKernel.bookshelf] 的排序结果，保证 REST 与 MCP 行为不漂移（AD-3-01）。
     */
    suspend fun searchBookshelf(keyword: String): List<Book> {
        val key = keyword.trim()
        if (key.isEmpty()) throw IllegalArgumentException("搜索关键词不能为空")
        return BookKernel.bookshelf().filter {
            it.name.contains(key, ignoreCase = true) || it.author.contains(key, ignoreCase = true)
        }
    }
}
