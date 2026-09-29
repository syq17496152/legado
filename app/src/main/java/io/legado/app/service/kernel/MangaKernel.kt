package io.legado.app.service.kernel

import io.legado.app.data.appDb
import io.legado.app.help.ai.AiImageGalleryManager
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

/**
 * ⑰ 漫画 / 图片域业务内核（web-mcp-productization 二期 · tasks 2.21 / 2.28）。
 *
 * 契约同 [BookKernel]：**只返回领域对象 / 结构化 Map**、**全链挂起**、**零 `runBlocking`**、
 * 失败抛异常（调用方决定话术）。
 *
 * 数据源（全部既有能力，不新增存储）：
 * - 章节目录：复用 [BookKernel.chapterList]（`BookChapterDao`，只读元数据，不触发联网）；
 * - 章节图片：`BookHelp.getContent` 命中缓存则用缓存，未命中回落 `WebBook.getContentAwait`，
 *   再以 `BookHelp.flowImages`（纯正则提取 `<img>` + 相对路径转绝对）得到图片 URL 列表；
 * - 漫画偏好：`AppConfig` 的 `disableMangaScale` / `mangaPreDownloadNum` / `mangaColorFilter` /
 *   `enableMangaEInk` 等（getter/setter 封装 `PreferKey`，见报告 ⑰）；
 * - 图片画廊：**按报告 §⑰ 裁决映射到 AI 图库**（`AiImageGalleryManager.listImages(GalleryFilter)`）。
 *
 * 已知上限 / 降级（如实声明）：
 * 1. `pages()` **不代抓图片**（只回 URL 列表），且**不启用 WebView 嗅探兜底**
 *    （`ImageUrlExtractor.sniffBookChapterImages` 会用 `ImageSnifferWebView` 加载页面，成本高，
 *    与「只读元数据」语义冲突）⇒ 纯嗅探型图片源在本方法下可能返回 0 图，需端侧阅读器兜底；
 * 2. `chapters()` 只读**已落库**的目录：未拉取过目录的书返回空列表（需先由端侧/其它接口刷新目录）；
 * 3. `AppConfig.showMangaUi` 是**只读**属性（`val`，无 setter）⇒ 只在 `configGet()` 中出现，不可保存；
 * 4. 图片 URL 统一经 [DiagKernel.maskUrl] 打码敏感 query（token/key/secret 等），保留路径结构。
 */
object MangaKernel {

    /** 画廊筛选口径（映射到 AI 图库的 `GalleryFilter`）。 */
    const val GALLERY_ALL = "all"
    const val GALLERY_TEMPORARY = "temporary"
    const val GALLERY_FAVORITE = "favorite"
    const val GALLERY_GROUP = "group"
    const val GALLERY_SEARCH = "search"

    // ============================================================ 章节 / 页面

    /**
     * `manga_chapters`：漫画章节目录（只读元数据）。
     *
     * @return `loaded = false` 表示该书尚未拉取过目录（列表为空），而非「这本漫画没有章节」。
     */
    suspend fun chapters(bookUrl: String): Map<String, Any?> {
        require(bookUrl.isNotBlank()) { "bookUrl 不能为空" }
        val chapters = BookKernel.chapterList(bookUrl)
        return mapOf(
            "bookUrl" to bookUrl,
            "total" to chapters.size,
            "loaded" to chapters.isNotEmpty(),
            "chapters" to chapters.map {
                mapOf(
                    "index" to it.index,
                    "title" to it.title,
                    "isVolume" to it.isVolume,
                )
            },
        )
    }

    /**
     * `manga_pages`：某章图片 URL 列表（**不代抓图片**）。
     *
     * 链路：章节正文（缓存优先 / 联网回落）→ `BookHelp.flowImages` 正则提取 → 打码后返回。
     *
     * @throws NoSuchElementException 书籍 / 章节 / 书源不存在
     */
    suspend fun pages(bookUrl: String, chapterIndex: Int): Map<String, Any?> {
        require(bookUrl.isNotBlank()) { "bookUrl 不能为空" }
        require(chapterIndex >= 0) { "chapterIndex 不能为负数：$chapterIndex" }
        val book = withContext(IO) { appDb.bookDao.getBook(bookUrl) }
            ?: throw NoSuchElementException("书籍不存在：$bookUrl")
        val chapter = withContext(IO) { appDb.bookChapterDao.getChapter(bookUrl, chapterIndex) }
            ?: throw NoSuchElementException("章节不存在：bookUrl=$bookUrl, index=$chapterIndex")
        val content = BookHelp.getContent(book, chapter) ?: run {
            val bookSource = withContext(IO) { appDb.bookSourceDao.getBookSource(book.origin) }
                ?: throw NoSuchElementException("未找到书源：${DiagKernel.maskUrl(book.origin)}")
            WebBook.getContentAwait(bookSource, book, chapter)
        }
        val images = BookHelp.flowImages(chapter, content)
            .distinctUntilChanged()
            .toList()
            .map { DiagKernel.maskUrl(it) }
        return mapOf(
            "bookUrl" to bookUrl,
            "chapterIndex" to chapterIndex,
            "chapterTitle" to chapter.title,
            "isVolume" to chapter.isVolume,
            "total" to images.size,
            "images" to images,
        )
    }

    // ============================================================ 漫画配置

