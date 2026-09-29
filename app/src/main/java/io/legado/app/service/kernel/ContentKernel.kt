package io.legado.app.service.kernel

import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.SceneBookmark
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.time.LocalDate

/**
 * ② 阅读域业务内核（web-mcp-productization 二期 · tasks 2.9 / 2.28）。
 *
 * 契约同 [BookKernel]：**只返回领域对象** / **全链挂起** / **零 `runBlocking`** / 失败抛异常。
 *
 * **复用口径（AD-10）**：
 * - 阅读配置读写 = **与 REST `/getReadConfig` 同源**（`BookKernel.webReadConfig`），保证 MCP ↔ REST 对拍一致（REQ-2-205）；
 * - 换源 = 照搬 `ChangeBookSourceViewModel.autoChangeSource` / `BookshelfManageViewModel.changeSource` 的同一链；
 * - 正文清洗 / 目录重建 = 复用 `ContentProcessor` / `BookHelp` / `BookChapterDao`，不自造文本处理；
 * - EPUB 目录与正文 = 复用 [LocalBook]（内部按后缀分发到 `EpubFile`），local 与 epub 走同一条链。
 */
object ContentKernel {

    // ============================================================ 阅读进度

    /**
     * 保存阅读进度（`book_save_progress`）。
     *
     * [BookKernel.saveBookProgress] 以 `name + author` 定位书籍，故这里先用 `bookUrl` 解出身份，
     * 再复用一期同一实现（含云进度上传与 `ReadBook` 内存态对齐）。
     *
     * @return `false` = 书不在书架（与 REST `/saveBookProgress` 的同义返回）
     */
    suspend fun saveProgress(bookUrl: String, chapterIndex: Int, chapterPos: Int, chapterTitle: String?): Boolean {
        val book = withContext(IO) { appDb.bookDao.getBook(bookUrl) }
            ?: throw NoSuchElementException("书籍不存在：$bookUrl")
        val progress = BookProgress(
            name = book.name,
            author = book.author,
            durChapterIndex = chapterIndex,
            durChapterPos = chapterPos,
            durChapterTime = System.currentTimeMillis(),
            durChapterTitle = chapterTitle ?: book.durChapterTitle,
        )
        return BookKernel.saveBookProgress(progress)
    }

    // ============================================================ 阅读配置 / 菜单按钮

    /** 读取阅读配置（Web 阅读配置 JSON；与 REST `/getReadConfig` 同源）。 */
    suspend fun readConfigGet(): String? = BookKernel.webReadConfig()

    /** 保存阅读配置（与 REST `/saveReadConfig` 同源）。 */
    suspend fun readConfigSave(json: String?) {
        BookKernel.saveWebReadConfig(json)
    }

    /** 读取阅读菜单自定义按钮：`layout`（两行按钮布局 JSON）+ `buttons`（自定义按钮实体）。 */
    suspend fun menuButtonsGet(): Map<String, Any?> = withContext(IO) {
        mapOf(
            "layout" to appCtx.getPrefString(PreferKey.readMenuButtonLayout).orEmpty(),
            "buttons" to appDb.readMenuCustomButtonDao.all(),
        )
    }

    /**
     * 保存阅读菜单自定义按钮。
     *
     * - `layoutJson` 非空 ⇒ 原样写入布局偏好（读取侧 `ReadMenuButtonConfig.load` 会做 sanitize 兜底）；
     * - `buttonsJson` 非空 ⇒ 按 JSON 数组替换自定义按钮（先删后插，避免残留旧按钮）。
     */
    suspend fun menuButtonsSave(layoutJson: String?, buttonsJson: String?): Map<String, Any?> {
        if (layoutJson != null) {
            withContext(IO) { appCtx.putPrefString(PreferKey.readMenuButtonLayout, layoutJson) }
        }
        var savedButtons = 0
        if (!buttonsJson.isNullOrBlank()) {
            val parsed = kotlin.runCatching {
                GSON.fromJson(
                    buttonsJson,
                    com.google.gson.reflect.TypeToken.getParameterized(
                        List::class.java,
                        io.legado.app.data.entities.ReadMenuCustomButton::class.java
                    ).type
                ) as? List<io.legado.app.data.entities.ReadMenuCustomButton>
            }.getOrNull() ?: throw IllegalArgumentException("buttonsJson 不是合法的自定义按钮数组")
            withContext(IO) {
                appDb.readMenuCustomButtonDao.all().forEach { appDb.readMenuCustomButtonDao.delete(it) }
                parsed.forEach { appDb.readMenuCustomButtonDao.insert(it) }
            }
            savedButtons = parsed.size
        }
        return mapOf("layoutSaved" to (layoutJson != null), "buttons" to savedButtons)
    }

