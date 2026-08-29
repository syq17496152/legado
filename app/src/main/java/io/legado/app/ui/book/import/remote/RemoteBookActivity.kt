package io.legado.app.ui.book.import.remote

import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.remote.RemoteBook
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.book.import.BaseImportBookActivity
import io.legado.app.ui.book.import.ImportBookDisplayItem
import io.legado.app.ui.book.import.ImportBookScreen
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.components.MenuAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.find
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 展示远程书籍（L-B9 S2 列表管理页）：Compose 内容区（ImportBookScreen）+
 * View SelectActionBar 多选底栏混合接线。
 */
class RemoteBookActivity : BaseImportBookActivity<RemoteBookViewModel>(),
    SelectActionBar.CallBack,
    ServersDialog.Callback {

    override val viewModel by viewModels<RemoteBookViewModel>()

    // Compose 桥接状态
    private var composeItems by mutableStateOf(listOf<ImportBookDisplayItem>())
    private var composePath by mutableStateOf("")
    private var composeCanGoBack by mutableStateOf(false)
    private var composeIsLoading by mutableStateOf(false)
    private var composeSearchQuery by mutableStateOf("")
    // 当前列表原始数据 + 选中索引
    private var currentItems = listOf<RemoteBook>()
    private var selectedIndexes = linkedSetOf<Int>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(this) {
            if (!goBackDir()) {
                finish()
            }
        }
        initSelectActionBar()
        initComposeHost()
        lifecycleScope.launch {
            if (!setBookStorage()) {
                finish()
                return@launch
            }
            launch {
                viewModel.dataFlow.conflate().collect { sortedRemoteBooks ->
                    currentItems = sortedRemoteBooks
                    selectedIndexes.retainAll(sortedRemoteBooks.indices)
                    refreshComposeItems()
                    delay(500)
                }
            }
            viewModel.initData {
                upPath()
            }
        }
    }

    override fun observeLiveBus() {
        viewModel.permissionDenialLiveData.observe(this) {
            localBookTreeSelect.launch {
                title = getString(R.string.select_book_folder)
            }
        }
    }

    private fun initSelectActionBar() {
        binding.selectActionBar.setMainActionText(R.string.add_to_bookshelf)
        binding.selectActionBar.setCallBack(this)
        if (!LocalConfig.webDavBookHelpVersionIsLast) {
            showHelp("webDavBookHelp")
        }
    }

    private fun initComposeHost() {
        binding.composeHost.setContent {
            LegadoTheme {
                ImportBookScreen(
                    items = composeItems,
                    currentPath = composePath,
                    canGoBack = composeCanGoBack,
                    isLoading = composeIsLoading,
                    title = getString(R.string.remote_book),
                    menuActions = buildMenuActions(),
                    searchQuery = composeSearchQuery,
                    onSearchQueryChange = { composeSearchQuery = it; onSearchTextChange(it) },
                    onBack = { finish() },
                    onGoBack = { goBackDir() },
                    onItemClick = { onItemClick(it) },
                    onItemLongClick = { onItemLongClick(it) },
                    emptyMessage = getString(R.string.empty)
                )
            }
        }
    }

    private fun buildMenuActions(): List<MenuAction> = listOf(
        MenuAction(
            icon = Icons.Default.Refresh,
            title = getString(R.string.refresh),
            // topbar-icon-semantics-fix 3.3：刷新恢复一级图标（原版 book_remote.xml menu_refresh always）
            alwaysShow = true,
            onClick = { upPath() }
        ),
        MenuAction(
            icon = Icons.Default.Sort,
            title = getString(R.string.sort_by_name),
            checked = viewModel.sortKey == RemoteBookSort.Name,
            onClick = {
                sortCheck(RemoteBookSort.Name)
                upPath()
            }
        ),
        MenuAction(
            icon = Icons.Default.Sort,
            title = getString(R.string.sort_by_lastUpdateTime),
            checked = viewModel.sortKey == RemoteBookSort.Default,
            onClick = {
                sortCheck(RemoteBookSort.Default)
                upPath()
            }
        ),
        MenuAction(
            icon = Icons.Default.Settings,
            title = getString(R.string.server_config),
            onClick = { showDialogFragment<ServersDialog>() }
        ),
        MenuAction(
            icon = Icons.Default.HelpOutline,
            title = getString(R.string.help),
            onClick = { showHelp("webDavBookHelp") }
        ),
        MenuAction(
            icon = Icons.Default.Info,
            title = getString(R.string.log),
            onClick = { showDialogFragment<AppLogDialog>() }
        )
    )

    private fun sortCheck(sortKey: RemoteBookSort) {
        if (viewModel.sortKey == sortKey) {
            viewModel.sortAscending = !viewModel.sortAscending
        } else {
            viewModel.sortAscending = true
            viewModel.sortKey = sortKey
        }
    }

    private fun onItemClick(index: Int) {
        val item = currentItems.getOrNull(index) ?: return
        if (item.isDir) {
            openDir(item)
        } else if (!item.isOnBookShelf) {
            toggleSelect(index)
        } else {
            startRead(item)
        }
    }

    private fun onItemLongClick(index: Int) {
        val item = currentItems.getOrNull(index) ?: return
        if (item.isOnBookShelf) {
            addToBookShelfAgain(item)
        }
    }

    private fun toggleSelect(index: Int) {
        if (!selectedIndexes.remove(index)) {
            selectedIndexes.add(index)
        }
        refreshComposeItems()
        upCountView()
    }

    override fun revertSelection() {
        val checkable = currentItems.mapIndexedNotNull { index, item ->
            if (!item.isDir && !item.isOnBookShelf) index else null
        }
        val selected = selectedIndexes
        selectedIndexes = checkable.filter { !selected.contains(it) }.toCollection(linkedSetOf())
        refreshComposeItems()
        upCountView()
    }

    override fun selectAll(selectAll: Boolean) {
        selectedIndexes = if (selectAll) {
            currentItems.mapIndexedNotNull { index, item ->
                if (!item.isDir && !item.isOnBookShelf) index else null
            }.toCollection(linkedSetOf())
        } else {
            linkedSetOf()
        }
        refreshComposeItems()
        upCountView()
    }

    override fun onClickSelectBarMainAction() {
        val selection = currentItems.filterIndexed { index, _ -> index in selectedIndexes }
        if (selection.isEmpty()) return
        composeIsLoading = true
        viewModel.addToBookshelf(selection.toHashSet()) {
            selectedIndexes.clear()
            refreshComposeItems()
            upCountView()
            composeIsLoading = false
        }
    }

    private fun goBackDir(): Boolean {
        if (viewModel.dirList.isEmpty()) {
            return false
        }
        viewModel.dirList.removeLastOrNull()
        upPath()
        return true
    }

    private fun upPath() {
        composeCanGoBack = viewModel.dirList.isNotEmpty()
        composePath = buildPath()
        selectedIndexes.clear()
        viewModel.loadRemoteBookList(
            viewModel.dirList.lastOrNull()?.path
        ) { loading ->
            composeIsLoading = loading
        }
    }

    private fun buildPath(): String {
        var path = if (viewModel.isDefaultWebdav) {
            "books" + File.separator
        } else {
            File.separator
        }
        viewModel.dirList.forEach {
            path = path + it.filename + File.separator
        }
        return path
    }

    private fun refreshComposeItems() {
        composeItems = currentItems.mapIndexed { index, item ->
            ImportBookDisplayItem(
                name = item.filename,
                isDir = item.isDir,
                isOnBookShelf = item.isOnBookShelf,
                tag = item.contentType,
                size = ConvertUtils.formatFileSize(item.size),
                date = AppConst.dateFormat.format(item.lastModify),
                isSelected = index in selectedIndexes
            )
        }
    }

    fun openDir(remoteBook: RemoteBook) {
        viewModel.dirList.add(remoteBook)
        upPath()
    }

    fun upCountView() {
        binding.selectActionBar.upCountView(selectedIndexes.size, checkableCount())
    }

    private fun checkableCount(): Int = currentItems.count { !it.isDir && !it.isOnBookShelf }

    override fun onDialogDismiss(tag: String) {
        viewModel.initData {
            upPath()
        }
    }

    override fun onSearchTextChange(newText: String?) {
        viewModel.updateCallBackFlow(newText)
    }

    private fun showRemoteBookDownloadAlert(
        remoteBook: RemoteBook,
        onDownloadFinish: (() -> Unit)? = null
    ) {
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.archive_not_found),
            positiveText = getString(R.string.ok),
            negativeText = getString(R.string.no),
            onPositive = {
                viewModel.addToBookshelf(hashSetOf(remoteBook)) {
                    onDownloadFinish?.invoke()
                }
            }
        )
    }

    fun startRead(remoteBook: RemoteBook) {
        val downloadFileName = remoteBook.filename
        if (!ArchiveUtils.isArchive(downloadFileName)) {
            lifecycleScope.launch {
                withContext(IO) { appDb.bookDao.getBookByFileName(downloadFileName) }?.let {
                    startReadBook(it)
                }
            }
        } else {
            AppConfig.defaultBookTreeUri ?: return
            val downloadArchiveFileDoc = FileDoc.fromUri(
                android.net.Uri.parse(AppConfig.defaultBookTreeUri), true
            ).find(downloadFileName)
            if (downloadArchiveFileDoc == null) {
                showRemoteBookDownloadAlert(remoteBook) {
                    startRead(remoteBook)
                }
            } else {
                onArchiveFileClick(downloadArchiveFileDoc)
            }
        }
    }

    fun addToBookShelfAgain(remoteBook: RemoteBook) {
        showComposeConfirmDialog(
            title = getString(R.string.sure),
            message = getString(R.string.re_add_to_bookshelf),
            positiveText = getString(R.string.ok),
            negativeText = getString(R.string.no),
            onPositive = {
                composeIsLoading = true
                viewModel.addToBookshelf(hashSetOf(remoteBook)) {
                    composeIsLoading = false
                }
            }
        )
    }

}