package io.legado.app.help.book

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.SceneBookmark
import io.legado.app.help.ai.AiSceneDescService
import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * W8 / REQ-32（AD-12）：**名场面书签统一业务入口**。
 *
 * 三条内容路径（文字 `ReadBookActivity` / 漫画 `ReadMangaActivity` / 图片订阅 `ImageGalleryActivity`）
 * 共用本入口，避免每页各写一套装锚点 / 落库 / 描述生成逻辑。
 *
 * 职责边界：
 *  · **锚点 JSON 构造与解析**（[textAnchor] / [mangaAnchor] / [imageAnchor] 与三个 `xxxOf`）；
 *  · **落库 + 异步描述回写**（[add] / [addAndDescribe] / [describe]）；
 *  · **读路径与按书聚合**（[flowAll] / [flowByBook] / [groupByBook]）。
 *
 * ⚠ 本入口**不持有 UI 状态**：Toast / "生成描述中…" 悬浮提示由调用页给出（回调 [onSaved] / [onDesc]）。
 */
object SceneBookmarkHelper {

    /** 文字路径（阅读页划词 / 阅读菜单） */
    const val KIND_TEXT = 0

    /** 漫画路径（长按菜单） */
    const val KIND_MANGA = 1

    /** 图片订阅路径（画廊菜单） */
    const val KIND_IMAGE = 2

    /** 锚点键：文字路径的段落位置（对应 `ReadBook` 的 `chapterPos`） */
    const val ANCHOR_CHAPTER_POS = "chapterPos"

    /** 锚点键：漫画路径的页下标（同章节内的图片序号） */
    const val ANCHOR_PAGE_INDEX = "pageIndex"

    /** 锚点键：图片订阅路径的图片 URL（画廊跳回依据） */
    const val ANCHOR_IMAGE_URL = "imageUrl"

    /** 按书聚合分组（库页 `CollapseSectionHeader` 数据源） */
    data class BookSceneGroup(
        val bookUrl: String,
        val bookName: String,
        val bookAuthor: String,
        val items: List<SceneBookmark>
    )

    // ---------------------------------------------------------------- 锚点

    /** 文字路径锚点：`{"chapterPos":N}` */
    fun textAnchor(chapterPos: Int): String =
        JSONObject().put(ANCHOR_CHAPTER_POS, chapterPos).toString()

    /** 漫画路径锚点：`{"pageIndex":N}` */
    fun mangaAnchor(pageIndex: Int): String =
        JSONObject().put(ANCHOR_PAGE_INDEX, pageIndex).toString()

    /** 图片订阅路径锚点：`{"imageUrl":"..."}` */
    fun imageAnchor(imageUrl: String): String =
        JSONObject().put(ANCHOR_IMAGE_URL, imageUrl).toString()

    /** 读回文字路径段落位置；锚点缺失/损坏返回 null（调用方回退到章节首段） */
    fun chapterPosOf(anchor: String): Int? = optInt(anchor, ANCHOR_CHAPTER_POS)

    /** 读回漫画页下标；缺失返回 null */
    fun pageIndexOf(anchor: String): Int? = optInt(anchor, ANCHOR_PAGE_INDEX)

    /** 读回图片 URL；缺失返回 null */
    fun imageUrlOf(anchor: String): String? {
        val json = parseAnchor(anchor) ?: return null
        return json.optString(ANCHOR_IMAGE_URL).takeIf { it.isNotBlank() }
    }

    private fun optInt(anchor: String, key: String): Int? {
        val json = parseAnchor(anchor) ?: return null
        if (!json.has(key)) return null
        return json.optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
    }

    private fun parseAnchor(anchor: String): JSONObject? {
        if (anchor.isBlank()) return null
        return kotlin.runCatching { JSONObject(anchor) }.getOrNull()
    }

    // ---------------------------------------------------------------- 写

    /**
     * 落库并**异步**生成 AI 描述（非阻塞）。
     *
     * @param onSaved 落库成功回调（返回自增 id），调用方可据此给出 toast
     * @param onDesc 描述回写完成回调（AI 未配置时不会触发，此时 desc 已是降级片段）
     */
    fun addAndDescribe(
        bookmark: SceneBookmark,
        onSaved: (Long) -> Unit = {},
        onDesc: (AiSceneDescService.SceneDesc) -> Unit = {}
    ) {
        if (bookmark.desc.isBlank()) {
            bookmark.desc = AiSceneDescService.fallbackDesc(bookmark)
        }
        Coroutine.async {
            appDb.sceneBookmarkDao.insert(bookmark)
        }.onSuccess { id ->
            bookmark.id = id
            onSaved(id)
            if (AiSceneDescService.isAvailable()) {
                describeAsync(bookmark, onDesc)
            }
        }.onError {
            AppLog.put("名场面书签保存失败", it)
        }
    }

