package io.legado.app.ui.book.bookmark

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.databinding.ActivityAllBookmarkBinding
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 所有书签（L-B5 枝叶页）：全 Compose 接管（AllBookmarkScreen），
 * 导出逻辑保留 ViewModel，点击/长按回抛宿主处理。
 */
class AllBookmarkActivity : VMBaseActivity<ActivityAllBookmarkBinding, AllBookmarkViewModel>() {

    override val viewModel by viewModels<AllBookmarkViewModel>()
    override val binding by viewBinding(ActivityAllBookmarkBinding::inflate)

    // Compose 桥接状态（列表/顶栏/分组在 Compose 侧渲染）
    private var composeBookmarks by mutableStateOf(listOf<Bookmark>())

    private val exportDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                1 -> viewModel.exportBookmark(uri)
                2 -> viewModel.exportBookmarkMd(uri)
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeHost.setContent {
            LegadoTheme {
                AllBookmarkScreen(
                    bookmarks = composeBookmarks,
                    onBack = { finish() },
                    onExportJson = {
                        exportDir.launch {
                            requestCode = 1
                        }
                    },
                    onExportMd = {
                        exportDir.launch {
                            requestCode = 2
                        }
                    },
                    onItemClick = { onItemClick(it) },
                    onItemLongClick = { onItemLongClick(it) }
                )
            }
        }
        observeData()
    }

    private fun observeData() {
        lifecycleScope.launch {
            appDb.bookmarkDao.flowAll().catch {
                AppLog.put("所有书签界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                composeBookmarks = it
            }
        }
    }

    private fun onItemClick(bookmark: Bookmark) {
        lifecycleScope.launch {
            val book = withContext(IO) {
                appDb.bookDao.getBook(bookmark.bookName, bookmark.bookAuthor)
            }
            if (book == null) {
                showDialogFragment(BookmarkDialog(bookmark, 0))
            } else {
                startActivityForBook(book) {
                    putExtra("index", bookmark.chapterIndex)
                    putExtra("chapterPos", bookmark.chapterPos)
                }
            }
        }
    }

    private fun onItemLongClick(bookmark: Bookmark) {
        showDialogFragment(BookmarkDialog(bookmark, 0))
    }
}
