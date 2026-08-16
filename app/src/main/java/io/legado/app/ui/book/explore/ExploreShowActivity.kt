package io.legado.app.ui.book.explore

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ActivityExploreShowBinding
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 发现列表分页页（L-B14，S2 列表族 + 分页加载范式）。
 *
 * Compose 内容区（[ExploreShowScreen]）桥接：保留原分页加载（滚动到底下一页 /
 * 滚动到顶上一页）、跳页（NumberPicker 1-999）、错误重试业务逻辑，
 * UI 全部收敛到受控组件（GlassTopAppBar / EmptyStatePlaceholder / 骨架屏 / 弹窗族）。
 */
class ExploreShowActivity : VMBaseActivity<ActivityExploreShowBinding, ExploreShowViewModel>() {
    override val binding by viewBinding(ActivityExploreShowBinding::inflate)
    override val viewModel by viewModels<ExploreShowViewModel>()

    // Compose 桥接状态
    private var composeItems by mutableStateOf(listOf<ExploreShowDisplayItem>())
    private var currentPage by mutableStateOf(1)
    private var topLoading by mutableStateOf(false)
    private var bottomLoading by mutableStateOf(true)
    private var hasMore by mutableStateOf(true)
    private var loadMoreError by mutableStateOf<String?>(null)
    private var jumpFlag by mutableStateOf(0)
    private var oldPage = -1

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeHost()
        observeViewModel()
        viewModel.initData(intent)
    }

    private fun initComposeHost() {
        binding.composeHost.setContent {
            LegadoTheme {
                ExploreShowScreen(
                    items = composeItems,
                    title = intent.getStringExtra("exploreName") ?: getString(R.string.discovery),
                    currentPage = currentPage,
                    topLoading = topLoading,
                    bottomLoading = bottomLoading,
                    hasMore = hasMore,
                    canLoadTop = oldPage > 1,
                    emptyMessage = getString(R.string.empty),
                    loadMoreError = loadMoreError,
                    jumpFlag = jumpFlag,
                    onBack = { finish() },
                    onLoadMore = { scrollToBottom() },
                    onLoadTop = { scrollToTop() },
                    onRetryLoadMore = { scrollToBottom(true) },
                    onItemClick = { index ->
                        composeItems.getOrNull(index)?.let { item ->
                            startActivity<BookInfoActivity> {
                                putExtra("name", item.book.name)
                                putExtra("author", item.book.author)
                                putExtra("bookUrl", item.book.bookUrl)
                            }
                        }
                    },
                    onPageChange = { page -> changePage(page) }
                )
            }
        }
    }

    private fun observeViewModel() {
        viewModel.booksData.observe(this) { books -> upData(books) }
        viewModel.addBooksData.observe(this) { books -> upDataTop(books) }
        viewModel.errorLiveData.observe(this) {
            bottomLoading = false
            loadMoreError = it
        }
        viewModel.errorTopLiveData.observe(this) {
            topLoading = false
            loadMoreError = it
        }
        viewModel.upAdapterLiveData.observe(this) {
            composeItems = composeItems.map { item ->
                item.copy(isInBookshelf = viewModel.isInBookShelf(item.book))
            }
        }
        viewModel.pageLiveData.observe(this) { currentPage = it }
    }

    /** 滚动到底 -> 加载下一页 */
    private fun scrollToBottom(forceLoad: Boolean = false) {
        if ((hasMore && !bottomLoading && !topLoading) || forceLoad) {
            bottomLoading = true
            loadMoreError = null
            viewModel.explore()
        }
    }

    /** 滚动到顶 -> 加载上一页 */
    private fun scrollToTop(forceLoad: Boolean = false) {
        if ((oldPage > 1 && !bottomLoading && !topLoading) || forceLoad) {
            topLoading = true
            loadMoreError = null
            oldPage--
            viewModel.explore(oldPage)
        }
    }

    /** 跳页：清空当前列表并强制加载指定页 */
    private fun changePage(page: Int) {
        oldPage = page
        viewModel.skipPage(page)
        composeItems = emptyList()
        jumpFlag++
        viewModel.explore()
    }

    private fun upData(books: List<SearchBook>) {
        bottomLoading = false
        if (books.isEmpty() && composeItems.isEmpty()) {
            hasMore = false
        } else if (composeItems.size == books.size) {
            hasMore = false
        } else {
            hasMore = true
            composeItems = books.map { it.toDisplayItem() }
        }
    }

    private fun upDataTop(books: List<SearchBook>) {
        topLoading = false
        composeItems = books.map { it.toDisplayItem() } + composeItems
    }

    private fun SearchBook.toDisplayItem() = ExploreShowDisplayItem(
        name = name,
        author = author,
        intro = trimIntro(this@ExploreShowActivity),
        kinds = getKindList(),
        latestChapterTitle = latestChapterTitle.orEmpty(),
        isInBookshelf = viewModel.isInBookShelf(this),
        book = this
    )
}
