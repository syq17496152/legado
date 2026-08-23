package io.legado.app.ui.book.toc

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.constant.EventBus
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.databinding.ActivityChapterListBinding
import io.legado.app.help.book.isVideo
import io.legado.app.model.ReadBook
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.toc.rule.TxtTocRuleDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.observeEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 目录
 */
class TocActivity : VMBaseActivity<ActivityChapterListBinding, TocViewModel>(),
    TxtTocRuleDialog.CallBack {

    override val binding by viewBinding(ActivityChapterListBinding::inflate)
    override val viewModel by viewModels<TocViewModel>()

    private val waitDialog by lazy { WaitDialog(this) }
    private var cacheRefreshTick = mutableIntStateOf(0)
    private var contentRefreshTick = mutableIntStateOf(0)
    private val exportDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                1 -> viewModel.saveBookmark(uri)
                2 -> viewModel.saveBookmarkMd(uri)
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val bookUrl = intent.getStringExtra("bookUrl").orEmpty()
        if (bookUrl.isNotBlank()) {
            viewModel.initBook(bookUrl)
        }
        binding.composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeView.setContent {
            TocComposeScreen(
                bookUrl = bookUrl,
                contentRefreshTick = contentRefreshTick.intValue,
                cacheRefreshTick = cacheRefreshTick.intValue,
                viewModel = viewModel,
                onBack = { finish() },
                onBookLoaded = { book ->
                    viewModel.bookData.value = book
                },
                onOpenChapter = ::openChapter,
                onEditBookmark = ::editBookmark,
                onShowTocRule = { book ->
                    showDialogFragment(TxtTocRuleDialog(book?.tocUrl ?: viewModel.bookData.value?.tocUrl))
                },
                onUpdateToc = ::upBookAndToc,
                onExportBookmark = {
                    exportDir.launch { requestCode = 1 }
                },
                onExportBookmarkMd = {
                    exportDir.launch { requestCode = 2 }
                },
                onShowLog = {
                    showDialogFragment<AppLogDialog>()
                }
            )
        }
    }

    override fun observeLiveBus() {
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, _) ->
            if (book.bookUrl == viewModel.bookUrl) {
                cacheRefreshTick.intValue++
            }
        }
    }

    override fun onResume() {
        super.onResume()
        contentRefreshTick.intValue++
    }

    override fun onTocRegexDialogResult(tocRegex: String) {
        viewModel.bookData.value?.let { book ->
            book.tocUrl = tocRegex
            upBookAndToc(book)
        }
    }

    private fun upBookAndToc(book: Book) {
        waitDialog.show()
        viewModel.upBookTocRule(book) {
            waitDialog.dismiss()
            contentRefreshTick.intValue++
            if (ReadBook.book == book) {
                if (it == null) {
                    ReadBook.upMsg(null)
                } else {
                    ReadBook.upMsg("LoadTocError:${it.localizedMessage}")
                }
            }
        }
    }

    private fun editBookmark(bookmark: Bookmark, position: Int) {
        showDialogFragment(BookmarkDialog(bookmark, position))
    }

    private fun openChapter(book: Book, chapterList: List<BookChapter>, bookChapter: BookChapter) {
        if (book.isVideo) {
            val volumes = chapterList.filter { it.isVolume }
            var chapterInVolumeIndex = 0
            var durVolumeIndex = 0
            if (volumes.isNotEmpty()) {
                for ((index, volume) in volumes.reversed().withIndex()) {
                    val first = bookChapter.index
                    if (volume.index < first) {
                        chapterInVolumeIndex = first - volume.index - 1
                        durVolumeIndex = volumes.size - index - 1
                        break
                    } else if (volume.index == first) {
                        chapterInVolumeIndex = 0
                        durVolumeIndex = volumes.size - index - 1
                        break
                    }
                }
            } else {
                chapterInVolumeIndex = bookChapter.index
            }
            setResult(
                RESULT_OK, Intent()
                    .putExtra("index", bookChapter.index)
                    .putExtra("chapterChanged", bookChapter.index != book.durChapterIndex)
                    .putExtra("durVolumeIndex", durVolumeIndex)
                    .putExtra("chapterInVolumeIndex", chapterInVolumeIndex)
            )
            finish()
            return
        }
        setResult(
            RESULT_OK, Intent()
                .putExtra("index", bookChapter.index)
                .putExtra("chapterChanged", bookChapter.index != book.durChapterIndex)
        )
        finish()
    }
}
