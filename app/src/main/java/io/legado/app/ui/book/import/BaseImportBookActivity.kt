package io.legado.app.ui.book.import

import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.base.attachComposeContent
import io.legado.app.base.composeShell
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfig
import io.legado.app.model.localBook.LocalBook
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

abstract class BaseImportBookActivity<VM : ViewModel> :
    VMBaseActivity<ViewBinding, VM>() {

    // 原 activity_import_book.xml 已退役（CE-b）：改 composeShell 工厂创建合成壳，Compose 全权接管
    final override val binding: ViewBinding by lazy { composeShell(this) }

    /**
     * 多选底栏（原 XML `select_action_bar`）程序化等价物（CE-b）。
     *
     * 必须是 Activity 字段而非在 `AndroidView` 工厂里创建：宿主在 `onActivityCreated` 里就要
     * 对它调用 `setMainActionText/inflateMenu/setCallBack`（组合发生在 `onActivityCreated` 之后，
     * 工厂内创建会让这些配置扑空）。
     */
    protected val selectActionBar: SelectActionBar by lazy { SelectActionBar(this) }

    /**
     * 装配「主内容区 + 底部多选底栏」（原 XML：`compose_host` 为 `0dp` 且底边约束到
     * `select_action_bar` 之上）。语义等价：底栏恒贴底、主内容占其余全部高度。
     */
    protected fun installImportBookContent(content: @Composable () -> Unit) {
        binding.root.attachComposeContent {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) { content() }
                AndroidView(factory = { selectActionBar })
            }
        }
    }

    private var localBookTreeSelectListener: ((Boolean) -> Unit)? = null

    val localBookTreeSelect = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { treeUri ->
            AppConfig.defaultBookTreeUri = treeUri.toString()
            localBookTreeSelectListener?.invoke(true)
        } ?: localBookTreeSelectListener?.invoke(false)
    }

    /**
     * 设置书籍保存位置
     */
    protected suspend fun setBookStorage() = suspendCancellableCoroutine sc@{ block ->
        localBookTreeSelectListener = {
            localBookTreeSelectListener = null
            block.resume(it)
        }
        //测试书籍保存位置是否设置
        if (!AppConfig.defaultBookTreeUri.isNullOrBlank()) {
            localBookTreeSelectListener = null
            block.resume(true)
            return@sc
        }
        //测试读写??
        val storageHelp = String(assets.open("storageHelp.md").readBytes())
        val hint = getString(R.string.select_book_folder)
        showComposeConfirmDialog(
            title = hint,
            message = storageHelp,
            positiveText = getString(R.string.ok),
            negativeText = getString(R.string.cancel),
            onPositive = {
                localBookTreeSelect.launch {
                    title = hint
                }
            },
            onNegative = {
                localBookTreeSelectListener = null
                block.resume(false)
            },
            onDismissAction = {
                localBookTreeSelectListener = null
                block.resume(false)
            }
        )
    }

    abstract fun onSearchTextChange(newText: String?)

    protected fun startReadBook(book: Book) {
        startActivityForBook(book)
    }

    protected fun onArchiveFileClick(fileDoc: FileDoc) {
        val fileNames = ArchiveUtils.getArchiveFilesName(fileDoc) {
            it.matches(AppPattern.bookFileRegex)
        }
        if (fileNames.size == 1) {
            val name = fileNames[0]
            lifecycleScope.launch {
                withContext(IO) { appDb.bookDao.getBookByFileName(name) }?.let {
                    startReadBook(it)
                } ?: showImportAlert(fileDoc, name)
            }
        } else {
            showSelectBookReadAlert(fileDoc, fileNames)
        }
    }

    private fun showSelectBookReadAlert(fileDoc: FileDoc, fileNames: List<String>) {
        if (fileNames.isEmpty()) {
            toastOnUi(R.string.unsupport_archivefile_entry)
            return
        }
        showComposeChoiceListDialog(
            getString(R.string.start_read),
            fileNames
        ) { index ->
            val name = fileNames[index]
            lifecycleScope.launch {
                withContext(IO) { appDb.bookDao.getBookByFileName(name) }?.let {
                    startReadBook(it)
                } ?: showImportAlert(fileDoc, name)
            }
        }
    }

    /* 添加压缩包内指定文件到书架 */
    private inline fun addArchiveToBookShelf(
        fileDoc: FileDoc,
        fileName: String,
        onSuccess: (Book) -> Unit
    ) {
        LocalBook.importArchiveFile(fileDoc.uri, fileName) {
            it.contains(fileName)
        }.firstOrNull()?.run {
            onSuccess.invoke(this)
        }
    }

    /* 提示是否重新导入所点击的压缩文件 */
    private fun showImportAlert(fileDoc: FileDoc, fileName: String) {
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.no_book_found_bookshelf),
            positiveText = getString(R.string.ok),
            negativeText = getString(R.string.no),
            onPositive = {
                addArchiveToBookShelf(fileDoc, fileName) {
                    startReadBook(it)
                }
            }
        )
    }

}