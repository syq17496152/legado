package io.legado.app.ui.book.import.local

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.book.import.BaseImportBookActivity
import io.legado.app.ui.book.import.ImportBookDisplayItem
import io.legado.app.ui.book.import.ImportBookScreen
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.ui.widget.components.MenuAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sort
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isUri
import io.legado.app.utils.launch
import io.legado.app.utils.putPrefInt
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 导入本地书籍界面（L-B9 S2 列表管理页）：Compose 内容区（ImportBookScreen）+
 * View SelectActionBar 多选底栏混合接线。
 */
class ImportBookActivity : BaseImportBookActivity<ImportBookViewModel>(),
    SelectActionBar.CallBack {

    override val viewModel by viewModels<ImportBookViewModel>()
    private var scanDocJob: Job? = null

    // Compose 桥接状态
    private var composeItems by mutableStateOf(listOf<ImportBookDisplayItem>())
    private var composePath by mutableStateOf("")
    private var composeCanGoBack by mutableStateOf(false)
    private var composeIsLoading by mutableStateOf(false)
    private var composeSearchQuery by mutableStateOf("")
    // 当前列表原始数据（供多选操作）+ 选中索引
    private var currentItems = listOf<ImportBook>()
    private var selectedIndexes = linkedSetOf<Int>()

    private val selectFolder = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            AppConfig.importBookPath = uri.toString()
            initRootDoc(true)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(this) {
            if (!goBackDir()) {
                finish()
            }
        }
        initSelectActionBar()
        initComposeHost()
        lifecycleScope.launch {
            if (setBookStorage() && AppConfig.importBookPath.isNullOrBlank()) {
                AppConfig.importBookPath = AppConfig.defaultBookTreeUri
            }
            initData()
        }
    }

    private fun initSelectActionBar() {
        binding.selectActionBar.setMainActionText(R.string.add_to_bookshelf)
        binding.selectActionBar.inflateMenu(R.menu.import_book_sel)
        binding.selectActionBar.setOnMenuItemClickListener { item ->
            when (item?.itemId) {
                R.id.menu_del_selection -> deleteSelection()
            }
            false
        }
        binding.selectActionBar.setCallBack(this)
    }

    private fun initComposeHost() {
        binding.composeHost.setContent {
            LegadoTheme {
                ImportBookScreen(
                    items = composeItems,
                    currentPath = composePath,
                    canGoBack = composeCanGoBack,
                    isLoading = composeIsLoading,
                    title = getString(R.string.local_book),
                    menuActions = buildMenuActions(),
                    searchQuery = composeSearchQuery,
                    onSearchQueryChange = { composeSearchQuery = it; onSearchTextChange(it) },
                    onBack = { finish() },
                    onGoBack = { goBackDir() },
                    onItemClick = { onItemClick(it) },
                    onItemLongClick = { },
                    emptyMessage = getString(R.string.empty_msg_import_book)
                )
            }
        }
    }

    private fun buildMenuActions(): List<MenuAction> = listOf(
        MenuAction(
            icon = Icons.Default.FolderOpen,
            title = getString(R.string.select_folder),
            // topbar-icon-semantics-fix 3.3：选目录恢复一级图标（原版 import_book.xml menu_select_folder always）
            alwaysShow = true,
            onClick = { selectFolder.launch() }
        ),
        MenuAction(
            icon = Icons.Default.Sort,
            title = getString(R.string.sort_by_name),
            checked = viewModel.sort == 0,
            onClick = { upSort(0) }
        ),
        MenuAction(
            icon = Icons.Default.Sort,
            title = getString(R.string.sort_by_size),
            checked = viewModel.sort == 1,
            onClick = { upSort(1) }
        ),
        MenuAction(
            icon = Icons.Default.Sort,
            title = getString(R.string.sort_by_time),
            checked = viewModel.sort == 2,
            onClick = { upSort(2) }
        ),
        MenuAction(
            icon = Icons.Default.Refresh,
            title = getString(R.string.scan_folder),
            onClick = { scanFolder() }
        ),
        MenuAction(
            icon = Icons.Default.Edit,
            title = getString(R.string.import_file_name),
            onClick = { alertImportFileName() }
        )
    )

    private fun onItemClick(index: Int) {
        val item = currentItems.getOrNull(index) ?: return
        if (item.isDir) {
            nextDoc(item.file)
        } else if (!item.isOnBookShelf) {
            toggleSelect(index)
        } else {
            startRead(item.file)
        }
    }

    private fun toggleSelect(index: Int) {
        if (!selectedIndexes.remove(index)) {
            selectedIndexes.add(index)
        }
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

    override fun revertSelection() {
        val checkable = currentItems.mapIndexedNotNull { index, item ->
            if (!item.isDir && !item.isOnBookShelf) index else null
        }
        val selected = selectedIndexes
        selectedIndexes = checkable.filter { !selected.contains(it) }.toCollection(linkedSetOf())
        refreshComposeItems()
        upCountView()
    }

    override fun onClickSelectBarMainAction() {
        val selection = currentItems.filterIndexed { index, _ -> index in selectedIndexes }
        if (selection.isEmpty()) return
        viewModel.addToBookshelf(selection.toHashSet()) {
            selectedIndexes.clear()
            refreshComposeItems()
            upCountView()
        }
    }

    private fun deleteSelection() {
        val selection = currentItems.filterIndexed { index, _ -> index in selectedIndexes }
        if (selection.isEmpty()) return
        viewModel.deleteDoc(selection.toHashSet()) {
            selectedIndexes.clear()
            refreshComposeItems()
            upCountView()
        }
    }

    private fun initData() {
        viewModel.dataFlowStart = {
            initRootDoc()
        }
        lifecycleScope.launch {
            viewModel.dataFlow.conflate().collect { docs ->
                currentItems = docs
                selectedIndexes.retainAll(docs.indices)
                refreshComposeItems()
            }
        }
    }

    private fun refreshComposeItems() {
        composeItems = currentItems.mapIndexed { index, item ->
            ImportBookDisplayItem(
                name = item.name,
                isDir = item.isDir,
                isOnBookShelf = item.isOnBookShelf,
                tag = if (item.isDir) "" else item.name.substringAfterLast("."),
                size = ConvertUtils.formatFileSize(item.size),
                date = AppConst.dateFormat.format(item.lastModified),
                isSelected = index in selectedIndexes
            )
        }
        composeCanGoBack = viewModel.subDocs.isNotEmpty()
        composePath = buildPath()
    }

    private fun buildPath(): String {
        val rootDoc = viewModel.rootDoc ?: return ""
        var path = rootDoc.name + File.separator
        for (doc in viewModel.subDocs) {
            path = path + doc.name + File.separator
        }
        return path
    }

    private fun initRootDoc(changedFolder: Boolean = false) {
        if (viewModel.rootDoc != null && !changedFolder) {
            upPath()
        } else {
            val lastPath = AppConfig.importBookPath
            if (lastPath.isNullOrBlank()) {
                selectFolder.launch()
            } else {
                val rootUri = if (lastPath.isUri()) {
                    lastPath.toUri()
                } else {
                    Uri.fromFile(File(lastPath))
                }
                when {
                    rootUri.isContentScheme() -> initRootPath(rootUri)
                    else -> initRootPath(rootUri.path!!)
                }
            }
        }
    }

    private fun initRootPath(rootUri: Uri) {
        kotlin.runCatching {
            val doc = DocumentFile.fromTreeUri(this, rootUri)
            if (doc == null || doc.name.isNullOrEmpty() || !doc.isDirectory) {
                selectFolder.launch()
            } else {
                viewModel.subDocs.clear()
                viewModel.rootDoc = FileDoc.fromDocumentFile(doc)
                upPath()
            }
        }.onFailure {
            selectFolder.launch()
        }
    }

    private fun initRootPath(path: String) {
        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.tip_perm_request_storage)
            .onGranted {
                kotlin.runCatching {
                    val file = File(path)
                    if (!file.isDirectory) {
                        selectFolder.launch()
                    } else {
                        viewModel.subDocs.clear()
                        viewModel.rootDoc = FileDoc.fromFile(file)
                        upPath()
                    }
                }.onFailure {
                    selectFolder.launch()
                }
            }
            .request()
    }

    private fun upSort(sort: Int) {
        viewModel.sort = sort
        putPrefInt(PreferKey.localBookImportSort, sort)
        if (scanDocJob?.isActive != true) {
            viewModel.dataCallback?.upAdapter()
        }
    }

    @Synchronized
    private fun upPath() {
        composeCanGoBack = viewModel.subDocs.isNotEmpty()
        viewModel.rootDoc?.let {
            scanDocJob?.cancel()
            upDocs(it)
        }
    }

    private fun upDocs(rootDoc: FileDoc) {
        composePath = buildPath()
        selectedIndexes.clear()
        viewModel.loadDoc(rootDoc)
    }

    /**
     * 扫描当前文件夹及所有子文件夹
     */
    private fun scanFolder() {
        viewModel.rootDoc?.let { doc ->
            val lastDoc = viewModel.subDocs.lastOrNull() ?: doc
            composeIsLoading = true
            scanDocJob?.cancel()
            scanDocJob = lifecycleScope.launch(IO) {
                viewModel.scanDoc(lastDoc)
                withContext(Main) {
                    composeIsLoading = false
                }
            }
        }
    }

    private fun alertImportFileName() {
        // 弹框托管（ui-theme-governance-polish tasks 9.4 孤岛家族迁移）
        showComposeTextInputDialog(
            title = getString(R.string.import_file_name),
            message = """使用js处理文件名变量src，将书名作者分别赋值到变量name author""",
            hint = "js",
            initialValue = AppConfig.bookImportFileName.orEmpty(),
            positiveText = getString(R.string.ok),
            negativeText = getString(R.string.cancel),
            onPositive = { text ->
                AppConfig.bookImportFileName = text
            }
        )
    }

    @Synchronized
    fun nextDoc(fileDoc: FileDoc) {
        viewModel.subDocs.add(fileDoc)
        upPath()
    }

    @Synchronized
    private fun goBackDir(): Boolean {
        return if (viewModel.subDocs.isNotEmpty()) {
            viewModel.subDocs.removeAt(viewModel.subDocs.lastIndex)
            upPath()
            true
        } else {
            false
        }
    }

    override fun onSearchTextChange(newText: String?) {
        viewModel.updateCallBackFlow(newText)
    }

    fun upCountView() {
        binding.selectActionBar.upCountView(selectedIndexes.size, checkableCount())
    }

    private fun checkableCount(): Int = currentItems.count { !it.isDir && !it.isOnBookShelf }

    fun startRead(fileDoc: FileDoc) {
        if (!ArchiveUtils.isArchive(fileDoc.name)) {
            lifecycleScope.launch {
                withContext(IO) {
                    appDb.bookDao.getBookByFileName(fileDoc.name)?.let {
                        val filePath = fileDoc.toString()
                        if (it.bookUrl != filePath) {
                            it.bookUrl = filePath
                            appDb.bookDao.insert(it)
                        }
                        it
                    }
                }?.let {
                    startReadBook(it)
                }
            }
        } else {
            onArchiveFileClick(fileDoc)
        }
    }

}