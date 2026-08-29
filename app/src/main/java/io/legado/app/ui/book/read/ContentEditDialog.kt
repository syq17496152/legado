package io.legado.app.ui.book.read

import android.app.Application
import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isLocal
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.dpToPx
import io.legado.app.utils.postEvent
import io.legado.app.utils.sendToClip
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 内容编辑
 */
class ContentEditDialog() : ComposeDialogFragment() {

    override val dialogWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT
    override val dialogHeight: Int = ViewGroup.LayoutParams.MATCH_PARENT

    val viewModel by viewModels<ContentEditViewModel>()

    private var loading by mutableStateOf(false)
    private var chapterTitle by mutableStateOf("")
    private var menuExpanded by mutableStateOf(false)
    private var editorRef: EditText? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        chapterTitle = ReadBook.curTextChapter?.title.orEmpty()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(bottomBackground))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(style.surface)
                            .heightIn(min = 52.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = chapterTitle,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onTitleClick() }
                                .padding(vertical = 12.dp),
                            color = style.primaryText,
                            fontFamily = style.titleFontFamily,
                            fontSize = MaterialTheme.typography.titleMedium.fontSize,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(onClick = {
                            save()
                            dismiss()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Save,
                                contentDescription = stringResource(R.string.action_save),
                                tint = style.primaryText
                            )
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.more),
                                    tint = style.primaryText
                                )
                            }
                            AppDropdownMenu(
                                expanded = menuExpanded,
                                onDismiss = { menuExpanded = false },
                                actions = listOf(
                                    MenuAction(
                                        icon = Icons.Filled.Refresh,
                                        title = stringResource(R.string.reset),
                                        onClick = {
                                            menuExpanded = false
                                            viewModel.initContent(true) { content ->
                                                editorRef?.setText(content)
                                                refreshCurrentChapter()
                                            }
                                        }
                                    ),
                                    MenuAction(
                                        icon = Icons.Filled.ContentCopy,
                                        title = stringResource(R.string.copy_all),
                                        onClick = {
                                            menuExpanded = false
                                            requireContext()
                                                .sendToClip("$chapterTitle\n${editorRef?.text}")
                                        }
                                    )
                                )
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                EditText(ctx).apply {
                                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                    gravity = Gravity.TOP or Gravity.START
                                    val pad = 12.dpToPx()
                                    setPadding(pad, pad, pad, pad)
                                    importantForAutofill =
                                        View.IMPORTANT_FOR_AUTOFILL_NO
                                    editorRef = this
                                }
                            },
                            update = { editor ->
                                editor.setTextColor(style.primaryText.toArgb())
                                editor.setHintTextColor(style.secondaryText.toArgb())
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        if (loading) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(56.dp),
                                    color = style.accent
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.loadStateLiveData.observe(viewLifecycleOwner) {
            loading = it
        }
        viewModel.initContent { content ->
            editorRef?.apply {
                setText(content)
                post {
                    val lineIndex = layout.getLineForOffset(ReadBook.durChapterPos)
                    val lineHeight = layout.getLineTop(lineIndex)
                    scrollTo(0, lineHeight)
                }
            }
        }
    }

    private fun onTitleClick() {
        lifecycleScope.launch {
            val book = ReadBook.book ?: return@launch
            val chapter = withContext(IO) {
                appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
            } ?: return@launch
            editTitle(chapter)
        }
    }

    private fun editTitle(chapter: BookChapter) {
        this@ContentEditDialog.showComposeTextInputDialog(
            title = getString(R.string.edit),
            initialValue = chapter.title,
            positiveText = getString(android.R.string.ok),
            negativeText = getString(android.R.string.cancel),
            onPositive = { title ->
                chapter.title = title
                lifecycleScope.launch {
                    withContext(IO) {
                        chapter.update()
                    }
                    chapterTitle = chapter.getDisplayTitle()
                    refreshCurrentChapter()
                }
            }
        )
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        save()
    }

    private fun save() {
        val content = editorRef?.text?.toString() ?: return
        Coroutine.async {
            val book = ReadBook.book ?: return@async
            val chapter = appDb.bookChapterDao
                .getChapter(book.bookUrl, ReadBook.durChapterIndex)
                ?: return@async
            BookHelp.saveText(book, chapter, content)
            refreshCurrentChapter()
        }
    }

    private fun refreshCurrentChapter() {
        ReadBook.clearTextChapter()
        postEvent(EventBus.UP_CONFIG, arrayListOf(5))
    }

    class ContentEditViewModel(application: Application) : BaseViewModel(application) {
        val loadStateLiveData = MutableLiveData<Boolean>()
        var content: String? = null

        fun initContent(reset: Boolean = false, success: (String) -> Unit) {
            execute {
                val book = ReadBook.book ?: return@execute null
                val chapter = appDb.bookChapterDao
                    .getChapter(book.bookUrl, ReadBook.durChapterIndex)
                    ?: return@execute null
                if (reset) {
                    content = null
                    BookHelp.delContent(book, chapter)
                    if (!book.isLocal) ReadBook.bookSource?.let { bookSource ->
                        WebBook.getContentAwait(bookSource, book, chapter)
                    }
                }
                return@execute content ?: let {
                    val contentProcessor = ContentProcessor.get(book.name, book.origin)
                    val loaded = BookHelp.getContent(book, chapter) ?: return@let null
                    contentProcessor.getContent(book, chapter, loaded, includeTitle = false)
                        .toString()
                }
            }.onStart {
                loadStateLiveData.postValue(true)
            }.onSuccess {
                content = it
                success.invoke(it ?: "")
            }.onFinally {
                loadStateLiveData.postValue(false)
            }
        }

    }

}
