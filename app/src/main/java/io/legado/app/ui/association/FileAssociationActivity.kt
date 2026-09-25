package io.legado.app.ui.association

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.postDelayed
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.base.composeShell
import io.legado.app.constant.AppLog
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.lib.permission.Permissions
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppShapes
import io.legado.app.ui.widget.compose.AppUiTokens
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.canRead
import io.legado.app.utils.checkWrite
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.inputStream
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.readUri
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream

class FileAssociationActivity :
    VMBaseActivity<ViewBinding, FileAssociationViewModel>() {

    // 原 activity_translucence.xml 已退役（CE-b）：composeShell 合成壳 + 共享装配（5 个宿主共用）
    override val binding: ViewBinding by lazy { composeShell(this) }
    private val shell by lazy { TransparentShellViews(this) }

    /** 本地书籍目录选择（选中后落 `AppConfig.defaultBookTreeUri` 并继续导入） */
    private val localBookTreeSelect = registerForActivityResult(HandleFileContract()) {
        intent.data?.let { uri ->
            it.uri?.let { treeUri ->
                AppConfig.defaultBookTreeUri = treeUri.toString()
                importBook(treeUri, uri)
            } ?: let {
                val storageHelp = String(assets.open("storageHelp.md").readBytes())
                toastOnUi(storageHelp)
                importBook(null, uri)
            }
        }
    }

    override val viewModel by viewModels<FileAssociationViewModel>()

    private val handler by lazy {
        buildMainHandler()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        shell.install(binding.root)
        shell.rotateLoading.visible()
        initResultCard()
        viewModel.importBookLiveData.observe(this) { uri ->
            importBook(uri)
        }
        viewModel.importRedThemeLiveData.observe(this) { uri ->
            shell.rotateLoading.gone()
            showDialogFragment(ImportRedThemeDialog(uri, true))
        }
        viewModel.importBubbleLiveData.observe(this) { uri ->
            importBubble(uri)
        }
        viewModel.onLineImportLive.observe(this) {
            startActivity<OnLineImportActivity> {
                data = it
            }
            finish()
        }
        viewModel.successLive.observe(this) {
            when (it.first) {
                "bookSource" -> showDialogFragment(ImportBookSourceDialog(it.second, true))
                "rssSource" -> showDialogFragment(ImportRssSourceDialog(it.second, true))
                "replaceRule" -> showDialogFragment(ImportReplaceRuleDialog(it.second, true))
                "httpTts" -> showDialogFragment(ImportHttpTtsDialog(it.second, true))
                "theme" -> showDialogFragment(ImportThemeDialog(it.second, true))
                "bubble" -> importBubble(Uri.parse(it.second))
                "txtRule" -> showDialogFragment(ImportTxtTocRuleDialog(it.second, true))
                "dictRule" -> showDialogFragment(ImportDictRuleDialog(it.second, true))
            }
        }
        viewModel.errorLive.observe(this) {
            // F351：壳自有失败反馈从裸 toast 升为结果卡（文案随卡留档，2s 后沿用既有自动 finish）
            showResult(AssociationResult.Fail(it), finishAfterDelay = true)
        }
        viewModel.openBookLiveData.observe(this) {
            shell.rotateLoading.gone()
            startActivityForBook(it)
            finish()
        }
        viewModel.notSupportedLiveData.observe(this) { data ->
            shell.rotateLoading.gone()
            showComposeConfirmDialog(
                title = appCtx.getString(R.string.draw),
                message = appCtx.getString(R.string.file_not_supported, data.second),
                positiveText = getString(R.string.yes),
                negativeText = getString(R.string.no),
                onPositive = { importBook(data.first) },
                onNegative = { finish() },
                onDismissAction = { finish() }
            )
        }
        intent.data?.let { data ->
            if (data.isContentScheme() && data.canRead()) {
                viewModel.dispatchIntent(data)
            } else {
                PermissionsCompat.Builder()
                    .addPermissions(*Permissions.Group.STORAGE)
                    .rationale(R.string.tip_perm_request_storage)
                    .onGranted {
                        viewModel.dispatchIntent(data)
                    }.onDenied {
                        toastOnUi(getString(R.string.tip_perm_request_storage_failed))
                        handler.postDelayed(2000) {
                            finish()
                        }
                    }.request()
            }
        } ?: finish()
    }

    /** F351：壳自有路径的完成/失败反馈（结果卡原位呈现，替代裸 toast；2s 后随既有自动 finish 收场） */
    private var resultState by mutableStateOf<AssociationResult?>(null)

    private fun initResultCard() {
        // 合成策略已收敛到 TransparentShellViews.slot() 单一工厂（本页不再自行设置）
        shell.resultCard.visibility = View.VISIBLE
        shell.resultCard.setContent {
            LegadoTheme {
                AssociationResultCard(resultState)
            }
        }
    }

    private fun showResult(result: AssociationResult, finishAfterDelay: Boolean) {
        shell.rotateLoading.gone()
        resultState = result
        if (finishAfterDelay) {
            handler.postDelayed(2000) { finish() }
        }
    }

    private fun importBubble(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                withContext(IO) {
                    val file = externalFiles.getFile(
                        "bubbleImports",
                        "import_${System.currentTimeMillis()}.zip"
                    )
                    file.parentFile?.mkdirs()
                    uri.inputStream(this@FileAssociationActivity).getOrThrow().use { input ->
                        FileOutputStream(file).use { output -> input.copyTo(output) }
                    }
                    BubblePackageManager.importZip(file)
                }
            }.onSuccess {
                // F351：成功反馈从裸 toast 升为结果卡（保留既有 2s 自动 finish 习惯，结果已确认在场）
                showResult(
                    AssociationResult.Ok(getString(R.string.association_result_hint)),
                    finishAfterDelay = true
                )
            }.onFailure {
                val msg = getString(R.string.import_bubble_failed, it.localizedMessage)
                AppLog.put(msg, it)
                showResult(AssociationResult.Fail(msg), finishAfterDelay = true)
            }
        }
    }

    /**
     * F350（优化 6）：书籍导入「先看货，再选仓」。
     *
     * 首次导入（`defaultBookTreeUri` 为空）时，原实现**直接拉起**系统 SAF 目录选择器——用户在不知道
     * 「要导入的是哪本书、多大、什么格式」的状态下先做目录授权决策（顺序反了，取消即 finish，
     * 重来只能从外部应用再打开一次）。改为先出摘要确认卡，确认后才拉起目录选择器；
     * **已持久化 treeUri 的老用户不看到此卡**（零打断）。
     */
    private fun importBook(uri: Uri) {
        if (uri.isContentScheme()) {
            val treeUriStr = AppConfig.defaultBookTreeUri
            if (treeUriStr.isNullOrEmpty()) {
                confirmBookImport(uri)
            } else {
                importBook(Uri.parse(treeUriStr), uri)
            }
        } else {
            importBook(null, uri)
        }
    }

    private fun launchBookTreeSelect() {
        localBookTreeSelect.launch {
            title = getString(R.string.select_book_folder)
            mode = HandleFileContract.DIR_SYS
        }
    }

    /** F350：摘要确认卡（文件名 / 格式 / 大小 + 去向说明，全部为 intent 元数据级信息，不读文件内容） */
    private fun confirmBookImport(uri: Uri) {
        lifecycleScope.launch {
            val summary = withContext(IO) { describeBookUri(uri) }
            showComposeConfirmDialog(
                title = getString(R.string.book_import_summary_title),
                message = summary,
                positiveText = getString(R.string.select_book_folder),
                negativeText = getString(R.string.cancel),
                messageInContent = true,
                onPositive = { launchBookTreeSelect() },
                onNegative = { finish() },
                onDismissAction = { finish() }
            )
        }
    }

    private fun describeBookUri(uri: Uri): String {
        val doc = DocumentFile.fromSingleUri(this, uri)
        val name = doc?.name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
        val format = name.substringAfterLast('.', "").uppercase().ifBlank { "?" }
        val size = ConvertUtils.formatFileSize(doc?.length() ?: 0L)
        return buildString {
            append(getString(R.string.book_import_summary_file, name)).append('\n')
            append(getString(R.string.book_import_summary_format, format)).append('\n')
            append(getString(R.string.book_import_summary_size, size)).append('\n')
            append(getString(R.string.book_import_summary_hint))
        }
    }

    private fun importBook(treeUri: Uri?, uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                withContext(IO) {
                    if (treeUri == null) {
                        viewModel.importBook(uri)
                    } else if (treeUri.isContentScheme()) {
                        val treeDoc =
                            DocumentFile.fromTreeUri(this@FileAssociationActivity, treeUri)
                        if (!treeDoc!!.checkWrite()) {
                            throw InvalidBooksDirException(getString(R.string.books_dir_permission_denied))
                        }
                        readUri(uri) { fileDoc, inputStream ->
                            val name = fileDoc.name
                            var doc = treeDoc.findFile(name)
                            if (doc == null || fileDoc.lastModified > doc.lastModified()) {
                                if (doc == null) {
                                    doc = treeDoc.createFile(FileUtils.getMimeType(name), name)
                                        ?: throw InvalidBooksDirException(
                                            getString(R.string.books_dir_permission_denied)
                                        )
                                }
                                contentResolver.openOutputStream(doc.uri)!!.use { oStream ->
                                    inputStream.copyTo(oStream)
                                    oStream.flush()
                                }
                            }
                            viewModel.importBook(doc.uri)
                        }
                    } else {
                        val treeFile = File(treeUri.path ?: treeUri.toString())
                        if (!treeFile.checkWrite()) {
                            throw InvalidBooksDirException(getString(R.string.books_dir_permission_denied))
                        }
                        readUri(uri) { fileDoc, inputStream ->
                            val name = fileDoc.name
                            val file = treeFile.getFile(name)
                            if (!file.exists() || fileDoc.lastModified > file.lastModified()) {
                                FileOutputStream(file).use { oStream ->
                                    inputStream.copyTo(oStream)
                                    oStream.flush()
                                }
                            }
                            viewModel.importBook(Uri.fromFile(file))
                        }
                    }
                }
            }.onFailure {
                when (it) {
                    is InvalidBooksDirException -> launchBookTreeSelect()

                    else -> {
                        val msg = getString(R.string.import_book_failed, it.localizedMessage)
                        AppLog.put(msg, it)
                        showResult(AssociationResult.Fail(msg), finishAfterDelay = true)
                    }
                }
            }
        }
    }

}