    // ============================================================ 自动阅读

    /** 读取自动阅读设置（全局单源 [ReadBookConfig.autoReadSpeed] / [ReadBookConfig.autoReadMode]）。 */
    suspend fun autoReadConfigGet(): Map<String, Any?> = mapOf(
        "autoReadSpeed" to ReadBookConfig.autoReadSpeed,
        "autoReadMode" to ReadBookConfig.autoReadMode,
        "autoReadModeNames" to mapOf(
            ReadBookConfig.AUTO_READ_MODE_SCROLL to "滚动",
            ReadBookConfig.AUTO_READ_MODE_TIMED to "定时",
        ),
    )

    /** 保存自动阅读设置（只写传入项）。 */
    suspend fun autoReadConfigSave(speed: Int?, mode: Int?): Map<String, Any?> {
        speed?.let { ReadBookConfig.autoReadSpeed = it }
        mode?.let { ReadBookConfig.autoReadMode = it }
        return autoReadConfigGet()
    }

    // ============================================================ 换源

    /**
     * 换源 / 自动换源：只改书籍的 `sourceUrl`（origin）与目录，**不动替换规则**。
     *
     * 链路与 App 内「换源」一致：目标源内精确搜索 → 补详情（`tocUrl` 为空时）→ 拉目录 →
     * [Book.migrateTo] 迁移进度 → 落库新书 + 新目录。
     */
    suspend fun changeSource(bookUrl: String, sourceUrl: String, keyword: String?, author: String?): Map<String, Any?> {
        val book = withContext(IO) { appDb.bookDao.getBook(bookUrl) }
            ?: throw NoSuchElementException("书籍不存在：$bookUrl")
        val source = withContext(IO) { appDb.bookSourceDao.getBookSource(sourceUrl) }
            ?: throw NoSuchElementException("书源不存在：$sourceUrl")
        if (book.isLocal) throw IllegalStateException("本地书不支持换源")
        if (book.origin == source.bookSourceUrl) return mapOf("changed" to false, "reason" to "已经是该书源")

        val key = keyword?.takeIf { it.isNotBlank() } ?: book.name
        val newBook = WebBook.preciseSearchAwait(source, key, author ?: book.author).getOrNull()
            ?: throw NoSuchElementException("在目标书源中未找到《$key》，无法换源")
        if (newBook.tocUrl.isEmpty()) WebBook.getBookInfoAwait(source, newBook)
        val toc = WebBook.getChapterListAwait(source, newBook).getOrNull()
            ?: throw IllegalStateException("目标书源目录获取失败，已放弃换源")
        withContext(IO) {
            book.migrateTo(newBook, toc)
            appDb.bookDao.insert(newBook)
            appDb.bookChapterDao.insert(*toc.toTypedArray())
        }
        return mapOf(
            "changed" to true,
            "oldOrigin" to book.origin,
            "newOrigin" to newBook.origin,
            "chapterCount" to toc.size,
        )
    }

    /** 换源前跨源搜同书（返回候选源 + 书籍，供 AI 选目标源）。 */
    suspend fun searchForChange(bookUrl: String, sourceUrls: List<String>, maxSources: Int): List<Map<String, Any?>> {
        val book = withContext(IO) { appDb.bookDao.getBook(bookUrl) }
            ?: throw NoSuchElementException("书籍不存在：$bookUrl")
        val sources = withContext(IO) {
            val all = if (sourceUrls.isEmpty()) {
                appDb.bookSourceDao.allEnabled
            } else {
                sourceUrls.mapNotNull { appDb.bookSourceDao.getBookSource(it) }
            }
            all.filter { it.bookSourceUrl != book.origin && !it.searchUrl.isNullOrBlank() }
                .take(maxSources.coerceIn(1, 64))
        }
        val result = ArrayList<Map<String, Any?>>()
        sources.forEach { source ->
            val found = kotlin.runCatching { WebBook.preciseSearchAwait(source, book.name, book.author) }
                .onFailure { AppLog.put("MCP 换源候选搜索失败：${it.localizedMessage}", it) }
                .getOrNull()?.getOrNull()
            if (found != null) {
                result.add(
                    mapOf(
                        "origin" to source.bookSourceUrl,
                        "originName" to source.bookSourceName,
                        "name" to found.name,
                        "author" to found.author,
                        "bookUrl" to found.bookUrl,
                        "latestChapterTitle" to found.latestChapterTitle,
                        "totalChapterNum" to found.totalChapterNum,
                    )
                )
            }
        }
        return result
    }

