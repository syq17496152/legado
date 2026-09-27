package io.legado.app.ui.book.manage

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MenuItem
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.viewbinding.ViewBinding
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.base.attachComposeContent
import io.legado.app.base.composeShell
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.book.contains
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.info.BookInfoNavigator
import io.legado.app.ui.book.manage.compose.ArrangeBookList
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.AppEditDialog
import io.legado.app.ui.widget.components.EditField
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsSearchBar
import io.legado.app.ui.widget.compose.AppUiTokens
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.cnCompare
import io.legado.app.utils.dpToPx
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 书架管理
 */
class BookshelfManageActivity :
    VMBaseActivity<ViewBinding, BookshelfManageViewModel>(),
    PopupMenu.OnMenuItemClickListener,
    SelectActionBar.CallBack,
    SourcePickerDialog.Callback,
    GroupSelectDialog.CallBack {

    // 原 activity_arrange_book.xml 已退役（CE-b）：改 composeShell 工厂创建合成壳，Compose 全权接管页面骨架
    override val binding: ViewBinding by lazy { composeShell(this) }
    override val viewModel by viewModels<BookshelfManageViewModel>()

    /** 原 XML `select_action_bar`（批量操作底栏）的程序化等价物（CE-b） */
    private val selectActionBar: SelectActionBar by lazy { SelectActionBar(this) }
    val groupList: ArrayList<BookGroup> = arrayListOf()
    /** 行内「分组」键的 requestCode（原 `BookAdapter.groupRequestCode`，与底栏「移至分组」的 22 区分） */
    private val rowGroupRequestCode = 12
    private val groupRequestCode = 22
    private val addToGroupRequestCode = 34
    private val removeToGroupRequestCode = 42

    /**
     * CF 6.2：`item_arrange_book` 退役后列表状态单源 ——
     * ①`booksState` = DB 全量（未过滤）；②`selectedUrls` = 选择态（按 bookUrl，宿主持有，
     * 供底栏计数与全部批量操作读取）；③`listState` = 滚动状态（Activity 持有 ⇒ 旋转/进程重建可恢复，
     * 对齐 View 侧 `RecyclerView` 自带布局态保存的既有行为，见交接文档 §8-64）。
     */
    private var booksState by mutableStateOf<List<Book>>(emptyList())
    private var selectedUrls by mutableStateOf<Set<String>>(emptySet())
    private val listState = LazyListState()
    private var pendingScrollIndex: Int? = null
    private var pendingScrollOffset: Int = 0

    /** 行内「分组」键作用的书（原 `BookAdapter.actionItem`） */
    private var actionItem: Book? = null
    private var booksFlowJob: Job? = null
    // L-B8 顶栏 Compose 状态
    private var composeTitle by mutableStateOf("")
    private var composeSearchQuery by mutableStateOf("")
    private var searchVisible by mutableStateOf(false)
    private var menuExpanded by mutableStateOf(false)
    /** F41：排序模式（可拖拽）状态——驱动顶栏模式提示条 */
    private var dragModeState by mutableStateOf(false)
    private var openBookInfoByClickTitle by mutableStateOf(AppConfig.openBookInfoByClickTitle)
    private var composeGroupNames by mutableStateOf(listOf<String>())
    private val waitDialog by lazy { WaitDialog(this) }
    private val exportDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showComposeTextInputDialog(
                title = getString(R.string.export_success),
                message = if (uri.toString().isAbsUrl()) DirectLinkUpload.getSummary() else null,
                hint = getString(R.string.path),
                initialValue = uri.toString(),
                readOnly = true,
                positiveText = getString(R.string.copy_text),
                onPositive = {
                    sendToClip(uri.toString())
                }
            )
        }
    }

    // ui-theme-governance-polish P6：管理族宿主接入背景透明度（1.5 封闭清单成员）
    override fun manageBackgroundAlphaEnabled(): Boolean = true

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.groupId = intent.getLongExtra("groupId", -1)
        lifecycleScope.launch {
            viewModel.groupName = withContext(IO) {
                appDb.bookGroupDao.getByID(viewModel.groupId)?.groupName
                    ?: getString(R.string.no_group)
            }
            upTitle()
        }
        initComposeContent()
        pendingScrollIndex = savedInstanceState?.getInt(KEY_SCROLL_INDEX)
        pendingScrollOffset = savedInstanceState?.getInt(KEY_SCROLL_OFFSET) ?: 0
        initDragMode()
        initOtherView()
        initGroupData()
        upBookDataByGroupId()
    }

    /**
     * 滚动位置保存（CF 6.2 换装 Compose 后必须显式补：View 侧 `RecyclerView` 带 id 时自带布局态保存，
     * `LazyListState` 不会 —— 见交接文档 §8-64）。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SCROLL_INDEX, listState.firstVisibleItemIndex)
        outState.putInt(KEY_SCROLL_OFFSET, listState.firstVisibleItemScrollOffset)
    }

    override fun observeLiveBus() {
        viewModel.batchChangeSourceState.observe(this) {
            if (it) {
                waitDialog.setText(R.string.change_source_batch)
                waitDialog.show()
            } else {
                waitDialog.dismiss()
            }
        }
        viewModel.batchChangeSourceProcessLiveData.observe(this) {
            waitDialog.setText(it)
        }
    }

    override fun selectAll(selectAll: Boolean) {
        selectedUrls = if (selectAll) visibleBooks.map { it.bookUrl }.toSet() else emptySet()
        upSelectCount()
    }

    override fun revertSelection() {
        val reverted = visibleBooks.map { it.bookUrl }.toSet()
        selectedUrls = reverted - selectedUrls + (selectedUrls - reverted)
        upSelectCount()
    }

    /** 选择区间（原 `BookAdapter.checkSelectedInterval`：把首尾已选项之间的项全部选中） */
    private fun checkSelectedInterval() {
        val positions = visibleBooks.mapIndexedNotNull { index, book ->
            index.takeIf { book.bookUrl in selectedUrls }
        }
        if (positions.isEmpty()) return
        val lo = positions.min()
        val hi = positions.max()
        val add = visibleBooks.subList(lo, hi + 1).map { it.bookUrl }
        selectedUrls = selectedUrls + add
        upSelectCount()
    }

    /** 行内点击/复选框切换选择（原 Adapter `selectedBooks` 语义） */
    private fun toggleSelect(book: Book) {
        selectedUrls = if (book.bookUrl in selectedUrls) {
            selectedUrls - book.bookUrl
        } else {
            selectedUrls + book.bookUrl
        }
        upSelectCount()
    }

    /** 当前展示（已按搜索词过滤）的列表 */
    private val visibleBooks: List<Book>
        get() = booksState.filter {
            composeSearchQuery.isEmpty() || it.contains(composeSearchQuery)
        }

    /** 当前选中项（原 `BookAdapter.selection`：以**当前展示列表**为序，保证批量操作顺序稳定） */
    private val selection: List<Book>
        get() = visibleBooks.filter { it.bookUrl in selectedUrls }

    /** 分组名解析（原 `BookAdapter.getGroupName` 的双层位掩码口径，逐字保留） */
    private fun groupNameOf(book: Book): String =
        groupList.filter { it.groupId > 0 && it.groupId and book.group > 0 }
            .joinToString(",") { it.groupName }

    override fun onClickSelectBarMainAction() {
        selectGroup(groupRequestCode, 0)
    }

    private fun upTitle() {
        composeTitle = getString(R.string.screen) + " • " + viewModel.groupName
    }

    /**
     * CE-b：Compose 承载页面骨架（顶栏 + 列表 + 批量底栏）。
     *
     * 与原 XML（`activity_arrange_book.xml`）的**逐一对应关系**：
     *  · 根 `ConstraintLayout` → `composeShell` 合成壳（`binding.root`）
     *  · `compose_top_bar`(ComposeView) → 页内直接渲染 `GlassTopAppBar`（内容逐行不变，含 F41 模式提示 secondRow）
     *  · 原列表节点（`0dp` + 上下约束）→ **CF 6.2 后为 Compose `ArrangeBookList`**（`weight(1f)`；
     *    此前 CE-b 阶段曾以程序化 View 列表 + `AndroidView` 托管，该实现已随 `item_arrange_book` 退役）
     *  · `select_action_bar`(View 批量底栏) → `AndroidView` 托管程序化 `SelectActionBar`（恒贴底）
     */
    private fun initComposeContent() {
        binding.root.attachComposeContent {
            LegadoTheme {
                Column {
                    GlassTopAppBar(
                        title = composeTitle,
                        navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        onNavClick = { finish() },
                        // F41：排序模式下常显模式提示（第二行必须走 secondRow——NORM F317：subtitle 会被固定栏高裁掉）
                        secondRow = if (dragModeState) {
                            {
                                Text(
                                    text = stringResource(R.string.bookshelf_drag_mode_hint),
                                    color = AppUiTokens.settingPalette().accent,
                                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                )
                            }
                        } else {
                            null
                        },
                        actions = {
                            IconButton(onClick = { searchVisible = !searchVisible }) {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = getString(R.string.action_search)
                                )
                            }
                            IconButton(onClick = { showDialogFragment<GroupManageDialog>() }) {
                                Icon(
                                    imageVector = Icons.Filled.Groups,
                                    contentDescription = getString(R.string.group_manage)
                                )
                            }
                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription = null
                                    )
                                }
                                AppDropdownMenu(
                                    expanded = menuExpanded,
                                    onDismiss = { menuExpanded = false },
                                    actions = buildMenuActions()
                                )
                            }
                        }
                    )
                    if (searchVisible) {
                        SettingsSearchBar(
                            query = composeSearchQuery,
                            onQueryChange = {
                                composeSearchQuery = it
                            },
                            placeholder = getString(R.string.screen) + " • " + viewModel.groupName
                        )
                    }
                    // ---- 列表（原 recycler_view；CF 6.2：item_arrange_book 退役 ⇒ Compose LazyColumn）----
                    ArrangeBookList(
                        books = visibleBooks,
                        selectedUrls = selectedUrls,
                        dragMode = dragModeState,
                        openBookInfoByClickTitle = openBookInfoByClickTitle,
                        groupNameOf = ::groupNameOf,
                        onToggleSelect = ::toggleSelect,
                        onOpenBook = ::openBook,
                        onDeleteBook = ::deleteBook,
                        onSelectGroup = { book ->
                            actionItem = book
                            selectGroup(rowGroupRequestCode, book.group)
                        },
                        onOrderCommitted = { ordered ->
                            viewModel.updateBook(*ordered.toTypedArray())
                        },
                        listState = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                    // 旋转/进程重建后的滚动恢复（须等数据到达再滚，否则会被钳到 0 —— 见交接文档 §8-64）
                    LaunchedEffect(visibleBooks.size, pendingScrollIndex) {
                        val index = pendingScrollIndex ?: return@LaunchedEffect
                        if (visibleBooks.isNotEmpty()) {
                            listState.scrollToItem(index, pendingScrollOffset)
                            pendingScrollIndex = null
                        }
                    }
                    // ---- 批量操作底栏（原 select_action_bar，恒贴底）----
                    AndroidView(factory = { selectActionBar })
                }
            }
        }
    }

    private fun buildMenuActions(): List<MenuAction> {
        val actions = mutableListOf<MenuAction>()
        // 导出所用书源
        actions += MenuAction(
            Icons.Filled.Description,
            getString(R.string.export_all_use_book_source),
            onClick = {
                viewModel.saveAllUseBookSourceToFile { file ->
                    exportDir.launch {
                        mode = HandleFileContract.EXPORT
                        fileData = HandleFileContract.FileData(
                            "bookSource.json",
                            file,
                            "application/json"
                        )
                    }
                }
            }
        )
        // 详情开关
        actions += MenuAction(
            Icons.Filled.Info,
            getString(R.string.open_book_info_by_click_title),
            checked = openBookInfoByClickTitle,
            onClick = {
                openBookInfoByClickTitle = !openBookInfoByClickTitle
                AppConfig.openBookInfoByClickTitle = openBookInfoByClickTitle
                // CF 6.2：原 `adapter.notifyItemRangeChanged(...)` 由 Compose 状态驱动取代（本状态即组合输入）
            }
        )
        // 分组切换（动态加载，等价原 menu_book_group 子菜单）
        composeGroupNames.forEach { name ->
            actions += MenuAction(
                Icons.Filled.Folder,
                name,
                onClick = {
                    viewModel.groupName = name
                    upTitle()
                    lifecycleScope.launch {
                        viewModel.groupId = withContext(IO) {
                            appDb.bookGroupDao.getByName(name)?.groupId ?: 0
                        }
                        upBookDataByGroupId()
                    }
                }
            )
        }
        return actions
    }

    /**
     * F41 排序模式（可拖拽）初值 —— 原 `initRecyclerView()` 里「recyclerView 装配 + 两个拖拽助手」
     * 已随 `item_arrange_book` 退役并入 Compose 列表（`ArrangeBookList` 的 `dragMode`），
     * 本函数只剩状态初始化（顶栏模式提示条 + 行尾手柄可见性同源）。
     */
    private fun initDragMode() {
        dragModeState = AppConfig.bookshelfSort == 3
    }

    private fun initOtherView() {
        selectActionBar.setMainActionText(R.string.move_to_group)
        // F39：批量操作菜单按用途分组（常用 → 更新管理 → 分组管理 → 数据清理）；
        // 删除/换源为批量管理最高频动作 ⇒ 置首组「常用」，避免埋在长菜单中（蓝图原意「就近可达」，
        // 差异：底栏共享件只有单个主操作槽位，未把两项移出菜单，已登记）
        selectActionBar.inflateMenu(
            R.menu.bookshelf_menage_sel,
            linkedMapOf(
                R.id.menu_del_selection to getString(R.string.bookshelf_menu_group_common),
                R.id.menu_update_enable to getString(R.string.bookshelf_menu_group_update),
                R.id.menu_add_to_group to getString(R.string.bookshelf_menu_group_group),
                R.id.menu_clear_cache to getString(R.string.bookshelf_menu_group_clean)
            )
        )
        selectActionBar.setOnMenuItemClickListener(this)
        selectActionBar.setCallBack(this)
        waitDialog.setOnCancelListener {
            viewModel.batchChangeSourceCoroutine?.cancel()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun initGroupData() {
        lifecycleScope.launch {
            appDb.bookGroupDao.flowAll().catch {
                AppLog.put("书架管理界面获取分组数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                groupList.clear()
                groupList.addAll(it)
                composeGroupNames = groupList.map { g -> g.groupName }
                // CF 6.2：原 `adapter.notifyDataSetChanged()` 由 `composeGroupNames` 状态驱动取代
            }
        }
    }

    private fun upBookDataByGroupId() {
        booksFlowJob?.cancel()
        booksFlowJob = lifecycleScope.launch {
            val bookSort = withContext(IO) { AppConfig.getBookSortByGroupId(viewModel.groupId) }
            appDb.bookDao.flowByGroup(viewModel.groupId).map { list ->
                when (bookSort) {
                    1 -> list.sortedByDescending {
                        it.latestChapterTime
                    }

                    2 -> list.sortedWith { o1, o2 ->
                        o1.name.cnCompare(o2.name)
                    }

                    3 -> list.sortedBy {
                        it.order
                    }

                    4 -> list.sortedByDescending {
                        max(it.latestChapterTime, it.durChapterTime)
                    }

                    else -> list.sortedByDescending {
                        it.durChapterTime
                    }
                }
            }.catch {
                AppLog.put("书架管理界面获取书籍列表失败\n${it.localizedMessage}", it)
            }.flowOn(IO)
                .conflate().collect {
                    booksState = it
                    dragModeState = bookSort == 3
                }
        }
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_del_selection -> alertDelSelection()
            R.id.menu_update_enable ->
                viewModel.upCanUpdate(selection, true)

            R.id.menu_update_disable ->
                viewModel.upCanUpdate(selection, false)

            R.id.menu_add_to_group -> selectGroup(addToGroupRequestCode, 0)
            R.id.menu_remove_to_group -> selectGroup(removeToGroupRequestCode, 0)
            R.id.menu_change_source -> showDialogFragment<SourcePickerDialog>()
            R.id.menu_clear_cache -> viewModel.clearCache(selection)
            R.id.menu_check_selected_interval -> checkSelectedInterval()
        }
        return false
    }

    private fun alertDelSelection() {
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            val checkBox = CheckBox(this@BookshelfManageActivity).apply {
                setText(R.string.delete_book_file)
                isChecked = LocalConfig.deleteBookOriginal
            }
            val view = LinearLayout(this@BookshelfManageActivity).apply {
                setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
                addView(checkBox)
            }
            customView { view }
            okButton {
                LocalConfig.deleteBookOriginal = checkBox.isChecked
                viewModel.deleteBook(selection, checkBox.isChecked)
            }
            noButton()
        }
    }

    fun selectGroup(requestCode: Int, groupId: Long) {
        showDialogFragment(
            GroupSelectDialog(groupId, requestCode)
        )
    }

    override fun upGroup(requestCode: Int, groupId: Long) {
        when (requestCode) {
            groupRequestCode -> selection.let { books ->
                val array = Array(books.size) {
                    books[it].copy(group = groupId)
                }
                viewModel.updateBook(*array)
            }

            rowGroupRequestCode -> {
                actionItem?.let {
                    viewModel.updateBook(it.copy(group = groupId))
                }
            }

            addToGroupRequestCode -> selection.let { books ->
                val array = Array(books.size) { index ->
                    val book = books[index]
                    book.copy(group = book.group or groupId)
                }
                viewModel.updateBook(*array)
            }

            removeToGroupRequestCode -> selection.let { books ->
                val array = Array(books.size) { index ->
                    val book = books[index]
                    book.copy(group = book.group and groupId.inv())
                }
                viewModel.updateBook(*array)
            }
        }
    }

    fun upSelectCount() {
        selectActionBar.upCountView(selection.size, visibleBooks.size)
    }

    fun updateBook(vararg book: Book) {
        viewModel.updateBook(*book)
    }

    fun deleteBook(book: Book) {
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            var checkBox: CheckBox? = null
            if (book.isLocal) {
                checkBox = CheckBox(this@BookshelfManageActivity).apply {
                    setText(R.string.delete_book_file)
                    isChecked = LocalConfig.deleteBookOriginal
                }
                val view = LinearLayout(this@BookshelfManageActivity).apply {
                    setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
                    addView(checkBox)
                }
                customView { view }
            }
            okButton {
                if (checkBox != null) {
                    LocalConfig.deleteBookOriginal = checkBox.isChecked
                }
                viewModel.deleteBook(listOf(book), LocalConfig.deleteBookOriginal)
            }
        }
    }

    fun openBook(book: Book) {
        BookInfoNavigator.open(this, book)
    }

    override fun sourceOnClick(source: BookSource) {
        viewModel.changeSource(selection, source)
        viewModel.batchChangeSourceState.value = true
    }

    companion object {
        /** 列表滚动位置保存键（CF 6.2：Compose 列表需宿主显式保存/恢复，见 `onSaveInstanceState`） */
        private const val KEY_SCROLL_INDEX = "arrange_scroll_index"
        private const val KEY_SCROLL_OFFSET = "arrange_scroll_offset"
    }

}