/** F351：壳自有路径的结果态（成功 / 失败，副文案随卡留档） */
private sealed interface AssociationResult {
    val message: String

    data class Ok(override val message: String) : AssociationResult
    data class Fail(override val message: String) : AssociationResult
}

/**
 * F351：透明壳结果卡。
 *
 * 透明壳一切 UI 都叠加在 scrim 上（弹层叠弹层会碎）⇒ 结果反馈复用**壳自身**的居中槽位原位呈现
 * （替代原裸 toast）：状态徽标（成功 ✓ / 失败 ✗）+ 标题 + 副文案；结果态由调用方持用
 * （2s 自动 finish 的既有习惯不变）。取色走 [AppUiTokens.settingPalette]（禁止页内自建取色链）。
 */
@Composable
private fun AssociationResultCard(result: AssociationResult?) {
    if (result == null) return
    val palette = AppUiTokens.settingPalette()
    val ok = result is AssociationResult.Ok
    Surface(
        color = palette.row.let { Color(it) },
        shape = AppShapes.Card,
        modifier = Modifier.padding(horizontal = 32.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                text = (if (ok) "✓ " else "✗ ") + stringResource(
                    if (ok) R.string.association_result_ok else R.string.association_result_fail
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (ok) colorResource(R.color.success) else palette.danger
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = result.message,
                style = MaterialTheme.typography.bodySmall,
                color = palette.primaryText,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
