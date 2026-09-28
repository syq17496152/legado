package io.legado.app.ui.scene

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.attachComposeContent
import io.legado.app.base.composeShell
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.SceneBookmark
import io.legado.app.help.book.SceneBookmarkHelper
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.image.ImageGalleryActivity
import io.legado.app.ui.image.ImagePlay
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * W8 9.4（REQ-32）：**名场面书签库页宿主**（纯 Compose 页，`composeShell` 合成壳 + `attachComposeContent`）。
 *
 * 双入口：
 * ① 「我的 → 工具 → 名场面书签」= 全量视图（无 extra）；
 * ② 阅读菜单「本书名场面」= 按书过滤视图（[bookIntent] 传 `bookUrl`，`bookName` 仅用于标题）。
 *
 * **跳回路由（REQ-32 验收「跳回位置精确」）**：
 *  · 文字（[SceneBookmarkHelper.KIND_TEXT]）→ `ReadBookActivity`（`index` + `chapterPos`，精确到段落）；
 *  · 漫画（[SceneBookmarkHelper.KIND_MANGA]）→ `ReadMangaActivity`（经 [startActivityForBook] 自动选页，
 *    `index` 生效；**已知上限**：`ReadMangaViewModel` 只读 `bookUrl`，页内定位取书内进度 ⇒ 不保证落在原页）；
 *  · 图片订阅（[SceneBookmarkHelper.KIND_IMAGE]）→ 重建 `ImagePlay` 取图上下文后开 `ImageGalleryActivity`
 *    （与 `ReadRss.readNoHtml` 同口径：文章链接是取图的必要输入，故锚点里一并存 `articleLink`）。
 */
class SceneBookmarkActivity : BaseActivity<ViewBinding>() {

    override val binding: ViewBinding by lazy { composeShell(this) }

    private var bookUrl: String? = null
    private var bookName: String? = null

