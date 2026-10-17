package io.legado.app.ui.config

import android.net.Uri
import android.os.Bundle
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
import io.legado.app.help.config.EpubLoadingTemplate
import io.legado.app.help.config.EpubLoadingTemplateManager
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.TopBarActionRow
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.ComposeConfirmDialog
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首屏加载模板管理页宿主（reader-theme-family 3.12 Phase 3）。
 *
 * 结构照 `ReaderTemplateManageActivity`（同族管理页先例）：
 * `BaseActivity<ViewBinding>` + `composeShell` + `attachComposeContent` 单源，
 * 内容区交给 [EpubLoadingTemplateManageScreen]，宿主只负责「IO 编排 + 用户动作 + 弹层」。
 *
 * 取色/刷新卡点：页面取色由 `AppPackageManageScreen` 的 `rememberAppManagementPalette` 承担
 * （不硬编码色）；`manageBackgroundAlphaEnabled() = true` 接入管理族背景透明度单键。
 *
 * 任何失败（读取/校验/保存/删除/应用）都给出**可见反馈**（`exceptionOrNull()?.message`），禁止静默。
 */
class EpubLoadingTemplateManageActivity : BaseActivity<ViewBinding>() {

    override val binding: ViewBinding by lazy { composeShell(this) }

    private val templatesState = mutableStateOf<List<EpubLoadingTemplate>>(emptyList())
    private val selectedIdState = mutableStateOf(EpubLoadingTemplate.DEFAULT.id)
    private var loadJob: Job? = null

    /** 正在被 JSON 覆盖的自定义模板 id；为空表示新增导入（`save` 的 `editingId` 语义）。 */
    private var importEditingId: String? = null

    private val importTemplate = registerForActivityResult(HandleFileContract()) { result ->
        val uri = result.uri ?: return@registerForActivityResult
        val editingId = importEditingId
        importEditingId = null
        showDialogFragment(
            ComposeConfirmDialog.create(
                title = if (editingId != null) "编辑模板" else "导入模板",
                message = ImportDisclaimer,
                positiveText = if (editingId != null) "确认保存" else "确认导入",
                negativeText = getString(R.string.cancel),
                onPositive = { importFromUri(uri, editingId) }
            )
        )
    }

    private fun importFromUri(uri: Uri, editingId: String?) {
        lifecycleScope.launch {
            val json = try {
                withContext(Dispatchers.IO) {
                    // 模板是纯文本 JSON：按文本读（不用二进制），解码校验在 save 内完成
                    contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?: throw IllegalStateException("无法读取所选文件")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("Loading template read failed\n${e.localizedMessage}", e)
                toastOnUi("文件读取失败：${e.localizedMessage ?: "未知错误"}")
                return@launch
            }
            val saved = withContext(Dispatchers.IO) {
                EpubLoadingTemplateManager.save(this@EpubLoadingTemplateManageActivity, json, editingId)
            }
            saved.onSuccess {
                toastOnUi(R.string.success)
                loadTemplates()
            }.onFailure {
                toastOnUi(it.message ?: getString(R.string.error))
            }
        }
    }

    private val exportTemplate = registerForActivityResult(HandleFileContract()) { result ->
        if (result.uri != null) toastOnUi(R.string.export_success)
    }

    override fun manageBackgroundAlphaEnabled(): Boolean = true

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeContent()
        loadTemplates()
    }

