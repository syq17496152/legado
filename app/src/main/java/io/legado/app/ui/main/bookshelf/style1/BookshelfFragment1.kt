package io.legado.app.ui.main.bookshelf.style1

import android.os.Bundle
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.databinding.FragmentBookshelf1Binding
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.BookshelfScreen
import io.legado.app.ui.main.bookshelf.sortedByBook
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.MainTopBarView
import io.legado.app.utils.observeEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class BookshelfFragment1() : BaseBookshelfFragment(R.layout.fragment_bookshelf1) {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentBookshelf1Binding::bind)

    private var groupList by mutableStateOf(listOf<BookGroup>())
    private var selectedGroupId by mutableLongStateOf(BookGroup.IdAll)
    private var currentBooks by mutableStateOf(listOf<Book>())
    private var loading by mutableStateOf(true)
    private var error by mutableStateOf(false)
    private var topScrollTrigger by mutableLongStateOf(0L)
    private var refreshing by mutableStateOf(false)
    // 顶栏设置版本号：TOP_BAR_CHANGED 时自增，驱动书架分组标签（BookGroupTabs）重组读取最新 TopBarConfig
    private var topBarVersion by mutableIntStateOf(0)
    private var booksJob: Job? = null

    private var bookSort: Int = 0

    override val groupId: Long get() = selectedGroupId

    override val books: List<Book> get() = currentBooks

    override var onlyUpdateRead = false

    @Composable
    private fun BookshelfContent() {
        BookshelfScreen(
            bookGroups = groupList,
            books = currentBooks,
            loading = loading,
            error = error,
            groupId = selectedGroupId,
            isFolder = false,
            topScrollTrigger = topScrollTrigger,
            isRefreshing = refreshing,
            topBarVersion = topBarVersion,
            onRefresh = {
                refreshing = true
                activityViewModel.upToc(currentBooks, onlyUpdateRead)
                lifecycleScope.launch {
                    delay(1000)
                    refreshing = false
                }
            },
            onRetry = { upConnect() },
            onGroupSelected = { onGroupSelected(it) },
            onGroupLongClick = { showDialogFragment(GroupEditDialog(it)) },
            onBookClick = { book -> startActivityForBook(book) },
            onBookLongClick = { book ->
                startActivity<BookInfoActivity> {
                    putExtra("bookUrl", book.bookUrl)
                    putExtra("name", book.name)
                    putExtra("author", book.author)
                    putExtra("origin", book.origin)
                    putExtra("originName", book.originName)
                }
            },
        )
    }

    override val topBar: MainTopBarView get() = binding.topBar

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initComposeTopBar()
        initBookGroupData()
        binding.viewPagerBookshelf.setContent {
            LegadoTheme {
                BookshelfContent()
            }
        }
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        // 顶栏设置变化：MainActivity 只刷新 View 层 MainTopBarView，Compose 书架分组标签需自增版本触发重组
        observeEvent<Boolean>(EventBus.TOP_BAR_CHANGED) {
            if (it == AppConfig.isNightTheme) {
                topBarVersion++
            }
        }
    }

    @Synchronized
    override fun upGroup(data: List<BookGroup>) {
        if (data.isEmpty()) {
            lifecycleScope.launch {
                appDb.bookGroupDao.enableGroup(BookGroup.IdAll)
            }
        } else if (data != groupList) {
            groupList = data
            if (selectedGroupId == BookGroup.IdAll) {
                selectedGroupId = data[0].groupId
            }
            upConnect()
        }
    }

    private fun onGroupSelected(newGroupId: Long) {
        if (selectedGroupId == newGroupId) return
        selectedGroupId = newGroupId
        val group = groupList.firstOrNull { it.groupId == newGroupId }
        onlyUpdateRead = group?.onlyUpdateRead ?: false
        AppConfig.saveTabPosition = groupList.indexOfFirst { it.groupId == newGroupId }
            .coerceAtLeast(0)
        upConnect()
    }

    private fun upConnect() {
        loading = true
        error = false
        booksJob?.cancel()
        booksJob = lifecycleScope.launch {
            try {
                val sortType = AppConfig.getBookSortByGroupId(selectedGroupId)
                bookSort = sortType
                appDb.bookDao.flowByGroup(selectedGroupId)
                    .map { list -> list.sortedByBook(sortType) }
                    .collect {
                        currentBooks = it
                        loading = false
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = true
                loading = false
                AppLog.putDebugWithTag(AppLog.TAG_DATA, "书架分组书籍加载失败", e)
            }
        }
    }

    override fun upSort() {
        upConnect()
    }

    override fun gotoTop() {
        topScrollTrigger++
    }

    fun switchToGroupId(groupId: Long) {
        if (groupList.any { it.groupId == groupId }) {
            onGroupSelected(groupId)
        }
    }

    override fun onDestroyView() {
        booksJob?.cancel()
        super.onDestroyView()
    }
}
