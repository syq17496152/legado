package io.legado.app.ui.book.cache

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst.charsets
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.ActivityCacheBookBinding
import io.legado.app.databinding.DialogSelectSectionExportBinding
import io.legado.app.help.ConcurrentRateLimiter
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.tryParesExportFileName
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.CacheBook
import io.legado.app.service.ExportBookService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.utils.ACache
import io.legado.app.utils.FileDoc
import io.legado.app.utils.checkWrite
import io.legado.app.utils.cnCompare
import io.legado.app.utils.enableCustomExport
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.observeEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startService
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.verificationField
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import kotlin.math.max

/**
 * cache/download 缓存界面
 */
class CacheActivity : VMBaseActivity<ActivityCacheBookBinding, CacheViewModel>() {

    override val binding by viewBinding(ActivityCacheBookBinding::inflate)
    override val viewModel by viewModels<CacheViewModel>()
    private val cacheManageViewModel by viewModels<CacheManageViewModel>()

    private val exportBookPathKey = "exportBookPath"
    private val exportTypes = arrayListOf("txt", "epub")
    private var booksFlowJob: Job? = null
    private val groupList = mutableStateListOf<BookGroup>()
    private var groupId: Long = -1

    // 列表数据源与局部刷新 tick：书籍列表用 SnapshotStateList，事件驱动局部更新按 bookUrl bump tick
    private val booksCache = mutableStateListOf<Book>()
    private val refreshTicks = mutableStateMapOf<String, Long>()
    // 导出目录选择回调待导出的书籍：单本=该书籍，全部导出=null
    private var pendingExportBook: Book? = null

    // L-B10 顶栏 Compose 状态
    private var composeTitle by mutableStateOf("")
    private var composeSubtitle by mutableStateOf("")
    private var menuExpanded by mutableStateOf(false)
    private var downloadMenuExpanded by mutableStateOf(false)
    private var groupMenuExpanded by mutableStateOf(false)
    private var downloadRunning by mutableStateOf(CacheBook.isRun)

    private val exportDir = registerForActivityResult(HandleFileContract()) { result ->
        var isReadyPath = false
        var dirPath = ""
        result.uri?.let { uri ->
            if (uri.isContentScheme()) {
                ACache.get().put(exportBookPathKey, uri.toString())
                dirPath = uri.toString()
                isReadyPath = true
            } else {
                uri.path?.let { path ->
                    ACache.get().put(exportBookPathKey, path)
                    dirPath = path
                    isReadyPath = true
                }
            }
        }
        if (!isReadyPath) {
            return@registerForActivityResult
        }
        if (enableCustomExport()) {// 启用自定义导出 and 导出类型为Epub
            pendingExportBook?.let { configExportSection(dirPath, it) }
        } else {
            startExport(dirPath, pendingExportBook)
        }
    }

