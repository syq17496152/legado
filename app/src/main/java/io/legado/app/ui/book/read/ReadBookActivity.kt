package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.core.view.get
import androidx.core.view.size
import androidx.lifecycle.lifecycleScope
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.BuildConfig
import io.legado.app.R
import java.lang.ref.WeakReference
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.IntentData
import io.legado.app.help.TTS
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isMobi
import io.legado.app.help.book.removeType
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.getSourceType
import io.legado.app.help.storage.Backup
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJsonObject
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.MobiFile
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.receiver.TimeBatteryReceiver
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.changesource.ChangeChapterSourceDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.highlight.HighlightRuleActivity
import io.legado.app.ui.book.read.config.AutoReadDialog
import io.legado.app.ui.book.read.config.BgTextConfigDialog.Companion.BG_COLOR
import io.legado.app.ui.book.read.config.BgTextConfigDialog.Companion.TEXT_ACCENT_COLOR
import io.legado.app.ui.book.read.config.BgTextConfigDialog.Companion.TEXT_COLOR
import io.legado.app.ui.book.read.config.MoreConfigDialog
import io.legado.app.ui.book.read.config.ReadAloudDialog
import io.legado.app.ui.book.read.config.ReadStyleDialog
import io.legado.app.ui.book.read.config.TipConfigDialog.Companion.TIP_COLOR
import io.legado.app.ui.book.read.config.TipConfigDialog.Companion.TIP_DIVIDER_COLOR
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.delegate.ScrollPageDelegate
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.LayoutProgressListener
import io.legado.app.ui.book.searchContent.SearchContentActivity
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.model.SourceCallBack
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.book.toc.rule.TxtTocRuleDialog
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.dict.DictDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.replace.edit.ReplaceEditActivity
import io.legado.app.ui.widget.PopupAction
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppModalBottomSheet
import io.legado.app.ui.widget.components.BookTocBookmarkSheet
import io.legado.app.ui.widget.components.MenuLayer
import io.legado.app.ui.widget.components.MenuLayerAction
import io.legado.app.ui.widget.components.MenuLayerState
import io.legado.app.ui.widget.components.ReaderMenuSheet
import io.legado.app.ui.widget.components.ReaderMenuSheetAction
import io.legado.app.ui.widget.components.ReaderMenuSheetState
import io.legado.app.help.config.ThemeConfig
import io.legado.app.utils.putPrefBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import io.legado.app.utils.ACache
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.Debounce
import io.legado.app.utils.LogUtils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.applyOpenTint
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.dismissDialogFragment
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.hexString
import io.legado.app.utils.iconItemOnLongClick
import io.legado.app.utils.invisible
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isTrue
import io.legado.app.utils.launch
import io.legado.app.utils.navigationBarGravity
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.sysScreenOffTime
import io.legado.app.utils.throttle
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import com.script.rhino.runScriptWithContext
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.paramPattern
import io.legado.app.ui.login.SourceLoginJsExtensions

/**
 * 阅读界面
 */
