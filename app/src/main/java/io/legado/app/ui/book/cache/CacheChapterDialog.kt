package io.legado.app.ui.book.cache

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.isAudio
import io.legado.app.ui.widget.components.SettingsSearchBar
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 缓存章节管理（View→Compose 迁移）
 * RecyclerView + SearchView → LazyColumn + SettingsSearchBar；
 * 对外接口不变：[newInstance]、[Callback]，搜索防抖/筛选/多选/批量缓存/批量删除行为语义保持一致。
 */
class CacheChapterDialog() : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    override val dialogWindowAnimations: Int = R.style.AnimDialogFade

    private val viewModel by activityViewModels<CacheManageActivityViewModel>()

    private val book: Book by lazy {
        requireArguments().getParcelable<Book>("book")!!
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    CacheChapterContent()
                }
            }
        }
    }

    private val callback: Callback?
        get() = activity as? Callback

    @Composable
    private fun CacheChapterContent() {
        val style = rememberAppDialogStyle()
        val palette = style.toMiuixPalette()
        val scope = rememberCoroutineScope()

        var searchQuery by rememberSaveable { mutableStateOf("") }
        var filter by rememberSaveable { mutableStateOf(CacheChapterFilter.ALL) }
        var refreshTick by remember { mutableIntStateOf(0) }
        var chapterItems by remember { mutableStateOf<List<CacheChapterItem>>(emptyList()) }
        var loaded by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var selectionMode by remember { mutableStateOf(false) }
        var selectedKeys by remember { mutableStateOf(setOf<String>()) }

        val selectedItems = chapterItems.filter { it.selectionKey() in selectedKeys }

        LaunchedEffect(searchQuery, filter, refreshTick) {
            val searchKey = searchQuery.trim()
            if (searchKey.isNotBlank()) {
                delay(SEARCH_DEBOUNCE_MS)
            }
            loading = true
            try {
                val result = viewModel.getChapterItems(book, searchKey.ifBlank { null }, filter)
                chapterItems = result
                errorMessage = null
                loaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chapterItems = emptyList()
                errorMessage = e.localizedMessage
            } finally {
                loading = false
            }
        }

        fun exitSelectionMode() {
            selectionMode = false
            selectedKeys = emptySet()
        }

        fun toggleSelection(item: CacheChapterItem) {
            val key = item.selectionKey()
            selectedKeys = if (key in selectedKeys) {
                selectedKeys - key
            } else {
                selectedKeys + key
            }
        }

        fun onChapterClick(item: CacheChapterItem) {
            if (!selectionMode) {
                callback?.openCacheChapter(book, item.chapter)
                return
            }
            toggleSelection(item)
        }

        fun onChapterLongClick(item: CacheChapterItem) {
            if (!selectionMode) {
                selectionMode = true
            }
            toggleSelection(item)
        }

        fun switchFilter(newFilter: CacheChapterFilter) {
            if (filter == newFilter) return
            filter = newFilter
            exitSelectionMode()
        }

        fun selectAllVisible() {
            selectedKeys = chapterItems.map { it.selectionKey() }.toSet()
        }

        fun cacheSelectedChapters() {
            val targets = selectedItems.filterNot { it.cached }
            if (targets.isEmpty()) {
                toastOnUi(R.string.cache_manage_batch_empty)
                return
            }
            scope.launch {
                kotlin.runCatching {
                    if (book.isAudio) {
                        viewModel.cacheAudioChapters(book, targets.map { it.chapter })
                    } else {
                        viewModel.cacheBookChapters(book, targets.map { it.chapter })
                    }
                }.onSuccess { count ->
                    callback?.onCacheChanged()
                    exitSelectionMode()
                    if (book.isAudio && count > 0) {
                        toastOnUi(getString(R.string.cache_manage_audio_cache_started, count))
                        dismissAllowingStateLoss()
                    } else {
                        refreshTick++
                        toastOnUi(getString(R.string.cache_manage_cache_selected_done, count))
                    }
                }.onFailure {
                    toastOnUi(getString(R.string.cache_manage_cache_failed, it.localizedMessage))
                }
            }
        }

        fun deleteSelectedChapters() {
            val targets = selectedItems.filter { it.cached }
            if (targets.isEmpty()) {
                toastOnUi(R.string.cache_manage_batch_empty)
                return
            }
            showComposeConfirmDialog(
                title = getString(R.string.delete),
                message = getString(R.string.cache_manage_delete_selected_confirm, targets.size),
                dangerPositive = true,
                onPositive = {
                    loading = true
                    scope.launch {
                        kotlin.runCatching {
                            viewModel.deleteChapterCaches(book, targets.map { it.chapter })
                        }.onSuccess {
                            callback?.onCacheChanged()
                            exitSelectionMode()
                            refreshTick++
                            toastOnUi(R.string.delete_success)
                        }.onFailure {
                            loading = false
                            toastOnUi(
                                getString(
                                    R.string.cache_manage_delete_chapter_failed,
                                    it.localizedMessage
                                )
                            )
                        }
                    }
                }
            )
        }

        val message = errorMessage
        AppDialogFrame(
            title = stringResource(R.string.cache_manage_chapters),
            scrollContent = false,
            content = {
                SettingsSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = stringResource(R.string.cache_manage_search_chapter)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterText(
                        text = stringResource(R.string.cache_manage_all_chapters),
                        selected = filter == CacheChapterFilter.ALL,
                        style = style,
                        onClick = { switchFilter(CacheChapterFilter.ALL) }
                    )
                    FilterText(
                        text = stringResource(R.string.cache_manage_cached),
                        selected = filter == CacheChapterFilter.CACHED,
                        style = style,
                        onClick = { switchFilter(CacheChapterFilter.CACHED) }
                    )
                    FilterText(
                        text = stringResource(R.string.cache_manage_not_cached),
                        selected = filter == CacheChapterFilter.UNCACHED,
                        style = style,
                        onClick = { switchFilter(CacheChapterFilter.UNCACHED) }
                    )
                }
                if (!selectionMode) {
                    Text(
                        text = stringResource(R.string.cache_manage_long_press_hint),
                        color = style.secondaryText,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    when {
                        message != null -> Text(
                            text = message,
                            color = style.secondaryText,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp)
                        )
                        loaded && chapterItems.isEmpty() -> Text(
                            text = stringResource(R.string.chapter_list_empty),
                            color = style.secondaryText,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp)
                        )
                        else -> LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(items = chapterItems, key = { it.selectionKey() }) { item ->
                                ChapterRow(
                                    item = item,
                                    selectionMode = selectionMode,
                                    selected = item.selectionKey() in selectedKeys,
                                    style = style,
                                    onClick = { onChapterClick(item) },
                                    onLongClick = { onChapterLongClick(item) }
                                )
                            }
                        }
                    }
                    if (loading) {
                        CircularProgressIndicator(
                            color = style.accent,
                            strokeWidth = 3.dp,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(28.dp)
                        )
                    }
                }
            },
            actions = {
                if (selectionMode) {
                    Text(
                        text = stringResource(R.string.cache_manage_selected_count, selectedItems.size),
                        color = style.primaryText,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    SelectionActionButton(
                        text = stringResource(R.string.select_all),
                        palette = palette,
                        style = style,
                        onClick = { selectAllVisible() }
                    )
                    val canCache = selectedItems.isNotEmpty()
                    SelectionActionButton(
                        text = stringResource(R.string.cache_manage_cache_selected),
                        palette = palette,
                        style = style,
                        enabled = canCache,
                        onClick = { if (canCache) cacheSelectedChapters() }
                    )
                    val canDelete = selectedItems.isNotEmpty()
                    SelectionActionButton(
                        text = stringResource(R.string.delete),
                        palette = palette,
                        style = style,
                        enabled = canDelete,
                        danger = true,
                        onClick = { if (canDelete) deleteSelectedChapters() }
                    )
                }
            }
        )
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ChapterRow(
        item: CacheChapterItem,
        selectionMode: Boolean,
        selected: Boolean,
        style: AppDialogStyle,
        onClick: () -> Unit,
        onLongClick: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = if (selected) style.accent.copy(alpha = 0.14f) else style.fieldSurface,
                    shape = RoundedCornerShape(style.actionRadius)
                )
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = style.accent,
                        uncheckedColor = style.secondaryText,
                        checkmarkColor = style.surface
                    )
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${item.chapter.index + 1}. ${item.chapter.title}",
                    color = style.primaryText,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        if (item.cached) R.string.cache_manage_cached else R.string.cache_manage_not_cached
                    ),
                    color = style.secondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }

    @Composable
    private fun FilterText(
        text: String,
        selected: Boolean,
        style: AppDialogStyle,
        onClick: () -> Unit
    ) {
        Text(
            text = text,
            color = if (selected) style.accent else style.primaryText,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(style.actionRadius))
                .clickable(onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 6.dp)
        )
    }

    @Composable
    private fun SelectionActionButton(
        text: String,
        palette: LegadoMiuixPalette,
        style: AppDialogStyle,
        enabled: Boolean = true,
        danger: Boolean = false,
        onClick: () -> Unit
    ) {
        LegadoMiuixActionButton(
            text = text,
            palette = palette,
            onClick = onClick,
            modifier = Modifier.alpha(if (enabled) 1f else 0.45f),
            danger = danger,
            cornerRadius = style.actionRadius
        )
    }

    interface Callback {
        fun onCacheChanged()
        fun openCacheChapter(book: Book, chapter: BookChapter) {}
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 180L

        fun newInstance(book: Book): CacheChapterDialog {
            return CacheChapterDialog().apply {
                arguments = bundleOf("book" to book)
            }
        }
    }
}

private fun CacheChapterItem.selectionKey(): String {
    return "${chapter.bookUrl}\u0000${chapter.index}\u0000${chapter.url}"
}
