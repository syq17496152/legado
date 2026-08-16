package io.legado.app.ui.main.bookshelf

import android.annotation.SuppressLint
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.indices
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.DialogBookshelfConfigBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.cache.CacheActivity
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.import.local.ImportBookActivity
import io.legado.app.ui.book.import.remote.RemoteBookActivity
import io.legado.app.ui.book.manage.BookshelfManageActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.checkByIndex
import io.legado.app.utils.getCheckedIndex
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.postEvent
import io.legado.app.utils.readText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch

abstract class BaseBookshelfFragment(layoutId: Int) : VMBaseFragment<BookshelfViewModel>(layoutId),
    MainFragmentInterface {

    override val position: Int? get() = arguments?.getInt("position")

    val activityViewModel by activityViewModels<MainViewModel>()
    override val viewModel by viewModels<BookshelfViewModel>()

    private val importBookshelf = registerForActivityResult(HandleFileContract()) {
        kotlin.runCatching {
            it.uri?.readText(requireContext())?.let { text ->
                viewModel.importBookshelf(text, groupId)
            }
        }.onFailure {
            toastOnUi(it.localizedMessage ?: "ERROR")
        }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            alert(R.string.export_success) {
                if (uri.toString().isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary())
                }
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = getString(R.string.path)
                    editView.setText(uri.toString())
                }
                customView { alertBinding.root }
                okButton {
                    requireContext().sendToClip(uri.toString())
                }
            }
        }
    }
    abstract val groupId: Long
    abstract val books: List<Book>
    abstract var onlyUpdateRead: Boolean
    private var groupsLiveData: LiveData<List<BookGroup>>? = null
    private val waitDialog by lazy {
        WaitDialog(requireContext()).apply {
            setOnCancelListener {
                viewModel.addBookJob?.cancel()
            }
        }
    }

    abstract fun gotoTop()

    // 顶栏 Compose 状态：更多菜单展开 + 标题（style1 固定"书架"，style2 动态更新分组名）
    protected var menuExpanded by mutableStateOf(false)
    protected var composeTopBarTitle by mutableStateOf("")

    // 子类提供 Compose 顶栏容器（对应布局中的 compose_top_bar）
    protected abstract val composeTopBar: ComposeView

    // 顶栏 Compose 化：GlassTopAppBar 用 colorScheme.surface（跟随昼夜主题），替代 View TitleBar 的固定 primaryColor
    protected fun initComposeTopBar() {
        if (composeTopBarTitle.isBlank()) {
            composeTopBarTitle = getString(R.string.bookshelf)
        }
        composeTopBar.setContent {
            LegadoTheme {
                Column(modifier = Modifier.statusBarsPadding()) {
                    GlassTopAppBar(
                        title = composeTopBarTitle,
                        actions = {
                            // 搜索（原 main_bookshelf.xml 的 showAsAction="always" 项）
                            IconButton(onClick = { startActivity<SearchActivity>() }) {
                                Icon(Icons.Default.Search, contentDescription = null)
                            }
                            // 更多菜单（原 main_bookshelf.xml 其余项，数据驱动）
                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = null)
                                }
                                AppDropdownMenu(
                                    expanded = menuExpanded,
                                    onDismiss = { menuExpanded = false },
                                    actions = buildMenuActions()
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    // 更多菜单数据（保留全部原菜单动作，顺序同 main_bookshelf.xml）
    private fun buildMenuActions(): List<MenuAction> {
        return listOf(
            MenuAction(Icons.Default.Refresh, getString(R.string.update_toc), onClick = {
                activityViewModel.upToc(books, onlyUpdateRead)
            }),
            MenuAction(Icons.Default.Add, getString(R.string.book_local), onClick = {
                startActivity<ImportBookActivity>()
            }),
            MenuAction(Icons.Default.Cloud, getString(R.string.add_remote_book), onClick = {
                startActivity<RemoteBookActivity>()
            }),
            MenuAction(Icons.Default.Link, getString(R.string.add_url), onClick = {
                showAddBookByUrlAlert()
            }),
            MenuAction(Icons.Default.ManageSearch, getString(R.string.bookshelf_management), onClick = {
                startActivity<BookshelfManageActivity> {
                    putExtra("groupId", groupId)
                }
            }),
            MenuAction(Icons.Default.Download, getString(R.string.cache_export), onClick = {
                startActivity<CacheActivity> {
                    putExtra("groupId", groupId)
                }
            }),
            MenuAction(Icons.Default.Groups, getString(R.string.group_manage), onClick = {
                showDialogFragment<GroupManageDialog>()
            }),
            MenuAction(Icons.Default.GridView, getString(R.string.bookshelf_layout), onClick = {
                configBookshelf()
            }),
            MenuAction(Icons.Default.FileUpload, getString(R.string.export_bookshelf), onClick = {
                viewModel.exportBookshelf(books) { file ->
                    exportResult.launch {
                        mode = HandleFileContract.EXPORT
                        fileData =
                            HandleFileContract.FileData("bookshelf.json", file, "application/json")
                    }
                }
            }),
            MenuAction(Icons.Default.FileDownload, getString(R.string.import_bookshelf), onClick = {
                importBookshelfAlert(groupId)
            }),
            MenuAction(Icons.Default.Info, getString(R.string.log), onClick = {
                showDialogFragment<AppLogDialog>()
            })
        )
    }

    protected fun initBookGroupData() {
        groupsLiveData?.removeObservers(viewLifecycleOwner)
        groupsLiveData = appDb.bookGroupDao.show.apply {
            observe(viewLifecycleOwner) {
                upGroup(it)
            }
        }
    }

    abstract fun upGroup(data: List<BookGroup>)

    abstract fun upSort()

    override fun observeLiveBus() {
        viewModel.addBookProgressLiveData.observe(this) { count ->
            if (count < 0) {
                waitDialog.dismiss()
            } else {
                waitDialog.setText("添加中... ($count)")
            }
        }
    }

    @SuppressLint("InflateParams")
    fun showAddBookByUrlAlert() {
        alert(titleResource = R.string.add_book_url) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url"
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let {
                    waitDialog.setText("添加中...")
                    waitDialog.show()
                    viewModel.addBookByUrl(it)
                }
            }
            cancelButton()
        }
    }

    @SuppressLint("InflateParams")
    fun configBookshelf() {
        alert(titleResource = R.string.bookshelf_layout) {
            var bookshelfLayout = AppConfig.bookshelfLayout
            var bookshelfSort = AppConfig.bookshelfSort
            var showBookname = AppConfig.showBookname
            val alertBinding =
                DialogBookshelfConfigBinding.inflate(layoutInflater)
                    .apply {
                        if (AppConfig.bookGroupStyle !in 0..<spGroupStyle.count) {
                            AppConfig.bookGroupStyle = 0
                        }
                        if (bookshelfLayout !in rgLayout.indices) {
                            bookshelfLayout = 0
                            AppConfig.bookshelfLayout = 0
                        }
                        if (bookshelfSort !in rgSort.indices) {
                            bookshelfSort = 0
                            AppConfig.bookshelfSort = 0
                        }
                        if (showBookname !in rgbLayout.indices) {
                            showBookname = 0
                            AppConfig.showBookname = 0
                        }
                        spGroupStyle.setSelection(AppConfig.bookGroupStyle)
                        swShowUnread.isChecked = AppConfig.showUnread
                        swShowReadProgress.isChecked = AppConfig.showBookshelfReadProgress
                        swShowLastUpdateTime.isChecked = AppConfig.showLastUpdateTime
                        swShowWaitUpBooks.isChecked = AppConfig.showWaitUpCount
                        swShowBookshelfFastScroller.isChecked = AppConfig.showBookshelfFastScroller
                        rgLayout.checkByIndex(bookshelfLayout)
                        rgbLayout.checkByIndex(showBookname)
                        if (bookshelfLayout < 2) {
                            bookNameChoice.visibility = View.GONE
                        }
                        rgLayout.setOnCheckedChangeListener { group, checkedId ->
                            val index = group.getCheckedIndex()
                            bookNameChoice.visibility = if (index > 1) View.VISIBLE else View.GONE
                        }
                        rgSort.checkByIndex(bookshelfSort)
                        margin.progress = AppConfig.bookshelfMargin
                    }
            customView { alertBinding.root }
            okButton {
                alertBinding.apply {
                    var notifyMain = false
                    var recreate = false
                    if (AppConfig.bookGroupStyle != spGroupStyle.selectedItemPosition) {
                        AppConfig.bookGroupStyle = spGroupStyle.selectedItemPosition
                        notifyMain = true
                    }
                    if (showBookname != rgbLayout.getCheckedIndex()) {
                        AppConfig.showBookname = rgbLayout.getCheckedIndex()
                        recreate = true
                    }
                    if (AppConfig.bookshelfMargin != margin.progress) {
                        AppConfig.bookshelfMargin = margin.progress
                        recreate = true
                    }
                    if (AppConfig.showUnread != swShowUnread.isChecked) {
                        AppConfig.showUnread = swShowUnread.isChecked
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (AppConfig.showBookshelfReadProgress != swShowReadProgress.isChecked) {
                        AppConfig.showBookshelfReadProgress = swShowReadProgress.isChecked
                        kotlin.runCatching {
                            AppLog.putDebugWithTag(
                                AppLog.TAG_SHELF_PROGRESS,
                                "showBookshelfReadProgress switched -> ${swShowReadProgress.isChecked}",
                                level = AppLog.Level.INFO
                            )
                        }
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (AppConfig.showLastUpdateTime != swShowLastUpdateTime.isChecked) {
                        AppConfig.showLastUpdateTime = swShowLastUpdateTime.isChecked
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (AppConfig.showWaitUpCount != swShowWaitUpBooks.isChecked) {
                        AppConfig.showWaitUpCount = swShowWaitUpBooks.isChecked
                        activityViewModel.postUpBooksLiveData(true)
                    }
                    if (AppConfig.showBookshelfFastScroller != swShowBookshelfFastScroller.isChecked) {
                        AppConfig.showBookshelfFastScroller = swShowBookshelfFastScroller.isChecked
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (bookshelfSort != rgSort.getCheckedIndex()) {
                        AppConfig.bookshelfSort = rgSort.getCheckedIndex()
                        upSort()
                    }
                    if (bookshelfLayout != rgLayout.getCheckedIndex()) {
                        AppConfig.bookshelfLayout = rgLayout.getCheckedIndex()
                        if (AppConfig.bookshelfLayout < 2) {
                            activityViewModel.booksGridRecycledViewPool.clear()
                        } else {
                            activityViewModel.booksListRecycledViewPool.clear()
                        }
                        recreate = true
                    }
                    if (recreate) {
                        postEvent(EventBus.RECREATE, "")
                    } else if (notifyMain) {
                        postEvent(EventBus.NOTIFY_MAIN, false)
                    }
                }
            }
            cancelButton()
        }
    }


    private fun importBookshelfAlert(groupId: Long) {
        alert(titleResource = R.string.import_bookshelf) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url/json"
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let {
                    viewModel.importBookshelf(it, groupId)
                }
            }
            cancelButton()
            neutralButton(R.string.select_file) {
                importBookshelf.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("txt", "json")
                }
            }
        }
    }

}