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
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityArrangeBookBinding
import io.legado.app.databinding.DialogEditTextBinding
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
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.AppEditDialog
import io.legado.app.ui.widget.components.EditField
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsSearchBar
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.cnCompare
import io.legado.app.utils.dpToPx
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
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
    VMBaseActivity<ActivityArrangeBookBinding, BookshelfManageViewModel>(),
    PopupMenu.OnMenuItemClickListener,
    SelectActionBar.CallBack,
    BookAdapter.CallBack,
    SourcePickerDialog.Callback,
    GroupSelectDialog.CallBack {

    override val binding by viewBinding(ActivityArrangeBookBinding::inflate)
    override val viewModel by viewModels<BookshelfManageViewModel>()
    override val groupList: ArrayList<BookGroup> = arrayListOf()
    private val groupRequestCode = 22
    private val addToGroupRequestCode = 34
    private val removeToGroupRequestCode = 42
    private val adapter by lazy { BookAdapter(this, this) }
    private val itemTouchCallback by lazy { ItemTouchCallback(adapter) }
    private var booksFlowJob: Job? = null
    private var books: List<Book>? = null
    // L-B8 顶栏 Compose 状态
    private var composeTitle by mutableStateOf("")
    private var composeSearchQuery by mutableStateOf("")
    private var searchVisible by mutableStateOf(false)
    private var menuExpanded by mutableStateOf(false)
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

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.groupId = intent.getLongExtra("groupId", -1)
        lifecycleScope.launch {
            viewModel.groupName = withContext(IO) {
                appDb.bookGroupDao.getByID(viewModel.groupId)?.groupName
                    ?: getString(R.string.no_group)
            }
            upTitle()
        }
        initComposeTopBar()
        initRecyclerView()
        initOtherView()
        initGroupData()
        upBookDataByGroupId()
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
        adapter.selectAll(selectAll)
    }

    override fun revertSelection() {
        adapter.revertSelection()
    }

    override fun onClickSelectBarMainAction() {
        selectGroup(groupRequestCode, 0)
    }

    private fun upTitle() {
        composeTitle = getString(R.string.screen) + " • " + viewModel.groupName
    }

    // L-B8 顶栏 Compose 化：GlassTopAppBar + 搜索内联 + 分组管理对话框 + 更多菜单 AppDropdownMenu
    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                Column {
                    GlassTopAppBar(
                        title = composeTitle,
                        navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        onNavClick = { finish() },
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
                                upBookData()
                            },
                            placeholder = getString(R.string.screen) + " • " + viewModel.groupName
                        )
                    }
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
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
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

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.adapter = adapter
        itemTouchCallback.isCanDrag = AppConfig.bookshelfSort == 3
        val dragSelectTouchHelper: DragSelectTouchHelper =
            DragSelectTouchHelper(adapter.dragSelectCallback).setSlideArea(16, 50)
        dragSelectTouchHelper.attachToRecyclerView(binding.recyclerView)
        // When this page is opened, it is in selection mode
        dragSelectTouchHelper.activeSlideSelect()
        // Note: need judge selection first, so add ItemTouchHelper after it.
        ItemTouchHelper(itemTouchCallback).attachToRecyclerView(binding.recyclerView)
    }

    private fun initOtherView() {
        binding.selectActionBar.setMainActionText(R.string.move_to_group)
        binding.selectActionBar.inflateMenu(R.menu.bookshelf_menage_sel)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        binding.selectActionBar.setCallBack(this)
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
                adapter.notifyDataSetChanged()
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
                    books = it
                    upBookData()
                    itemTouchCallback.isCanDrag = bookSort == 3
                }
        }
    }

    private fun upBookData() {
        books?.let { books ->
            val searchKey = composeSearchQuery
            if (searchKey.isNullOrEmpty()) {
                adapter.setItems(books)
            } else {
                books.filter {
                    it.contains(searchKey.toString())
                }.let {
                    adapter.setItems(it)
                }
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_del_selection -> alertDelSelection()
            R.id.menu_update_enable ->
                viewModel.upCanUpdate(adapter.selection, true)

            R.id.menu_update_disable ->
                viewModel.upCanUpdate(adapter.selection, false)

            R.id.menu_add_to_group -> selectGroup(addToGroupRequestCode, 0)
            R.id.menu_remove_to_group -> selectGroup(removeToGroupRequestCode, 0)
            R.id.menu_change_source -> showDialogFragment<SourcePickerDialog>()
            R.id.menu_clear_cache -> viewModel.clearCache(adapter.selection)
            R.id.menu_check_selected_interval -> adapter.checkSelectedInterval()
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
                viewModel.deleteBook(adapter.selection, checkBox.isChecked)
            }
            noButton()
        }
    }

    override fun selectGroup(requestCode: Int, groupId: Long) {
        showDialogFragment(
            GroupSelectDialog(groupId, requestCode)
        )
    }

    override fun upGroup(requestCode: Int, groupId: Long) {
        when (requestCode) {
            groupRequestCode -> adapter.selection.let { books ->
                val array = Array(books.size) {
                    books[it].copy(group = groupId)
                }
                viewModel.updateBook(*array)
            }

            adapter.groupRequestCode -> {
                adapter.actionItem?.let {
                    viewModel.updateBook(it.copy(group = groupId))
                }
            }

            addToGroupRequestCode -> adapter.selection.let { books ->
                val array = Array(books.size) { index ->
                    val book = books[index]
                    book.copy(group = book.group or groupId)
                }
                viewModel.updateBook(*array)
            }

            removeToGroupRequestCode -> adapter.selection.let { books ->
                val array = Array(books.size) { index ->
                    val book = books[index]
                    book.copy(group = book.group and groupId.inv())
                }
                viewModel.updateBook(*array)
            }
        }
    }

    override fun upSelectCount() {
        binding.selectActionBar.upCountView(adapter.selection.size, adapter.getItems().size)
    }

    override fun updateBook(vararg book: Book) {
        viewModel.updateBook(*book)
    }

    override fun deleteBook(book: Book) {
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

    override fun openBook(book: Book) {
        BookInfoNavigator.open(this, book)
    }

    override fun sourceOnClick(source: BookSource) {
        viewModel.changeSource(adapter.selection, source)
        viewModel.batchChangeSourceState.value = true
    }

}