    /** `manga_config_get`：漫画阅读偏好（`AppConfig` 字段，见报告 ⑰ 清单）。 */
    suspend fun configGet(): Map<String, Any?> = mapOf(
        "showMangaUi" to AppConfig.showMangaUi,
        "disableMangaScale" to AppConfig.disableMangaScale,
        "mangaVolumeKeyPage" to AppConfig.mangaVolumeKeyPage,
        "disableMangaPageAnim" to AppConfig.disableMangaPageAnim,
        "mangaPreDownloadNum" to AppConfig.mangaPreDownloadNum,
        "mangaAutoPageSpeed" to AppConfig.mangaAutoPageSpeed,
        "mangaFooterConfig" to AppConfig.mangaFooterConfig,
        "enableMangaHorizontalScroll" to AppConfig.enableMangaHorizontalScroll,
        "mangaColorFilter" to AppConfig.mangaColorFilter,
        "hideMangaTitle" to AppConfig.hideMangaTitle,
        "enableMangaEInk" to AppConfig.enableMangaEInk,
        "mangaEInkThreshold" to AppConfig.mangaEInkThreshold,
        "enableMangaGray" to AppConfig.enableMangaGray,
    )

    /**
     * `manga_config_save`：保存漫画偏好（**只覆盖传入的非 null 字段**，其余沿用现值）。
     *
     * `showMangaUi` 为只读属性（`val`）⇒ 不在入参内（见类注释「已知上限 3」）。
     */
    suspend fun configSave(
        disableMangaScale: Boolean? = null,
        mangaVolumeKeyPage: Boolean? = null,
        disableMangaPageAnim: Boolean? = null,
        mangaPreDownloadNum: Int? = null,
        mangaAutoPageSpeed: Int? = null,
        mangaFooterConfig: String? = null,
        enableMangaHorizontalScroll: Boolean? = null,
        mangaColorFilter: String? = null,
        hideMangaTitle: Boolean? = null,
        enableMangaEInk: Boolean? = null,
        mangaEInkThreshold: Int? = null,
        enableMangaGray: Boolean? = null,
    ): Map<String, Any?> = withContext(IO) {
        disableMangaScale?.let { AppConfig.disableMangaScale = it }
        mangaVolumeKeyPage?.let { AppConfig.mangaVolumeKeyPage = it }
        disableMangaPageAnim?.let { AppConfig.disableMangaPageAnim = it }
        mangaPreDownloadNum?.let { AppConfig.mangaPreDownloadNum = it }
        mangaAutoPageSpeed?.let { AppConfig.mangaAutoPageSpeed = it }
        mangaFooterConfig?.let { AppConfig.mangaFooterConfig = it }
        enableMangaHorizontalScroll?.let { AppConfig.enableMangaHorizontalScroll = it }
        mangaColorFilter?.let { AppConfig.mangaColorFilter = it }
        hideMangaTitle?.let { AppConfig.hideMangaTitle = it }
        enableMangaEInk?.let { AppConfig.enableMangaEInk = it }
        mangaEInkThreshold?.let { AppConfig.mangaEInkThreshold = it }
        enableMangaGray?.let { AppConfig.enableMangaGray = it }
        configGet()
    }

    // ============================================================ 图片画廊（AI 图库）

    /**
     * `image_gallery_list`：图片画廊列表（**数据源 = AI 图库**，报告 §⑰ 裁决）。
     *
     * @param filter 筛选口径：`all`（默认）/ `temporary`（临时图）/ `favorite`（收藏）/ `group` / `search`
     * @param groupId `filter = group` 时的分组 id
     * @param keyword `filter = search` 时的关键词（`name/prompt/…` 模糊匹配）
     * @param favoriteOnly 置 true 时**强制按收藏筛选**（优先于 `filter`）
     * @param limit <= 0 表示不限
     */
    suspend fun galleryList(
        filter: String = GALLERY_ALL,
        groupId: String? = null,
        keyword: String? = null,
        favoriteOnly: Boolean = false,
        limit: Int = 100,
    ): Map<String, Any?> = withContext(IO) {
        val galleryFilter = when {
            favoriteOnly -> AiImageGalleryManager.GalleryFilter.FAVORITE
            !keyword.isNullOrBlank() -> AiImageGalleryManager.GalleryFilter.SEARCH(keyword)
            !groupId.isNullOrBlank() -> AiImageGalleryManager.GalleryFilter.GROUP(groupId)
            else -> when (filter.lowercase()) {
                GALLERY_ALL -> AiImageGalleryManager.GalleryFilter.ALL
                GALLERY_TEMPORARY -> AiImageGalleryManager.GalleryFilter.TEMPORARY
                GALLERY_FAVORITE -> AiImageGalleryManager.GalleryFilter.FAVORITE
                GALLERY_GROUP -> AiImageGalleryManager.GalleryFilter.GROUP(
                    AiImageGalleryManager.DEFAULT_GROUP_ID
                )

                GALLERY_SEARCH -> throw IllegalArgumentException("filter=search 需同时提供 keyword")
                else -> throw IllegalArgumentException(
                    "未知画廊筛选：$filter（支持 all/temporary/favorite/group/search）"
                )
            }
        }
        val all = AiImageGalleryManager.listImages(galleryFilter)
        val picked = if (limit > 0) all.take(limit) else all
        mapOf(
            "filter" to filter,
            "total" to all.size,
            "returned" to picked.size,
            "groups" to AiImageGalleryManager.listGroups().map {
                mapOf("id" to it.id, "name" to it.name, "sortOrder" to it.sortOrder)
            },
            "images" to picked.map {
                mapOf(
                    "id" to it.id,
                    "name" to it.name,
                    "prompt" to it.prompt,
                    "providerName" to it.providerName,
                    "model" to it.model,
                    "localPath" to it.localPath,
                    "favorite" to it.favorite,
                    "groupId" to it.groupId,
                    "bookName" to it.bookName,
                    "chapterTitle" to it.chapterTitle,
                    "characterName" to it.characterName,
                    "sourceType" to it.sourceType,
                    "createdAt" to it.createdAt,
                )
            },
        )
    }
}