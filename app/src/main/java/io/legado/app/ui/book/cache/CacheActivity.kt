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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
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
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogSelectSectionExportBinding
import io.legado.app.help.ConcurrentRateLimiter
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.tryParesExportFileName
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.CacheBook
import io.legado.app.service.ExportBookService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.utils.ACache
import io.legado.app.utils.FileDoc
import io.legado.app.utils.applyNavigationBarPadding
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
class CacheActivity : VMBaseActivity<ActivityCacheBookBinding, CacheViewModel>(),
    CacheAdapter.CallBack {

    override val binding by viewBinding(ActivityCacheBookBinding::inflate)
    override val viewModel by viewModels<CacheViewModel>()
    private val cacheManageViewModel by viewModels<CacheManageViewModel>()

    private val exportBookPathKey = "exportBookPath"
    private val exportTypes = arrayListOf("txt", "epub")
    private val layoutManager by lazy { LinearLayoutManager(this) }
    private val adapter by lazy { CacheAdapter(this, this) }
    private var booksFlowJob: Job? = null
    private val groupList = mutableStateListOf<BookGroup>()
    private var groupId: Long = -1

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
            configExportSection(dirPath, result.requestCode)
        } else {
            startExport(dirPath, result.requestCode)
        }
    }

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
        initRecyclerView()
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
                        adapter.getItems().forEach { book ->
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
                        adapter.getItems().forEach { book ->
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
            MenuAction(Icons.Filled.Folder, getString(R.string.export_folder), onClick = { selectExportFolder(-1) }),
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

    private fun initRecyclerView() {
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
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
                adapter.setItems(books)
                viewModel.loadCacheFiles(books)
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun initGroupData() {
        lifecycleScope.launch {
            appDb.bookGroupDao.flowAll().catch {
                AppLog.put("缓存管理界面获取分组数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                groupList.clear()
                groupList.addAll(it)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun notifyItemChanged(bookUrl: String) {
        kotlin.runCatching {
            adapter.getItems().forEachIndexed { index, book ->
                if (bookUrl == book.bookUrl) {
                    adapter.notifyItemChanged(index, true)
                    return
                }
            }
        }
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

    override fun export(position: Int) {
        val path = ACache.get().getAsString(exportBookPathKey)
        lifecycleScope.launch {
            if (path.isNullOrEmpty() ||
                withContext(IO) { !FileDoc.fromDir(path).checkWrite() }
            ) {
                selectExportFolder(position)
            } else if (enableCustomExport()) {// 启用自定义导出 and 导出类型为Epub
                configExportSection(path, position)
            } else {
                startExport(path, position)
            }
        }
    }

    private fun exportAll() {
        val path = ACache.get().getAsString(exportBookPathKey)
        if (path.isNullOrEmpty()) {
            selectExportFolder(-10)
        } else {
            startExport(path, -10)
        }
    }

    /**
     * 配置自定义导出对话框
     *
     * @param path  导出路径
     * @param position  book位置
     * @author Discut
     * @since 1.0.0
     */
    private fun configExportSection(path: String, position: Int) {

        val alertBinding = DialogSelectSectionExportBinding.inflate(layoutInflater)
            .apply {
                fun verifyExportFileNameJsStr(js: String): Boolean {
                    return tryParesExportFileName(js) && etEpubFilename.text.toString()
                        .isNotEmpty()
                }

                fun enableLyEtEpubFilenameIcon() {
                    lyEtEpubFilename.endIconMode = TextInputLayout.END_ICON_CUSTOM
                    lyEtEpubFilename.setEndIconOnClickListener {
                        adapter.getItem(position)?.run {
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
                        } ?: run {
                            lyEtEpubFilename.helperText = "Error"
                            AppLog.put("未找到书籍，position is $position")
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
                    startExport(path, position)
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
                adapter.getItem(position)?.let { book ->
                    startService<ExportBookService> {
                        action = IntentAction.start
                        putExtra("bookUrl", book.bookUrl)
                        putExtra("exportType", "epub")
                        putExtra("exportPath", path)
                        putExtra("epubSize", epubSize)
                        putExtra("epubScope", epubScope)
                    }
                }
                alertDialog.hide()
            }

        }
    }

    private fun selectExportFolder(exportPosition: Int) {
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(exportBookPathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        exportDir.launch {
            otherActions = default
            requestCode = exportPosition
        }
    }

    private fun startExport(path: String, exportPosition: Int) {
        val exportType = when (AppConfig.exportType) {
            1 -> "epub"
            else -> "txt"
        }
        if (exportPosition == -10) {
            if (adapter.getItems().isNotEmpty()) {
                adapter.getItems().forEach { book ->
                    startService<ExportBookService> {
                        action = IntentAction.start
                        putExtra("bookUrl", book.bookUrl)
                        putExtra("exportType", exportType)
                        putExtra("exportPath", path)
                    }
                }
            } else {
                toastOnUi(R.string.no_book)
            }
        } else if (exportPosition >= 0) {
            adapter.getItem(exportPosition)?.let { book ->
                startService<ExportBookService> {
                    action = IntentAction.start
                    putExtra("bookUrl", book.bookUrl)
                    putExtra("exportType", exportType)
                    putExtra("exportPath", path)
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun alertExportFileName() {
        alert(R.string.export_file_name) {
            val message = "Variable: name, author."
            setMessage(message)
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "file name js"
                editView.setText(AppConfig.bookExportFileName)
            }
            customView { alertBinding.root }
            okButton {
                AppConfig.bookExportFileName = alertBinding.editView.text?.toString()
            }
            cancelButton()
        }
    }

    private fun getTypeName(): String {
        return exportTypes.getOrElse(AppConfig.exportType) {
            exportTypes[0]
        }
    }

    private fun showExportTypeConfig() {
        selector(R.string.export_type, exportTypes) { _, i ->
            AppConfig.exportType = i
        }
    }

    private fun showCharsetConfig() {
        alert(R.string.set_charset) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "charset name"
                editView.setFilterValues(charsets)
                editView.setText(AppConfig.exportCharset)
            }
            customView { alertBinding.root }
            okButton {
                AppConfig.exportCharset = alertBinding.editView.text?.toString() ?: "UTF-8"
            }
            cancelButton()
        }
    }

    private fun sureCacheBook(action: () -> Unit) {
        alert(R.string.draw) {
            setMessage(R.string.sure_cache_book)
            noButton()
            yesButton {
                action.invoke()
            }
        }
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
            selector(R.string.cache_stats, names) { _, index ->
                val detail = details.filter { it.bytes > 0L }[index]
                deleteStorageTarget(detail)
            }
        }
    }

    private fun deleteStorageTarget(detail: CacheStorageDetail) {
        alert(R.string.cache_stats) {
            setMessage(getString(R.string.cache_stats_delete_msg, getString(detail.nameRes)))
            noButton()
            yesButton {
                cacheManageViewModel.deleteStorageTarget(detail).onSuccess { success ->
                    if (success) {
                        toastOnUi(R.string.cache_stats_delete_success)
                    } else {
                        toastOnUi(R.string.cache_stats_video_playing)
                    }
                }
            }
        }
    }    override val cacheChapters: HashMap<String, HashSet<String>>
        get() = viewModel.cacheChapters

    override fun exportProgress(bookUrl: String): Int? {
        return ExportBookService.exportProgress[bookUrl]
    }

    /**
     * B12 缓存并发率设置对话框
     */
    private fun showCacheRateDialog() {
        var rateEdit: EditText? = null
        val alertDialog = alert(R.string.cache_concurrent_rate) {
            setMessage(getString(R.string.cache_rate_desc))
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = getString(R.string.cache_rate_hint)
                editView.inputType = InputType.TYPE_CLASS_TEXT
                AppConfig.cacheConcurrentRate?.let { editView.setText(it) }
            }
            rateEdit = alertBinding.editView
            customView { alertBinding.root }
            positiveButton(R.string.ok)
            cancelButton()
        }
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = rateEdit?.text?.toString()
            if (!value.isNullOrBlank() && !ConcurrentRateLimiter.isValidRate(value)) {
                toastOnUi(R.string.cache_rate_invalid)
                return@setOnClickListener
            }
            AppConfig.cacheConcurrentRate = if (value.isNullOrBlank()) null else value
            kotlin.runCatching {
                AppLog.putDebugWithTag(
                    AppLog.TAG_CACHE_CONCURRENT,
                    "缓存并发率设置变更 -> $value",
                    level = AppLog.Level.INFO
                )
            }
            alertDialog.hide()
        }
    }

    override fun exportMsg(bookUrl: String): String? {
        return ExportBookService.exportMsg[bookUrl]
    }

}