    override fun onResume() {
        super.onResume()
        // 模板可能在别处变动 / 夜间主题切换（预览与回落都依赖）⇒ 回来时刷新
        loadTemplates()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        super.onDestroy()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun initComposeContent() {
        binding.root.attachComposeContent {
            // G-37：`LegadoTheme` 上提为承载内容的根作用域，否则在主题包/夜间下退回默认色板
            LegadoTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    GlassTopAppBar(
                        title = getString(R.string.epub_loading_template_manage),
                        navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        onNavClick = { finish() },
                        actions = { TopBarActionRow(emptyList()) }
                    )
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        EpubLoadingTemplateManageScreen(
                            templates = templatesState.value,
                            selectedId = selectedIdState.value,
                            onApply = ::applyTemplate,
                            onEdit = ::startEdit,
                            onMoreActions = ::templateActions,
                            onAddClick = ::startImport,
                            onRestoreDefault = ::restoreDefault
                        )
                    }
                }
            }
        }
    }

    private fun loadTemplates() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val loaded = try {
                withContext(Dispatchers.IO) {
                    val all = EpubLoadingTemplateManager.all(this@EpubLoadingTemplateManageActivity)
                    val selected = EpubLoadingTemplateManager.selected(this@EpubLoadingTemplateManageActivity)
                    all to selected.id
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("Loading template catalog load failed\n${e.localizedMessage}", e)
                toastOnUi(getString(R.string.error))
                return@launch
            }
            templatesState.value = loaded.first
            selectedIdState.value = loaded.second
        }
    }

    private fun applyTemplate(template: EpubLoadingTemplate) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                EpubLoadingTemplateManager.apply(this@EpubLoadingTemplateManageActivity, template.id)
            }
            if (result.isSuccess) {
                toastOnUi(R.string.success)
                loadTemplates()
            } else {
                toastOnUi(result.exceptionOrNull()?.message ?: getString(R.string.error))
            }
        }
    }

    private fun startImport() {
        importEditingId = null
        launchImportPicker()
    }

    /** 自定义模板的「编辑」＝用新的 JSON 覆盖该模板（复用 `save` 的 `editingId` 语义）。 */
    private fun startEdit(template: EpubLoadingTemplate) {
        importEditingId = template.id
        launchImportPicker()
    }

    private fun launchImportPicker() {
        importTemplate.launch {
            mode = HandleFileContract.FILE
            title = "导入首屏加载模板（JSON）"
            allowExtensions = arrayOf("json")
        }
    }

    private fun templateActions(template: EpubLoadingTemplate): List<AppManagementMenuAction> = buildList {
        add(AppManagementMenuAction("预览") { previewTemplate(template) })
        add(AppManagementMenuAction("导出 JSON") { exportTemplateFile(template) })
        if (!template.builtIn) {
            add(AppManagementMenuAction("删除", danger = true) { confirmDelete(template) })
        }
    }

    private fun previewTemplate(template: EpubLoadingTemplate) {
        showDialogFragment(EpubLoadingPreviewDialog.create(template))
    }

    private fun exportTemplateFile(template: EpubLoadingTemplate) {
        val json = EpubLoadingTemplateManager.export(template)
        exportTemplate.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                "${safeFileName(template.name)}.loadingTemplate.json",
                json.toByteArray(),
                "application/json"
            )
        }
    }

    private fun confirmDelete(template: EpubLoadingTemplate) {
        showDialogFragment(
            ComposeConfirmDialog.create(
                title = getString(R.string.delete),
                message = template.name,
                positiveText = getString(R.string.delete),
                negativeText = getString(R.string.cancel),
                dangerPositive = true,
                onPositive = {
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            EpubLoadingTemplateManager.delete(
                                this@EpubLoadingTemplateManageActivity, template.id
                            )
                        }
                        if (result.isSuccess) {
                            toastOnUi(R.string.success)
                            loadTemplates()
                        } else {
                            toastOnUi(result.exceptionOrNull()?.message ?: getString(R.string.error))
                        }
                    }
                }
            )
        )
    }

    private fun restoreDefault() {
        EpubLoadingTemplateManager.restoreDefault(this)
        toastOnUi(R.string.success)
        loadTemplates()
    }

    private fun safeFileName(name: String): String =
        name.trim().ifBlank { "loading_template" }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")

    companion object {
        /** 导入/编辑前告知来源可信（模板仅含配色与自研插画，不含可执行内容）。 */
        private const val ImportDisclaimer =
            "请确认该模板文件来源可信。模板仅包含配色与自研插画参数，不含任何可执行内容；" +
                "导入后会经格式与上限校验（单件 ≤ 48KB、最多 32 套）。"
    }
}
