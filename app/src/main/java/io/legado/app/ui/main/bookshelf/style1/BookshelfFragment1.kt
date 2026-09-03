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
import io.legado.app.help.book.BookTagHelper
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.info.BookInfoNavigator
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.BookshelfScreen
import io.legado.app.ui.main.bookshelf.sortedByBook
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.MainTopBarView
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.RoundedTagBarView
import io.legado.app.utils.observeEvent
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    // 1.2：刷新复位协程单一 Job 管理（新刷新先 cancel 旧复位协程，防竞态）
    private var refreshResetJob: Job? = null

    // 受控布局配置（config-needs-restart-fix AD-04）：onFragmentCreated 中 migrate 后全量重读，
    // REFRESH/STRUCTURE 事件回调重读传入 BookshelfScreen，替代 Screen 内 remember{AppConfig.x} 快照
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
        // showBookname/layout 属结构类，REFRESH 场景不变；但重读无副作用，保持一致
        shelfShowBookname = AppConfig.showBookname
        shelfLayout = AppConfig.bookshelfLayout.coerceIn(0, 6)
    }

    private fun rebuildBookshelfContent() {
        shelfLayout = AppConfig.bookshelfLayout.coerceIn(0, 6)
        shelfShowBookname = AppConfig.showBookname
        refreshShelfRenderConfig()
        upConnect()
    }

    private var bookSort: Int = 0

    // 顶栏标签体系（与订阅同源一套 MainTopBarView/RoundedTagBarView）
    private var groupMenuPopup: ModernActionPopup.Handle? = null
    private var bookTags = emptyList<String>()
    private var selectedBookTag = ""

    override val groupId: Long get() = selectedGroupId

    override val books: List<Book> get() = currentBooks

    override var onlyUpdateRead = false

    private val selectedGroup: BookGroup?
        get() = groupList.firstOrNull { it.groupId == selectedGroupId }

    /** 当前展示书籍：按所选书本标签（selectedBookTag）过滤当前分组书籍，空串=全部。 */
    private val displayedBooks: List<Book>
        get() = if (selectedBookTag.isBlank()) {
            currentBooks
        } else {
            currentBooks.filter { BookTagHelper.has(it.customTag, selectedBookTag) }
        }

    @Composable
    private fun BookshelfContent() {
        BookshelfScreen(
            bookGroups = groupList,
            books = displayedBooks,
            loading = loading,
            error = error,
            groupId = selectedGroupId,
            isFolder = false,
            topScrollTrigger = topScrollTrigger,
            isRefreshing = refreshing,
            layout = shelfLayout,
            showBookname = shelfShowBookname,
            listItemStyle = shelfListItemStyle,
            introLines = shelfIntroLines,
            margin = shelfMargin,
            showUnread = shelfShowUnread,
            showReadProgress = shelfShowReadProgress,
            showLastUpdateTime = shelfShowLastUpdateTime,
            onRefresh = {
                refreshing = true
                activityViewModel.upToc(currentBooks, onlyUpdateRead)
                // 1.2：事件驱动复位——upToc 队列排空（upTocIdle.first { it }）后收转圈，
                // 5s 超时兜底防信号丢失；协程挂 viewLifecycleOwner（页面销毁自动取消，无冻结滞留）
                refreshResetJob?.cancel()
                refreshResetJob = viewLifecycleOwner.lifecycleScope.launch {
                    val idle = withTimeoutOrNull(5_000) {
                        activityViewModel.upTocIdle.first { it }
                    }
                    if (idle == true || refreshing) refreshing = false
                }
            },
            onRetry = { upConnect() },
            onBookClick = { book -> startActivityForBook(book) },
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
        initTopBarTags()
        initBookGroupData()
        binding.viewPagerBookshelf.setContent {
            LegadoTheme {
                BookshelfContent()
            }
        }
    }

    /**
     * 接线顶栏标签：标题下拉换组（titleSelect）+ 分组胶囊（primaryBar）+ 书本标签（tagsBar）。
     * 全部复用 MainTopBarView 内置的 RoundedTagBarView，与订阅同一套，受 TopBarConfig/主题统一管理。
     */
    private fun initTopBarTags() {
        topBar.titleSelect.setOnClickListener {
            showGroupSwitchMenu(it)
        }
        topBar.primaryBar.setOnTagClickListener { index ->
            selectedBookTag = ""
            switchToGroup(index)
        }
        topBar.showTags(true)
        topBar.tagsBar.setOnTagClickListener { index ->
            val tag = bookTags.getOrNull(index).orEmpty()
            selectedBookTag = tag
            binding.topBar.tagsBar.setSelectedIndex(index, smooth = true)
        }
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            // REFRESH：重读渲染配置（margin/style/introLines/数据类开关）+ 刷数据
            refreshShelfRenderConfig()
            upConnect()
        }
        // STRUCTURE：layout/showBookname 结构变更 → 全量重建（config-needs-restart-fix 实锤 3）
        observeEvent<String>(EventBus.BOOKSHELF_STRUCTURE_CHANGED) {
            rebuildBookshelfContent()
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
            val saved = AppConfig.saveTabPosition.coerceIn(0, data.lastIndex)
            if (selectedGroupId == BookGroup.IdAll || data.none { it.groupId == selectedGroupId }) {
                selectedGroupId = data[saved].groupId
            }
            onlyUpdateRead = selectedGroup?.onlyUpdateRead ?: false
            renderGroupSelector()
            updateHeaderTitle()
            renderBookTags()
            upConnect()
        } else {
            renderGroupSelector()
            updateHeaderTitle()
        }
    }

    private fun onGroupSelected(newGroupId: Long) {
        if (selectedGroupId == newGroupId) return
        selectedGroupId = newGroupId
        val group = groupList.firstOrNull { it.groupId == newGroupId }
        onlyUpdateRead = group?.onlyUpdateRead ?: false
        AppConfig.saveTabPosition = groupList.indexOfFirst { it.groupId == newGroupId }
            .coerceAtLeast(0)
        selectedBookTag = ""
        updateHeaderTitle()
        renderGroupSelector()
        upConnect()
    }

    private fun switchToGroup(index: Int) {
        val group = groupList.getOrNull(index) ?: return
        onGroupSelected(group.groupId)
    }

    private fun renderGroupSelector() {
        val selectedIndex = groupList.indexOfFirst { it.groupId == selectedGroupId }
        binding.topBar.setPrimaryItems(
            groupList.map { RoundedTagBarView.Item(it.groupName) },
            selectedIndex.coerceAtLeast(0)
        )
    }

    private fun updateHeaderTitle() {
        binding.topBar.setTitle(selectedGroup?.groupName ?: getString(R.string.bookshelf))
    }

    /** 从当前分组书籍解析 customTag 标签并刷新 tagsBar（对齐订阅/Rimchars 的 renderBookTags）。 */
    private fun renderBookTags() {
        if (!isAdded) return
        val allText = getString(R.string.bookshelf_tag_all)
        val tags = currentBooks.asSequence()
            .flatMap { BookTagHelper.parse(it.customTag).asSequence() }
            .distinct()
            .sorted()
            .toList()
        bookTags = listOf("") + tags
        if (selectedBookTag.isNotBlank() && selectedBookTag !in tags) {
            selectedBookTag = ""
        }
        binding.topBar.tagsBar.submitItems(
            bookTags.map { RoundedTagBarView.Item(it.ifBlank { allText }) },
            bookTags.indexOf(selectedBookTag).takeIf { it >= 0 } ?: 0
        )
    }

    private fun showGroupSwitchMenu(anchor: View) {
        if (groupList.isEmpty()) return
        groupMenuPopup?.dismiss()
        val selectedId = selectedGroup?.groupId
        val actions = groupList.mapIndexed { index, group ->
            val prefix = if (group.groupId == selectedId) "✓ " else ""
            ModernActionPopup.Action(prefix + group.groupName) {
                selectedBookTag = ""
                switchToGroup(index)
            }
        }
        groupMenuPopup = ModernActionPopup.show(anchor, actions, groupMenuPopup)
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
                        renderBookTags()
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
        groupMenuPopup?.dismiss()
        groupMenuPopup = null
        super.onDestroyView()
    }
}