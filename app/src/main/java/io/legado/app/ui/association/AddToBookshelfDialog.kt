package io.legado.app.ui.association

import android.annotation.SuppressLint
import android.app.Application
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.info.BookInfoNavigator
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 添加书籍链接到书架，需要对应网站书源
 * ${origin}/${path}, {origin: bookSourceUrl}
 * 按以下顺序尝试匹配书源并添加网址
 * - UrlOption中的指定的书源网址bookSourceUrl
 * - 在所有启用的书源中匹配orgin
 * - 在所有启用的书源中使用详情页正则匹配${origin}/${path}, {origin: bookSourceUrl}
 * （D1 P0 迁移：BaseDialogFragment 旧 View 弹框 → ComposeDialogFragment，随主题全量纳管）
 */
class AddToBookshelfDialog() : ComposeDialogFragment() {

    constructor(bookUrl: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("bookUrl", bookUrl)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    override val dialogSize: AppDialogSize = AppDialogSize.Form
    override val dialogWindowAnimations: Int = R.style.AnimDialogFade

    val viewModel by viewModels<ViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val bookUrl = arguments?.getString("bookUrl")
        if (bookUrl.isNullOrBlank()) {
            toastOnUi("url不能为空")
            dismissAllowingStateLoss()
        }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val context = LocalContext.current
                val lifecycleOwner = LocalLifecycleOwner.current
                var loadError by remember { mutableStateOf<String?>(null) }
                DisposableEffect(lifecycleOwner) {
                    val errObserver = Observer<String?> { loadError = it }
                    viewModel.loadErrorLiveData.observe(lifecycleOwner, errObserver)
                    onDispose { viewModel.loadErrorLiveData.removeObserver(errObserver) }
                }
                LaunchedEffect(Unit) {
                    bookUrl?.let { url ->
                        val existing = withContext(IO) { appDb.bookDao.getBook(url) }
                        if (existing != null) {
                            AppLog.put("${existing.name} 已在书架", null, true)
                            BookInfoNavigator.open(context, existing)
                            dismissAllowingStateLoss()
                        } else {
                            viewModel.load(url) { book ->
                                viewModel.saveSearchBook(book) {
                                    BookInfoNavigator.open(context, book)
                                    dismissAllowingStateLoss()
                                }
                            }
                        }
                    }
                }
                val errorText = loadError
                if (errorText != null) {
                    LaunchedEffect(errorText) {
                        toastOnUi(errorText)
                        dismissAllowingStateLoss()
                    }
                }
                AddToBookshelfPanel(
                    loading = true,
                    onCancel = { dismissAllowingStateLoss() }
                )
            }
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        if (arguments?.getBoolean("finishOnDismiss") == true) {
            activity?.finish()
        }
    }

    @SuppressLint("SetTextI18n")
    class ViewModel(application: Application) : BaseViewModel(application) {

        val loadStateLiveData = MutableLiveData<Boolean>()
        val loadErrorLiveData = MutableLiveData<String>()
        var book: Book? = null

        fun load(bookUrl: String, success: (book: Book) -> Unit) {
            execute {
//                appDb.bookDao.getBook(bookUrl)?.let {
//                    throw NoStackTraceException("${it.name} 已在书架")
//                } //onFragmentCreated的时候已经判断
                val baseUrl = NetworkUtils.getBaseUrl(bookUrl)
                    ?: throw NoStackTraceException("书籍地址格式不对")
                val urlMatcher = AnalyzeUrl.paramPattern.matcher(bookUrl)
                if (urlMatcher.find()) {
                    val origin = GSON.fromJsonObject<AnalyzeUrl.UrlOption>(
                        bookUrl.substring(urlMatcher.end())
                    ).getOrNull()?.getOrigin()
                    origin?.let {
                        val source = appDb.bookSourceDao.getBookSource(it)
                        source?.let {
                            getBookInfo(bookUrl, source)?.let { book ->
                                return@execute book
                            }
                        }
                    }
                }
                appDb.bookSourceDao.getBookSourceAddBook(baseUrl)?.let { source ->
                    getBookInfo(bookUrl, source)?.let { book ->
                        return@execute book
                    }
                }
                appDb.bookSourceDao.hasBookUrlPattern.forEach { source ->
                    try {
                        val bs = source.getBookSource()!!
                        if (bookUrl.matches(bs.bookUrlPattern!!.toRegex())) {
                            getBookInfo(bookUrl, bs)?.let { book ->
                                return@execute book
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                throw NoStackTraceException("未找到匹配书源")
            }.onError {
                AppLog.put("添加书籍 $bookUrl 出错", it)
                loadErrorLiveData.postValue(it.localizedMessage)
            }.onSuccess {
                book = it
                success.invoke(it)
            }.onStart {
                loadStateLiveData.postValue(true)
            }.onFinally {
                loadStateLiveData.postValue(false)
            }
        }

        private suspend fun getBookInfo(bookUrl: String, source: BookSource): Book? {
            return kotlin.runCatching {
                val book = Book(
                    bookUrl = bookUrl,
                    origin = source.bookSourceUrl,
                    originName = source.bookSourceName
                )
                WebBook.getBookInfoAwait(source, book)
            }.getOrNull()
        }

        fun saveSearchBook(book: Book, success: () -> Unit) {
            execute {
                val searchBook = book.toSearchBook()
                appDb.searchBookDao.insert(searchBook)
                searchBook
            }.onSuccess {
                success.invoke()
            }
        }

    }

}

@Composable
private fun AddToBookshelfPanel(
    loading: Boolean,
    onCancel: () -> Unit
) {
    AppDialogFrame(
        title = stringResource(R.string.add_to_bookshelf),
        content = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .padding(end = 12.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.loading),
                    modifier = Modifier.weight(1f),
                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge
                )
            }
        },
        actions = {
            androidx.compose.material3.TextButton(onClick = onCancel) {
                Text(text = stringResource(android.R.string.cancel))
            }
        }
    )
}