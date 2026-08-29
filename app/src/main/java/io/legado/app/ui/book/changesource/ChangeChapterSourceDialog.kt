package io.legado.app.ui.book.changesource

import android.app.Dialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle.State.STARTED
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.AppMenuSheet
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsSearchBar
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixSelectField
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.observeEvent
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 单章换源界面（Compose 迁移：原 BaseDialogFragment + Toolbar 菜单 +
 * FastScrollRecyclerView/RecyclerView(TOC) 迁移为 ComposeDialogFragment +
 * AppDialogFrame + LazyColumn；构造器、CallBack/Callback 接口、
 * ViewModel 数据流行为保持不变。TOC 面板由返回键先关闭再退出对话框）
 */
@OptIn(ExperimentalMaterial3Api::class)
class ChangeChapterSourceDialog() : ComposeDialogFragment(),
    ChangeChapterSourceAdapter.CallBack,
    ChangeChapterTocAdapter.Callback {

    override val dialogSize: AppDialogSize = AppDialogSize.Wide

    constructor(name: String, author: String, chapterIndex: Int, chapterTitle: String) : this() {
        arguments = Bundle().apply {
            putString("name", name)
            putString("author", author)
            putInt("chapterIndex", chapterIndex)
            putString("chapterTitle", chapterTitle)
        }
    }

    private val callBack: CallBack? get() = activity as? CallBack
    private val viewModel: ChangeChapterSourceViewModel by viewModels()
    private val editSourceResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            viewModel.startSearch()
        }
    private val listState = LazyListState()
    private val tocListState = LazyListState()

    private var titleText by mutableStateOf("")
    private var searchBooks by mutableStateOf<List<SearchBook>>(emptyList())
    private var searchState by mutableStateOf(false)
    private var durText by mutableStateOf("")
    private var curBookUrl by mutableStateOf<String?>(null)
    private var groupItems by mutableStateOf<List<String>>(emptyList())
    private var selectedGroup by mutableStateOf("")
    private var screenKey by mutableStateOf("")
    private var menuTarget by mutableStateOf<SearchBook?>(null)
    private var scoreTick by mutableIntStateOf(0)
    private var tocVisible by mutableStateOf(false)
    private var tocLoading by mutableStateOf(false)
    private var tocItems by mutableStateOf<List<BookChapter>>(emptyList())
    private var tocDurIndex by mutableIntStateOf(0)
    private var searchBook: SearchBook? = null

    private val searchFinishCallback: (isEmpty: Boolean) -> Unit = {
        if (it) {
            val searchGroup = AppConfig.searchGroup
            if (searchGroup.isNotEmpty()) {
                lifecycleScope.launch {
                    showComposeConfirmDialog(
                        title = "搜索结果为空",
                        message = "${searchGroup}分组搜索结果为空,是否切换到全部分组",
                        onPositive = {
                            AppConfig.searchGroup = ""
                            selectedGroup = ""
                            viewModel.startSearch()
                        }
                    )
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            setOnKeyListener { _, keyCode, event ->
                keyCode == KeyEvent.KEYCODE_BACK &&
                        event.action == KeyEvent.ACTION_UP &&
                        handleDialogBackPressed()
            }
            (this as? ComponentDialog)?.onBackPressedDispatcher?.addCallback(
                this@ChangeChapterSourceDialog,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        handleDialogBackPressed()
                    }
                }
            )
        }
    }

    private fun handleDialogBackPressed(): Boolean {
        if (tocVisible) {
            tocVisible = false
            return true
        }
        if (isCancelable) {
            dismissAllowingStateLoss()
        }
        return true
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    Panel()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.initData(arguments, callBack?.oldBook, activity is ReadBookActivity)
        titleText = viewModel.chapterTitle
        durText = callBack?.oldBook?.originName ?: ""
        curBookUrl = callBack?.oldBook?.bookUrl
        selectedGroup = AppConfig.searchGroup
        viewModel.searchFinishCallback = searchFinishCallback
        initLiveData()
        observeEvent<String>(EventBus.SOURCE_CHANGED) {
            curBookUrl = callBack?.oldBook?.bookUrl
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.searchFinishCallback = null
    }

    private fun initLiveData() {
        viewModel.searchStateData.observe(viewLifecycleOwner) {
            searchState = it == true
        }
        lifecycleScope.launch {
            lifecycle.currentStateFlow.first { it.isAtLeast(STARTED) }
            viewModel.searchDataFlow.conflate().collect {
                searchBooks = it
                delay(1000)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(STARTED) {
                viewModel.changeSourceProgress
                    .drop(1)
                    .collect { (count, name) ->
                        durText = getString(
                            R.string.change_source_progress,
                            searchBooks.size,
                            count,
                            viewModel.totalSourceCount,
                            name
                        )
                        delay(500)
                    }
            }
        }
        lifecycleScope.launch {
            appDb.bookSourceDao.flowEnabledGroups().flowOn(IO).conflate().collect {
                groupItems = it
            }
        }
    }

    private fun selectGroup(group: String) {
        if (group == selectedGroup) return
        selectedGroup = group
        AppConfig.searchGroup = group
        lifecycleScope.launch(IO) {
            viewModel.stopSearch()
            if (viewModel.refresh()) {
                viewModel.startSearch()
            }
        }
    }

    private fun scrollToDurSource() {
        val url = oldBookUrl ?: return
        val index = searchBooks.indexOfFirst { it.bookUrl == url }
        if (index >= 0) {
            lifecycleScope.launch { listState.scrollToItem(index) }
        }
    }

    override fun openToc(searchBook: SearchBook) {
        this.searchBook = searchBook
        tocItems = emptyList()
        tocVisible = true
        tocLoading = true
        val book = searchBook.toBook()
        viewModel.getToc(book, { toc: List<BookChapter>, _: BookSource ->
            tocDurIndex = BookHelp.getDurChapter(viewModel.chapterIndex, viewModel.chapterTitle, toc)
            tocLoading = false
            tocItems = toc
            lifecycleScope.launch {
                tocListState.scrollToItem((tocDurIndex - 5).coerceAtLeast(0))
            }
        }, {
            tocVisible = false
            AppLog.put("单章换源获取目录出错\n$it", it, true)
        })
    }

    override fun clickChapter(bookChapter: BookChapter, nextChapterUrl: String?) {
        searchBook?.let {
            tocLoading = true
            viewModel.getContent(it.toBook(), bookChapter, nextChapterUrl, { content ->
                tocLoading = false
                callBack?.replaceContent(content)
                dismissAllowingStateLoss()
            }, { msg ->
                tocLoading = false
                tocVisible = false
                toastOnUi(msg)
            })
        }
    }

    override val oldBookUrl: String?
        get() = callBack?.oldBook?.bookUrl

    override fun topSource(searchBook: SearchBook) {
        viewModel.topSource(searchBook)
    }

    override fun bottomSource(searchBook: SearchBook) {
        viewModel.bottomSource(searchBook)
    }

    override fun editSource(searchBook: SearchBook) {
        editSourceResult.launch {
            putExtra("sourceUrl", searchBook.origin)
        }
    }

    override fun disableSource(searchBook: SearchBook) {
        viewModel.disableSource(searchBook)
    }

    override fun deleteSource(searchBook: SearchBook) {
        viewModel.del(searchBook)
        if (oldBookUrl == searchBook.bookUrl) {
            viewModel.autoChangeSource(callBack?.oldBook?.type) { book, toc, source ->
                callBack?.changeTo(source, book, toc)
            }
        }
    }

    override fun setBookScore(searchBook: SearchBook, score: Int) {
        scoreTick++
        viewModel.setBookScore(searchBook, score)
    }

    override fun getBookScore(searchBook: SearchBook): Int {
        return viewModel.getBookScore(searchBook)
    }

    @Composable
    private fun Panel() {
        val style = rememberAppDialogStyle()
        val palette = style.toMiuixPalette()
        val scope = rememberCoroutineScope()
        var overflowExpanded by remember { mutableStateOf(false) }
        val allGroupLabel = stringResource(R.string.all_source)
        val selectedLabel = selectedGroup.ifEmpty { allGroupLabel }
        AppDialogFrame(
            title = titleText,
            scrollContent = false,
            content = {
                SettingsSearchBar(
                    query = screenKey,
                    onQueryChange = {
                        screenKey = it
                        viewModel.screen(it)
                    },
                    placeholder = stringResource(R.string.screen)
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (searchState) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = style.accent,
                        trackColor = style.fieldSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LegadoMiuixSelectField(
                        label = stringResource(R.string.group),
                        options = listOf(allGroupLabel) + groupItems,
                        selected = selectedLabel,
                        optionLabel = { it },
                        onSelected = { label ->
                            selectGroup(if (label == allGroupLabel) "" else label)
                        },
                        palette = palette,
                        modifier = Modifier.weight(1f),
                        compact = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    LegadoMiuixActionButton(
                        text = if (searchState) {
                            stringResource(R.string.stop)
                        } else {
                            stringResource(R.string.refresh)
                        },
                        palette = palette,
                        onClick = { viewModel.startOrStopSearch() },
                        cornerRadius = style.actionRadius
                    )
                    Box {
                        IconButton(onClick = { overflowExpanded = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.more)
                            )
                        }
                        AppDropdownMenu(
                            expanded = overflowExpanded,
                            onDismiss = { overflowExpanded = false },
                            actions = listOf(
                                MenuAction(
                                    icon = Icons.Filled.Settings,
                                    title = stringResource(R.string.book_source_manage),
                                    onClick = { startActivity<BookSourceActivity>() }
                                ),
                                MenuAction(
                                    icon = Icons.Filled.Person,
                                    title = stringResource(R.string.checkAuthor),
                                    checked = AppConfig.changeSourceCheckAuthor,
                                    onClick = {
                                        AppConfig.changeSourceCheckAuthor =
                                            !AppConfig.changeSourceCheckAuthor
                                        viewModel.refresh()
                                    }
                                ),
                                MenuAction(
                                    icon = Icons.Filled.Sort,
                                    title = stringResource(R.string.load_word_count),
                                    checked = AppConfig.changeSourceLoadWordCount,
                                    onClick = {
                                        AppConfig.changeSourceLoadWordCount =
                                            !AppConfig.changeSourceLoadWordCount
                                        viewModel.onLoadWordCountChecked(
                                            AppConfig.changeSourceLoadWordCount
                                        )
                                    }
                                ),
                                MenuAction(
                                    icon = Icons.Filled.Info,
                                    title = stringResource(R.string.load_info),
                                    checked = AppConfig.changeSourceLoadInfo,
                                    onClick = {
                                        AppConfig.changeSourceLoadInfo =
                                            !AppConfig.changeSourceLoadInfo
                                    }
                                ),
                                MenuAction(
                                    icon = Icons.Filled.Menu,
                                    title = stringResource(R.string.load_toc),
                                    checked = AppConfig.changeSourceLoadToc,
                                    onClick = {
                                        AppConfig.changeSourceLoadToc =
                                            !AppConfig.changeSourceLoadToc
                                    }
                                )
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (tocVisible) {
                    TocPanel(style = style)
                } else {
                    SearchListPanel(style = style, scope = scope)
                }
            },
            actions = {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.close),
                    palette = palette,
                    onClick = { dismissAllowingStateLoss() },
                    cornerRadius = style.actionRadius
                )
            }
        )
        menuTarget?.let { target ->
            AppMenuSheet(
                title = target.originName,
                onDismiss = { menuTarget = null },
                actions = listOf(
                    MenuAction(
                        icon = Icons.Filled.KeyboardArrowUp,
                        title = stringResource(R.string.to_top),
                        onClick = {
                            menuTarget = null
                            topSource(target)
                        }
                    ),
                    MenuAction(
                        icon = Icons.Filled.KeyboardArrowDown,
                        title = stringResource(R.string.to_bottom),
                        onClick = {
                            menuTarget = null
                            bottomSource(target)
                        }
                    ),
                    MenuAction(
                        icon = Icons.Filled.Edit,
                        title = stringResource(R.string.edit_source),
                        onClick = {
                            menuTarget = null
                            editSource(target)
                        }
                    ),
                    MenuAction(
                        icon = Icons.Filled.VisibilityOff,
                        title = stringResource(R.string.disable_source),
                        onClick = {
                            menuTarget = null
                            disableSource(target)
                        }
                    ),
                    MenuAction(
                        icon = Icons.Filled.Delete,
                        title = stringResource(R.string.delete_source),
                        onClick = {
                            menuTarget = null
                            deleteSource(target)
                        }
                    )
                )
            )
        }
    }

    @Composable
    private fun SearchListPanel(
        style: AppDialogStyle,
        scope: kotlinx.coroutines.CoroutineScope
    ) {
        if (searchBooks.isEmpty()) {
            Text(
                text = stringResource(R.string.chapter_list_empty),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .padding(24.dp),
                color = style.secondaryText
            )
        } else {
            var lastTopUrl by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(searchBooks) {
                val topUrl = searchBooks.firstOrNull()?.bookUrl
                if (topUrl != null && topUrl != lastTopUrl) {
                    listState.scrollToItem(0)
                }
                lastTopUrl = topUrl
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                items(items = searchBooks, key = { it.bookUrl }) { item ->
                    val score = remember(item.bookUrl, scoreTick) { getBookScore(item) }
                    SearchBookRow(
                        item = item,
                        isCurrent = item.bookUrl == curBookUrl,
                        score = score,
                        showWordCount = AppConfig.changeSourceLoadWordCount,
                        style = style,
                        onItemClick = { openToc(item) },
                        onItemLongClick = { menuTarget = item },
                        onGoodClick = {
                            setBookScore(item, if (score < 0) 1 else 0)
                        },
                        onBadClick = {
                            setBookScore(item, if (score > 0) -1 else 0)
                        }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = durText,
                modifier = Modifier
                    .weight(1f)
                    .clickable { scrollToDurSource() }
                    .padding(horizontal = 10.dp),
                color = style.primaryText,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis
            )
            IconButton(onClick = { scope.launch { listState.scrollToItem(0) } }) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_drop_up),
                    contentDescription = stringResource(R.string.go_to_top),
                    tint = style.primaryText
                )
            }
            IconButton(onClick = {
                scope.launch {
                    listState.scrollToItem(searchBooks.lastIndex.coerceAtLeast(0))
                }
            }) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_drop_down),
                    contentDescription = stringResource(R.string.go_to_bottom),
                    tint = style.primaryText
                )
            }
        }
    }

    @Composable
    private fun TocPanel(style: AppDialogStyle) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clickable { tocVisible = false },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_down),
                contentDescription = null,
                tint = style.primaryText,
                modifier = Modifier.size(24.dp)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
        ) {
            LazyColumn(
                state = tocListState,
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(items = tocItems, key = { _, item -> item.index }) { _, chapter ->
                    TocChapterRow(
                        chapter = chapter,
                        isDur = chapter.index == tocDurIndex,
                        style = style,
                        onClick = {
                            val next = tocItems.getOrNull(chapter.index + 1)?.url
                            clickChapter(chapter, next)
                        }
                    )
                }
            }
            if (tocLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(40.dp),
                    color = style.accent
                )
            }
        }
    }

    interface CallBack {
        val oldBook: Book?
        fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>)
        fun replaceContent(content: String)
    }

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchBookRow(
    item: SearchBook,
    isCurrent: Boolean,
    score: Int,
    showWordCount: Boolean,
    style: AppDialogStyle,
    onItemClick: () -> Unit,
    onItemLongClick: () -> Unit,
    onGoodClick: () -> Unit,
    onBadClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onItemClick, onLongClick = onItemLongClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.width(30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (score >= 0) {
                Icon(
                    painter = painterResource(R.drawable.ic_praise),
                    contentDescription = stringResource(R.string.like_source),
                    tint = colorResource(if (score > 0) R.color.md_red_A200 else R.color.md_red_100),
                    modifier = Modifier
                        .size(28.dp)
                        .clickable(onClick = onGoodClick)
                        .padding(3.dp)
                )
            }
            if (score <= 0) {
                Icon(
                    painter = painterResource(R.drawable.ic_praise),
                    contentDescription = stringResource(R.string.not_like_source),
                    tint = colorResource(if (score < 0) R.color.md_blue_A200 else R.color.md_blue_100),
                    modifier = Modifier
                        .graphicsLayer { rotationX = 180f }
                        .size(28.dp)
                        .clickable(onClick = onBadClick)
                        .padding(3.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.originName,
                    modifier = Modifier.weight(1f),
                    color = style.primaryText,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.author,
                    modifier = Modifier.padding(start = 8.dp),
                    color = style.secondaryText,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = item.getDisplayLastChapterTitle(),
                color = style.secondaryText,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (showWordCount && !item.chapterWordCountText.isNullOrBlank()) {
                Text(
                    text = item.chapterWordCountText.orEmpty(),
                    color = style.secondaryText,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize
                )
            }
            if (showWordCount && item.respondTime >= 0) {
                Text(
                    text = stringResource(R.string.respondTime, item.respondTime),
                    color = style.secondaryText,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize
                )
            }
        }
        if (isCurrent) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = style.primaryText,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(22.dp)
            )
        }
    }
}

@Composable
private fun TocChapterRow(
    chapter: BookChapter,
    isDur: Boolean,
    style: AppDialogStyle,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (chapter.isVolume) {
                    colorResource(R.color.btn_bg_press)
                } else {
                    Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = chapter.title,
                modifier = Modifier.weight(1f),
                color = if (isDur) style.accent else style.primaryText,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (isDur) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = style.primaryText,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(20.dp)
                )
            }
        }
        if (!chapter.tag.isNullOrEmpty() && !chapter.isVolume) {
            Text(
                text = chapter.tag.orEmpty(),
                color = style.secondaryText,
                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
