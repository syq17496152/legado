package io.legado.app.ui.main.bookshelf.style2

import android.os.Bundle
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import io.legado.app.R
import io.legado.app.constant.AppLog
import androidx.lifecycle.lifecycleScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentBookshelf2Binding
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.BookshelfScreen
import io.legado.app.ui.main.bookshelf.sortedByBook
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.MainTopBarView
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class BookshelfFragment2() : BaseBookshelfFragment(R.layout.fragment_bookshelf2) {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentBookshelf2Binding::bind)

    private var groupList by mutableStateOf(listOf<BookGroup>())
    private var currentGroupId by mutableLongStateOf(BookGroup.IdRoot)
    private var currentBooks by mutableStateOf(listOf<Book>())
    private var loading by mutableStateOf(true)
    private var error by mutableStateOf(false)
    private var refreshing by mutableStateOf(false)
    private var topScrollTrigger by mutableLongStateOf(0L)
    private var booksJob: Job? = null

    override var groupId: Long
        get() = currentGroupId
        set(value) {
            currentGroupId = value
        }

    override val books: List<Book> get() = currentBooks

    override var onlyUpdateRead = false

    @Composable
    private fun BookshelfContent() {
        BookshelfScreen(
            bookGroups = groupList,
            books = currentBooks,
            loading = loading,
            error = error,
            groupId = currentGroupId,
            isFolder = true,
            topScrollTrigger = topScrollTrigger,
            isRefreshing = refreshing,
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
        initBooksData()
        binding.rvBookshelf.setContent {
            LegadoTheme {
                BookshelfContent()
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
            if (currentGroupId == BookGroup.IdRoot) {
                upTitle()
            }
        }
    }

    private fun onGroupSelected(newGroupId: Long) {
        if (currentGroupId == newGroupId) return
        currentGroupId = newGroupId
        upTitle()
        upConnect()
    }

    private fun upTitle() {
        if (currentGroupId == BookGroup.IdRoot) {
            composeTopBarTitle = getString(R.string.bookshelf)
            onlyUpdateRead = false
        } else {
            groupList.firstOrNull { it.groupId == currentGroupId }?.let {
                composeTopBarTitle = "${getString(R.string.bookshelf)}(${it.groupName})"
                onlyUpdateRead = it.onlyUpdateRead
            }
        }
        binding.topBar.setTitle(composeTopBarTitle)
    }

    private fun upConnect() {
        loading = true
        error = false
        booksJob?.cancel()
        booksJob = lifecycleScope.launch {
            try {
                val sortType = AppConfig.getBookSortByGroupId(currentGroupId)
                appDb.bookDao.flowByGroup(currentGroupId)
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

    private fun initBooksData() {
        upTitle()
        upConnect()
    }

    fun back(): Boolean {
        if (currentGroupId != BookGroup.IdRoot) {
            currentGroupId = BookGroup.IdRoot
            upTitle()
            upConnect()
            return true
        }
        return false
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