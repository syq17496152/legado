package io.legado.app.ui.book.info.edit

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Observer
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityBookInfoEditBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.addType
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.removeType
import io.legado.app.ui.book.changecover.ChangeCoverDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.inputStream
import io.legado.app.utils.readUri
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.init.appCtx
import java.io.FileOutputStream

/**
 * 书籍信息编辑（L-B7 枝叶页）：全 Compose 接管（BookInfoEditScreen），
 * 保存/换封面（本地选图/换源/刷新）逻辑保留 Activity，View 状态由 Screen 上抛。
 */
class BookInfoEditActivity :
    VMBaseActivity<ActivityBookInfoEditBinding, BookInfoEditViewModel>(),
    ChangeCoverDialog.CallBack {

    private val selectCover = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            coverChangeTo(uri)
        }
    }

    override val binding by viewBinding(ActivityBookInfoEditBinding::inflate)
    override val viewModel by viewModels<BookInfoEditViewModel>()

    // Compose 桥接状态（数据经 VM 加载后驱动 Screen）
    private var composeBook by mutableStateOf<Book?>(null)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeHost.setContent {
            LegadoTheme {
                BookInfoEditScreen(
                    book = composeBook,
                    onBack = { finish() },
                    onSave = { name, author, typeIndex, coverUrl, intro ->
                        saveData(name, author, typeIndex, coverUrl, intro)
                    },
                    onSelectCover = {
                        selectCover.launch {
                            mode = HandleFileContract.IMAGE
                        }
                    },
                    onChangeCover = {
                        viewModel.book?.let {
                            showDialogFragment(
                                ChangeCoverDialog(it.name, it.author)
                            )
                        }
                    },
                    onRefreshCover = { coverUrl ->
                        viewModel.book?.customCoverUrl = coverUrl
                        composeBook = viewModel.book?.copy()
                    }
                )
            }
        }
        viewModel.bookData.observe(this, Observer { upView(it) })
        if (viewModel.bookData.value == null) {
            intent.getStringExtra("bookUrl")?.let {
                viewModel.loadBook(it)
            }
        }
    }

    private fun upView(book: Book) {
        composeBook = book
    }

    private fun saveData(
        name: String,
        author: String,
        typeIndex: Int,
        coverUrl: String,
        intro: String
    ) {
        val book = viewModel.book ?: return
        val oldBook = book.copy()
        book.name = name
        book.author = author
        val local = if (book.isLocal) BookType.local else 0
        val bookType = when (typeIndex) {
            4 -> BookType.video or local
            2 -> BookType.image or local
            1 -> BookType.audio or local
            else -> BookType.text or local
        }
        book.removeType(BookType.video, BookType.local, BookType.image, BookType.audio, BookType.text)
        book.addType(bookType)
        book.customCoverUrl = if (coverUrl == book.coverUrl) null else coverUrl
        book.customIntro = if (intro == book.intro) null else intro
        BookHelp.updateCacheFolder(oldBook, book)
        viewModel.saveBook(book) {
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun coverChangeTo(coverUrl: String) {
        viewModel.book?.customCoverUrl = coverUrl
        composeBook = viewModel.book?.copy()
    }

    private fun coverChangeTo(uri: Uri) {
        if (uri.scheme?.lowercase() in listOf("http", "https")) {
            coverChangeTo(uri.toString())
            return
        }
        readUri(uri) { fileDoc, inputStream ->
            runCatching {
                inputStream.use {
                    var file = this.externalFiles
                    val suffix = if (fileDoc.name.contains(".9.png", true)) {
                        ".9.png"
                    } else {
                        "." + fileDoc.name.substringAfterLast(".")
                    }
                    val fileName = uri.inputStream(this).getOrThrow().use {
                        MD5Utils.md5Encode(it) + suffix
                    }
                    file = FileUtils.createFileIfNotExist(file, "covers", fileName)
                    FileOutputStream(file).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    coverChangeTo(file.absolutePath)
                }
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }
}