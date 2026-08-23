package io.legado.app.ui.book.read

import android.content.Context
import android.database.ContentObserver
import android.graphics.RectF
import android.net.Uri
import android.provider.Settings
import android.util.AttributeSet
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadMenuCustomButton
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.library.LibraryCloudState
import io.legado.app.help.book.isEpub
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.getSourceType
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.rememberThemeUiPalette
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.book.read.config.rememberReaderMenuDialogStyle
import io.legado.app.model.ReadBook
import io.legado.app.model.SourceCallBack
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.utils.activity
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.openUrl
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.startActivity

class ReadMenu @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    // region Public API
    var canShowMenu: Boolean = false

    fun reset() {
        updateColorConfig()
        updateButtonLayout()
        upBookView()
        upSeekBar()
    }

    fun refreshMenuColorFilter() {
        updateColorConfig()
    }

    fun upBrightnessState() {
        brightnessAuto = context.getPrefBoolean("brightnessAuto", true) || !showBrightnessView
        setScreenBrightness(AppConfig.readBrightness.toFloat())
    }

    fun setScreenBrightness(value: Float) {
        activity?.run {
            fun setBrightness(v: Float) {
                val params = window.attributes
                params.screenBrightness = v
                window.attributes = params
            }
            val autoBrightness = BRIGHTNESS_OVERRIDE_NONE
            if (brightnessAuto || value == autoBrightness) {
                setBrightness(autoBrightness)
                return
            }
            val brightness = if (value < 1f) 0.004f else value / 255f
            var isSunMax = false
            if (brightness == 1f) {
                val sysBrightness = getCurrentBrightness(context)
                if (sysBrightness == 255) {
                    isSunMax = true
                }
            }
            if (isSunMax) {
                contentObserver = object : ContentObserver(buildMainHandler()) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        super.onChange(selfChange, uri)
                        if (contentObserver == null) return
                        if (uri == Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)) {
                            val sysBrightness = getCurrentBrightness(context)
                            if (sysBrightness < 200) {
                                setBrightness(brightness)
                                contentObserver?.let {
                                    context.contentResolver.unregisterContentObserver(it)
                                }
                                contentObserver = null
                            } else if (sysBrightness < 255) {
                                setBrightness(brightness)
                            } else {
                                setBrightness(autoBrightness)
                            }
                        }
                    }
                }
                val brightnessUri = Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)
                context.contentResolver.registerContentObserver(
                    brightnessUri, false, contentObserver!!
                )
                setBrightness(autoBrightness)
            } else {
                setBrightness(brightness)
            }
        }
    }

    fun runMenuIn(anim: Boolean = !AppConfig.isEInkMode) {
        callBack.onMenuShow()
        this.visibility = VISIBLE
        callBack.upSystemUiVisibility()
        isTopBarVisible = true
        isBottomMenuVisible = true
        if (!LocalConfig.readMenuHelpVersionIsLast) {
            callBack.showHelp()
        }
    }

    fun runMenuOut(anim: Boolean = !AppConfig.isEInkMode, onMenuOutEnd: (() -> Unit)? = null) {
        if (isMenuOutAnimating) return
        isMenuOutAnimating = true
        callBack.onMenuHide()
        this.onMenuOutEnd = onMenuOutEnd
        isTopBarVisible = false
        isBottomMenuVisible = false
        // 延迟清理状态（等待动画结束）
        postDelayed({
            this.visibility = INVISIBLE
            canShowMenu = false
            isMenuOutAnimating = false
            onMenuOutEnd?.invoke()
            callBack.upSystemUiVisibility()
        }, if (anim) 300L else 0L)
    }

    fun bottomMenuBoundsIn(target: android.view.View): RectF? {
        val bounds = bottomMenuBounds ?: return null
        if (bounds.width() <= 0 || bounds.height() <= 0) return null
        if (target.width <= 0 || target.height <= 0) return null
        val targetLocation = IntArray(2)
        target.getLocationInWindow(targetLocation)
        return RectF(
            bounds.left - targetLocation[0],
            bounds.top - targetLocation[1],
            bounds.right - targetLocation[0],
            bounds.bottom - targetLocation[1]
        )
    }

    fun upBookView() {
        val currentBook = ReadBook.book
        currentBookName = currentBook?.name
        if (callBack.isEpubCoreBook()) {
            currentChapterUrl = callBack.epubCoreChapterUrl()
            currentChapterName = callBack.epubCoreChapterTitle().orEmpty()
            upSeekBar()
            canGoPrev = ReadBook.durChapterIndex != 0
            canGoNext = ReadBook.durChapterIndex != ReadBook.simulatedChapterSize - 1
            return
        }
        val currentTextChapter = ReadBook.curTextChapter
        val currentChapter = currentTextChapter?.chapter
            ?: currentBook?.let { book ->
                appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
            }
        if (currentChapter != null) {
            currentChapterName = currentTextChapter?.title
                ?: currentChapter.title.takeIf { it.isNotBlank() }
                ?: currentBook?.durChapterTitle?.takeIf { it.isNotBlank() }
            if (!ReadBook.isLocalBook) {
                currentChapterUrl = resolveChapterUrl(currentChapter)
            } else {
                currentChapterUrl = null
            }
            upSeekBar()
            canGoPrev = ReadBook.durChapterIndex != 0
            canGoNext = ReadBook.durChapterIndex != ReadBook.simulatedChapterSize - 1
        } else {
            currentChapterUrl = null
            currentChapterName = currentBook?.durChapterTitle?.takeIf { it.isNotBlank() }
        }
    }

    fun upSeekBar() {
        when (AppConfig.progressBarBehavior) {
            "page" -> {
                val epubPageCount = callBack.epubCorePageCount()
                if (epubPageCount > 0) {
                    seekMax = epubPageCount - 1
                    seekProgress = callBack.epubCorePageIndex().coerceIn(0, seekMax)
                } else {
                    ReadBook.curTextChapter?.let {
                        seekMax = it.pageSize - 1
                        seekProgress = ReadBook.durPageIndex
                    }
                }
            }
            "chapter" -> {
                seekMax = ReadBook.simulatedChapterSize - 1
                seekProgress = ReadBook.durChapterIndex
            }
        }
    }

    fun setSeekPage(seek: Int) {
        seekProgress = seek
    }

    fun setAutoPage(autoPage: Boolean) {
        autoPageActive = autoPage
    }

    fun updateCloudLibraryState(state: LibraryCloudState) {
        cloudState = state
        showCloudIcon = callBack.isLibraryCloudEnabled()
    }
    // endregion

    // region Private state
    private val callBack: CallBack get() = activity as CallBack
    private var composeView: ComposeView? = null
    private var confirmSkipToChapter: Boolean = false
    private var isMenuOutAnimating = false
    private var onMenuOutEnd: (() -> Unit)? = null
    private var contentObserver: ContentObserver? = null

    private val immersiveMenu: Boolean
        get() = AppConfig.readBarStyleFollowPage && ReadBookConfig.durConfig.curBgType() == 0

    private val showBrightnessView: Boolean
        get() = context.getPrefBoolean(PreferKey.showBrightnessView, true)

    // Compose state
    private var isTopBarVisible by mutableStateOf(false)
    private var isBottomMenuVisible by mutableStateOf(false)
    private var seekProgress by mutableIntStateOf(0)
    private var seekMax by mutableIntStateOf(0)
    private var canGoPrev by mutableStateOf(false)
    private var canGoNext by mutableStateOf(false)
    private var brightnessAuto by mutableStateOf(true)
    private var currentBookName by mutableStateOf<String?>(null)
    private var currentChapterName by mutableStateOf<String?>(null)
    private var currentChapterUrl by mutableStateOf<String?>(null)
    private var cloudState by mutableStateOf(LibraryCloudState.DISABLED)
    private var showCloudIcon by mutableStateOf(false)
    private var autoPageActive by mutableStateOf(false)
    private var buttonLayout by mutableStateOf(ReadMenuButtonConfig.defaultLayout())
    private var customButtonMetadata by mutableStateOf(emptyMap<Long, ReadMenuCustomButton>())
    private var bottomMenuBounds by mutableStateOf<RectF?>(null)
    private var colorTick by mutableIntStateOf(0)
    // endregion

    init {
        // 设置 FrameLayout 背景透明，避免退出时闪白屏
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { ReadMenuContent() }
        }
        addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        upBrightnessState()
        updateButtonLayout()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        contentObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
            contentObserver = null
        }
    }

    // region Compose content
    @Composable
    private fun ReadMenuContent() {
        colorTick
        val themeSignature = rememberThemeUiPalette().signature
        val style = rememberReadMenuStyle()
        val bodyFontFamily = remember(context, themeSignature) {
            androidx.compose.ui.text.font.FontFamily(context.uiTypeface())
        }
        val titleFontFamily = remember(context, themeSignature) {
            androidx.compose.ui.text.font.FontFamily(context.titleTypeface())
        }

        CompositionLocalProvider(
            androidx.compose.material3.LocalTextStyle provides
                androidx.compose.material3.LocalTextStyle.current.copy(fontFamily = bodyFontFamily)
        ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 点击空白区域关闭菜单（只响应 tap，不干扰拖拽）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { runMenuOut() }
                    }
            )

            // 顶栏（带状态栏 padding）
            AnimatedVisibility(
                visible = isTopBarVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = slideInVertically(
                    initialOffsetY = { -it },
                    animationSpec = if (AppConfig.isEInkMode) snap() else tween(150)
                ),
                exit = slideOutVertically(
                    targetOffsetY = { -it },
                    animationSpec = if (AppConfig.isEInkMode) snap() else tween(150)
                )
            ) {
                val statusBarHeight = WindowInsets.statusBars
                    .asPaddingValues()
                    .calculateTopPadding()
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 状态栏占位（带背景色，衔接顶栏）
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(statusBarHeight)
                            .background(style.surface)
                    )
                    // Toolbar（书名 + 换源/刷新/缓存 + 三点菜单）
                    ReadMenuTitleBar(
                        state = ReadMenuTitleBarState(
                            bookName = currentBookName,
                            isLocalBook = ReadBook.isLocalBook,
                            isEpub = callBack.isEpubCoreBook()
                        ),
                        actions = ReadMenuTitleBarActions(
                            onBackClick = { callBack.returnToBookshelf() },
                            onBookClick = { callBack.openBookInfoActivity() },
                            onChangeSourceClick = { callBack.changeSource() },
                            onChangeSourceLongClick = { callBack.changeSourceSingle() },
                            onRefreshClick = { callBack.refreshContent() },
                            onRefreshLongClick = { callBack.showRefreshOptions() },
                            onCacheClick = { callBack.showCacheDialog() },
                            onAddBookmarkClick = { callBack.addBookmark() },
                            onEditContentClick = { callBack.editContent() },
                            onPageAnimClick = { callBack.showPageAnim() },
                            onMenuEditClick = { callBack.editMenu() },
                            onGetProgressClick = { callBack.showGetProgress() },
                            onCoverProgressClick = { callBack.showCoverProgress() },
                            onReverseContentClick = { callBack.reverseContent() },
                            onSimulatedReadingClick = { (activity as? BaseReadBookActivity)?.showSimulatedReading() },
                            onChangeReplaceRuleClick = { callBack.changeReplaceRuleState() },
                            onSameTitleRemovedClick = { callBack.showSameTitleRemoved() },
                            onReSegmentClick = { callBack.showReSegment() },
                            onImageStyleClick = { callBack.showImageStyle() },
                            onUpdateTocClick = { callBack.updateToc() },
                            onParagraphRuleClick = { callBack.showParagraphRuleManage() },
                            onEffectiveReplacesClick = { callBack.showEffectiveReplaces() },
                            onLogClick = { callBack.showLog() },
                            onHelpClick = { callBack.showHelp() }
                        ),
                        style = style,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (AppConfig.showReadTitleBarAddition) {
                        // 操作栏（章节信息 + 操作按钮）
                        ReadMenuActionBar(
                            state = ReadMenuActionBarState(
                                chapterName = currentChapterName,
                                isLocalBook = ReadBook.isLocalBook,
                                sourceName = ReadBook.bookSource?.bookSourceName,
                                showCustomButton = ReadBook.bookSource?.customButton == true,
                                showCloudIcon = showCloudIcon,
                                cloudState = cloudState,
                                hasLogin = !ReadBook.bookSource?.loginUrl.isNullOrEmpty(),
                                hasVipChapter = !ReadBook.bookSource?.loginUrl.isNullOrEmpty()
                                        && ReadBook.curTextChapter?.isVip == true
                                        && ReadBook.curTextChapter?.isPay != true
                            ),
                            actions = ReadMenuActionBarActions(
                                onChapterClick = { handleChapterClick() },
                                onChapterLongClick = { handleChapterLongClick() },
                                onLoginClick = { callBack.showLogin() },
                                onPayClick = { callBack.payAction() },
                                onEditSourceClick = { callBack.openSourceEditActivity() },
                                onDisableSourceClick = { callBack.disableSource() },
                                onCustomButtonClick = { handleCustomButtonClick() },
                                onCustomButtonLongClick = { handleCustomButtonLongClick() },
                                onCloudClick = { callBack.showLibraryCloudChapters(refresh = false) },
                                onCloudLongClick = {
                                    if (io.legado.app.BuildConfig.DEBUG) {
                                        callBack.showLibraryCloudDebug()
                                    } else {
                                        callBack.showLibraryCloudChapters(refresh = true)
                                    }
                                    true
                                }
                            ),
                            style = style,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 底部菜单
            AnimatedVisibility(
                visible = isBottomMenuVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = if (AppConfig.isEInkMode) snap() else tween(150)
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = if (AppConfig.isEInkMode) snap() else tween(150)
                )
            ) {
                ReadMenuBottomPanel(style = style)
            }
        }
        } // CompositionLocalProvider
    }

    @Composable
    private fun ReadMenuBottomPanel(style: AppDialogStyle) {
        val layout = buttonLayout
        val hasButtons = layout.firstRow.isNotEmpty() || layout.secondRow.isNotEmpty()
        val showBrightness = showBrightnessView && hasButtons
        val navigationBarBottom = WindowInsets.navigationBars
            .asPaddingValues()
            .calculateBottomPadding()

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    val pos = coordinates.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
                    val size = coordinates.size
                    bottomMenuBounds = RectF(
                        pos.x, pos.y,
                        pos.x + size.width, pos.y + size.height
                    )
                },
            shape = RoundedCornerShape(topStart = style.panelRadius, topEnd = style.panelRadius),
            color = style.surface,
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 14.dp,
                        top = 10.dp,
                        end = 14.dp,
                        bottom = 10.dp + navigationBarBottom
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // SeekBar 区域
                ReadMenuSeekBarRow(
                    seekProgress = seekProgress,
                    seekMax = seekMax,
                    canGoPrev = canGoPrev,
                    canGoNext = canGoNext,
                    style = style,
                    onPrevClick = {
                        if (callBack.isEpubCoreBook()) {
                            callBack.openPreviousEpubCoreChapter()
                        } else {
                            ReadBook.moveToPrevChapter(upContent = true, toLast = false)
                        }
                    },
                    onNextClick = {
                        if (callBack.isEpubCoreBook()) {
                            callBack.openNextEpubCoreChapter()
                        } else {
                            ReadBook.moveToNextChapter(true)
                        }
                    },
                    onSeekStart = {
                        // 拖拽开始时禁用遮罩点击
                    },
                    onSeekStop = { progress ->
                        when (AppConfig.progressBarBehavior) {
                            "page" -> {
                                if (!callBack.skipToEpubCorePage(progress)) {
                                    ReadBook.skipToPage(progress)
                                }
                            }
                            "chapter" -> {
                                if (confirmSkipToChapter) {
                                    callBack.skipToChapter(progress)
                                } else {
                                    context.alert("章节跳转确认", "确定要跳转章节吗？") {
                                        yesButton {
                                            confirmSkipToChapter = true
                                            callBack.skipToChapter(progress)
                                        }
                                        noButton { upSeekBar() }
                                        onCancelled { upSeekBar() }
                                    }
                                }
                            }
                        }
                    }
                )

                // 亮度区
                if (showBrightness) {
                    ReadMenuBrightnessRow(
                        brightness = AppConfig.readBrightness,
                        isAuto = brightnessAuto,
                        showBrightnessView = true,
                        style = style,
                        onAutoClick = {
                            context.putPrefBoolean("brightnessAuto", !brightnessAuto)
                            upBrightnessState()
                        },
                        onBrightnessChange = { setScreenBrightness(it.toFloat()) },
                        onBrightnessStop = { AppConfig.readBrightness = it }
                    )
                }

                // 按钮区
                if (hasButtons) {
                    ReadMenuButtonGrid(
                        firstRow = layout.firstRow,
                        secondRow = layout.secondRow,
                        customButtonMetadata = customButtonMetadata,
                        autoPageActive = autoPageActive,
                        isNightTheme = AppConfig.isNightTheme,
                        style = style,
                        onClick = { ref -> handleMenuButtonClick(ref) },
                        onLongClick = { ref -> handleMenuButtonLongClick(ref) }
                    )
                }
            }
        }
    }

    @Composable
    private fun rememberReadMenuStyle(): AppDialogStyle {
        val bgColor = if (immersiveMenu) {
            runCatching {
                ReadBookConfig.durConfig.curBgStr().toColorInt()
            }.getOrDefault(context.bottomBackground)
        } else {
            context.bottomBackground
        }
        return rememberReaderMenuDialogStyle(bgColor)
    }
    // endregion

    // region Button handling
    private fun updateButtonLayout() {
        val layout = ReadMenuButtonConfig.load(context)
        customButtonMetadata = if ((layout.firstRow + layout.secondRow).any { it.type == ReadMenuButtonConfig.TYPE_CUSTOM }) {
            appDb.readMenuCustomButtonDao.all().associateBy { it.id }
        } else {
            emptyMap()
        }
        buttonLayout = layout
    }

    private fun handleMenuButtonClick(ref: ReadMenuButtonConfig.ButtonRef) {
        when (ref.type) {
            ReadMenuButtonConfig.TYPE_BUILTIN -> handleBuiltinButtonClick(ref.id)
            ReadMenuButtonConfig.TYPE_CUSTOM -> ref.id.toLongOrNull()?.let {
                callBack.runCustomReadMenuButton(it)
            }
        }
    }

    private fun handleBuiltinButtonClick(id: String) {
        when (id) {
            ReadMenuButtonConfig.Builtin.SEARCH -> runMenuOut {
                callBack.openSearchActivity(null)
            }
            ReadMenuButtonConfig.Builtin.AUTO_PAGE -> runMenuOut {
                callBack.autoPage()
            }
            ReadMenuButtonConfig.Builtin.REPLACE_RULE -> callBack.openReplaceRule()
            ReadMenuButtonConfig.Builtin.NIGHT_THEME -> {
                AppConfig.isNightTheme = !AppConfig.isNightTheme
                ThemeConfig.applyDayNight(context)
                callBack.onNightThemeChanged()
            }
            ReadMenuButtonConfig.Builtin.CATALOG -> runMenuOut {
                callBack.openChapterList()
            }
            ReadMenuButtonConfig.Builtin.READ_ALOUD -> runMenuOut {
                callBack.showReadAloudDialog()
            }
            ReadMenuButtonConfig.Builtin.READ_STYLE -> runMenuOut {
                callBack.showReadStyle()
            }
            ReadMenuButtonConfig.Builtin.SETTING -> runMenuOut {
                callBack.showMoreSetting()
            }
            ReadMenuButtonConfig.Builtin.READ_ASSISTANT -> runMenuOut {
                callBack.openReadAssistant()
            }
            ReadMenuButtonConfig.Builtin.AI_SUMMARY -> runMenuOut {
                callBack.openReadAiSummary()
            }
            ReadMenuButtonConfig.Builtin.PARAGRAPH_RULES -> runMenuOut {
                callBack.showParagraphRuleQuickDialog()
            }
            ReadMenuButtonConfig.Builtin.BUBBLE -> runMenuOut {
                callBack.showBubbleQuickSwitch()
            }
            ReadMenuButtonConfig.Builtin.CHARACTERS -> runMenuOut {
                callBack.openBookCharacters()
            }
        }
    }

    private fun handleMenuButtonLongClick(ref: ReadMenuButtonConfig.ButtonRef): Boolean {
        return when {
            ref.type == ReadMenuButtonConfig.TYPE_BUILTIN &&
                    ref.id == ReadMenuButtonConfig.Builtin.READ_ALOUD -> {
                runMenuOut { callBack.showReadAloudDialog() }
                true
            }
            ref.type == ReadMenuButtonConfig.TYPE_CUSTOM -> {
                ref.id.toLongOrNull()?.let { id ->
                    runMenuOut { callBack.loginCustomReadMenuButton(id) }
                }
                true
            }
            else -> false
        }
    }
    // endregion

    // region Source popup (handled by ReadMenuTopBar DropdownMenu)
    // endregion

    // region Chapter click
    private fun handleChapterClick() {
        if (ReadBook.isLocalBook) return
        val url = currentChapterUrl?.trim().orEmpty().takeIf { it.isNotBlank() } ?: return
        Coroutine.async {
            context.startActivity<WebViewActivity> {
                val bookSource = ReadBook.bookSource
                putExtra("title", currentChapterName)
                putExtra("url", url)
                putExtra("sourceOrigin", bookSource?.bookSourceUrl)
                putExtra("sourceName", bookSource?.bookSourceName)
                putExtra("sourceType", bookSource?.getSourceType())
            }
        }
    }

    private fun handleChapterLongClick() {
        if (ReadBook.isLocalBook) return
        val url = currentChapterUrl?.trim().orEmpty().takeIf { it.isNotBlank() } ?: return
        context.alert(R.string.open_fun) {
            setMessage(R.string.use_browser_open)
            okButton { context.openUrl(url) }
            noButton()
        }
    }
    // endregion

    // region Custom button
    private fun handleCustomButtonClick() {
        val book = ReadBook.book ?: return
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
        activity?.let { act ->
            SourceCallBack.callBackBtn(
                act, SourceCallBack.CLICK_CUSTOM_BUTTON,
                ReadBook.bookSource, book, chapter, BookType.text
            )
        }
    }

    private fun handleCustomButtonLongClick() {
        val book = ReadBook.book ?: return
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
        activity?.let { act ->
            SourceCallBack.callBackBtn(
                act, SourceCallBack.LONG_CLICK_CUSTOM_BUTTON,
                ReadBook.bookSource, book, chapter, BookType.text
            )
        }
    }
    // endregion

    // region Utility
    private fun updateColorConfig() {
        colorTick++
    }

    private fun getCurrentBrightness(ctx: Context): Int {
        return try {
            Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Settings.SettingNotFoundException) {
            -1
        }
    }

    private fun resolveChapterUrl(chapter: io.legado.app.data.entities.BookChapter): String? {
        val candidates = listOf(
            chapter.url,
            runCatching { chapter.getAbsoluteURL() }.getOrNull(),
            chapter.baseUrl,
            ReadBook.book?.bookUrl
        )
        return candidates.asSequence()
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotBlank() }
    }
    // endregion

    interface CallBack {
        fun autoPage()
        fun openReplaceRule()
        fun openChapterList()
        fun openSearchActivity(searchWord: String?)
        fun openSourceEditActivity()
        fun openBookInfoActivity()
        fun returnToBookshelf()
        fun showReadStyle()
        fun showMoreSetting()
        fun openReadAiSummary()
        fun showReadAloudDialog()
        fun upSystemUiVisibility()
        fun onClickReadAloud()
        fun showHelp()
        fun showLogin()
        fun payAction()
        fun disableSource()
        fun skipToChapter(index: Int)
        fun onMenuShow()
        fun onMenuHide()
        fun epubCorePageCount(): Int = 0
        fun epubCorePageIndex(): Int = 0
        fun skipToEpubCorePage(index: Int): Boolean = false
        fun isEpubCoreBook(): Boolean = false
        fun epubCoreChapterTitle(): String? = null
        fun epubCoreChapterUrl(): String? = null
        fun openPreviousEpubCoreChapter() = Unit
        fun openNextEpubCoreChapter() = Unit
        fun onNightThemeChanged() = Unit
        fun isLibraryCloudEnabled(): Boolean = false
        fun libraryCloudState(): LibraryCloudState = LibraryCloudState.DISABLED
        fun showLibraryCloudChapters(refresh: Boolean) = Unit
        fun showLibraryCloudDebug() = Unit
        fun openReadAssistant() = Unit
        fun showParagraphRuleQuickDialog() = Unit
        fun showBubbleQuickSwitch() = Unit
        fun openBookCharacters() = Unit
        fun runCustomReadMenuButton(id: Long) = Unit
        fun editCustomReadMenuButton(id: Long) = Unit
        fun loginCustomReadMenuButton(id: Long) = Unit
        fun refreshContent()
        fun changeSource()
        fun changeSourceSingle()
        fun showRefreshOptions()
        fun showCacheDialog()
        fun addBookmark()
        fun editContent()
        fun showPageAnim()
        fun editMenu()
        fun updateToc()
        fun reverseContent()
        fun changeReplaceRuleState()
        fun showReSegment()
        fun showSameTitleRemoved()
        fun showImageStyle()
        fun showParagraphRuleManage()
        fun showEffectiveReplaces()
        fun showLog()
        fun showGetProgress()
        fun showCoverProgress()
    }
}