class ReadBookActivity : BaseReadBookActivity(),
    View.OnTouchListener,
    ReadView.CallBack,
    TextActionMenu.CallBack,
    ContentTextView.CallBack,
    PopupMenu.OnMenuItemClickListener,
    ReadMenu.CallBack,
    SearchMenu.CallBack,
    ReadAloudDialog.CallBack,
    ChangeBookSourceDialog.CallBack,
    ChangeChapterSourceDialog.CallBack,
    ReadBook.CallBack,
    AutoReadDialog.CallBack,
    TxtTocRuleDialog.CallBack,
    ColorPickerDialogListener,
    LayoutProgressListener {

    private val tocActivity =
        registerForActivityResult(TocActivityResult()) {
            it?.let {
                viewModel.openChapter(it[0] as Int, it[1] as Int)
            }
        }
    // Phase4 阅读浮层 Sheet 化：目录/书签 Compose 浮层显隐状态（S5 阶段2 收敛到 activeSheet 单态；保留 TocActivity 全功能入口）
    private var tocChapterTitles: List<String> = emptyList()
    private var tocBookmarkTitles: List<String> = emptyList()
    // S5 阅读器浮层（AD-03）：Compose 菜单层 UI 状态单源（直接替换 read_menu）
    val readerUiState = ReaderUiStateHolder()
    private val menuLayerState = MutableStateFlow(MenuLayerState())
    private val readerMenuState = MutableStateFlow(ReaderMenuSheetState())
    // S5：菜单覆盖层可见性（覆写 Base，让系统栏/自动翻页感知 Compose 菜单层状态）
    override val menuOverlayVisible: Boolean
        get() = readerUiState.state.value.menuVisible
    private val sourceEditActivity =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upBookSource {
                    upMenuView()
                }
            }
        }
    private val replaceActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                viewModel.replaceRuleChanged()
            }
        }
    private val searchContentActivity =
        registerForActivityResult(StartActivityContract(SearchContentActivity::class.java)) {
            val data = it.data ?: return@registerForActivityResult
            val key = data.getLongExtra("key", System.currentTimeMillis())
            val index = data.getIntExtra("index", 0)
            val searchResult = IntentData.get<SearchResult>("searchResult$key")
            val searchResultList = IntentData.get<List<SearchResult>>("searchResultList$key")
            if (searchResult != null && searchResultList != null) {
                viewModel.searchContentQuery = searchResult.query
                binding.searchMenu.upSearchResultList(searchResultList)
                isShowingSearchResult = true
                viewModel.searchResultIndex = index
                binding.searchMenu.updateSearchResultIndex(index)
                binding.searchMenu.selectedSearchResult?.let { currentResult ->
                    ReadBook.saveCurrentBookProgress() //退出全文搜索恢复此时进度
                    skipToSearch(currentResult)
                    showActionMenu()
                }
            }
        }
    private val bookInfoActivity =
        registerForActivityResult(StartActivityContract(BookInfoActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                setResult(RESULT_DELETED)
                super.finish()
            } else {
                ReadBook.loadOrUpContent()
            }
        }
    private val selectImageDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ACache.get().put(AppConst.imagePathKey, uri.toString())
            viewModel.saveImage(it.value, uri)
        }
    }
    private var menu: Menu? = null
    private var backupJob: Job? = null
    private var tts: TTS? = null
    val textActionMenu: TextActionMenu by lazy {
        TextActionMenu(this, this)
    }
    private val popupAction: PopupAction by lazy {
        PopupAction(this)
    }
    override val isInitFinish: Boolean get() = viewModel.isInitFinish
    override val isScroll: Boolean get() = binding.readView.isScroll
    private val isAutoPage get() = binding.readView.isAutoPage
    override var isShowingSearchResult = false
    override var isSelectingSearchResult = false
        set(value) {
            field = value && isShowingSearchResult
        }
    private val timeBatteryReceiver = TimeBatteryReceiver()
    private var screenTimeOut: Long = 0
    private var loadStates: Boolean = false
    override val pageFactory get() = binding.readView.pageFactory
    override val pageDelegate get() = binding.readView.pageDelegate
    override val headerHeight: Int get() = binding.readView.curPage.headerHeight
    override val imgBgPaddingStart: Int get() = binding.readView.curPage.imgBgPaddingStart
    private val nextPageDebounce by lazy { Debounce { keyPage(PageDirection.NEXT) } }
    private val prevPageDebounce by lazy { Debounce { keyPage(PageDirection.PREV) } }
    private var bookChanged = false
    private var pageChanged = false
    private val handler by lazy { buildMainHandler() }
    private val screenOffRunnable by lazy { Runnable { keepScreenOn(false) } }
    private val executor = ReadBook.executor
    private val upSeekBarThrottle = throttle(200) {
        runOnUiThread {
            upSeekBarProgress()
            binding.readMenu.upSeekBar()
        }
    }

    //恢复跳转前进度对话框的交互结果
    private var confirmRestoreProcess: Boolean? = null
    private val networkChangedListener by lazy {
        NetworkChangedListener(this)
    }
    private var justInitData: Boolean = false
    private var syncDialog: AlertDialog? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        binding.cursorLeft.setColorFilter(accentColor)
        binding.cursorRight.setColorFilter(accentColor)
        binding.cursorLeft.setOnTouchListener(this)
        binding.cursorRight.setOnTouchListener(this)
        window.setBackgroundDrawable(null)
        upScreenTimeOut()
        ReadBook.register(this)
        onBackPressedDispatcher.addCallback(this) {
            // S5 阶段4：Back 优先级链（弹层→搜索→自动翻页→菜单路由→退出阅读，R6）
            // 1) 弹层单态（activeSheet）最优先关闭
            if (readerUiState.state.value.activeSheet != null) {
                readerUiState.dismissSheet()
                binding.composeSheetHost.invisible()
                return@addCallback
            }
            // 2) 全文搜索层
            if (isShowingSearchResult) {
                exitSearchMenu()
                restoreLastBookProcess()
                return@addCallback
            }
            //拦截返回供恢复阅读进度
            if (ReadBook.lastBookProgress != null && confirmRestoreProcess != false) {
                restoreLastBookProcess()
                return@addCallback
            }
            if (BaseReadAloudService.isPlay()) {
                ReadAloud.pause(this@ReadBookActivity)
                toastOnUi(R.string.read_aloud_pause)
                return@addCallback
            }
            // 3) 自动翻页（菜单打开时已 pause，此处 stop 退出自动翻页态）
            if (isAutoPage) {
                autoPageStop()
                return@addCallback
            }
            // 4) 菜单路由：收起 Compose 菜单层
            if (readerUiState.state.value.menuVisible) {
                hideMenu()
                return@addCallback
            }
            if (getPrefBoolean("disableReturnKey") && !menuLayoutIsVisible) {
                return@addCallback
            }
            // 5) 退出阅读
            finish()
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        setupTocSheet()
        viewModel.initReadBookConfig(intent)
        Looper.myQueue().addIdleHandler {
            viewModel.initData(intent)
            false
        }
        justInitData = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.initData(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        upSystemUiVisibility()
        if (hasFocus) {
            binding.readMenu.upBrightnessState()
        } else if (!menuLayoutIsVisible) {
            ReadBook.cancelPreDownloadTask()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        upSystemUiVisibility()
        binding.readView.upStatusBar()
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        if (!isTopResumedActivity) {
            ReadBook.cancelPreDownloadTask()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        ReadBook.readStartTime = System.currentTimeMillis()
        if (bookChanged) {
            bookChanged = false
            ReadBook.callBack = WeakReference(this)
            viewModel.initData(intent)
            justInitData = true
        } else {
            //web端阅读时，app处于阅读界面，本地记录会覆盖web保存的进度，在此处恢复
            ReadBook.webBookProgress?.let {
                ReadBook.setProgress(it)
                ReadBook.webBookProgress = null
            }
        }
        upSystemUiVisibility()
        registerReceiver(timeBatteryReceiver, timeBatteryReceiver.filter)
        binding.readView.upTime()
        screenOffTimerStart()
        // 网络监听，当从无网切换到网络环境时同步进度（注意注册的同时就会收到监听，因此界面激活时无需重复执行同步操作）
        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = {
            // 当网络是可用状态且无需初始化时同步进度（初始化中已有同步进度逻辑）
            if (AppConfig.syncBookProgressPlus && NetworkUtils.isAvailable() && !justInitData && ReadBook.inBookshelf) {
                ReadBook.syncProgress({ progress -> sureNewProgress(progress) })
            }
        }
    }

    override fun onPause() {
        super.onPause()
        autoPageStop()
        backupJob?.cancel()
        ReadBook.saveRead()
        ReadBook.cancelPreDownloadTask()
        unregisterReceiver(timeBatteryReceiver)
        upSystemUiVisibility()
        if (!BuildConfig.DEBUG && ReadBook.inBookshelf) {
            if (AppConfig.syncBookProgressPlus) {
                ReadBook.syncProgress()
            } else {
                ReadBook.uploadProgress()
            }
        }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
        justInitData = false
        networkChangedListener.unRegister()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_read, menu)
        menu.iconItemOnLongClick(R.id.menu_change_source) {
            PopupMenu(this, it).apply {
                inflate(R.menu.book_read_change_source)
                this.menu.applyOpenTint(this@ReadBookActivity)
                setOnMenuItemClickListener(this@ReadBookActivity)
            }.show()
        }
        menu.iconItemOnLongClick(R.id.menu_refresh) {
            PopupMenu(this, it).apply {
                inflate(R.menu.book_read_refresh)
                this.menu.applyOpenTint(this@ReadBookActivity)
                setOnMenuItemClickListener(this@ReadBookActivity)
            }.show()
        }
        binding.readMenu.refreshMenuColorFilter()
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        this.menu = menu
        upMenu()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_same_title_removed)?.isChecked =
            ReadBook.curTextChapter?.sameTitleRemoved == true
        return super.onMenuOpened(featureId, menu)
    }

    /**
     * 更新菜单
     */
    private fun upMenu() {
        val menu = menu ?: return
        val book = ReadBook.book ?: return
        val onLine = !book.isLocal
        for (i in 0 until menu.size) {
            val item = menu[i]
            when (item.groupId) {
                R.id.menu_group_on_line -> item.isVisible = onLine
                R.id.menu_group_local -> item.isVisible = !onLine
                R.id.menu_group_text -> item.isVisible = book.isLocalTxt
                R.id.menu_group_epub -> item.isVisible = book.isEpub
                else -> when (item.itemId) {
                    R.id.menu_enable_replace -> item.isChecked = book.getUseReplaceRule()
                    R.id.menu_re_segment -> item.isChecked = book.getReSegment()
//                    R.id.menu_enable_review -> {
//                        item.isVisible = BuildConfig.DEBUG
//                        item.isChecked = AppConfig.enableReview
//                    }

                    R.id.menu_reverse_content -> item.isVisible = onLine
                    R.id.menu_del_ruby_tag -> item.isChecked = book.getDelTag(Book.rubyTag)
                    R.id.menu_del_h_tag -> item.isChecked = book.getDelTag(Book.hTag)
                }
            }
        }
        lifecycleScope.launch {
            val show = ReadBook.inBookshelf && withContext(IO) {
                AppWebDav.isOk
            }
            menu.findItem(R.id.menu_get_progress)?.isVisible = show
            menu.findItem(R.id.menu_cover_progress)?.isVisible = show
        }
    }

    /**
     * 菜单
     */
    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_change_source,
            R.id.menu_book_change_source -> {
                hideMenu()
                ReadBook.book?.let {
                    showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
                }
            }

            R.id.menu_chapter_change_source -> lifecycleScope.launch(IO) {
                val book = ReadBook.book ?: return@launch
                val chapter =
                    appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                        ?: return@launch
                withContext(Main) {
                    hideMenu()
                    showDialogFragment(
                        ChangeChapterSourceDialog(book.name, book.author, chapter.index, chapter.title)
                    )
                }
            }

            R.id.menu_refresh,
            R.id.menu_refresh_dur -> {
                if (ReadBook.bookSource == null) {
                    upContent()
                } else {
                    ReadBook.book?.let {
                        ReadBook.curTextChapter = null
                        binding.readView.upContent()
                        viewModel.refreshContentDur(it)
                    }
                }
            }

            R.id.menu_refresh_after -> {
                if (ReadBook.bookSource == null) {
                    upContent()
                } else {
                    ReadBook.book?.let {
                        ReadBook.clearTextChapter()
                        binding.readView.upContent()
                        viewModel.refreshContentAfter(it)
                    }
                }
            }

            R.id.menu_refresh_all -> {
                if (ReadBook.bookSource == null) {
                    upContent()
                } else {
                    ReadBook.book?.let {
                        refreshContentAll(it)
                    }
                }
            }

            R.id.menu_download -> showDownloadDialog()
            R.id.menu_add_bookmark -> addBookmark()
            // F-P1-2 高亮规则管理入口
            R.id.menu_highlight_rule -> startActivity<HighlightRuleActivity>()
            R.id.menu_simulated_reading -> showSimulatedReading()
            R.id.menu_edit_content -> showDialogFragment(ContentEditDialog())
            R.id.menu_update_toc -> ReadBook.book?.let {
                if (it.isEpub) {
                    BookHelp.clearCache(it)
                    EpubFile.clear()
                }
                if (it.isMobi) {
                    MobiFile.clear()
                }
                loadChapterList(it)
            }

            R.id.menu_enable_replace -> changeReplaceRuleState()
            R.id.menu_re_segment -> ReadBook.book?.let {
                it.setReSegment(!it.getReSegment())
                item.isChecked = it.getReSegment()
                ReadBook.loadContent(false)
            }

//            R.id.menu_enable_review -> {
//                AppConfig.enableReview = !AppConfig.enableReview
//                item.isChecked = AppConfig.enableReview
//                ReadBook.loadContent(false)
//            }

            R.id.menu_del_ruby_tag -> ReadBook.book?.let {
                item.isChecked = !item.isChecked
                if (item.isChecked) {
                    it.addDelTag(Book.rubyTag)
                } else {
                    it.removeDelTag(Book.rubyTag)
                }
                refreshContentAll(it)
            }

            R.id.menu_del_h_tag -> ReadBook.book?.let {
                item.isChecked = !item.isChecked
                if (item.isChecked) {
                    it.addDelTag(Book.hTag)
                } else {
                    it.removeDelTag(Book.hTag)
                }
                refreshContentAll(it)
            }

            R.id.menu_page_anim -> showPageAnimConfig {
                binding.readView.upPageAnim()
                ReadBook.loadContent(false)
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            R.id.menu_toc_regex -> showDialogFragment(
                TxtTocRuleDialog(ReadBook.book?.tocUrl)
            )

            R.id.menu_reverse_content -> ReadBook.book?.let {
                viewModel.reverseContent(it)
            }

            R.id.menu_set_charset -> showCharsetConfig()
            R.id.menu_image_style -> {
                val imgStyles =
                    arrayListOf(
                        Book.imgStyleDefault, Book.imgStyleFull, Book.imgStyleText,
                        Book.imgStyleSingle
                    )
                selector(
                    R.string.image_style,
                    imgStyles
                ) { _, index ->
                    val imageStyle = imgStyles[index]
                    ReadBook.book?.setImageStyle(imageStyle)
                    if (imageStyle == Book.imgStyleSingle) {
                        ReadBook.book?.setPageAnim(0)  // 切换图片样式single后，自动切换为覆盖
                        binding.readView.upPageAnim()
                    }
                    ReadBook.loadContent(false)
                }
            }

            R.id.menu_get_progress -> ReadBook.book?.let {
                viewModel.syncBookProgress(it) { progress ->
                    sureSyncProgress(progress)
                }
            }

            R.id.menu_cover_progress -> ReadBook.book?.let {
                ReadBook.uploadProgress(true) { toastOnUi(R.string.upload_book_success) }
            }

            R.id.menu_same_title_removed -> {
                ReadBook.book?.let {
                    lifecycleScope.launch {
                        val contentProcessor = withContext(IO) { ContentProcessor.get(it) }
                        val textChapter = ReadBook.curTextChapter
                        if (textChapter != null
                            && !textChapter.sameTitleRemoved
                            && !contentProcessor.removeSameTitleCache.contains(
                                textChapter.chapter.getFileName("nr")
                            )
                        ) {
                            toastOnUi("未找到可移除的重复标题")
                        }
                        viewModel.reverseRemoveSameTitle()
                    }
                }
            }

            R.id.menu_effective_replaces -> showDialogFragment<EffectiveReplacesDialog>()

            R.id.menu_help -> showHelp()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun refreshContentAll(book: Book) {
        ReadBook.clearTextChapter()
        binding.readView.upContent()
        viewModel.refreshContentAll(book)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        return onCompatOptionsItemSelected(item)
    }

    /**
     * 按键拦截,显示菜单
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action
        val isDown = action == 0

        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val menuVisible = readerUiState.state.value.menuVisible
            if (isDown && !menuVisible) {
                showMenuBar()
                return true
            }
            if (!isDown && !menuVisible) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 鼠标滚轮事件
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (0 != (event.source and InputDevice.SOURCE_CLASS_POINTER)) {
            if (event.action == MotionEvent.ACTION_SCROLL) {
                val axisValue = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                LogUtils.d("onGenericMotionEvent", "axisValue = $axisValue")
                // 获得垂直坐标上的滚动方向
                if (axisValue < 0.0f) { // 滚轮向下滚
                    mouseWheelPage(PageDirection.NEXT, axisValue)
                } else { // 滚轮向上滚
                    mouseWheelPage(PageDirection.PREV, axisValue)
                }
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /**
     * 按键事件
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuLayoutIsVisible) {
            return super.onKeyDown(keyCode, event)
        }
        val longPress = event.repeatCount > 0
        when {
            isPrevKey(keyCode) -> {
                handleKeyPage(PageDirection.PREV, longPress)
                return true
            }

            isNextKey(keyCode) -> {
                handleKeyPage(PageDirection.NEXT, longPress)
                return true
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> if (volumeKeyPage(PageDirection.PREV, longPress)) {
                return true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeKeyPage(PageDirection.NEXT, longPress)) {
                return true
            }

            KeyEvent.KEYCODE_PAGE_UP -> {
                handleKeyPage(PageDirection.PREV, longPress)
                return true
            }

            KeyEvent.KEYCODE_PAGE_DOWN -> {
                handleKeyPage(PageDirection.NEXT, longPress)
                return true
            }

            KeyEvent.KEYCODE_SPACE -> {
                handleKeyPage(PageDirection.NEXT, longPress)
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    /**
     * 松开按键事件
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (volumeKeyPage(PageDirection.NONE, false)) {
                    return true
                }
            }

        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * view触摸,文字选择
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean = binding.run {
        if (!binding.readView.isTextSelected) {
            return false
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> textActionMenu.dismiss()
            MotionEvent.ACTION_MOVE -> {
                when (v.id) {
                    R.id.cursor_left -> if (!readView.curPage.getReverseStartCursor()) {
                        readView.curPage.selectStartMove(
                            event.rawX + cursorLeft.width,
                            event.rawY - cursorLeft.height
                        )
                    } else {
                        readView.curPage.selectEndMove(
                            event.rawX - cursorRight.width,
                            event.rawY - cursorRight.height
                        )
                    }

                    R.id.cursor_right -> if (readView.curPage.getReverseEndCursor()) {
                        readView.curPage.selectStartMove(
                            event.rawX + cursorLeft.width,
                            event.rawY - cursorLeft.height
                        )
                    } else {
                        readView.curPage.selectEndMove(
                            event.rawX - cursorRight.width,
                            event.rawY - cursorRight.height
                        )
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                readView.curPage.resetReverseCursor()
                showTextActionMenu()
            }
        }
        return true
    }

    /**
     * 更新文字选择开始位置
     */
    override fun upSelectedStart(x: Float, y: Float, top: Float) = binding.run {
        cursorLeft.x = x - cursorLeft.width
        cursorLeft.y = y
        cursorLeft.visible(true)
        textMenuPosition.x = x
        textMenuPosition.y = top
    }

    /**
     * 更新文字选择结束位置
     */
    override fun upSelectedEnd(x: Float, y: Float) = binding.run {
        cursorRight.x = x
        cursorRight.y = y
        cursorRight.visible(true)
    }

    /**
     * 取消文字选择
     */
    override fun onCancelSelect() = binding.run {
        cursorLeft.invisible()
        cursorRight.invisible()
        textActionMenu.dismiss()
    }

    override fun onLongScreenshotTouchEvent(event: MotionEvent): Boolean {
        return binding.readView.onTouchEvent(event)
    }

    /**
     * 显示文本操作菜单
     */
    override fun showTextActionMenu() {
        val navigationBarHeight =
            if (!ReadBookConfig.hideNavigationBar && navigationBarGravity == Gravity.BOTTOM)
                binding.navigationBar.height else 0
        textActionMenu.show(
            binding.textMenuPosition,
            binding.root.height + navigationBarHeight,
            binding.textMenuPosition.x.toInt(),
            binding.textMenuPosition.y.toInt(),
            binding.cursorLeft.y.toInt() + binding.cursorLeft.height,
            binding.cursorRight.x.toInt(),
            binding.cursorRight.y.toInt() + binding.cursorRight.height
        )
    }

    /**
     * 当前选择的文本
     */
    override val selectedText: String get() = binding.readView.getSelectText()

    /**
     * 文本选择菜单操作
     */
    override fun onMenuItemSelected(itemId: Int): Boolean {
        when (itemId) {
            R.id.menu_aloud -> when (AppConfig.contentSelectSpeakMod) {
                1 -> lifecycleScope.launch {
                    binding.readView.aloudStartSelect()
                }

                else -> speak(binding.readView.getSelectText())
            }

            R.id.menu_bookmark -> binding.readView.curPage.let {
                val bookmark = it.createBookmark()
                if (bookmark == null) {
                    toastOnUi(R.string.create_bookmark_error)
                } else {
                    showDialogFragment(BookmarkDialog(bookmark))
                }
                return true
            }

            R.id.menu_replace -> {
                val scopes = arrayListOf<String>()
                ReadBook.book?.name?.let {
                    scopes.add(it)
                }
                ReadBook.bookSource?.bookSourceUrl?.let {
                    scopes.add(it)
                }
                val text = selectedText.lineSequence().joinToString("\n") { it.trim() }
                replaceActivity.launch(
                    ReplaceEditActivity.startIntent(
                        this,
                        pattern = text,
                        scope = scopes.joinToString(";")
                    )
                )
                return true
            }

            R.id.menu_search_content -> {
                viewModel.searchContentQuery = selectedText
                openSearchActivity(selectedText)
                return true
            }

            R.id.menu_dict -> {
                showDialogFragment(DictDialog(selectedText))
                return true
            }
        }
        return false
    }

    /**
     * 文本选择菜单操作完成
     */
    override fun onMenuActionFinally() = binding.run {
        textActionMenu.dismiss()
        readView.cancelSelect()
    }

    private fun speak(text: String) {
        if (tts == null) {
            tts = TTS()
        }
        tts?.speak(text)
    }

    /**
     * 鼠标滚轮翻页
     */
    private fun mouseWheelPage(direction: PageDirection, distance: Float) {
        if (menuLayoutIsVisible || !AppConfig.mouseWheelPage) {
            return
        }
        if (binding.readView.isScroll) {
            // 滚动视图时滚动,否则翻页
            (binding.readView.pageDelegate as? ScrollPageDelegate)?.curPage?.scroll((distance * 50).toInt())
        } else {
            keyPageDebounce(direction, mouseWheel = true, longPress = false)
        }
    }

    /**
     * 音量键翻页
     */
    private fun volumeKeyPage(direction: PageDirection, longPress: Boolean): Boolean {
        if (!AppConfig.volumeKeyPage) {
            return false
        }
        if (!AppConfig.volumeKeyPageOnPlay && BaseReadAloudService.isPlay()) {
            return false
        }
        handleKeyPage(direction, longPress)
        return true
    }

    private fun handleKeyPage(direction: PageDirection, longPress: Boolean) {
        if (AppConfig.keyPageOnLongPress || direction == PageDirection.NONE) {
            keyPage(direction)
        } else {
            keyPageDebounce(direction, longPress = longPress)
        }
    }

    private fun keyPageDebounce(
        direction: PageDirection,
        mouseWheel: Boolean = false,
        longPress: Boolean
    ) {
        if (longPress) {
            return
        }
        nextPageDebounce.apply {
            wait = if (mouseWheel) 200L else 600L
            leading = !mouseWheel
            trailing = mouseWheel
        }
        prevPageDebounce.apply {
            wait = if (mouseWheel) 200L else 600L
            leading = !mouseWheel
            trailing = mouseWheel
        }
        when (direction) {
            PageDirection.NEXT -> nextPageDebounce.invoke()
            PageDirection.PREV -> prevPageDebounce.invoke()
            else -> {}
        }
    }

    private fun keyPage(direction: PageDirection) {
        binding.readView.cancelSelect()
        binding.readView.pageDelegate?.isCancel = false
        binding.readView.pageDelegate?.keyTurnPage(direction)
    }

    override fun upMenuView() {
        handler.post {
            upMenu()
            binding.readMenu.upBookView()
            updateMenuLayerState()
        }
    }

    override fun loadChapterList(book: Book) {
        ReadBook.upMsg(getString(R.string.toc_updateing))
        viewModel.loadChapterList(book)
    }

    /**
     * 内容加载完成
     */
    override fun contentLoadFinish() {
        if (intent.getBooleanExtra("readAloud", false)) {
            intent.removeExtra("readAloud")
            ReadBook.readAloud()
        }
        loadStates = true
    }

    /**
     * 更新内容
     */
    override fun upContent(
        relativePosition: Int,
        resetPageOffset: Boolean,
        success: (() -> Unit)?
    ) {
        lifecycleScope.launch {
            binding.readView.upContent(relativePosition, resetPageOffset)
            if (relativePosition == 0) {
                upSeekBarProgress()
            }
            loadStates = false
            success?.invoke()
        }
    }

    override suspend fun upContentAwait(
        relativePosition: Int,
        resetPageOffset: Boolean,
        success: (() -> Unit)?
    ) = withContext(Main.immediate) {
        binding.readView.upContent(relativePosition, resetPageOffset)
        if (relativePosition == 0) {
            upSeekBarProgress()
        }
        loadStates = false
    }

    override fun upPageAnim(upRecorder: Boolean) {
        lifecycleScope.launch {
            binding.readView.upPageAnim(upRecorder)
        }
    }

    override fun notifyBookChanged() {
        bookChanged = true
        if (!ReadBook.inBookshelf) {
            viewModel.removeFromBookshelf { super.finish() }
        }
    }

    override fun cancelSelect() {
        runOnUiThread {
            binding.readView.cancelSelect()
        }
    }

    /**
     * 页面改变
     */
    override fun pageChanged() {
        pageChanged = true
        binding.readView.onPageChange()
        handler.post {
            upSeekBarProgress()
        }
        executor.execute {
            startBackupJob()
        }
    }

    /**
     * 更新进度条位置
     */
    private fun upSeekBarProgress() {
        val progress = when (AppConfig.progressBarBehavior) {
            "page" -> ReadBook.durPageIndex
            else /* chapter */ -> ReadBook.durChapterIndex
        }
        binding.readMenu.setSeekPage(progress)
        updateMenuLayerState()
    }

    /**
     * 显示菜单
     */
    override fun showMenuBar() {
        binding.composeSheetHost.visible()
        onMenuShow()
        updateMenuLayerState()
        readerUiState.showMenu()
    }

    /**
     * S5：收起 Compose 菜单层（同步暂停/恢复自动翻页，替代原 read_menu.runMenuOut）
     */
    private fun hideMenu() {
        onMenuHide()
        readerUiState.hideMenu()
        if (readerUiState.state.value.activeSheet == null) {
            binding.composeSheetHost.invisible()
        }
    }

    override val oldBook: Book?
        get() = ReadBook.book

    override fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        if (!book.isAudio) {
            viewModel.changeTo(book, toc)
        } else {
            ReadAloud.stop(this)
            lifecycleScope.launch {
                withContext(IO) {
                    ReadBook.book?.migrateTo(book, toc)
                    book.removeType(BookType.updateError)
                    ReadBook.book?.delete()
                    appDb.bookDao.insert(book)
                }
                startActivityForBook(book)
                finish()
            }
        }
    }

    override fun replaceContent(content: String) {
        ReadBook.book?.let {
            viewModel.saveContent(it, content)
        }
    }

    override fun showActionMenu() {
        when {
            BaseReadAloudService.isRun -> showReadAloudDialog()
            isAutoPage -> showDialogFragment<AutoReadDialog>()
            isShowingSearchResult -> binding.searchMenu.runMenuIn()
            else -> showMenuBar()
        }
    }

    /**
     * 显示朗读菜单
     */
    override fun showReadAloudDialog() {
        showDialogFragment<ReadAloudDialog>()
    }

    /**
     * 自动翻页
     */
    override fun autoPage() {
        ReadAloud.stop(this)
        if (isAutoPage) {
            autoPageStop()
        } else {
            binding.readView.autoPager.start()
            binding.readMenu.setAutoPage(true)
            screenTimeOut = -1L
            screenOffTimerStart()
        }
    }

    override fun autoPageStop() {
        if (isAutoPage) {
            binding.readView.autoPager.stop()
            binding.readMenu.setAutoPage(false)
            dismissDialogFragment<AutoReadDialog>()
            upScreenTimeOut()
        }
    }

    override fun openSourceEditActivity() {
        ReadBook.bookSource?.let {
            sourceEditActivity.launch {
                putExtra("sourceUrl", it.bookSourceUrl)
            }
        }
    }

    override fun openBookInfoActivity() {
        ReadBook.book?.let {
            bookInfoActivity.launch {
                putExtra("name", it.name)
                putExtra("author", it.author)
            }
        }
    }

    /**
     * 替换
     */
    override fun openReplaceRule() {
        replaceActivity.launch(Intent(this, ReplaceRuleActivity::class.java))
    }

    /**
     * 打开目录
     */
    override fun openChapterList() {
        ReadBook.book?.let { book ->
            // Phase4：优先展示 Compose 目录/书签浮层做快速跳转；「完整目录」按钮仍启动 TocActivity 保留全功能
            loadTocSheetData(book)
            readerUiState.showSheet(ReadBookSheet.Toc)
            binding.composeSheetHost.visible()
        }
    }

    /**
     * Phase4：加载浮层所需的章节标题与书签标题数据
     */
    private fun loadTocSheetData(book: Book) {
        lifecycleScope.launch(IO) {
            val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
            val titles = chapters.map { it.title }
            val bookmarks = appDb.bookmarkDao.getByBook(book.name, book.author)
            val bookmarkTitles = bookmarks.map {
                (if (it.chapterName.isBlank()) "" else it.chapterName + " ") + it.bookText
            }
            withContext(Main) {
                tocChapterTitles = titles
                tocBookmarkTitles = bookmarkTitles
            }
        }
    }

    /**
     * Phase4：关闭 Compose 目录/书签浮层
     */
    private fun dismissTocSheet() {
        readerUiState.dismissSheet(ReadBookSheet.Toc)
        binding.composeSheetHost.invisible()
    }

    /**
     * S5 阶段3：打开阅读设置 Sheet（ReaderMenu）
     */
    private fun showReaderMenu() {
        updateReaderMenuState()
        readerUiState.showSheet(ReadBookSheet.ReaderMenu)
        binding.composeSheetHost.visible()
    }

    /**
     * S5 阶段3：关闭阅读设置 Sheet
     */
    private fun dismissReaderMenu() {
        readerUiState.dismissSheet(ReadBookSheet.ReaderMenu)
        binding.composeSheetHost.invisible()
        ReadBookConfig.save()
    }

    /**
     * S5 阶段3：同步阅读设置 Sheet 展示数据
     */
    private fun updateReaderMenuState() {
        val resources = resources
        val indentIndex = ReadBookConfig.paragraphIndent.takeWhile { it == '　' }.length.coerceAtMost(4)
        val indentLabels = runCatching { resources.getStringArray(R.array.indent) }.getOrNull()
        readerMenuState.value = ReaderMenuSheetState(
            textSize = ReadBookConfig.textSize,
            lineSpacingExtra = ReadBookConfig.lineSpacingExtra,
            brightness = AppConfig.readBrightness,
            brightnessAuto = brightnessAuto(),
            isNightTheme = AppConfig.isNightTheme,
            indentLabel = indentLabels?.getOrNull(indentIndex) ?: "",
            pageAnimLabel = pageAnimLabel(ReadBook.pageAnim()),
            fontLabel = ReadBookConfig.textFont.substringAfterLast('/')
                .ifBlank { getString(R.string.text_font) },
        )
    }

    /**
     * S5 阶段3：翻页动画展示名
     */
    private fun pageAnimLabel(anim: Int): String = when (anim) {
        0 -> getString(R.string.page_anim_cover)
        1 -> getString(R.string.page_anim_slide)
        2 -> getString(R.string.page_anim_simulation)
        3 -> getString(R.string.page_anim_scroll)
        else -> getString(R.string.page_anim_none)
    }

    /**
     * S5 阶段3：构建阅读设置 Sheet 回调
     */
    private fun readerMenuSheetAction() = ReaderMenuSheetAction(
        onDismiss = ::dismissReaderMenu,
        onTextSizeChange = { value ->
            ReadBookConfig.textSize = value
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        },
        onLineSpacingChange = { value ->
            ReadBookConfig.lineSpacingExtra = value
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        },
        onBrightnessAuto = {
            putPrefBoolean("brightnessAuto", !brightnessAuto())
            updateReaderMenuState()
        },
        onBrightnessChange = { value -> setScreenBrightness(value.toFloat()) },
        onNightTheme = {
            AppConfig.isNightTheme = !AppConfig.isNightTheme
            ThemeConfig.applyDayNight(this)
            updateReaderMenuState()
        },
        onIndentClick = {
            selector(
                title = getString(R.string.text_indent),
                items = resources.getStringArray(R.array.indent).toList()
            ) { _, index ->
                ReadBookConfig.paragraphIndent = "　".repeat(index)
                postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
                updateReaderMenuState()
            }
        },
        onPageAnimClick = {
            dismissReaderMenu()
            showReadStyle()
        },
        onFontClick = {
            dismissReaderMenu()
            showReadStyle()
        },
        onMoreSettingClick = {
            dismissReaderMenu()
            showMoreSetting()
        },
    )

    /**
     * Phase4：初始化 Compose 目录/书签浮层宿主
     */
    @OptIn(ExperimentalMaterial3Api::class)
    private fun setupTocSheet() {
        binding.composeSheetHost.setContent {
            LegadoTheme {
                // S5 菜单层（直接替换 read_menu）：Compose 承载菜单层+浮层，正文 ReadView 保持 XML 垫底
                val uiState by readerUiState.state.collectAsState()
                val menuState by menuLayerState.collectAsState()
                val readerMenuState by readerMenuState.collectAsState()
                MenuLayer(
                    uiState = uiState,
                    state = menuState,
                    action = menuLayerAction(),
                )
                // S5 阶段2：activeSheet 单态宿主（任意时刻最多一个 Sheet）
                if (uiState.activeSheet == ReadBookSheet.Toc) {
                    AppModalBottomSheet(onDismiss = ::dismissTocSheet) {
                        BookTocBookmarkSheet(
                            chapterList = tocChapterTitles,
                            bookmarkTitles = tocBookmarkTitles,
                            onChapterClick = { index ->
                                if (index < tocChapterTitles.size) {
                                    dismissTocSheet()
                                    ReadBook.book?.let { viewModel.openChapter(index) }
                                }
                            },
                            onBookmarkClick = { index ->
                                val book = ReadBook.book ?: return@BookTocBookmarkSheet
                                val bookmarks =
                                    appDb.bookmarkDao.getByBook(book.name, book.author)
                                if (index < bookmarks.size) {
                                    val bm = bookmarks[index]
                                    dismissTocSheet()
                                    viewModel.openChapter(bm.chapterIndex, bm.chapterPos)
                                }
                            }
                        )
                        // 完整目录全功能入口（无回归）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                dismissTocSheet()
                                ReadBook.book?.let { tocActivity.launch(it.bookUrl) }
                            }) {
                                Text(stringResource(R.string.chapter_list))
                            }
                        }
                    }
                }
                // S5 阶段3：阅读设置 Sheet（ReaderMenu）
                if (uiState.activeSheet == ReadBookSheet.ReaderMenu) {
                    ReaderMenuSheet(
                        state = readerMenuState,
                        action = readerMenuSheetAction(),
                    )
                }
            }
        }
    }

    /**
     * S5：构建菜单层动作回调（直接替换 read_menu 全部按钮逻辑）
     */
    private fun menuLayerAction() = MenuLayerAction(
        onBack = { onBackPressedDispatcher.onBackPressed() },
        onTitleClick = ::openBookInfoActivity,
        onChapterClick = ::openBookInfoActivity,
        onCustomBtn = ::customButtonClick,
        onSourceLogin = ::showLogin,
        onSourcePay = ::payAction,
        onSourceEdit = ::openSourceEditActivity,
        onSourceDisable = ::disableSource,
        onMoreChangeSource = {
            readerUiState.hideMenu()
            ReadBook.book?.let { showDialogFragment(ChangeBookSourceDialog(it.name, it.author)) }
        },
        onMoreRefresh = {
            readerUiState.hideMenu()
            refreshDurContent()
        },
        onMoreDownload = {
            readerUiState.hideMenu()
            showDownloadDialog()
        },
        onMoreBookmark = {
            readerUiState.hideMenu()
            addBookmark()
        },
        onMoreHighlightRule = {
            readerUiState.hideMenu()
            startActivity<HighlightRuleActivity>()
        },
        onMoreRefreshAfter = {
            readerUiState.hideMenu()
            if (ReadBook.bookSource == null) {
                upContent()
            } else {
                ReadBook.book?.let {
                    ReadBook.clearTextChapter()
                    binding.readView.upContent()
                    viewModel.refreshContentAfter(it)
                }
            }
        },
        onMoreRefreshAll = {
            readerUiState.hideMenu()
            if (ReadBook.bookSource == null) {
                upContent()
            } else {
                ReadBook.book?.let { refreshContentAll(it) }
            }
        },
        onMoreEditContent = {
            readerUiState.hideMenu()
            showDialogFragment(ContentEditDialog())
        },
        onMorePageAnim = {
            readerUiState.hideMenu()
            showPageAnimConfig {
                binding.readView.upPageAnim()
                ReadBook.loadContent(false)
            }
        },
        onMoreReverseContent = {
            readerUiState.hideMenu()
            ReadBook.book?.let { viewModel.reverseContent(it) }
        },
        onMoreSimulatedReading = {
            readerUiState.hideMenu()
            showSimulatedReading()
        },
        onMoreReplaceRuleToggle = {
            readerUiState.hideMenu()
            changeReplaceRuleState()
            updateMenuLayerState()
        },
        onMoreSameTitleRemoved = {
            readerUiState.hideMenu()
            ReadBook.book?.let {
                lifecycleScope.launch {
                    val contentProcessor = withContext(IO) { ContentProcessor.get(it) }
                    val textChapter = ReadBook.curTextChapter
                    if (textChapter != null
                        && !textChapter.sameTitleRemoved
                        && !contentProcessor.removeSameTitleCache.contains(
                            textChapter.chapter.getFileName("nr")
                        )
                    ) {
                        toastOnUi(getString(R.string.no_remove_duplicate_title))
                    }
                    viewModel.reverseRemoveSameTitle()
                    updateMenuLayerState()
                }
            }
        },
        onMoreReSegment = {
            readerUiState.hideMenu()
            ReadBook.book?.let {
                it.setReSegment(!it.getReSegment())
                ReadBook.loadContent(false)
                updateMenuLayerState()
            }
        },
        onMoreDelRubyTag = {
            readerUiState.hideMenu()
            ReadBook.book?.let {
                val checked = !it.getDelTag(Book.rubyTag)
                if (checked) {
                    it.addDelTag(Book.rubyTag)
                } else {
                    it.removeDelTag(Book.rubyTag)
                }
                refreshContentAll(it)
                updateMenuLayerState()
            }
        },
        onMoreDelHTag = {
            readerUiState.hideMenu()
            ReadBook.book?.let {
                val checked = !it.getDelTag(Book.hTag)
                if (checked) {
                    it.addDelTag(Book.hTag)
                } else {
                    it.removeDelTag(Book.hTag)
                }
                refreshContentAll(it)
                updateMenuLayerState()
            }
        },
        onMoreImageStyle = {
            readerUiState.hideMenu()
            val imgStyles = arrayListOf(
                Book.imgStyleDefault, Book.imgStyleFull, Book.imgStyleText, Book.imgStyleSingle
            )
            selector(
                R.string.image_style,
                imgStyles
            ) { _, index ->
                val imageStyle = imgStyles[index]
                ReadBook.book?.setImageStyle(imageStyle)
                if (imageStyle == Book.imgStyleSingle) {
                    ReadBook.book?.setPageAnim(0) // 切换图片样式 single 后自动切覆盖翻页
                    binding.readView.upPageAnim()
                }
                ReadBook.loadContent(false)
                updateMenuLayerState()
            }
        },
        onMoreUpdateToc = {
            readerUiState.hideMenu()
            ReadBook.book?.let {
                if (it.isEpub) {
                    BookHelp.clearCache(it)
                    EpubFile.clear()
                }
                if (it.isMobi) {
                    MobiFile.clear()
                }
                loadChapterList(it)
            }
        },
        onMoreEffectiveReplaces = {
            readerUiState.hideMenu()
            showDialogFragment<EffectiveReplacesDialog>()
        },
        onMoreLog = {
            readerUiState.hideMenu()
            showDialogFragment<AppLogDialog>()
        },
        onMoreHelp = {
            readerUiState.hideMenu()
            showHelp()
        },
        onMoreSetCharset = {
            readerUiState.hideMenu()
            showCharsetConfig()
        },
        onMoreTocRegex = {
            readerUiState.hideMenu()
            showDialogFragment(TxtTocRuleDialog(ReadBook.book?.tocUrl))
        },
        onSearch = {
            readerUiState.hideMenu()
            openSearchActivity(null)
        },
        onAutoPage = {
            readerUiState.hideMenu()
            autoPage()
        },
        onReplaceRule = {
            readerUiState.hideMenu()
            openReplaceRule()
        },
        onNightTheme = {
            AppConfig.isNightTheme = !AppConfig.isNightTheme
            ThemeConfig.applyDayNight(this)
            updateMenuLayerState()
        },
        onPrevChapter = { ReadBook.moveToPrevChapter(upContent = true, toLast = false) },
        onNextChapter = { ReadBook.moveToNextChapter(true) },
        onCatalog = {
            readerUiState.hideMenu()
            openChapterList()
        },
        onReadAloud = {
            readerUiState.hideMenu()
            onClickReadAloud()
        },
        onReadAloudLong = {
            readerUiState.hideMenu()
            showReadAloudDialog()
        },
        onFont = {
            readerUiState.hideMenu()
            showReaderMenu()
        },
        onSetting = {
            readerUiState.hideMenu()
            showMoreSetting()
        },
        onBrightnessAuto = {
            putPrefBoolean("brightnessAuto", !brightnessAuto())
            updateMenuLayerState()
        },
        onBrightnessChange = { value -> setScreenBrightness(value.toFloat()) },
        onBrightnessPosAdjust = { AppConfig.brightnessVwPos = !AppConfig.brightnessVwPos },
        onScrimClick = { hideMenu() },
    )

    /**
     * S5：同步 Compose 菜单层展示数据（由 upMenuView / seek 更新时调用）
     */
    private fun updateMenuLayerState() {
        val book = ReadBook.book
        val chapter = ReadBook.curTextChapter
        val source = ReadBook.bookSource
        val curIndex = ReadBook.durChapterIndex
        val pageMode = AppConfig.progressBarBehavior == "page"
        // 阅读器独立配色（复刻原 ReadMenu.upColorConfig：跟随阅读页/沉浸模式，非全局主题色）
        val immersiveMenu =
            AppConfig.readBarStyleFollowPage && ReadBookConfig.durConfig.curBgType() == 0
        val menuBg = if (immersiveMenu) {
            kotlin.runCatching { ReadBookConfig.durConfig.curBgStr().toColorInt() }
                .getOrDefault(bottomBackground)
        } else {
            bottomBackground
        }
        val menuText = if (immersiveMenu) {
            ReadBookConfig.durConfig.curTextColor()
        } else {
            getPrimaryTextColor(ColorUtils.isColorLight(menuBg))
        }
        menuLayerState.value = MenuLayerState(
            title = book?.name ?: "",
            chapterTitle = chapter?.title ?: "",
            chapterUrl = if (book != null && !book.isLocal && chapter != null) {
                chapter.chapter.getAbsoluteURL()
            } else {
                ""
            },
            sourceName = source?.bookSourceName ?: "",
            isLocalBook = ReadBook.isLocalBook,
            isLocalTxt = book?.isLocalTxt == true,
            isEpub = book?.isEpub == true,
            hasCustomButton = source?.customButton == true,
            brightnessAuto = brightnessAuto(),
            brightness = AppConfig.readBrightness,
            seekMax = if (pageMode) (chapter?.pageSize?.minus(1) ?: 0) else ReadBook.simulatedChapterSize - 1,
            seekProgress = if (pageMode) ReadBook.durPageIndex else ReadBook.durChapterIndex,
            isAutoPage = isAutoPage,
            isNightTheme = AppConfig.isNightTheme,
            canPrev = curIndex != 0,
            canNext = curIndex != ReadBook.simulatedChapterSize - 1,
            menuBg = menuBg,
            menuText = menuText,
            menuAccent = accentColor,
            useReplaceRule = book?.getUseReplaceRule() ?: false,
            sameTitleRemoved = chapter?.sameTitleRemoved == true,
            reSegment = book?.getReSegment() ?: false,
            delRubyTag = book?.getDelTag(Book.rubyTag) ?: false,
            delHTag = book?.getDelTag(Book.hTag) ?: false,
        )
    }

    /**
     * S5：自定义按钮回调（原 read_menu 逻辑迁移）
     */
    private fun customButtonClick() {
        val book = ReadBook.book ?: return
        Coroutine.async {
            appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
        }.onSuccess {
            SourceCallBack.callBackBtn(
                this@ReadBookActivity,
                SourceCallBack.CLICK_CUSTOM_BUTTON,
                ReadBook.bookSource,
                book,
                it,
                BookType.text
            )
        }
    }

    /**
     * S5：刷新当前章节内容（原 menu_refresh_dur 逻辑迁移）
     */
    private fun refreshDurContent() {
        if (ReadBook.bookSource == null) {
            upContent()
        } else {
            ReadBook.book?.let {
                ReadBook.curTextChapter = null
                binding.readView.upContent()
                viewModel.refreshContentDur(it)
            }
        }
    }

    /**
     * S5：亮度自动模式判定（原 read_menu 逻辑迁移）
     */
    private fun brightnessAuto(): Boolean =
        getPrefBoolean("brightnessAuto", true) || !getPrefBoolean(PreferKey.showBrightnessView, true)

    /**
     * S5：设置屏幕亮度（简化版，原 read_menu 逻辑迁移；不含高亮度日光观测分支）
     */
    private fun setScreenBrightness(value: Float) {
        val autoBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        val params = window.attributes
        params.screenBrightness = when {
            brightnessAuto() || value == autoBrightness -> autoBrightness
            value < 1f -> 0.004f
            else -> value / 255f
        }
        window.attributes = params
    }

    /**
     * 打开搜索界面
     */
    override fun openSearchActivity(searchWord: String?) {
        val book = ReadBook.book ?: return
        searchContentActivity.launch {
            putExtra("bookUrl", book.bookUrl)
            putExtra("searchWord", searchWord ?: viewModel.searchContentQuery)
            putExtra("searchResultIndex", viewModel.searchResultIndex)
            viewModel.searchResultList?.first()?.let {
                if (it.query == viewModel.searchContentQuery) {
                    IntentData.put("searchResultList", viewModel.searchResultList)
                }
            }
        }
    }

    /**
     * 禁用书源
     */
    override fun disableSource() {
        viewModel.disableSource()
    }

    /**
     * 显示阅读样式配置
     */
    override fun showReadStyle() {
        showDialogFragment<ReadStyleDialog>()
    }

    /**
     * 显示更多设置
     */
    override fun showMoreSetting() {
        showDialogFragment<MoreConfigDialog>()
    }

    override fun showSearchSetting() {
        showDialogFragment<MoreConfigDialog>()
    }

    /**
     * 更新状态栏,导航栏
     */
    override fun upSystemUiVisibility() {
        upSystemUiVisibility(isInMultiWindow, !menuLayoutIsVisible, bottomDialog > 0)
        upNavigationBarColor()
    }

    // 退出全文搜索
    override fun exitSearchMenu() {
        if (isShowingSearchResult) {
            isShowingSearchResult = false
            binding.searchMenu.invalidate()
            binding.searchMenu.invisible()
            ReadBook.clearSearchResult()
            binding.readView.cancelSelect(true)
        }
    }

    /* 恢复到 全文搜索/进度条跳转前的位置 */
    private fun restoreLastBookProcess() {
        if (confirmRestoreProcess == true) {
            ReadBook.restoreLastBookProgress()
        } else if (confirmRestoreProcess == null) {
            alert(R.string.draw) {
                setMessage(R.string.restore_last_book_process)
                yesButton {
                    confirmRestoreProcess = true
                    ReadBook.restoreLastBookProgress() //恢复启动全文搜索前的进度
                }
                noButton {
                    ReadBook.lastBookProgress = null
                    confirmRestoreProcess = false
                }
                onCancelled {
                    ReadBook.lastBookProgress = null
                    confirmRestoreProcess = false
                }
            }
        }
    }

    override fun showLogin() {
        ReadBook.bookSource?.let {
            startActivity<SourceLoginActivity> {
                putExtra("bookType", BookType.text)
            }
        }
    }

    override fun payAction() {
        val book = ReadBook.book ?: return
        if (book.isLocal) return
        lifecycleScope.launch(IO) {
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
            if (chapter == null) {
                withContext(Main) { toastOnUi("no chapter") }
                return@launch
            }
            withContext(Main) {
                alert(R.string.chapter_pay) {
                    setMessage(chapter.title)
                    yesButton {
                        Coroutine.async(lifecycleScope) {
                            val source =
                                ReadBook.bookSource ?: throw NoStackTraceException("no book source")
                            val payAction = source.getContentRule().payAction
                            if (payAction.isNullOrBlank()) {
                                throw NoStackTraceException("no pay action")
                            }
                            val java = SourceLoginJsExtensions(this@ReadBookActivity, source, BookType.text)
                            runScriptWithContext {
                                source.evalJS(payAction) {
                                    put("java", java)
                                    put("book", book)
                                    put("chapter", chapter)
                                    put("title", chapter.title)
                                    put("baseUrl", chapter.url)
                                    put("result", null)
                                    put("src", null)
                                }.toString()
                            }
                        }.onSuccess(IO) {
                            if (it.isAbsUrl()) {
                                startActivity<WebViewActivity> {
                                    val bookSource = ReadBook.bookSource
                                    putExtra("title", getString(R.string.chapter_pay))
                                    putExtra("url", it)
                                    putExtra("sourceOrigin", bookSource?.bookSourceUrl)
                                    putExtra("sourceName", bookSource?.bookSourceName)
                                    putExtra("sourceType", bookSource?.getSourceType())
                                }
                            } else if (it.isTrue()) {
                                //购买成功后刷新目录
                                ReadBook.book?.let {
                                    ReadBook.curTextChapter = null
                                    BookHelp.delContent(book, chapter)
                                    loadChapterList(book)
                                }
                            }
                        }.onError {
                            AppLog.put("执行购买操作出错\n${it.localizedMessage}", it, true)
                        }
                    }
                    noButton()
                }
            }
        }
    }

    /**
     * 点击图片
     */
    override fun oldClickImg(src: String): Boolean {
        val urlMatcher = paramPattern.matcher(src)
        if (urlMatcher.find()) {
            val urlOptionStr = src.substring(urlMatcher.end())
            val urlOptionMap = GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull()
            val click = urlOptionMap?.get("click")
            if (click != null) {
                Coroutine.async(lifecycleScope,IO) {
                    val source = ReadBook.bookSource ?: return@async
                    val java = SourceLoginJsExtensions(this@ReadBookActivity, source, BookType.text)
                    val book = ReadBook.book ?: return@async
                    val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex) ?: throw Exception("no find chapter")
                    runScriptWithContext {
                        source.evalJS(click) {
                            put("java", java)
                            put("book", book)
                            put("chapter", chapter)
                            put("result", src)
                        }
                    }
                }.onError {
                    AppLog.put("执行图片链接click键值出错\n${it.localizedMessage}", it, true)
                }
                return true
            }
            val jsStr = urlOptionMap?.get("js") ?: return false
            Coroutine.async(lifecycleScope, IO) {
                val source = ReadBook.bookSource ?: return@async
                val book = ReadBook.book ?: return@async
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex) ?: throw Exception("no find chapter")
                val urlNoOption = src.take(urlMatcher.start())
                AnalyzeRule(book, source).apply {
                    setCoroutineContext(coroutineContext)
                    setBaseUrl(chapter.url)
                    setChapter(chapter)
                    evalJS(jsStr, urlNoOption)
                }
            }.onError {
                AppLog.put("执行图片链接js键值出错\n${it.localizedMessage}", it, true)
            }
            return true
        }
        return false
    }

    override fun clickImg(click: String, src: String) {
        Coroutine.async(lifecycleScope,IO) {
            val source = ReadBook.bookSource ?: return@async
            val java = SourceLoginJsExtensions(this@ReadBookActivity, source, BookType.text)
            val book = ReadBook.book ?: return@async
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex) ?: throw Exception("no find chapter")
            runScriptWithContext {
                source.evalJS(click) {
                    put("java", java)
                    put("book", book)
                    put("chapter", chapter)
                    put("result", src)
                }
            }
        }.onError {
            AppLog.put("执行图片链接click键值出错\n${it.localizedMessage}", it, true)
        }
    }


    /**
     * 朗读按钮
     */
    override fun onClickReadAloud() {
        autoPageStop()
        when {
            !BaseReadAloudService.isRun -> {
                ReadAloud.upReadAloudClass()
                val scrollPageAnim = ReadBook.pageAnim() == 3
                if (scrollPageAnim) {
                    val pos = binding.readView.getReadAloudPos()
                    if (pos != null) {
                        val (index, line) = pos
                        if (ReadBook.durChapterIndex != index) {
                            ReadBook.openChapter(index, line.chapterPosition, false) {
                                ReadBook.readAloud(startPos = line.pagePosition)
                            }
                        } else {
                            ReadBook.durChapterPos = line.chapterPosition
                            ReadBook.readAloud(startPos = line.pagePosition)
                        }
                    } else {
                        ReadBook.readAloud()
                    }
                } else {
                    ReadBook.readAloud()
                }
            }

            BaseReadAloudService.pause -> {
                val scrollPageAnim = ReadBook.pageAnim() == 3
                if (scrollPageAnim && pageChanged) {
                    pageChanged = false
                    val pos = binding.readView.getReadAloudPos()
                    if (pos != null) {
                        val (index, line) = pos
                        if (ReadBook.durChapterIndex != index) {
                            ReadBook.openChapter(index, line.chapterPosition, false) {
                                ReadBook.readAloud(startPos = line.pagePosition)
                            }
                        } else {
                            ReadBook.durChapterPos = line.chapterPosition
                            ReadBook.readAloud(startPos = line.pagePosition)
                        }
                    } else {
                        ReadBook.readAloud()
                    }
                } else {
                    ReadAloud.resume(this)
                }
            }

            else -> ReadAloud.pause(this)
        }
    }

    override fun showHelp() {
        showHelp("readMenuHelp")
    }

    /**
     * 长按图片
     */
    @SuppressLint("RtlHardcoded")
    override fun onImageLongPress(x: Float, y: Float, src: String) {
        popupAction.setItems(
            listOf(
                SelectItem(getString(R.string.show), "show"),
                SelectItem(getString(R.string.refresh), "refresh"),
                SelectItem(getString(R.string.action_save), "save"),
                SelectItem(getString(R.string.menu), "menu"),
                SelectItem(getString(R.string.select_folder), "selectFolder")
            )
        )
        popupAction.onActionClick = {
            when (it) {
                "show" -> showDialogFragment(PhotoDialog(src, isBook = true))
                "refresh" -> viewModel.refreshImage(src)
                "save" -> {
                    val path = ACache.get().getAsString(AppConst.imagePathKey)
                    if (path.isNullOrEmpty()) {
                        selectImageDir.launch {
                            value = src
                        }
                    } else {
                        viewModel.saveImage(src, path.toUri())
                    }
                }

                "menu" -> showActionMenu()
                "selectFolder" -> selectImageDir.launch()
            }
            popupAction.dismiss()
        }
        val navigationBarHeight =
            if (!ReadBookConfig.hideNavigationBar && navigationBarGravity == Gravity.BOTTOM)
                binding.navigationBar.height else 0
        popupAction.showAtLocation(
            binding.readView, Gravity.BOTTOM or Gravity.LEFT, x.toInt(),
            binding.root.height + navigationBarHeight - y.toInt()
        )
    }

    /**
     * colorSelectDialog
     */
    override fun onColorSelected(dialogId: Int, color: Int) = ReadBookConfig.durConfig.run {
        when (dialogId) {
            TEXT_COLOR -> {
                setCurTextColor(color)
                postEvent(EventBus.UP_CONFIG, arrayListOf(2, 6, 9, 11))
                if (AppConfig.readBarStyleFollowPage) {
                    postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                }
            }

            TEXT_ACCENT_COLOR -> {
                setCurTextAccentColor(color)
                postEvent(EventBus.UP_CONFIG, arrayListOf(2, 6, 9, 11))
                if (AppConfig.readBarStyleFollowPage) {
                    postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                }
            }

            BG_COLOR -> {
                setCurBg(0, "#${color.hexString}")
                postEvent(EventBus.UP_CONFIG, arrayListOf(1))
                if (AppConfig.readBarStyleFollowPage) {
                    postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                }
            }

            TIP_COLOR -> {
                ReadTipConfig.tipColor = color
                postEvent(EventBus.TIP_COLOR, "")
                postEvent(EventBus.UP_CONFIG, arrayListOf(2))
            }

            TIP_DIVIDER_COLOR -> {
                ReadTipConfig.tipDividerColor = color
                postEvent(EventBus.TIP_COLOR, "")
                postEvent(EventBus.UP_CONFIG, arrayListOf(2))
            }
        }
    }

    /**
     * colorSelectDialog
     */
    override fun onDialogDismissed(dialogId: Int) = Unit

    override fun onTocRegexDialogResult(tocRegex: String) {
        ReadBook.book?.let {
            it.tocUrl = tocRegex
            loadChapterList(it)
        }
    }

    private fun sureSyncProgress(progress: BookProgress) {
        alert(R.string.get_book_progress) {
            setMessage(R.string.current_progress_exceeds_cloud)
            okButton {
                ReadBook.setProgress(progress)
            }
            noButton()
        }
    }

    /* 进度条跳转到指定章节 */
    override fun skipToChapter(index: Int) {
        ReadBook.saveCurrentBookProgress() //退出章节跳转恢复此时进度
        viewModel.openChapter(index)
    }

    /* 全文搜索跳转 */
    override fun navigateToSearch(searchResult: SearchResult, index: Int) {
        viewModel.searchResultIndex = index
        skipToSearch(searchResult)
    }

    override fun onMenuShow() {
        binding.readView.autoPager.pause()
    }

    override fun onMenuHide() {
        binding.readView.autoPager.resume()
    }

    override fun onLayoutPageCompleted(index: Int, page: TextPage) {
        upSeekBarThrottle.invoke()
        binding.readView.onLayoutPageCompleted(index, page)
    }

    /* 全文搜索跳转 */
    private fun skipToSearch(searchResult: SearchResult) {
        if (searchResult.chapterIndex != ReadBook.durChapterIndex) {
            viewModel.openChapter(searchResult.chapterIndex) {
                jumpToPosition(searchResult)
            }
        } else {
            jumpToPosition(searchResult)
        }
    }

    private fun jumpToPosition(searchResult: SearchResult) {
        val curTextChapter = ReadBook.curTextChapter ?: return
        binding.searchMenu.updateSearchInfo()
        val searchResultPositions =
            viewModel.searchResultPositions(curTextChapter, searchResult)
        val (pageIndex, lineIndex, charIndex, addLine, charIndex2) = searchResultPositions
        ReadBook.skipToPage(pageIndex) {
            isSelectingSearchResult = true
            binding.readView.curPage.selectStartMoveIndex(0, lineIndex, charIndex)
            when (addLine) {
                0 -> binding.readView.curPage.selectEndMoveIndex(
                    0,
                    lineIndex,
                    charIndex + searchResultPositions[5] - 1
                )

                1 -> binding.readView.curPage.selectEndMoveIndex(
                    0, lineIndex + 1, charIndex2
                )
                //consider change page, jump to scroll position
                -1 -> binding.readView.curPage.selectEndMoveIndex(1, 0, charIndex2)
            }
            binding.readView.isTextSelected = true
            isSelectingSearchResult = false
        }
    }

    override fun addBookmark() {
        val book = ReadBook.book
        val page = ReadBook.curTextChapter?.getPage(ReadBook.durPageIndex)
        if (book != null && page != null) {
            val bookmark = book.createBookMark().apply {
                chapterIndex = ReadBook.durChapterIndex
                chapterPos = ReadBook.durChapterPos
                chapterName = page.title
                bookText = page.text.trim()
            }
            showDialogFragment(BookmarkDialog(bookmark))
        }
    }

    override fun changeReplaceRuleState() {
        ReadBook.book?.let {
            it.setUseReplaceRule(!it.getUseReplaceRule())
            ReadBook.saveRead()
            menu?.findItem(R.id.menu_enable_replace)?.isChecked = it.getUseReplaceRule()
            viewModel.replaceRuleChanged()
        }
    }

    private fun startBackupJob() {
        backupJob?.cancel()
        backupJob = lifecycleScope.launch(IO) {
            delay(300000)
            ReadBook.book?.let {
                AppWebDav.uploadBookProgress(it)
                ensureActive()
                it.update()
                Backup.autoBack(this@ReadBookActivity)
            }
        }
    }

    override fun sureNewProgress(progress: BookProgress) {
        syncDialog?.dismiss()
        syncDialog = alert(R.string.get_book_progress) {
            setMessage(R.string.cloud_progress_exceeds_current)
            okButton {
                ReadBook.setProgress(progress)
            }
            noButton()
        }
    }

    override fun finish() {
        val book = ReadBook.book ?: return super.finish()
        if (ReadBook.inBookshelf) {
            callBackBookEnd()
            return super.finish()
        }
        if (!AppConfig.showAddToShelfAlert) {
            callBackBookEnd()
            viewModel.removeFromBookshelf { super.finish() }
        } else {
            alert(title = getString(R.string.add_to_bookshelf)) {
                setMessage(getString(R.string.check_add_bookshelf, book.name))
                okButton {
                    val book = ReadBook.book
                    book?.removeType(BookType.notShelf)
                    lifecycleScope.launch(IO) {
                        book?.save()
                        withContext(Main) {
                            SourceCallBack.callBackBook(SourceCallBack.ADD_BOOK_SHELF, ReadBook.bookSource, ReadBook.book)
                            ReadBook.inBookshelf = true
                            setResult(RESULT_OK)
                        }
                    }
                }
                noButton {
                    callBackBookEnd()
                    viewModel.removeFromBookshelf { super.finish() }
                }
            }
        }
    }

    private fun callBackBookEnd() {
        SourceCallBack.callBackBook(SourceCallBack.END_READ, ReadBook.bookSource, ReadBook.book, ReadBook.curTextChapter?.chapter)
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.clearTts()
        textActionMenu.dismiss()
        popupAction.dismiss()
        binding.readView.onDestroy()
        ReadBook.unregister(this)
        handler.removeCallbacksAndMessages(null) // 清理Handler消息
        if (!ReadBook.inBookshelf && !isChangingConfigurations) {
            viewModel.removeFromBookshelf(null)
        }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
    }

    override fun observeLiveBus() = binding.run {
        observeEvent<String>(EventBus.TIME_CHANGED) { readView.upTime() }
        observeEvent<Int>(EventBus.BATTERY_CHANGED) { readView.upBattery(it) }
        observeEvent<Boolean>(EventBus.MEDIA_BUTTON) {
            if (it) {
                onClickReadAloud()
            } else {
                ReadBook.readAloud(!BaseReadAloudService.pause)
            }
        }
        observeEvent<ArrayList<Int>>(EventBus.UP_CONFIG) {
            it.forEach { value ->
                when (value) {
                    0 -> upSystemUiVisibility()
                    1 -> readView.upBg()
                    2 -> readView.upStyle()
                    3 -> readView.upBgAlpha()
                    4 -> readView.upPageSlopSquare()
                    5 -> if (isInitFinish) ReadBook.loadContent(resetPageOffset = false)
                    6 -> readView.upContent(resetPageOffset = false)
                    8 -> ChapterProvider.upStyle()
                    9 -> readView.invalidateTextPage()
                    10 -> ChapterProvider.upLayout()
                    11 -> readView.submitRenderTask()
                    12 -> readView.upPageTouchClick()
                    13 -> upPageAnim()
                }
            }
        }
        observeEvent<Int>(EventBus.ALOUD_STATE) {
            if (it == Status.STOP || it == Status.PAUSE) {
                ReadBook.curTextChapter?.let { textChapter ->
                    val page = textChapter.getPageByReadPos(ReadBook.durChapterPos)
                    if (page != null) {
                        page.removePageAloudSpan()
                        readView.upContent(resetPageOffset = false)
                    }
                }
            }
        }
        observeEventSticky<Int>(EventBus.TTS_PROGRESS) { chapterStart ->
            lifecycleScope.launch(IO) {
                if (BaseReadAloudService.isPlay()) {
                    ReadBook.curTextChapter?.let { textChapter ->
                        ReadBook.durChapterPos = chapterStart
                        val pageIndex = ReadBook.durPageIndex
                        val aloudSpanStart = chapterStart - textChapter.getReadLength(pageIndex)
                        textChapter.getPage(pageIndex)
                            ?.upPageAloudSpan(aloudSpanStart)
                        upContent()
                    }
                }
            }
        }
        observeEvent<Boolean>(PreferKey.keepLight) {
            upScreenTimeOut()
        }
        observeEvent<Boolean>(PreferKey.textSelectAble) {
            readView.curPage.upSelectAble(it)
        }
        observeEvent<String>(PreferKey.showBrightnessView) {
            readMenu.upBrightnessState()
            updateMenuLayerState()
        }
        observeEvent<List<SearchResult>>(EventBus.SEARCH_RESULT) {
            viewModel.searchResultList = it
        }
        observeEvent<Boolean>(EventBus.UPDATE_READ_ACTION_BAR) {
            readMenu.reset()
            updateMenuLayerState()
        }
        observeEvent<Boolean>(EventBus.UP_SEEK_BAR) {
            readMenu.upSeekBar()
            updateMenuLayerState()
        }
        observeEvent<Boolean>(EventBus.REFRESH_BOOK_CONTENT) { //书源js函数触发刷新
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                ReadBook.book?.let {
                    ReadBook.curTextChapter = null
                    binding.readView.upContent()
                    viewModel.refreshContentDur(it)
                }
            }
        }
        observeEvent<Boolean>(EventBus.REFRESH_BOOK_TOC) { //书源js函数触发刷新
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                ReadBook.book?.let {
                    loadChapterList(it)
                }
            }
        }
    }

    private fun upScreenTimeOut() {
        val keepLightPrefer = getPrefString(PreferKey.keepLight)?.toInt() ?: 0
        screenTimeOut = keepLightPrefer * 1000L
        screenOffTimerStart()
    }

    /**
     * 重置黑屏时间
     */
    override fun screenOffTimerStart() {
        handler.post {
            if (screenTimeOut < 0) {
                keepScreenOn(true)
                return@post
            }
            val t = screenTimeOut - sysScreenOffTime
            if (t > 0) {
                keepScreenOn(true)
                handler.removeCallbacks(screenOffRunnable)
                handler.postDelayed(screenOffRunnable, screenTimeOut)
            } else {
                keepScreenOn(false)
            }
        }
    }

    companion object {
        const val RESULT_DELETED = 100
    }

}