    private var groups by mutableStateOf(listOf<SceneBookmarkHelper.BookSceneGroup>())

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        bookUrl = intent.getStringExtra(EXTRA_BOOK_URL)?.takeIf { it.isNotBlank() }
        bookName = intent.getStringExtra(EXTRA_BOOK_NAME)?.takeIf { it.isNotBlank() }
        binding.root.attachComposeContent {
            LegadoTheme {
                SceneBookmarkScreen(
                    groups = groups,
                    bookFilterName = bookName,
                    onBack = { finish() },
                    onOpen = ::openBookmark,
                    onRegenerate = ::regenerateDesc,
                    onDelete = ::confirmDelete,
                    onClearBook = ::confirmClearBook
                )
            }
        }
        observeData()
    }

    private fun observeData() {
        val flow = bookUrl?.let { SceneBookmarkHelper.flowByBook(it) }
            ?: SceneBookmarkHelper.flowAll()
        lifecycleScope.launch {
            flow.catch {
                AppLog.put("名场面书签库获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                groups = SceneBookmarkHelper.groupByBook(it)
            }
        }
    }

    // ------------------------------------------------------------ 跳回

    private fun openBookmark(bookmark: SceneBookmark) {
        when (bookmark.contentKind) {
            SceneBookmarkHelper.KIND_IMAGE -> openImageScene(bookmark)
            else -> openBookScene(bookmark)
        }
    }

    /** 文字/漫画：按 bookUrl 取书后走统一入口（漫画页由 `isImage && showMangaUi` 自动选中） */
    private fun openBookScene(bookmark: SceneBookmark) {
        lifecycleScope.launch {
            val book = withContext(IO) { appDb.bookDao.getBook(bookmark.bookUrl) }
            if (book == null) {
                toastOnUi(R.string.scene_bookmark_book_missing)
                return@launch
            }
            startActivityForBook(book) {
                putExtra("index", bookmark.chapterIndex)
                if (bookmark.contentKind == SceneBookmarkHelper.KIND_TEXT) {
                    putExtra("chapterPos", SceneBookmarkHelper.chapterPosOf(bookmark.anchor) ?: 0)
                }
            }
        }
    }

    /**
     * 图片订阅：重建 `ImagePlay` 后打开图片浏览页。
     *
     * 必须预置「订阅源 + 单篇文章（含 link）」，否则 `ImageGalleryActivity` 的画布拿不到取图上下文
     * （它只从 intent 读 `title`，来源与文章全部来自 `ImagePlay` 单例）。
     */
    private fun openImageScene(bookmark: SceneBookmark) {
        val articleLink = SceneBookmarkHelper.articleLinkOf(bookmark.anchor)
        if (articleLink.isNullOrBlank()) {
            toastOnUi(R.string.scene_bookmark_image_missing)
            return
        }
        lifecycleScope.launch {
            val source = withContext(IO) { appDb.rssSourceDao.getByKey(bookmark.bookUrl) }
            if (source == null) {
                toastOnUi(R.string.scene_bookmark_book_missing)
                return@launch
            }
            ImagePlay.clearImageCanvasState()
            ImagePlay.rssArticles = listOf(
                RssArticle(
                    origin = bookmark.bookUrl,
                    title = bookmark.chapterName,
                    link = articleLink
                )
            )
            ImagePlay.rssArticleIndex = 0
            ImagePlay.rssSource = source
            ImagePlay.rssSortName = null
            ImagePlay.rssSortUrl = null
            ImagePlay.rssNextPageUrl = null
            ImagePlay.rssArticlePage = 1
            ImagePlay.rssArticlesHasMore = false
            startActivity<ImageGalleryActivity> {
                putExtra("sourceKey", bookmark.bookUrl)
                putExtra("record", articleLink)
                putExtra("title", bookmark.chapterName)
            }
        }
    }

    // ------------------------------------------------------------ 管理

    /** 重新生成 AI 描述（未配置 AI 时回落原文片段，不弹网络错误） */
    private fun regenerateDesc(bookmark: SceneBookmark) {
        toastOnUi(R.string.scene_bookmark_regenerating)
        lifecycleScope.launch {
            val result = SceneBookmarkHelper.describe(bookmark)
            toastOnUi(getString(R.string.scene_bookmark_desc_ready, result.desc))
        }
    }

    private fun confirmDelete(bookmark: SceneBookmark) {
        alert(
            title = getString(R.string.scene_bookmark_delete),
            message = bookmark.desc.ifBlank { bookmark.chapterName }
        ) {
            positiveButton(R.string.ok) {
                SceneBookmarkHelper.delete(bookmark.id) {
                    toastOnUi(R.string.scene_bookmark_deleted)
                }
            }
            cancelButton()
        }
    }

    private fun confirmClearBook(group: SceneBookmarkHelper.BookSceneGroup) {
        val name = group.bookName.ifBlank { getString(R.string.scene_bookmark_untitled) }
        alert(
            title = getString(R.string.scene_bookmark_clear_book),
            message = getString(R.string.scene_bookmark_clear_book_confirm, name)
        ) {
            positiveButton(R.string.ok) {
                SceneBookmarkHelper.deleteByBook(group.bookUrl) {
                    toastOnUi(R.string.scene_bookmark_deleted)
                }
            }
            cancelButton()
        }
    }

    companion object {
        private const val EXTRA_BOOK_URL = "bookUrl"
        private const val EXTRA_BOOK_NAME = "bookName"
        private const val EXTRA_BOOK_AUTHOR = "bookAuthor"

        /** 阅读菜单「本书名场面」入口：按书过滤视图 */
        fun bookIntent(
            context: Context,
            bookUrl: String,
            bookName: String,
            bookAuthor: String
        ): Intent = Intent(context, SceneBookmarkActivity::class.java)
            .putExtra(EXTRA_BOOK_URL, bookUrl)
            .putExtra(EXTRA_BOOK_NAME, bookName)
            .putExtra(EXTRA_BOOK_AUTHOR, bookAuthor)
    }
}