    // ============================================================ 书内全文搜索 / 刷新 / 净化

    /**
     * 书内全文搜索（返回命中章节 + 片段）。
     *
     * 实现口径：**先查目录标题命中**（零网络），再按 `maxChapters`（默认 50）逐章取正文匹配。
     * 逐章取正文会走各章缓存/网络链，故默认限制章数并返回 `scanned` 供调用方判断是否需扩大范围。
     */
    suspend fun searchContent(bookUrl: String, key: String, maxChapters: Int, snippetLength: Int): Map<String, Any?> {
        if (key.isBlank()) throw IllegalArgumentException("搜索关键词不能为空")
        val book = withContext(IO) { appDb.bookDao.getBook(bookUrl) }
            ?: throw NoSuchElementException("书籍不存在：$bookUrl")
        val chapters = withContext(IO) { appDb.bookChapterDao.getChapterList(bookUrl) }
        val limit = if (maxChapters <= 0) chapters.size else maxChapters.coerceAtMost(chapters.size)
        val snippet = snippetLength.coerceIn(20, 500)
        val titleHits = chapters.filter { !it.isVolume && it.title.contains(key, ignoreCase = true) }
        val contentHits = ArrayList<Map<String, Any?>>()
        var scanned = 0
        for (chapter in chapters) {
            if (scanned >= limit) break
            if (chapter.isVolume) continue
            scanned++
            val content = kotlin.runCatching { BookKernel.bookContent(bookUrl, chapter.index) }
                .onFailure { AppLog.put("MCP 全文搜索取正文失败：${it.localizedMessage}", it) }
                .getOrNull() ?: continue
            val at = content.indexOf(key, ignoreCase = true)
            if (at >= 0) {
                val start = (at - snippet / 2).coerceAtLeast(0)
                val end = (start + snippet).coerceAtMost(content.length)
                contentHits.add(
                    mapOf(
                        "chapterIndex" to chapter.index,
                        "chapterName" to chapter.title,
                        "snippet" to content.substring(start, end),
                    )
                )
            }
        }
        return mapOf(
            "bookUrl" to bookUrl,
            "key" to key,
            "scanned" to scanned,
            "totalChapters" to chapters.size,
            "truncated" to (scanned < chapters.size),
            "titleHits" to titleHits.map { mapOf("chapterIndex" to it.index, "chapterName" to it.title) },
            "contentHits" to contentHits,
        )
    }

    /**
     * 三档刷新（spec §4.2② `book_refresh`）。
     *
     * - `content`：清当前章缓存后重取正文（强制走网络），并落回缓存；
     * - `cache`：清整书缓存（正文/图片）；
     * - `purify`：对当前章重跑替换规则并覆写缓存。
     */
    suspend fun refresh(bookUrl: String, mode: String, chapterIndex: Int): Map<String, Any?> {
        val book = withContext(IO) { appDb.bookDao.getBook(bookUrl) }
            ?: throw NoSuchElementException("书籍不存在：$bookUrl")
        val index = if (chapterIndex >= 0) chapterIndex else book.durChapterIndex
        return when (mode) {
            "content" -> {
                val chapter = withContext(IO) { appDb.bookChapterDao.getChapter(bookUrl, index) }
                    ?: throw NoSuchElementException("章节不存在：$index")
                withContext(IO) { BookHelp.delContent(book, chapter) }
                val content = BookKernel.bookContent(bookUrl, index)
                mapOf("mode" to mode, "chapterIndex" to index, "length" to (content?.length ?: 0), "refreshed" to (content != null))
            }

            "cache" -> {
                withContext(IO) { BookHelp.clearCache(book) }
                mapOf("mode" to mode, "cleared" to true)
            }

            "purify" -> {
                val content = BookKernel.bookContent(bookUrl, index)
                    ?: throw NoSuchElementException("章节正文不存在：$index")
                val purified = purify(bookUrl, content)
                val chapter = withContext(IO) { appDb.bookChapterDao.getChapter(bookUrl, index) }
                    ?: throw NoSuchElementException("章节不存在：$index")
                withContext(IO) { BookHelp.saveText(book, chapter, purified) }
                mapOf("mode" to mode, "chapterIndex" to index, "length" to purified.length, "saved" to true)
            }

            else -> throw IllegalArgumentException("mode 仅支持 content/cache/purify")
        }
    }