    /** 仅落库（**不触发 AI**；库页「重新生成」可后续补生成） */
    fun add(bookmark: SceneBookmark, onSaved: (Long) -> Unit = {}) {
        if (bookmark.desc.isBlank()) {
            bookmark.desc = AiSceneDescService.fallbackDesc(bookmark)
        }
        Coroutine.async {
            appDb.sceneBookmarkDao.insert(bookmark)
        }.onSuccess { id ->
            bookmark.id = id
            onSaved(id)
        }.onError {
            AppLog.put("名场面书签保存失败", it)
        }
    }

    /**
     * 生成描述并**写回**该行（库页「重新生成」与打标异步回写共用）。
     * AI 不可用时直接返回现有降级描述，不触发网络。
     */
    suspend fun describe(bookmark: SceneBookmark): AiSceneDescService.SceneDesc {
        if (!AiSceneDescService.isAvailable()) {
            return AiSceneDescService.SceneDesc(AiSceneDescService.fallbackDesc(bookmark), emptyList())
        }
        val result = AiSceneDescService.generate(bookmark)
        bookmark.desc = result.desc
        bookmark.tags = tagsToJson(result.tags)
        appDb.sceneBookmarkDao.update(bookmark)
        return result
    }

    /** 删除单项 */
    fun delete(id: Long, onDone: () -> Unit = {}) {
        Coroutine.async {
            appDb.sceneBookmarkDao.deleteById(id)
        }.onSuccess { onDone() }
            .onError { AppLog.put("名场面书签删除失败", it) }
    }

    /** 按书清空 */
    fun deleteByBook(bookUrl: String, onDone: () -> Unit = {}) {
        Coroutine.async {
            appDb.sceneBookmarkDao.deleteByBook(bookUrl)
        }.onSuccess { onDone() }
            .onError { AppLog.put("名场面书签按书清空失败", it) }
    }

    // ---------------------------------------------------------------- 读

    fun flowAll(): Flow<List<SceneBookmark>> = appDb.sceneBookmarkDao.flowAll()

    fun flowByBook(bookUrl: String): Flow<List<SceneBookmark>> =
        appDb.sceneBookmarkDao.flowByBook(bookUrl)

    fun count(): Int = appDb.sceneBookmarkDao.count()

    /**
     * 按书聚合（**纯函数**，便于单测）：书序取入参首次出现顺序（DAO 已按时间倒序 ⇒ 最近打标的书在前），
     * 组内按 `chapterIndex, time` 排序（与 `flowByBook` 同口径，保证回看顺序稳定）。
     */
    fun groupByBook(items: List<SceneBookmark>): List<BookSceneGroup> {
        return items
            .groupBy { it.bookUrl }
            .map { (bookUrl, group) ->
                val sorted = group.sortedWith(compareBy({ it.chapterIndex }, { it.time }))
                val head = group.first()
                BookSceneGroup(
                    bookUrl = bookUrl,
                    bookName = head.bookName,
                    bookAuthor = head.bookAuthor,
                    items = sorted
                )
            }
    }

    /** 标签列表 → 存储用 JSON 数组字符串（空列表存 `[]`） */
    fun tagsToJson(tags: List<String>): String {
        val array = JSONArray()
        tags.forEach { array.put(it) }
        return array.toString()
    }

    /** 存储用 JSON 数组字符串 → 标签列表（损坏/空即返回空列表） */
    fun tagsFromJson(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val array = kotlin.runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length())
            .map { array.optString(it).trim() }
            .filter { it.isNotBlank() }
    }

    private fun describeAsync(
        bookmark: SceneBookmark,
        onDesc: (AiSceneDescService.SceneDesc) -> Unit
    ) {
        Coroutine.async {
            describe(bookmark)
        }.onSuccess { onDesc(it) }
            .onError { AppLog.put("名场面书签描述写回失败", it) }
    }
}
