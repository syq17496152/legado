package io.legado.app.ui.config

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.help.config.ShareNoteTemplateManager
import io.legado.app.ui.book.read.ShareNoteImageRenderer
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.ComposeActionListDialog
import io.legado.app.ui.widget.compose.ComposeConfirmDialog
import io.legado.app.utils.readBytes
import io.legado.app.utils.readText
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ShareNoteTemplateManageActivity : BaseActivity<ActivityThemeManageBinding>() {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    private val entriesState = mutableStateOf<List<ShareNoteTemplateManager.Entry>>(emptyList())
    private val activeDirNameState = mutableStateOf(ShareNoteTemplateManager.activeDirName())
    private val previewFilesState = mutableStateOf<Map<String, File>>(emptyMap())
    private val shareStyleState = mutableStateOf(ShareNoteTemplateManager.currentStyle())
    private var editingEntry: ShareNoteTemplateManager.Entry? = null
    private var loadTemplatesJob: Job? = null
    private val previewJobs = mutableListOf<Job>()
    private var previewBatch = 0

    private val importTemplate = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            lifecycleScope.launch {
                kotlin.runCatching {
                    withContext(Dispatchers.IO) {
                        val name = uri.lastPathSegment.orEmpty().lowercase()
                        if (name.endsWith(".zip")) {
                            val temp = File(cacheDir, "share_note_template_import.zip")
                            temp.writeBytes(uri.readBytes(this@ShareNoteTemplateManageActivity))
                            ShareNoteTemplateManager.importZip(temp)
                        } else {
                            ShareNoteTemplateManager.importHtml(uri.readText(this@ShareNoteTemplateManageActivity))
                        }
                    }
                }.onSuccess {
                    toastOnUi(R.string.success)
                    loadTemplates()
                }.onFailure {
                    toastOnUi(it.localizedMessage ?: getString(R.string.wrong_format))
                }
            }
        }
    }

    private val exportTemplate = registerForActivityResult(HandleFileContract()) { result ->
        if (result.uri != null) toastOnUi(R.string.export_success)
    }

    private val editTemplateLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data?.getStringExtra("text") ?: return@registerForActivityResult
            lifecycleScope.launch {
                kotlin.runCatching {
                    withContext(Dispatchers.IO) {
                        ShareNoteTemplateManager.addOrUpdate(text, editingEntry)
                    }
                }.onSuccess {
                    editingEntry = null
                    toastOnUi(R.string.success)
                    loadTemplates()
                }.onFailure {
                    toastOnUi(it.localizedMessage ?: getString(R.string.wrong_format))
                }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = "摘录分享模板"
        initComposeContent()
        loadTemplates()
    }

    override fun onResume() {
        super.onResume()
        loadTemplates()
    }

    override fun onDestroy() {
        loadTemplatesJob?.cancel()
        cancelPreviewJobs()
        super.onDestroy()
    }

    private fun initComposeContent() {
        val container = binding.recyclerView.parent as? ViewGroup ?: return
        val index = container.indexOfChild(binding.recyclerView)
        container.removeView(binding.recyclerView)
        binding.tabBar.visibility = android.view.View.GONE
        binding.tvSummary.visibility = android.view.View.GONE
        binding.btnAdd.visibility = android.view.View.GONE
        val cv = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setContent {
                ShareNoteTemplateManageScreen(
                    entries = entriesState.value,
                    activeDirName = activeDirNameState.value,
                    shareStyle = shareStyleState.value,
                    previewFiles = previewFilesState.value,
                    onApply = ::applyTemplate,
                    onStyleChange = ::updateShareStyle,
                    onEdit = ::editTemplate,
                    onMoreActions = ::templateActions,
                    onAddClick = ::showAddActions
                )
            }
        }
        container.addView(cv, index)
    }

    private fun loadTemplates() {
        loadTemplatesJob?.cancel()
        cancelPreviewJobs()
        loadTemplatesJob = lifecycleScope.launch {
            val entries = try {
                withContext(Dispatchers.IO) { ShareNoteTemplateManager.loadEntries() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("Share note template load failed\n${e.localizedMessage}", e)
                toastOnUi(getString(R.string.theme_package_load_failed, e.localizedMessage ?: getString(R.string.error)))
                return@launch
            }
            entriesState.value = entries
            activeDirNameState.value = ShareNoteTemplateManager.activeDirName()
            refreshPreviews(entries)
        }
    }

    private fun refreshPreviews(
        entries: List<ShareNoteTemplateManager.Entry>,
        force: Boolean = false
    ) {
        cancelPreviewJobs()
        val currentDirs = entries.mapTo(hashSetOf()) { it.dirName }
        previewFilesState.value = previewFilesState.value.filterKeys { it in currentDirs }
        val batch = previewBatch
        val style = shareStyleState.value
        entries.forEach { entry ->
            val job = lifecycleScope.launch {
                val file = try {
                    ShareNoteImageRenderer.renderPreview(
                        context = this@ShareNoteTemplateManageActivity,
                        entry = entry,
                        force = force,
                        style = style
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.put("Share note template preview failed: ${entry.dirName}\n${e.localizedMessage}", e)
                    null
                }
                file?.let {
                    if (batch == previewBatch) {
                        previewFilesState.value = previewFilesState.value + (entry.dirName to it)
                    }
                }
            }
            previewJobs += job
        }
    }

    private fun cancelPreviewJobs() {
        previewBatch += 1
        previewJobs.forEach { it.cancel() }
        previewJobs.clear()
    }

    private fun applyTemplate(entry: ShareNoteTemplateManager.Entry) {
        ShareNoteTemplateManager.apply(entry)
        activeDirNameState.value = entry.dirName
        toastOnUi(R.string.success)
    }

    private fun updateShareStyle(style: ShareNoteTemplateManager.ShareStyle) {
        if (style == shareStyleState.value) return
        ShareNoteTemplateManager.saveStyle(style)
        shareStyleState.value = ShareNoteTemplateManager.currentStyle()
        previewFilesState.value = emptyMap()
        refreshPreviews(entriesState.value, force = true)
    }

    private fun showAddActions() {
        showDialogFragment(
            ComposeActionListDialog.create(
                title = "添加模板",
                labels = listOf("复制内置模板新建", "导入 HTML", "导入 ZIP"),
                negativeText = getString(R.string.cancel)
            ) { index ->
                when (index) {
                    0 -> copyTemplate(ShareNoteTemplateManager.builtinEntry(), editAfterCopy = true)
                    1 -> importTemplate.launch {
                        mode = HandleFileContract.FILE
                        title = "导入 HTML"
                        allowExtensions = arrayOf("html", "htm")
                    }
                    2 -> importTemplate.launch {
                        mode = HandleFileContract.FILE
                        title = "导入 ZIP"
                        allowExtensions = arrayOf("zip")
                    }
                }
            }
        )
    }

    private fun templateActions(entry: ShareNoteTemplateManager.Entry): List<AppManagementMenuAction> {
        return buildList {
            add(AppManagementMenuAction("预览头部") { openPreview(entry) })
            add(AppManagementMenuAction("复制新建") { copyTemplate(entry, editAfterCopy = true) })
            add(AppManagementMenuAction("导出 HTML") { exportHtml(entry) })
            add(AppManagementMenuAction("导出 ZIP") { exportZip(entry) })
            if (entry.source == ShareNoteTemplateManager.Source.LOCAL) {
                add(AppManagementMenuAction("删除", danger = true) { confirmDelete(entry) })
            }
        }
    }

    private fun openPreview(entry: ShareNoteTemplateManager.Entry) {
        lifecycleScope.launch {
            val file = try {
                ShareNoteImageRenderer.renderPreview(
                    context = this@ShareNoteTemplateManageActivity,
                    entry = entry,
                    force = true,
                    style = shareStyleState.value
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("Share note template manual preview failed: ${entry.dirName}\n${e.localizedMessage}", e, true)
                null
            }
            if (file == null) {
                toastOnUi(R.string.error)
            } else {
                previewFilesState.value = previewFilesState.value + (entry.dirName to file)
                toastOnUi(R.string.success)
            }
        }
    }

    private fun copyTemplate(entry: ShareNoteTemplateManager.Entry, editAfterCopy: Boolean) {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) { ShareNoteTemplateManager.copyToLocal(entry) }
            }.onSuccess {
                loadTemplates()
                if (editAfterCopy) editTemplate(it)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun editTemplate(entry: ShareNoteTemplateManager.Entry) {
        val editable = if (entry.source == ShareNoteTemplateManager.Source.BUILTIN) {
            copyTemplate(entry, editAfterCopy = true)
            return
        } else {
            entry
        }
        editingEntry = editable
        editTemplateLauncher.launch(Intent(this, CodeEditActivity::class.java).apply {
            putExtra("title", editable.meta.name)
            putExtra("text", ShareNoteTemplateManager.readTemplateHtml(editable))
            putExtra("languageName", "text.html.basic")
        })
    }

    private fun exportHtml(entry: ShareNoteTemplateManager.Entry) {
        exportTemplate.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                "${safeFileName(entry.meta.name)}.html",
                ShareNoteTemplateManager.exportHtmlBytes(entry),
                "text/html"
            )
        }
    }

    private fun exportZip(entry: ShareNoteTemplateManager.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) { ShareNoteTemplateManager.exportZip(entry) }
            }.onSuccess { file ->
                exportTemplate.launch {
                    mode = HandleFileContract.EXPORT
                    fileData = HandleFileContract.FileData(
                        "${safeFileName(entry.meta.name)}.zip",
                        file,
                        "application/zip"
                    )
                }
            }.onFailure {
                toastOnUi(it.localizedMessage ?: "导出失败")
            }
        }
    }

    private fun confirmDelete(entry: ShareNoteTemplateManager.Entry) {
        showDialogFragment(
            ComposeConfirmDialog.create(
                title = getString(R.string.delete),
                message = entry.meta.name,
                positiveText = getString(R.string.delete),
                negativeText = getString(R.string.cancel),
                dangerPositive = true,
                onPositive = {
                    ShareNoteTemplateManager.deleteLocal(entry)
                    loadTemplates()
                }
            )
        )
    }

    private fun safeFileName(name: String): String {
        return name.trim().ifBlank { "share_note_template" }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
    }
}
