package io.legado.app.ui.config

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.attachComposeContent
import io.legado.app.base.composeShell
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReaderTemplateManager
import io.legado.app.model.localBook.epubcore.template.EpubReaderTemplate
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.TopBarActionRow
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.ComposeActionListDialog
import io.legado.app.ui.widget.compose.ComposeConfirmDialog
import io.legado.app.utils.readText
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 阅读页面模板管理页宿主（epub-md-rich-rendering 阶段 4.8a）。
 *
 * 结构照 `ShareNoteTemplateManageActivity`（同族管理页先例）：
 * `BaseActivity<ViewBinding>` + `composeShell` + `attachComposeContent` 单源，
 * 内容区整体交给 [ReaderTemplateManageScreen]，宿主只负责「IO 编排 + 用户动作 + 弹层」。
 *
 * 取色/刷新卡点：页面行取色由 `AppPackageManageScreen` 的 `rememberAppManagementPalette` 承担
 * （不硬编码色）；`manageBackgroundAlphaEnabled() = true` 接入管理族背景透明度单键。
 */
class ReaderTemplateManageActivity : BaseActivity<ViewBinding>() {

    override val binding: ViewBinding by lazy { composeShell(this) }

    private val entriesState = mutableStateOf<List<ReaderTemplateManager.Entry>>(emptyList())
    private val errorsState = mutableStateOf<List<String>>(emptyList())
    private val appliedState = mutableStateOf(ReaderTemplateManager.appliedId())
    private val effectiveState = mutableStateOf<String?>(null)
    private val templatesEnabledState = mutableStateOf(ReaderTemplateManager.templatesEnabled())
    private var loadJob: Job? = null

    /** 已选中、等待用户确认免责后导入的文件（4.9：导入须显式确认）。 */
    private var pendingImportUri: Uri? = null

    /** 编辑中的模板快照 + 正在编辑的字段（编辑器返回后据此写回）。 */
    private var editingTemplate: EpubReaderTemplate? = null
    private var editingField: TemplateField? = null

    private val importTemplate = registerForActivityResult(HandleFileContract()) { result ->
        val uri = result.uri ?: return@registerForActivityResult
        // 4.9：用户导入模板可能含第三方素材 ⇒ **先显式确认**（免责提示在场）再落库
        pendingImportUri = uri
        showDialogFragment(
            ComposeConfirmDialog.create(
                title = "导入模板",
                message = ReaderTemplateManager.importDisclaimer,
                positiveText = "确认导入",
                negativeText = getString(R.string.cancel),
                onPositive = {
                    pendingImportUri?.let(::importFromUri)
                    pendingImportUri = null
                }
            )
        )
    }