    /**
     * 按书的内容域替换规则清洗文本（`book_purify`）。
     *
     * 规则来源与 App 内正文处理链**同一处**（[ContentProcessor.getContentReplaceRules]），
     * 只保留 `isEnabled && scopeContent` 的规则并按 `order` 升序作用。
     */
    suspend fun purify(bookUrl: String, text: String): String {
        val book = withContext(IO) { appDb.bookDao.getBook(bookUrl) }
            ?: throw NoSuchElementException("书籍不存在：$bookUrl")
        val rules = withContext(IO) {
            ContentProcessor.get(book.name, book.origin).getContentReplaceRules()
                .filter { it.isEnabled && it.scopeContent }
                .sortedBy { it.order }
        }
        return ReplaceRuleKernel.applyRules(rules, text)
    }

    // ============================================================ 云端进度

    /** 拉取云端阅读进度（WebDAV 通道；未配置或不存在返回 null）。 */
    suspend fun cloudProgressGet(bookUrl: String): BookProgress? {
        val book = withContext(IO) { appDb.bookDao.getBook(bookUrl) }
            ?: throw NoSuchElementException("书籍不存在：$bookUrl")
        return AppWebDav.getBookProgress(book)
    }

    /** 上传当前书籍进度到云端（覆盖）。 */
    suspend fun cloudProgressSave(bookUrl: String): BookProgress? {
        val book = withContext(IO) { appDb.bookDao.getBook(bookUrl) }
            ?: throw NoSuchElementException("书籍不存在：$bookUrl")
        AppWebDav.uploadBookProgress(book)
        return AppWebDav.getBookProgress(book)
    }

    // ============================================================ 目录更新 / EPUB / 编辑正文

    /**
     * 更新目录（`book_toc_update`）：重拉目录后按选项做**同题去重**与**反转**，再落库并重建序号。
     *
     * 与 App 内「更新目录」的差异：App 由 `TocViewModel` 组合多个开关，这里把同样的后处理
     * （去重 / 反转 / 重新编号）显式化，便于 AI 分步操作。
     */
    suspend fun tocUpdate(bookUrl: String, deDuplicate: Boolean, reverse: Boolean): List<BookChapter> {
        val toc = BookKernel.refreshToc(bookUrl).toMutableList()
        var result: MutableList<BookChapter> = toc
        if (deDuplicate) {
            val seen = HashSet<String>()
            result = result.filter { chapter ->
                if (chapter.isVolume) true else seen.add(chapter.title)
            }.toMutableList()
        }
        if (reverse) result.reverse()
        result.forEachIndexed { i, chapter -> chapter.index = i }
        withContext(IO) {
            appDb.bookChapterDao.delByBook(bookUrl)
            appDb.bookChapterDao.insert(*result.toTypedArray())
        }
        return result
    }

    /** 读取 EPUB / 本地书目录（[LocalBook] 按后缀分发，EPUB 走 `EpubFile`）。 */
    suspend fun epubToc(bookUrl: String): List<BookChapter> {
        val book = withContext(IO) { appDb.bookDao.getBook(bookUrl) }
            ?: throw NoSuchElementException("书籍不存在：$bookUrl")
        if (!book.isLocal) throw IllegalStateException("仅本地书（含 EPUB）支持该工具")
        return withContext(IO) { LocalBook.getChapterList(book) }
    }

