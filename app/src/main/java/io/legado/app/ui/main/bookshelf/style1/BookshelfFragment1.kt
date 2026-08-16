package io.legado.app.ui.main.bookshelf.style1

import android.os.Bundle
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView

import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.databinding.FragmentBookshelf1Binding
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.BookshelfScreen
import io.legado.app.ui.main.bookshelf.sortedByBook
import io.legado.app.ui.theme.LegadoTheme
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
                    putExtra("name", book.name)
                    putExtra("author", book.author)
                }
            },
        )
    }

    override val composeTopBar: ComposeView get() = binding.composeTopBar

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initComposeTopBar()
        initBookGroupData()
        binding.viewPagerBookshelf.setContent {
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

    override fun onDestroyView() {
        booksJob?.cancel()
        super.onDestroyView()
    }
}