    private fun importFromUri(uri: Uri) {
        lifecycleScope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) {
                    ReaderTemplateManager.importJson(uri.readText(this@ReaderTemplateManageActivity))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("Reader template import failed\n${e.localizedMessage}", e)
                toastOnUi(getString(R.string.wrong_format))
                return@launch
            }
            if (outcome.ok) {
                toastOnUi(R.string.success)
                loadCatalog()
            } else {
                toastOnUi(outcome.failureMessage)
            }
        }
    }

    private val exportTemplate = registerForActivityResult(HandleFileContract()) { result ->
        if (result.uri != null) toastOnUi(R.string.export_success)
    }

    private val editTemplate = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val text = result.data?.getStringExtra("text") ?: return@registerForActivityResult
        val base = editingTemplate
        val field = editingField
        editingTemplate = null
        editingField = null
        if (base == null || field == null) return@registerForActivityResult
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                ReaderTemplateManager.saveUserTemplate(field.write(base, text))
            }
            if (saved) {
                toastOnUi(R.string.success)
                loadCatalog()
            } else {
                toastOnUi(ReaderTemplateManager.lastLibraryError() ?: getString(R.string.error))
            }
        }
    }

    // ui-theme-governance-polish P6：管理族宿主接入背景透明度（封闭清单成员）
    override fun manageBackgroundAlphaEnabled(): Boolean = true

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeContent()
        loadCatalog()
    }

    override fun onResume() {
        super.onResume()
        // 夜间主题可能在别处切换（跟随主题的默认款随日夜变化）⇒ 回来时重算生效模板
        loadCatalog()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        super.onDestroy()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun initComposeContent() {
        binding.root.attachComposeContent {
            // G-37：`LegadoTheme` 必须上提为承载内容的**根作用域**（`LegadoComposeTheme` 只是字体层，
            // 不提供色板），否则页面在主题包/夜间下会退回默认色板。
            LegadoTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    GlassTopAppBar(
                        title = "阅读页面模板",
                        navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        onNavClick = { finish() },
                        actions = { TopBarActionRow(emptyList()) }
                    )
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        ReaderTemplateManageScreen(
                            entries = entriesState.value,
                            catalogErrors = errorsState.value,
                            appliedId = appliedState.value,
                            effectiveId = effectiveState.value,
                            templatesEnabled = templatesEnabledState.value,
                            onToggleTemplates = ::toggleTemplates,
                            onApply = ::applyTemplate,
                            onApplyFollowTheme = ::applyFollowTheme,
                            onEdit = ::openTemplateEditor,
                            onMoreActions = ::templateActions,
                            onAddClick = ::showAddActions,
                            onRestoreDefaults = ::confirmRestoreDefaults
                        )
                    }
                }
            }
        }
    }

    private fun loadCatalog() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val loaded = try {
                withContext(Dispatchers.IO) {
                    val catalog = ReaderTemplateManager.loadCatalog()
                    // 生效模板必须与"可用集 + 当前日夜"同批计算：否则会读到上一份目录的快照
                    val effective = ReaderTemplateManager.resolveEffectiveId(
                        ReaderTemplateManager.appliedId(),
                        AppConfig.isNightTheme,
                        catalog.entries.map { it.id }
                    )
                    catalog to effective
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("Reader template catalog load failed\n${e.localizedMessage}", e)
                toastOnUi(getString(R.string.error))
                return@launch
            }
            entriesState.value = loaded.first.entries
            errorsState.value = loaded.first.errors
            appliedState.value = ReaderTemplateManager.appliedId()
            effectiveState.value = loaded.second
            templatesEnabledState.value = ReaderTemplateManager.templatesEnabled()
        }
    }

    /** 4.9：整体开关。关闭 ⇒ 阅读页完全回到原有排版（无残留）。 */
    private fun toggleTemplates() {
        val next = !ReaderTemplateManager.templatesEnabled()
        ReaderTemplateManager.setTemplatesEnabled(next)
        toastOnUi(if (next) "已启用页面模板" else "已关闭页面模板")
        loadCatalog()
    }

    private fun applyTemplate(entry: ReaderTemplateManager.Entry) {
        lifecycleScope.launch {
            val applied = withContext(Dispatchers.IO) { ReaderTemplateManager.apply(entry.id) }
            if (!applied) {
                toastOnUi("模板不可用：${entry.name}")
                loadCatalog()
            } else {
                toastOnUi(R.string.success)
                loadCatalog()
            }
        }
    }

    private fun applyFollowTheme() {
        ReaderTemplateManager.applyFollowTheme()
        toastOnUi(R.string.success)
        loadCatalog()
    }

    private fun showAddActions() {
        val builtins = entriesState.value.filter { it.source == ReaderTemplateManager.Source.BUILTIN }
        val labels = buildList {
            add("导入模板 JSON")
            if (builtins.isNotEmpty()) add("从内置模板复制…")
        }
        showDialogFragment(
            ComposeActionListDialog.create(
                title = "添加模板",
                labels = labels,
                negativeText = getString(R.string.cancel)
            ) { index ->
                if (index == 0) {
                    importTemplate.launch {
                        mode = HandleFileContract.FILE
                        title = "导入模板 JSON"
                        allowExtensions = arrayOf("json")
                    }
                } else {
                    pickBuiltinToCopy(builtins)
                }
            }
        )
    }

    private fun pickBuiltinToCopy(builtins: List<ReaderTemplateManager.Entry>) {
        showDialogFragment(
            ComposeActionListDialog.create(
                title = "从内置模板复制",
                labels = builtins.map { it.name },
                negativeText = getString(R.string.cancel)
            ) { index ->
                builtins.getOrNull(index)?.let { duplicateTemplate(it, editAfterCopy = false) }
            }
        )
    }

    private fun templateActions(entry: ReaderTemplateManager.Entry): List<AppManagementMenuAction> = buildList {
        add(AppManagementMenuAction("复制新建") { duplicateTemplate(entry, editAfterCopy = false) })
        add(AppManagementMenuAction("导出 JSON") { exportJson(entry) })
        if (entry.source == ReaderTemplateManager.Source.USER) {
            add(AppManagementMenuAction("删除", danger = true) { confirmDelete(entry) })
        }
    }

    private fun duplicateTemplate(entry: ReaderTemplateManager.Entry, editAfterCopy: Boolean) {
        lifecycleScope.launch {
            val copy = withContext(Dispatchers.IO) { ReaderTemplateManager.duplicate(entry.id) }
            if (copy == null) {
                toastOnUi(ReaderTemplateManager.lastLibraryError() ?: getString(R.string.error))
                return@launch
            }
            toastOnUi(R.string.success)
            loadCatalog()
            if (editAfterCopy) {
                openTemplateEditor(ReaderTemplateManager.Entry(copy, ReaderTemplateManager.Source.USER))
            }
        }
    }

    private fun openTemplateEditor(entry: ReaderTemplateManager.Entry) {
        val fields = TemplateField.of(entry.template)
        if (fields.isEmpty()) {
            toastOnUi("该模板没有可编辑内容")
            return
        }
        if (fields.size == 1) {
            launchEditor(entry, fields.first())
            return
        }
        showDialogFragment(
            ComposeActionListDialog.create(
                title = "编辑 ${entry.name}",
                labels = fields.map { it.label },
                negativeText = getString(R.string.cancel)
            ) { index ->
                fields.getOrNull(index)?.let { launchEditor(entry, it) }
            }
        )
    }

    private fun launchEditor(entry: ReaderTemplateManager.Entry, field: TemplateField) {
        editingTemplate = entry.template
        editingField = field
        editTemplate.launch(Intent(this, CodeEditActivity::class.java).apply {
            putExtra("title", "${entry.name} · ${field.label}")
            putExtra("text", field.read(entry.template))
            putExtra("languageName", field.language)
        })
    }

    private fun exportJson(entry: ReaderTemplateManager.Entry) {
        // 4.9：导出/分享前给免责提示（内置模板为自研合规素材 ⇒ 提示为空 ⇒ 不打扰用户）
        val notice = ReaderTemplateManager.exportNotice(entry)
        if (notice.isBlank()) {
            launchExport(entry)
            return
        }
        showDialogFragment(
            ComposeConfirmDialog.create(
                title = "导出模板",
                message = notice,
                positiveText = "继续导出",
                negativeText = getString(R.string.cancel),
                onPositive = { launchExport(entry) }
            )
        )
    }

    private fun launchExport(entry: ReaderTemplateManager.Entry) {
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) { ReaderTemplateManager.exportJson(entry.id) }
            if (json == null) {
                toastOnUi(getString(R.string.error))
                return@launch
            }
            exportTemplate.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData(
                    "${safeFileName(entry.name)}.readerTemplate.json",
                    json.toByteArray(),
                    "application/json"
                )
            }
        }
    }

    private fun confirmDelete(entry: ReaderTemplateManager.Entry) {
        showDialogFragment(
            ComposeConfirmDialog.create(
                title = getString(R.string.delete),
                message = entry.name,
                positiveText = getString(R.string.delete),
                negativeText = getString(R.string.cancel),
                dangerPositive = true,
                onPositive = {
                    lifecycleScope.launch {
                        val removed = withContext(Dispatchers.IO) { ReaderTemplateManager.deleteUserTemplate(entry.id) }
                        if (removed) {
                            toastOnUi(R.string.success)
                            loadCatalog()
                        } else {
                            toastOnUi(ReaderTemplateManager.lastLibraryError() ?: getString(R.string.error))
                        }
                    }
                }
            )
        )
    }

    private fun confirmRestoreDefaults() {
        showDialogFragment(
            ComposeConfirmDialog.create(
                title = "恢复默认",
                message = "将清空全部用户模板与模板偏好，选中态回到「跟随主题」。内置模板不受影响。",
                positiveText = "恢复",
                negativeText = getString(R.string.cancel),
                dangerPositive = true,
                onPositive = {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { ReaderTemplateManager.restoreDefaults() }
                        toastOnUi(R.string.success)
                        loadCatalog()
                    }
                }
            )
        )
    }

    private fun safeFileName(name: String): String {
        return name.trim().ifBlank { "reader_template" }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
    }

    /**
     * 模板可编辑字段。
     *
     * 语言名取自本仓内置语法集（`assets/textmate/languages.json` 只有 js / html / markdown）：
     * CSS 走 html 语法（该语法内嵌 `source.css` 规则），不伪装成不存在的 `source.css` 单语法。
     */
    private enum class TemplateField(val label: String, val language: String) {
        FIRST_PAGE("首页 HTML", "text.html.basic"),
        OTHER_PAGE("续页 HTML", "text.html.basic"),
        SCROLL_HTML("滚动 HTML", "text.html.basic"),
        CSS("CSS", "text.html.basic"),
        JAVASCRIPT("JavaScript", "source.js");

        fun read(template: EpubReaderTemplate): String = when (this) {
            FIRST_PAGE -> template.firstPageHtml
            OTHER_PAGE -> template.otherPageHtml
            SCROLL_HTML -> template.scrollHtml
            CSS -> template.css
            JAVASCRIPT -> template.javascript
        }

        fun write(template: EpubReaderTemplate, text: String): EpubReaderTemplate = when (this) {
            FIRST_PAGE -> template.copy(firstPageHtml = text)
            OTHER_PAGE -> template.copy(otherPageHtml = text)
            SCROLL_HTML -> template.copy(scrollHtml = text)
            CSS -> template.copy(css = text)
            JAVASCRIPT -> template.copy(javascript = text)
        }

        companion object {
            /** 与模板类型匹配的字段集（滚动模板不出"首页/续页"，分页模板不出"滚动 HTML"）。 */
            fun of(template: EpubReaderTemplate): List<TemplateField> =
                if (template.isScrolling) {
                    listOf(SCROLL_HTML, CSS, JAVASCRIPT)
                } else {
                    listOf(FIRST_PAGE, OTHER_PAGE, CSS, JAVASCRIPT)
                }
        }
    }
}