    // ui-theme-governance-polish P6：管理族宿主接入背景透明度（1.5 封闭清单成员）
    override fun manageBackgroundAlphaEnabled(): Boolean = true

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        groupId = intent.getLongExtra("groupId", -1)
        composeTitle = getString(R.string.offline_cache)
        lifecycleScope.launch {
            composeSubtitle = withContext(IO) {
                appDb.bookGroupDao.getByID(groupId)?.groupName
                    ?: getString(R.string.no_group)
            }
        }
        initComposeTopBar()
        initComposeList()
        initGroupData()
        initBookData()
    }

    // L-B10 顶栏 Compose 化：GlassTopAppBar + 下载子菜单/分组/更多菜单全量下沉 AppDropdownMenu
    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                Box {
                    GlassTopAppBar(
                        title = if (composeSubtitle.isBlank()) composeTitle
                        else "$composeTitle • $composeSubtitle",
                        navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        onNavClick = { finish() },
                        actions = {
                            // 下载按钮：点击展开下载子菜单（当前章起/全部/停止）
                            Box {
                                IconButton(onClick = { downloadMenuExpanded = true }) {
                                    Icon(
                                        imageVector = if (downloadRunning) Icons.Filled.Stop else Icons.Filled.Download,
                                        contentDescription = getString(R.string.action_download)
                                    )
                                }
                                AppDropdownMenu(
                                    expanded = downloadMenuExpanded,
                                    onDismiss = { downloadMenuExpanded = false },
                                    actions = buildDownloadMenuActions()
                                )
                            }
                            // 分组按钮：点击展开分组切换子菜单
                            Box {
                                IconButton(onClick = { groupMenuExpanded = true }) {
                                    Icon(
                                        imageVector = Icons.Filled.Groups,
                                        contentDescription = getString(R.string.group)
                                    )
                                }
                                AppDropdownMenu(
                                    expanded = groupMenuExpanded,
                                    onDismiss = { groupMenuExpanded = false },
                                    actions = buildGroupMenuActions()
                                )
                            }
                            // 更多菜单：导出/替换/缓存并发率/日志/分项统计等全量下沉
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
                }
            }
        }
    }

    /** 下载子菜单（原 menu_book_cache_download + menu_download） */
    private fun buildDownloadMenuActions(): List<MenuAction> {
        return listOf(
            MenuAction(
                Icons.Filled.BubbleChart,
                if (downloadRunning) getString(R.string.stop)
                else getString(R.string.menu_download_after),
                onClick = {
                    if (!downloadRunning) sureCacheBook {
                        booksCache.forEach { book ->
                            CacheBook.start(
                                this@CacheActivity,
                                book,
                                book.durChapterIndex,
                                book.lastChapterIndex
                            )
                        }
                    } else {
                        CacheBook.stop(this@CacheActivity)
                    }
                }
            ),
            MenuAction(
                Icons.Filled.BubbleChart,
                getString(R.string.menu_download_all),
                onClick = {
                    if (!downloadRunning) sureCacheBook {
                        booksCache.forEach { book ->
                            CacheBook.start(
                                this@CacheActivity,
                                book,
                                0,
                                book.lastChapterIndex
                            )
                        }
                    } else {
                        CacheBook.stop(this@CacheActivity)
                    }
                }
            )
        )
    }

    /** 分组切换子菜单（原 menu_book_group 动态分组） */
    private fun buildGroupMenuActions(): List<MenuAction> {
        val actions = mutableListOf<MenuAction>()
        groupList.forEach { bookGroup ->
            actions += MenuAction(
                Icons.Filled.Folder,
                bookGroup.groupName,
                onClick = {
                    composeSubtitle = bookGroup.groupName
                    lifecycleScope.launch {
                        groupId = withContext(IO) { appDb.bookGroupDao.getByName(bookGroup.groupName) }?.groupId ?: 0
                        initBookData()
                    }
                }
            )
        }
        return actions
    }

    /**
     * 更多菜单（原 book_cache 全部导出/替换/并发率等项）
     */
    private fun buildMenuActions(): List<MenuAction> {
        val cacheRate = AppConfig.cacheConcurrentRate
        return listOf(
            MenuAction(Icons.Filled.Download, getString(R.string.export_all), onClick = { exportAll() }),
            MenuAction(
                Icons.Filled.List,
                getString(R.string.replace_purify),
                checked = AppConfig.exportUseReplace,
                onClick = { AppConfig.exportUseReplace = !AppConfig.exportUseReplace }
            ),
            MenuAction(
                Icons.Filled.List,
                getString(R.string.custom_export_section),
                checked = AppConfig.enableCustomExport,
                onClick = { AppConfig.enableCustomExport = !AppConfig.enableCustomExport }
            ),
            MenuAction(
                Icons.Filled.List,
                getString(R.string.export_no_chapter_name),
                checked = AppConfig.exportNoChapterName,
                onClick = { AppConfig.exportNoChapterName = !AppConfig.exportNoChapterName }
            ),
            MenuAction(
                Icons.Filled.List,
                getString(R.string.export_to_web_dav),
                checked = AppConfig.exportToWebDav,
                onClick = { AppConfig.exportToWebDav = !AppConfig.exportToWebDav }
            ),
            MenuAction(
                Icons.Filled.List,
                getString(R.string.export_pics_file),
                checked = AppConfig.exportPictureFile,
                onClick = { AppConfig.exportPictureFile = !AppConfig.exportPictureFile }
            ),
            MenuAction(
                Icons.Filled.List,
                getString(R.string.parallel_export_book),
                checked = AppConfig.parallelExportBook,
                onClick = { AppConfig.parallelExportBook = !AppConfig.parallelExportBook }
            ),
            MenuAction(Icons.Filled.Folder, getString(R.string.export_folder), onClick = { selectExportFolder() }),
            MenuAction(Icons.Filled.List, getString(R.string.export_file_name), onClick = { alertExportFileName() }),
            MenuAction(
                Icons.Filled.List,
                getString(R.string.export_type) + "(${getTypeName()})",
                onClick = { showExportTypeConfig() }
            ),
            MenuAction(
                Icons.Filled.List,
                getString(R.string.export_charset) + "(${AppConfig.exportCharset})",
                onClick = { showCharsetConfig() }
            ),
            MenuAction(
                Icons.Filled.List,
                getString(R.string.cache_concurrent_rate) +
                    if (cacheRate.isNullOrBlank()) "(${getString(R.string.text_default)})" else "($cacheRate)",
                onClick = { showCacheRateDialog() }
            ),
            MenuAction(Icons.Filled.List, getString(R.string.log), onClick = { showDialogFragment<AppLogDialog>() }),
            MenuAction(Icons.Filled.List, getString(R.string.cache_stats), onClick = { showCacheStatsDialog() })
        )
    }

    private fun initComposeList() {
        binding.recyclerView.setContent {
            LegadoTheme {
                CacheScreen(
                    books = booksCache,
                    refreshTickOf = { refreshTicks[it] ?: 0L },
                    cacheChaptersOf = { viewModel.cacheChapters[it] },
                    exportMsgOf = { ExportBookService.exportMsg[it] },
                    exportProgressOf = { ExportBookService.exportProgress[it] },
                    onDownloadToggle = {
                        if (CacheBook.cacheBookMap[it.bookUrl]?.isStop() == false) {
                            CacheBook.remove(this, it.bookUrl)
                        } else {
                            CacheBook.start(this, it, 0, it.lastChapterIndex)
                        }
                    },
                    onExport = { export(it) }
                )
            }
        }
    }

    private fun initBookData() {
        booksFlowJob?.cancel()
        booksFlowJob = lifecycleScope.launch {
            appDb.bookDao.flowByGroup(groupId).map { books ->
                val booksDownload = books.filter {
                    !it.isAudio
                }
                when (AppConfig.getBookSortByGroupId(groupId)) {
                    1 -> booksDownload.sortedByDescending { it.latestChapterTime }
                    2 -> booksDownload.sortedWith { o1, o2 ->
                        o1.name.cnCompare(o2.name)
                    }

                    3 -> booksDownload.sortedBy { it.order }
                    4 -> booksDownload.sortedByDescending {
                        max(it.latestChapterTime, it.durChapterTime)
                    }

                    else -> booksDownload.sortedByDescending { it.durChapterTime }
                }
            }.flowOn(IO).flowWithLifecycleAndDatabaseChange(
                lifecycle, table = AppDatabase.BOOK_TABLE_NAME
            ).catch {
                AppLog.put("缓存管理界面获取书籍列表失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect { books ->
                booksCache.clear()
                booksCache.addAll(books)
                viewModel.loadCacheFiles(books)
            }
        }
    }

    private fun initGroupData() {
        lifecycleScope.launch {
            appDb.bookGroupDao.flowAll().catch {
                AppLog.put("缓存管理界面获取分组数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                groupList.clear()
                groupList.addAll(it)
            }
        }
    }

    private fun notifyItemChanged(bookUrl: String) {
        // 按 bookUrl bump 局部刷新 tick，触发对应 Compose item 重组读取最新进度
        refreshTicks[bookUrl] = (refreshTicks[bookUrl] ?: 0L) + 1L
    }

    override fun observeLiveBus() {
        viewModel.upAdapterLiveData.observe(this) {
            notifyItemChanged(it)
        }
        observeEvent<String>(EventBus.EXPORT_BOOK) {
            notifyItemChanged(it)
        }
        observeEvent<String>(EventBus.UP_DOWNLOAD) {
            notifyItemChanged(it)
        }
        observeEvent<String>(EventBus.UP_DOWNLOAD_STATE) {
            downloadRunning = CacheBook.isRun
        }
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            viewModel.cacheChapters[book.bookUrl]?.add(chapter.url)
            notifyItemChanged(book.bookUrl)
        }
    }

    private fun export(book: Book) {
        pendingExportBook = book
        val path = ACache.get().getAsString(exportBookPathKey)
        lifecycleScope.launch {
            if (path.isNullOrEmpty() ||
                withContext(IO) { !FileDoc.fromDir(path).checkWrite() }
            ) {
                selectExportFolder()
            } else if (enableCustomExport()) {// 启用自定义导出 and 导出类型为Epub
                configExportSection(path, book)
            } else {
                startExport(path, book)
            }
        }
    }

    private fun exportAll() {
        pendingExportBook = null
        val path = ACache.get().getAsString(exportBookPathKey)
        if (path.isNullOrEmpty()) {
            selectExportFolder()
        } else {
            startExport(path, null)
        }
    }

    /**
     * 配置自定义导出对话框
     *
     * @param path  导出路径
     * @param book  待导出的书籍
     * @author Discut
     * @since 1.0.0
     */
    private fun configExportSection(path: String, book: Book) {

        val alertBinding = DialogSelectSectionExportBinding.inflate(layoutInflater)
            .apply {
                fun verifyExportFileNameJsStr(js: String): Boolean {
                    return tryParesExportFileName(js) && etEpubFilename.text.toString()
                        .isNotEmpty()
                }

                fun enableLyEtEpubFilenameIcon() {
                    lyEtEpubFilename.endIconMode = TextInputLayout.END_ICON_CUSTOM
                    lyEtEpubFilename.setEndIconOnClickListener {
                        book.run {
                            lyEtEpubFilename.helperText =
                                if (verifyExportFileNameJsStr(etEpubFilename.text.toString()))
                                    "${resources.getString(R.string.result_analyzed)}: ${
                                        getExportFileName(
                                            "epub",
                                            1,
                                            etEpubFilename.text.toString()
                                        )
                                    }"
                                else "Error"
                        }
                    }
                }
                etEpubSize.setText("1")
                // lyEtEpubFilename.endIconMode = TextInputLayout.END_ICON_NONE
                etEpubFilename.text?.append(AppConfig.episodeExportFileName)
                // 存储解析文件名的jsStr
                etEpubFilename.let {
                    it.setOnFocusChangeListener { _, hasFocus ->
                        if (hasFocus)
                            return@setOnFocusChangeListener
                        it.text?.run {
                            if (verifyExportFileNameJsStr(toString())) {
                                AppConfig.episodeExportFileName = toString()
                            }
                        }
                    }
                }
                tvAllExport.setOnClickListener {
                    cbAllExport.callOnClick()
                }
                tvSelectExport.setOnClickListener {
                    cbSelectExport.callOnClick()
                }
                cbSelectExport.onCheckedChangeListener = { _, isChecked ->
                    if (isChecked) {
                        etEpubSize.isEnabled = true
                        etInputScope.isEnabled = true
                        etEpubFilename.isEnabled = true
                        enableLyEtEpubFilenameIcon()
                        cbAllExport.isChecked = false
                    }
                }
                cbAllExport.onCheckedChangeListener = { _, isChecked ->
                    if (isChecked) {
                        etEpubSize.isEnabled = false
                        etInputScope.isEnabled = false
                        etEpubFilename.isEnabled = false
                        lyEtEpubFilename.endIconMode = TextInputLayout.END_ICON_NONE
                        cbSelectExport.isChecked = false
                    }
                }

                etInputScope.onFocusChangeListener =
                    View.OnFocusChangeListener { _, hasFocus ->
                        if (hasFocus) {
                            etInputScope.hint = "1-5,8,10-18"
                        } else {
                            etInputScope.hint = ""
                        }
                    }

                // 默认选择自定义导出
                cbSelectExport.callOnClick()
            }
        val alertDialog = alert(titleResource = R.string.select_section_export) {
            customView { alertBinding.root }
            positiveButton(R.string.ok)
            cancelButton()
        }
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            alertBinding.apply {
                if (cbAllExport.isChecked) {
                    startExport(path, book)
                    alertDialog.hide()
                    return@apply
                }
                val epubScope = etInputScope.text.toString()
                if (!verificationField(epubScope)) {
                    etInputScope.error = appCtx.getString(R.string.error_scope_input)//"请输入正确的范围"
                    return@apply
                }
                etInputScope.error = null
                val epubSize = etEpubSize.text.toString().toIntOrNull() ?: 1
                startService<ExportBookService> {
                    action = IntentAction.start
                    putExtra("bookUrl", book.bookUrl)
                    putExtra("exportType", "epub")
                    putExtra("exportPath", path)
                    putExtra("epubSize", epubSize)
                    putExtra("epubScope", epubScope)
                }
                alertDialog.hide()
            }

        }
    }

    private fun selectExportFolder() {
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(exportBookPathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        exportDir.launch {
            otherActions = default
        }
    }

    private fun startExport(path: String, book: Book?) {
        val exportType = when (AppConfig.exportType) {
            1 -> "epub"
            else -> "txt"
        }
        if (book == null) {
            // 全部导出
            if (booksCache.isNotEmpty()) {
                booksCache.forEach { item ->
                    startService<ExportBookService> {
                        action = IntentAction.start
                        putExtra("bookUrl", item.bookUrl)
                        putExtra("exportType", exportType)
                        putExtra("exportPath", path)
                    }
                }
            } else {
                toastOnUi(R.string.no_book)
            }
        } else {
            startService<ExportBookService> {
                action = IntentAction.start
                putExtra("bookUrl", book.bookUrl)
                putExtra("exportType", exportType)
                putExtra("exportPath", path)
            }
        }
    }

    private fun alertExportFileName() {
        // 弹框托管（ui-theme-governance-polish tasks 9.4 孤岛家族迁移）
        showComposeTextInputDialog(
            title = getString(R.string.export_file_name),
            message = "Variable: name, author.",
            hint = "file name js",
            initialValue = AppConfig.bookExportFileName.orEmpty(),
            positiveText = getString(R.string.ok),
            negativeText = getString(R.string.cancel),
            onPositive = { text ->
                AppConfig.bookExportFileName = text
            }
        )
    }

    private fun getTypeName(): String {
        return exportTypes.getOrElse(AppConfig.exportType) {
            exportTypes[0]
        }
    }

    private fun showExportTypeConfig() {
        showComposeChoiceListDialog(
            title = getString(R.string.export_type),
            labels = exportTypes,
            selectedIndex = AppConfig.exportType
        ) { i ->
            AppConfig.exportType = i
        }
    }

    private fun showCharsetConfig() {
        showComposeTextInputDialog(
            title = getString(R.string.set_charset),
            hint = "charset name",
            initialValue = AppConfig.exportCharset,
            positiveText = getString(R.string.ok),
            negativeText = getString(R.string.cancel),
            onPositive = { text ->
                AppConfig.exportCharset = text.ifBlank { "UTF-8" }
            }
        )
    }

    private fun sureCacheBook(action: () -> Unit) {
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.sure_cache_book),
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            onPositive = { action.invoke() }
        )
    }

    /**
     * B11 缓存分项统计对话框
     */
    private fun showCacheStatsDialog() {
        cacheManageViewModel.buildStorageBreakdown().onSuccess { details ->
            if (details.all { it.bytes <= 0L }) {
                toastOnUi(R.string.cache_stats_empty)
                return@onSuccess
            }
            val names = details.filter { it.bytes > 0L }.map { detail ->
                val size = formatBytes(detail.bytes)
                val name = getString(detail.nameRes)
                "${name}: $size"
            }.toMutableList()
            val total = details.sumOf { it.bytes }
            names.add("${getString(R.string.cache_stats_total)}: ${formatBytes(total)}")
            showComposeChoiceListDialog(
                title = getString(R.string.cache_stats),
                labels = names
            ) { index ->
                val detail = details.filter { it.bytes > 0L }[index]
                deleteStorageTarget(detail)
            }
        }
    }

    private fun deleteStorageTarget(detail: CacheStorageDetail) {
        showComposeConfirmDialog(
            title = getString(R.string.cache_stats),
            message = getString(R.string.cache_stats_delete_msg, getString(detail.nameRes)),
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = {
                cacheManageViewModel.deleteStorageTarget(detail).onSuccess { success ->
                    if (success) {
                        toastOnUi(R.string.cache_stats_delete_success)
                    } else {
                        toastOnUi(R.string.cache_stats_video_playing)
                    }
                }
            }
        )
    } 

    /**
     * B12 缓存并发率设置对话框
     */
    private fun showCacheRateDialog() {
        // 弹框托管（ui-theme-governance-polish tasks 9.4 孤岛家族迁移）
        // 行为等价：原 View 版通过覆写确定按钮点击实现"无效格式→toast 且不关闭"，validateInput 语义一致
        showComposeTextInputDialog(
            title = getString(R.string.cache_concurrent_rate),
            message = getString(R.string.cache_rate_desc),
            hint = getString(R.string.cache_rate_hint),
            initialValue = AppConfig.cacheConcurrentRate.orEmpty(),
            positiveText = getString(R.string.ok),
            negativeText = getString(R.string.cancel),
            validateInput = { value ->
                if (!value.isNullOrBlank() && !ConcurrentRateLimiter.isValidRate(value)) {
                    toastOnUi(R.string.cache_rate_invalid)
                    false
                } else {
                    true
                }
            },
            onPositive = { value ->
                AppConfig.cacheConcurrentRate = value.ifBlank { null }
                kotlin.runCatching {
                    AppLog.putDebugWithTag(
                        AppLog.TAG_CACHE_CONCURRENT,
                        "缓存并发率设置变更 -> $value",
                        level = AppLog.Level.INFO
                    )
                }
            }
        )
    }

}
