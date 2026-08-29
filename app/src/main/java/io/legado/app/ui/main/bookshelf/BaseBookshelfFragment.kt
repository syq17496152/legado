package io.legado.app.ui.main.bookshelf

import android.annotation.SuppressLint
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import splitties.init.appCtx
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.AppConfig
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
import io.legado.app.ui.widget.MainTopBarView
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.applyStatusBarPadding
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
            showComposeTextInputDialog(
                title = getString(R.string.export_success),
                message = if (uri.toString().isAbsUrl()) DirectLinkUpload.getSummary() else null,
                hint = getString(R.string.path),
                initialValue = uri.toString(),
                readOnly = true,
                positiveText = getString(R.string.copy_text),
                onPositive = {
                    requireContext().sendToClip(uri.toString())
                }
            )
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

    // 顶栏对齐 Archive MainTopBarView（BOOKSHELF 模式）：标题 + 搜索按钮 + 更多按钮；标题 style1 动态为当前分组名（updateHeaderTitle），style2 动态更新分组名
    protected var composeTopBarTitle: String = ""

    // 子类提供 MainTopBarView 顶栏（对应布局中的 top_bar）
    protected abstract val topBar: MainTopBarView

    // 更多菜单弹窗句柄（ModernActionPopup，生命周期由弹窗自身管理）
    protected var menuPopup: ModernActionPopup.Handle? = null

    // 顶栏初始化：MainTopBarView BOOKSHELF 模式 + 搜索/更多菜单接线
    protected fun initComposeTopBar() {
        if (composeTopBarTitle.isBlank()) {
            composeTopBarTitle = getString(R.string.bookshelf)
        }
        topBar.applyStatusBarPadding(withInitialPadding = true)
        topBar.setMode(MainTopBarView.Mode.BOOKSHELF)
        topBar.setTitle(composeTopBarTitle)
        // header-search-unify：关闭无效 searchEntry 胶囊（仅保留 searchButton → SearchActivity 新页搜索），形态对齐订阅页
        topBar.setSearchEntryVisible(false)
        // 搜索（原 main_bookshelf.xml 的 showAsAction="always" 项）
        topBar.setActionsVisible(search = true)
        topBar.searchButton.setOnClickListener {
            startActivity<SearchActivity>()
        }
        // 更多菜单（原 main_bookshelf.xml 其余项，数据驱动）
        topBar.moreButton.setOnClickListener {
            showBookshelfMenu(it)
        }
    }

    private fun showBookshelfMenu(anchor: View) {
        menuPopup = ModernActionPopup.show(anchor, buildMenuActions(), menuPopup)
    }

    // 更多菜单数据（保留全部原菜单动作，顺序同 main_bookshelf.xml；图标经 ModernActionPopup 间接目录省略）
    private fun buildMenuActions(): List<ModernActionPopup.Action> {
        return listOf(
            ModernActionPopup.Action(getString(R.string.update_toc)) {
                activityViewModel.upToc(books, onlyUpdateRead)
            },
            ModernActionPopup.Action(getString(R.string.book_local)) {
                startActivity<ImportBookActivity>()
            },
            ModernActionPopup.Action(getString(R.string.add_remote_book)) {
                startActivity<RemoteBookActivity>()
            },
            ModernActionPopup.Action(getString(R.string.add_url)) {
                showAddBookByUrlAlert()
            },
            ModernActionPopup.Action(getString(R.string.bookshelf_management)) {
                startActivity<BookshelfManageActivity> {
                    putExtra("groupId", groupId)
                }
            },
            ModernActionPopup.Action(getString(R.string.cache_export)) {
                startActivity<CacheActivity> {
                    putExtra("groupId", groupId)
                }
            },
            ModernActionPopup.Action(getString(R.string.group_manage)) {
                showDialogFragment<GroupManageDialog>()
            },
            ModernActionPopup.Action(getString(R.string.bookshelf_layout)) {
                configBookshelf()
            },
            // 7.11i 书架标签管理入口（对齐 Archive menu_book_tag_manage）
            ModernActionPopup.Action(getString(R.string.bookshelf_tag_manage)) {
                startActivity<BookshelfTagManageActivity> {
                    putExtra("groupId", groupId)
                }
            },
            ModernActionPopup.Action(getString(R.string.export_bookshelf)) {
                viewModel.exportBookshelf(books) { file ->
                    exportResult.launch {
                        mode = HandleFileContract.EXPORT
                        fileData =
                            HandleFileContract.FileData("bookshelf.json", file, "application/json")
                    }
                }
            },
            ModernActionPopup.Action(getString(R.string.import_bookshelf)) {
                importBookshelfAlert(groupId)
            },
            ModernActionPopup.Action(getString(R.string.log)) {
                showDialogFragment<AppLogDialog>()
            }
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
        showComposeTextInputDialog(
            title = getString(R.string.add_book_url),
            hint = "url",
            onPositive = {
                waitDialog.setText("添加中...")
                waitDialog.show()
                viewModel.addBookByUrl(it)
            }
        )
    }

    // 书架布局弹框：对齐 Archive BookshelfConfigDialog（Compose 弹框，分组样式/布局/排序/书名/列表样式/简介行数/边距）
    fun configBookshelf() {
        var bookshelfLayout = AppConfig.bookshelfLayout
        var bookshelfSort = AppConfig.bookshelfSort
        var showBookname = AppConfig.showBookname
        var listItemStyle = AppConfig.bookshelfListItemStyle
        var listIntroLines = AppConfig.bookshelfListIntroLines
        if (bookshelfLayout !in 0..6) {
            bookshelfLayout = 0
            AppConfig.bookshelfLayout = 0
        }
        if (bookshelfSort !in 0..5) {
            bookshelfSort = 0
            AppConfig.bookshelfSort = 0
        }
        if (showBookname !in 0..2) {
            showBookname = 0
            AppConfig.showBookname = 0
        }
        if (listItemStyle !in 0..2) {
            listItemStyle = 0
            AppConfig.bookshelfListItemStyle = 0
        }
        if (listIntroLines !in 0..3) {
            listIntroLines = 2
            AppConfig.bookshelfListIntroLines = 2
        }
        showDialogFragment(
            BookshelfConfigDialog.create(
                initialValues = BookshelfConfigValues(
                    groupStyle = AppConfig.bookGroupStyle,
                    showUnread = AppConfig.showUnread,
                    showLastUpdateTime = AppConfig.showLastUpdateTime,
                    showWaitUpCount = AppConfig.showWaitUpCount,
                    showFastScroller = AppConfig.showBookshelfFastScroller,
                    returnToTopAfterRead = AppConfig.bookshelfReturnToTopAfterRead,
                    layout = bookshelfLayout,
                    sort = bookshelfSort,
                    showBookname = showBookname,
                    listItemStyle = listItemStyle,
                    listIntroLines = listIntroLines,
                    margin = AppConfig.bookshelfMargin
                ),
                onPreviewMarginChange = ::previewBookshelfMargin,
                onApply = { values ->
                    applyBookshelfConfig(
                        previousLayout = bookshelfLayout,
                        previousSort = bookshelfSort,
                        previousShowBookname = showBookname,
                        values = values
                    )
                }
            )
        )
    }

    private fun previewBookshelfMargin(margin: Int) {
        val normalizedMargin = margin.coerceIn(0, 60)
        if (AppConfig.bookshelfMargin == normalizedMargin) {
            return
        }
        AppConfig.bookshelfMargin = normalizedMargin
        postEvent(EventBus.BOOKSHELF_REFRESH, "")
    }

    private fun applyBookshelfConfig(
        previousLayout: Int,
        previousSort: Int,
        previousShowBookname: Int,
        values: BookshelfConfigValues
    ) {
        val groupStyle = values.groupStyle.coerceIn(0, 1)
        val layout = values.layout.coerceIn(0, 6)
        val sort = values.sort.coerceIn(0, 5)
        val showBookname = values.showBookname.coerceIn(0, 2)
        val listItemStyle = values.listItemStyle.coerceIn(0, 2)
        val listIntroLines = values.listIntroLines.coerceIn(0, 3)
        val margin = values.margin.coerceIn(0, 60)
        var notifyMain = false
        var structureChanged = false
        var refreshBookshelf = false
        // 事件分类对齐 archive（config-needs-restart-fix AD-02 权威表）：
        // STRUCTURE 仅 layout/showBookname；REFRESH 为 margin/listItemStyle/listIntroLines/
        // showUnread/showLastUpdateTime/showFastScroller/showWaitUpCount（K3 OURS 无 postUpBooksLiveData）；
        // sort 仅 upSort()；returnToTopAfterRead 仅存值；groupStyle 走 NOTIFY_MAIN
        if (AppConfig.bookGroupStyle != groupStyle) {
            AppConfig.bookGroupStyle = groupStyle
            notifyMain = true
        }
        if (AppConfig.showBookname != showBookname) {
            AppConfig.showBookname = showBookname
            structureChanged = true
        }
        if (AppConfig.bookshelfMargin != margin) {
            AppConfig.bookshelfMargin = margin
            refreshBookshelf = true
        }
        if (AppConfig.bookshelfListItemStyle != listItemStyle) {
            AppConfig.bookshelfListItemStyle = listItemStyle
            refreshBookshelf = true
        }
        if (AppConfig.bookshelfListIntroLines != listIntroLines) {
            AppConfig.bookshelfListIntroLines = listIntroLines
            refreshBookshelf = true
        }
        if (AppConfig.showUnread != values.showUnread) {
            AppConfig.showUnread = values.showUnread
            refreshBookshelf = true
        }
        if (AppConfig.showLastUpdateTime != values.showLastUpdateTime) {
            AppConfig.showLastUpdateTime = values.showLastUpdateTime
            refreshBookshelf = true
        }
        if (AppConfig.showWaitUpCount != values.showWaitUpCount) {
            AppConfig.showWaitUpCount = values.showWaitUpCount
            refreshBookshelf = true
        }
        if (AppConfig.showBookshelfFastScroller != values.showFastScroller) {
            AppConfig.showBookshelfFastScroller = values.showFastScroller
            refreshBookshelf = true
        }
        if (AppConfig.bookshelfReturnToTopAfterRead != values.returnToTopAfterRead) {
            AppConfig.bookshelfReturnToTopAfterRead = values.returnToTopAfterRead
        }
        if (previousSort != sort) {
            AppConfig.bookshelfSort = sort
            upSort()
        }
        if (previousLayout != layout) {
            AppConfig.bookshelfLayout = layout
            if (AppConfig.bookshelfLayout < 2) {
                activityViewModel.booksGridRecycledViewPool.clear()
            } else {
                activityViewModel.booksListRecycledViewPool.clear()
            }
            structureChanged = true
        }
        if (notifyMain) {
            postEvent(EventBus.NOTIFY_MAIN, false)
        } else if (structureChanged) {
            // 对齐 archive：延迟一帧发布，等弹框 dismiss 动画完成再重建
            view?.post {
                postEvent(EventBus.BOOKSHELF_STRUCTURE_CHANGED, "")
            }
        } else if (refreshBookshelf) {
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
        }
    }

    /**
     * K7 存量迁移：showBookname 弹框/渲染语义错位修复（config-needs-restart-fix）。
     * 历史弹框值 [显示→0, 隐藏→1] 与渲染语义 [1=显示, 0=无书名] 反转，意图无法恢复；
     * 按用户裁决一次性重置为 1（显示）；2（遮罩）两端语义一致，保留。
     */
    protected fun migrateLegacyShowBookname() {
        if (AppConfig.showBookname == 2) {
            appCtx.putPrefBoolean(PreferKey.bookshelfShowBooknameMigrated, true)
            return
        }
        if (appCtx.getPrefBoolean(PreferKey.bookshelfShowBooknameMigrated, false)) {
            return
        }
        AppConfig.showBookname = 1
        appCtx.putPrefBoolean(PreferKey.bookshelfShowBooknameMigrated, true)
    }


    private fun importBookshelfAlert(groupId: Long) {
        showComposeTextInputDialog(
            title = getString(R.string.import_bookshelf),
            hint = "url/json",
            neutralText = getString(R.string.select_file),
            onPositive = {
                viewModel.importBookshelf(it, groupId)
            },
            onNeutral = {
                importBookshelf.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("txt", "json")
                }
            }
        )
    }

}