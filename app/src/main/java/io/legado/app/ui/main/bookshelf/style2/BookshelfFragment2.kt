package io.legado.app.ui.main.bookshelf.style2

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
import androidx.lifecycle.lifecycleScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentBookshelf2Binding
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.info.BookInfoNavigator
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.BookshelfScreen
import io.legado.app.ui.main.bookshelf.BookshelfSnapshotStore
import io.legado.app.ui.main.bookshelf.sortedByBook
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.MainTopBarView
import io.legado.app.utils.observeEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.help.book.isVideo
import io.legado.app.help.video.VideoPlaylistHolder
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookshelfFragment2() : BaseBookshelfFragment(R.layout.fragment_bookshelf2) {

    companion object {
        /** 快照键的样式前缀（style2 = 平铺分组头 + 书） */
        private const val STYLE_KEY = "style2"
    }

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
    private var topScrollTrigger by mutableLongStateOf(0L)
    private var booksJob: Job? = null

    // 受控布局配置（config-needs-restart-fix AD-04/实锤2）：
    // style2 原先零事件监听，配置变更完全无响应；现补 REFRESH+STRUCTURE 双监听（对齐 archive Fragment2:607-615）
    private var shelfLayout by mutableIntStateOf(0)
    private var shelfShowBookname by mutableIntStateOf(1)
    private var shelfListItemStyle by mutableIntStateOf(0)
    private var shelfIntroLines by mutableIntStateOf(2)
    private var shelfMargin by mutableIntStateOf(12)
    private var shelfShowUnread by mutableStateOf(false)
    private var shelfShowReadProgress by mutableStateOf(false)
    private var shelfShowLastUpdateTime by mutableStateOf(false)

    private fun refreshShelfRenderConfig() {
        shelfMargin = AppConfig.bookshelfMargin
        shelfListItemStyle = AppConfig.bookshelfListItemStyle
        shelfIntroLines = AppConfig.bookshelfListIntroLines
        shelfShowUnread = AppConfig.showUnread
        shelfShowReadProgress = AppConfig.showBookshelfReadProgress
        shelfShowLastUpdateTime = AppConfig.showLastUpdateTime
        shelfShowBookname = AppConfig.showBookname
        shelfLayout = AppConfig.bookshelfLayout.coerceIn(0, 6)
    }

    private fun rebuildBookshelfContent() {
        refreshShelfRenderConfig()
        upConnect()
    }

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
            layout = shelfLayout,
            showBookname = shelfShowBookname,
            listItemStyle = shelfListItemStyle,
            introLines = shelfIntroLines,
            margin = shelfMargin,
            showUnread = shelfShowUnread,
            showReadProgress = shelfShowReadProgress,
            showLastUpdateTime = shelfShowLastUpdateTime,
            // 优化 3（F1 空态操作化）：空书架引导动作（既有菜单同源流程）
            emptyPrimaryAction = emptyPrimaryAction(),
            emptySecondaryActions = emptySecondaryActions(),
            // 优化 5（F5 更新目录页内任务条）：进行中可取消，结束态保留结果回执
            taskState = upTocTaskState,
            taskText = upTocTaskText,
            onTaskCancel = { activityViewModel.cancelUpToc() },
            onRefresh = {
                // 对齐 archive：转圈已由 SwipeRefreshContainer 触发即收，
                // 这里仅触发后台目录更新，列表由 DB flow 静默刷新
                activityViewModel.upToc(currentBooks, onlyUpdateRead)
            },
            onRetry = { upConnect() },
            onGroupSelected = { onGroupSelected(it) },
            onGroupLongClick = { showDialogFragment(GroupEditDialog(it)) },
            onBookClick = { book ->
                if (book.isVideo) {
                    // video-playlist-continuity：书架视频书整表注入（跨影片续播，followup F1 恢复）
                    val videoList = currentBooks.filter { it.isVideo }.map { it.toSearchBook() }
                    val idx = videoList.indexOfFirst { it.bookUrl == book.bookUrl }
                    if (idx >= 0) {
                        VideoPlaylistHolder.set(videoList, idx)
                    }
                }
                startActivityForBook(book)
            },
            onBookLongClick = { book ->
                BookInfoNavigator.open(requireContext(), book)
            },
        )
    }

    override val topBar: MainTopBarView get() = binding.topBar

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        // K7 存量迁移（先于字段重读与首帧组合，幂等）
        migrateLegacyShowBookname()
        refreshShelfRenderConfig()
        initComposeTopBar()
        initUpTocTaskBar()
        initBookGroupData()
        initBooksData()
        // REFRESH + STRUCTURE 双监听（对齐 archive Fragment2:607-615，config-needs-restart-fix 实锤 2）
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            refreshShelfRenderConfig()
        }
        observeEvent<String>(EventBus.BOOKSHELF_STRUCTURE_CHANGED) {
            rebuildBookshelfContent()
        }
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
        val sortType = AppConfig.getBookSortByGroupId(currentGroupId)
        // Q6（R 批 §3.1.5）快照优先：先用上次渲染结果填列表（切分组/冷启动首帧不白屏），
        // 随后 DB flow 覆盖真值并回写快照；键由分组/排序/渲染配置/分组签名派生 ⇒ 任一变更即失效回源
        val snapshotKey = BookshelfSnapshotStore.buildKey(
            style = STYLE_KEY,
            groupId = currentGroupId,
            sort = sortType,
            tagFilter = "",
            groups = groupList
        )
        BookshelfSnapshotStore.read(snapshotKey)?.let {
            currentBooks = it
            loading = false
        }
        booksJob = lifecycleScope.launch {
            try {
                appDb.bookDao.flowByGroup(currentGroupId)
                    .map { list -> list.sortedByBook(sortType) }
                    .collect {
                        currentBooks = it
                        loading = false
                        // 快照回写走 IO：flow 可能高频发射（如批量更新目录），避免主线程反复写盘
                        withContext(Dispatchers.IO) {
                            BookshelfSnapshotStore.save(snapshotKey, it)
                        }
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