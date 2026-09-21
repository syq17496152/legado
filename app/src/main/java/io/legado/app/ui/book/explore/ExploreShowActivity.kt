package io.legado.app.ui.book.explore

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.PreferKey
import io.legado.app.help.video.VideoPlaylistHolder
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ActivityExploreShowBinding
import io.legado.app.help.webView.WebViewPool
import io.legado.app.ui.book.SearchBookOpenHelper
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppMenuSheet
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.TopBarActionRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Preview
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.stableSearchBookKey
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExploreShowActivity : VMBaseActivity<ActivityExploreShowBinding, ExploreShowViewModel>() {

    override val binding by viewBinding(ActivityExploreShowBinding::inflate)
    override val viewModel by viewModels<ExploreShowViewModel>()

    private val composeBooks = mutableStateListOf<SearchBook>()
    private val composeBottomLoading = mutableStateOf(false)
    private val composeTopLoading = mutableStateOf(false)
    private val composeHasMore = mutableStateOf(true)
    private val composeHasPrevious = mutableStateOf(false)
    private val composeBottomError = mutableStateOf<String?>(null)
    private val composeTopError = mutableStateOf<String?>(null)
    private val composeScrollToTopSignal = mutableIntStateOf(0)
    private val composeKeepPositionAfterPrependSignal = mutableIntStateOf(0)
    private val composePrependedItemCount = mutableIntStateOf(0)
    private val bookshelfTick = mutableIntStateOf(0)
    private var oldPage = -1
    private var isClearAll = false
    // F46：行尾 ⋮ 的上下文菜单（放入书架 / 预览 / 打开详情）
    private val moreSheetBook = mutableStateOf<SearchBook?>(null)
    /** F46：⋮ 菜单选「预览」的一次性请求（消费后由列表屏复位） */
    private val previewRequestBook = mutableStateOf<SearchBook?>(null)
    // F48：一次性预览提示（首次进入分类时展示，关闭或长按成功后不再出现）
    private val previewHintVisible = mutableStateOf(false)

    // W7.2：原 moreMenuPopup 随 ModernActionPopup 链删除（跳页入口迁 GlassTopAppBar 溢出菜单）

    /** video-regression-fix-0906 AD-05：页码跳页（原三横线 pageButton 逻辑收口到三点菜单） */
    private fun showPagePicker() {
        val page = viewModel.pageLiveData.value ?: 1
        NumberPickerDialog(this@ExploreShowActivity)
            .setTitle(getString(R.string.change_page))
            .setMaxValue(999)
            .setMinValue(1)
            .setValue(page)
            .show { targetPage ->
                if (page != targetPage) {
                    oldPage = targetPage
                    viewModel.skipPage(targetPage)
                    isClearAll = true
                    composeBooks.clear()
                    composeHasMore.value = true
                    composeHasPrevious.value = targetPage > 1
                    composeBottomError.value = null
                    composeTopError.value = null
                    composeTopLoading.value = false
                    scrollToBottom(forceLoad = true)
                }
            }
    }

    // W7.2（Delta 3→1）：顶栏归一 GlassTopAppBar（布局 compose_top_bar 直挂），跳页入口收敛至溢出菜单
    private fun initTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                GlassTopAppBar(
                    title = intent.getStringExtra("exploreName").orEmpty(),
                    navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                    onNavClick = { finish() },
                    actions = {
                        TopBarActionRow(
                            listOf(
                                MenuAction(title = getString(R.string.menu_page, viewModel.pageLiveData.value ?: 1)) {
                                    showPagePicker()
                                }
                            )
                        )
                    }
                )
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        // F48：仅首次（或从未关闭过提示）展示「长按可预览」——存量用户升级后看一次，关闭即永久置位
        previewHintVisible.value = !getPrefBoolean(PreferKey.exploreShowPreviewHintShown)
        initTopBar()
        initComposeList()
        viewModel.booksData.observe(this) { upData(it) }
        viewModel.addBooksData.observe(this) { upDataTop(it) }
        viewModel.errorLiveData.observe(this) {
            composeBottomLoading.value = false
            composeBottomError.value = it
        }
        viewModel.errorTopLiveData.observe(this) {
            composeTopLoading.value = false
            composeTopError.value = it
            composeHasPrevious.value = oldPage > 1
        }
        viewModel.upAdapterLiveData.observe(this) {
            bookshelfTick.intValue++
        }
        viewModel.initData(intent)
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    private fun initComposeList() {
        composeBottomLoading.value = true
        binding.composeList.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeList.setContent {
            LegadoComposeTheme {
                ExploreShowComposeScreen(
                    books = composeBooks,
                    isLoading = composeBottomLoading.value,
                    isLoadingPrevious = composeTopLoading.value,
                    hasMore = composeHasMore.value,
                    hasPrevious = composeHasPrevious.value,
                    errorMessage = composeBottomError.value,
                    previousErrorMessage = composeTopError.value,
                    scrollToTopSignal = composeScrollToTopSignal.intValue,
                    keepPositionAfterPrependSignal = composeKeepPositionAfterPrependSignal.intValue,
                    prependedItemCount = composePrependedItemCount.intValue,
                    bookshelfTick = bookshelfTick.intValue,
                    isInBookshelf = { book -> isInBookshelf(book) },
                    lifecycle = lifecycle,
                    onBookClick = { book -> showBookInfo(book) },
                    onBookMore = { book -> moreSheetBook.value = book },
                    showPreviewHint = previewHintVisible.value,
                    onPreviewHintDismiss = { dismissPreviewHint() },
                    previewRequest = previewRequestBook.value,
                    onPreviewRequestHandled = { previewRequestBook.value = null },
                    onLoadMore = { scrollToBottom(forceLoad = composeBottomError.value != null) },
                    onLoadPrevious = { scrollToTop(forceLoad = composeTopError.value != null) }
                )
                moreSheetBook.value?.let { book ->
                    AppMenuSheet(
                        title = book.name,
                        actions = buildBookActions(book),
                        onDismiss = { moreSheetBook.value = null }
                    )
                }
            }
        }
    }

    /**
     * F46：列表项「更多动作」菜单。
     *
     * 「放入书架」置于首位（发现页最高价值路径是「看到中意的书 → 加入书架」，原本要进详情页才有的动作）；
     * 已在书架时不再显示该项（避免无意义点击），保留预览与打开详情。
     */
    private fun buildBookActions(book: SearchBook): List<MenuAction> = buildList {
        if (!isInBookshelf(book)) {
            add(MenuAction(Icons.Default.Add, getString(R.string.add_to_bookshelf)) {
                moreSheetBook.value = null
                addToBookshelf(book)
            })
        }
        add(MenuAction(Icons.Default.Preview, getString(R.string.preview)) {
            moreSheetBook.value = null
            previewRequestBook.value = book
        })
        add(MenuAction(Icons.Default.Info, getString(R.string.book_info)) {
            moreSheetBook.value = null
            showBookInfo(book)
        })
    }

    /** F46：就地放入书架（口径与 BookInfoViewModel.addToBookshelf 一致，进度不归零） */
    private fun addToBookshelf(book: SearchBook) {
        viewModel.addToBookshelf(book) {
            toastOnUi(getString(R.string.add_to_bookshelf_success, book.name))
        }
    }

    /** F48：关闭一次性提示并落位（本页内不再展示） */
    private fun dismissPreviewHint() {
        previewHintVisible.value = false
        putPrefBoolean(PreferKey.exploreShowPreviewHintShown, true)
    }

    private fun scrollToBottom(forceLoad: Boolean = false) {
        val canLoad = composeHasMore.value && !composeBottomLoading.value && !composeTopLoading.value
        if (!canLoad && !forceLoad) return
        composeHasMore.value = true
        composeBottomLoading.value = true
        composeBottomError.value = null
        viewModel.explore()
    }

    private fun scrollToTop(forceLoad: Boolean = false) {
        if (composeBottomLoading.value || composeTopLoading.value) return
        val targetPage = if (forceLoad) oldPage else oldPage - 1
        if (targetPage < 1) return
        oldPage = targetPage
        composeTopLoading.value = true
        composeTopError.value = null
        composeHasPrevious.value = targetPage > 1
        viewModel.explore(targetPage)
    }

    private fun upData(books: List<SearchBook>) {
        val oldSize = composeBooks.size
        composeBottomLoading.value = false
        composeBottomError.value = null
        if (books.isEmpty() && oldSize == 0) {
            composeHasMore.value = false
            replaceComposeBooks(emptyList())
            isClearAll = false
            return
        }
        composeHasMore.value = isClearAll || books.size > oldSize
        replaceComposeBooks(books)
        if (isClearAll) {
            composeScrollToTopSignal.intValue++
            isClearAll = false
        }
    }

    private fun upDataTop(books: List<SearchBook>) {
        val oldSize = composeBooks.size
        val oldFirstKey = composeBooks.firstOrNull()?.stableSearchBookKey()
        composeTopLoading.value = false
        composeTopError.value = null
        replaceComposeBooks(books, resetPrepend = false)
        val prependedCount = oldFirstKey?.let { key ->
            books.indexOfFirst { it.stableSearchBookKey() == key }
        }?.takeIf { it > 0 } ?: (books.size - oldSize).coerceAtLeast(0)
        if (prependedCount > 0) {
            composePrependedItemCount.intValue = prependedCount
            composeKeepPositionAfterPrependSignal.intValue++
        } else {
            composePrependedItemCount.intValue = 0
        }
        composeHasPrevious.value = oldPage > 1
    }

    private fun replaceComposeBooks(books: List<SearchBook>, resetPrepend: Boolean = true) {
        composeBooks.clear()
        composeBooks.addAll(books)
        if (resetPrepend) {
            composePrependedItemCount.intValue = 0
        }
    }

    private fun isInBookshelf(book: SearchBook): Boolean {
        return viewModel.isInBookShelf(book)
    }

    private fun showBookInfo(book: SearchBook) {
        lifecycleScope.launch {
            val isVideo = withContext(IO) {
                SearchBookOpenHelper.isVideoResult(book, viewModel.sourceTypeHint())
            }
            if (isVideo) {
                // video-playlist-continuity：发现分类列表整表注入（跨影片续播；followup F1 恢复被 revert 移除的注入）
                // 统一 isVideo 过滤（红队 R2-2：防混排分类上滑落入文本书）
                val videoList = composeBooks.filter { SearchBookOpenHelper.isVideoResult(it, viewModel.sourceTypeHint()) }
                val idx = videoList.indexOfFirst { it.bookUrl == book.bookUrl }
                if (idx >= 0) {
                    VideoPlaylistHolder.set(videoList, idx)
                }
            }
            SearchBookOpenHelper.open(this@ExploreShowActivity, book, isVideo)
        }
    }

    override fun onPause() {
        WebViewPool.scheduleDestroyScope(WebViewPool.Scope.DISCOVERY)
        super.onPause()
    }

    override fun onDestroy() {
        WebViewPool.destroyScope(WebViewPool.Scope.DISCOVERY)
        super.onDestroy()
    }
}