    /** 读取 EPUB / 本地书章节正文。 */
    suspend fun epubContent(bookUrl: String, chapterIndex: Int): String? {
        val book = withContext(IO) { appDb.bookDao.getBook(bookUrl) }
            ?: throw NoSuchElementException("书籍不存在：$bookUrl")
        if (!book.isLocal) throw IllegalStateException("仅本地书（含 EPUB）支持该工具")
        val chapter = withContext(IO) { appDb.bookChapterDao.getChapter(bookUrl, chapterIndex) }
            ?: throw NoSuchElementException("章节不存在：$chapterIndex")
        return kotlin.runCatching { withContext(IO) { LocalBook.getContent(book, chapter) } }
            .onFailure { AppLog.put("MCP 读取本地书正文失败：${it.localizedMessage}", it) }
            .getOrNull()
    }

    /** 手动编辑正文（阅读页「编辑内容」的同一写回链 [BookHelp.saveText]）。 */
    suspend fun editContent(bookUrl: String, chapterIndex: Int, content: String): Map<String, Any?> {
        val book = withContext(IO) { appDb.bookDao.getBook(bookUrl) }
            ?: throw NoSuchElementException("书籍不存在：$bookUrl")
        val chapter = withContext(IO) { appDb.bookChapterDao.getChapter(bookUrl, chapterIndex) }
            ?: throw NoSuchElementException("章节不存在：$chapterIndex")
        withContext(IO) { BookHelp.saveText(book, chapter, content) }
        return mapOf("bookUrl" to bookUrl, "chapterIndex" to chapterIndex, "length" to content.length, "saved" to true)
    }

    /** 本地书后缀辅助（EPUB 判定），供工具层做前置校验与文案。 */
    suspend fun isEpub(bookUrl: String): Boolean {
        val book = withContext(IO) { appDb.bookDao.getBook(bookUrl) } ?: return false
        return book.isEpub
    }

    /** Web 服务端口等只读应用信息里的阅读相关项（供工具描述取用，避免工具层重复读配置）。 */
    fun readAloudEngineOfBook(): String? = AppConfig.ttsEngine

    /** 书籍对象（供上层复用，避免各工具重复 `appDb` 直查）。 */
    suspend fun book(bookUrl: String): Book? = withContext(IO) { appDb.bookDao.getBook(bookUrl) }

    /** 书源对象（换源相关工具复用）。 */
    suspend fun source(sourceUrl: String): BookSource? =
        withContext(IO) { appDb.bookSourceDao.getBookSource(sourceUrl) }

    // ============================================================ E 组（三期 · spec 4.4）：内容面补全

    /** 文章正文 HTML 中的图片地址提取（只出地址，不解析正文内容）。 */
    private val IMG_SRC_REGEX = Regex("""<img[^>]*?\ssrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /**
     * `getRssArticles`（三期 E1）：某订阅源某分类的文章列表（DB 已抓取内容；分页切片）。
     *
     * 复用 [RssSourceKernel.articles]（与 MCP `rss_articles_list` 同一条链，含已读态）。
     * 分页口径：`page`（从 1 起）+ `pageSize`；`keyword` 为空即不过滤。
     */
    suspend fun rssArticles(
        origin: String,
        sort: String,
        page: Int,
        pageSize: Int,
        keyword: String?,
    ): Map<String, Any?> = withContext(IO) {
        require(origin.isNotBlank()) { "origin 不能为空" }
        require(sort.isNotBlank()) { "sort（订阅分类）不能为空" }
        val size = pageSize.coerceIn(1, 200)
        val current = page.coerceAtLeast(1)
        val all = RssSourceKernel.articles(origin, sort, 0, keyword)
        mapOf(
            "origin" to origin,
            "sort" to sort,
            "page" to current,
            "pageSize" to size,
            "total" to all.size,
            "articles" to all.drop((current - 1) * size).take(size),
        )
    }

    /**
     * `getRssArticleContent`（三期 E2）：订阅文章正文 + 正文内图片地址列表。
     *
     * 正文链复用 [RssSourceKernel.articleContent]（按源规则抓取并净化）；
     * 图片列表为对正文 HTML 的 `img src` 提取（只出地址，不做任何内容解析）。
     */
    suspend fun rssArticleContent(origin: String, link: String, sort: String): Map<String, Any?> =
        withContext(IO) {
            val base = RssSourceKernel.articleContent(origin, link, sort)
            val content = base["content"] as? String ?: ""
            val images = IMG_SRC_REGEX.findAll(content).map { it.groupValues[1] }.distinct().toList()
            base + mapOf("images" to images)
        }

    /**
     * `markRssRead`（三期 E3）：按**文章链接**标记订阅文章已读 / 未读（粒度 = 单篇）。
     *
     * 复用 [RssSourceKernel.markArticleRead]（与整源 [RssSourceKernel.markRead] 同表同列，口径一致）。
     */
    suspend fun markRssRead(origin: String, links: List<String>, read: Boolean): Map<String, Any?> =
        withContext(IO) {
            require(origin.isNotBlank()) { "origin 不能为空" }
            RssSourceKernel.markArticleRead(origin, links, read)
        }

    /**
     * `getReadStats`（三期 E4）：阅读统计（近 N 天明细 + 连续天数 + 累计分钟 + 书时长 Top10）。
     *
     * 出参口径：`daily:[{date,minutes}]` / `streakDays` / `totalMinutes` / `topBooks:[{name,minutes}]`。
     * 连续天数 = 从今日起向前连续有日记录的天数（今日无记录则为 0）。
     */
    suspend fun readStats(days: Int): Map<String, Any?> {
        val window = days.coerceIn(1, 3650)
        val records = withContext(IO) { appDb.readRecordDailyDao.allDesc }
        val daily = records.take(window).map { mapOf("date" to it.date, "minutes" to it.readTime / 60_000L) }
        val dateSet = records.map { it.date }.toHashSet()
        var streak = 0
        var cursor = LocalDate.now()
        while (dateSet.contains(cursor.toString())) {
            streak++
            cursor = cursor.minusDays(1)
        }
        val topBooks = withContext(IO) { appDb.readRecordDao.allShow }
            .sortedByDescending { it.readTime }
            .take(10)
            .map { mapOf("name" to it.bookName, "minutes" to it.readTime / 60_000L) }
        return mapOf(
            "days" to window,
            "daily" to daily,
            "streakDays" to streak,
            "totalMinutes" to records.sumOf { it.readTime } / 60_000L,
            "topBooks" to topBooks,
        )
    }

    /** `saveBookmark`（三期 E6）：保存书签（JSON 体 → 实体后走 [BookmarkKernel.saveBookmark]）。 */
    suspend fun saveBookmark(json: String): Bookmark = withContext(IO) {
        val bookmark = GSON.fromJsonObject<Bookmark>(json).getOrThrow()
        if (bookmark.bookName.isBlank()) throw IllegalArgumentException("书签 bookName 不能为空")
        BookmarkKernel.saveBookmark(bookmark)
    }

    /**
     * `saveSceneBookmark`（三期补 E6b）：名场面保存。
     *
     * 校验口径：`bookUrl` 必填 —— 名场面按**书/源标识**归属（本地书 path / 书源 bookUrl /
     * 订阅源 sourceUrl），空值会让人误以为"属于全部书"（列表按 `bookUrl` 过滤，见 [BookmarkKernel.sceneBookmarks]）。
     */
    suspend fun saveSceneBookmark(json: String): SceneBookmark = withContext(IO) {
        val bookmark = GSON.fromJsonObject<SceneBookmark>(json).getOrThrow()
        if (bookmark.bookUrl.isBlank()) throw IllegalArgumentException("名场面 bookUrl 不能为空")
        BookmarkKernel.saveSceneBookmark(bookmark)
    }

    /**
     * `restoreBackup`（三期 E7）：恢复备份。
     *
     * 复用二期 [BackupKernel.restore]（**默认 dryRun**：只返回"将恢复概览"、不落库；
     * `dryRun=false` 时才按 `filePath` 真恢复）。危险级：调用方（`ApiRoute.level=ADMIN`）
     * 已保证令牌，恢复的端侧确认由前端二次确认承接（spec §7.3）。
     */
    suspend fun restoreBackup(filePath: String, dryRun: Boolean): Map<String, Any?> = withContext(IO) {
        require(filePath.isNotBlank()) { "备份文件为空" }
        BackupKernel.restore(dryRun = dryRun, path = if (dryRun) null else filePath)
    }
}
