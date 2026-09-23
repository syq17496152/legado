package io.legado.app.ui.highlight

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import androidx.viewbinding.ViewBinding
import io.legado.app.base.attachComposeContent
import io.legado.app.base.composeShell
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.config.HighlightRule
import io.legado.app.ui.book.read.config.HighlightRuleStore
import io.legado.app.ui.book.read.config.RestoreMode
import io.legado.app.ui.highlight.edit.HighlightRuleEditDialog
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.GSON
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.fromJsonArray
import java.io.File

/**
 * F-P1-2 高亮规则管理页（借鉴阅读T，适配 SharedPreferences 存储）
 * F-P1-2 Phase 8 蛋蛋Max 补齐：分组管理 + 预设规则 + 导入导出
 *
 * L-C5 枝叶页：全 Compose 接管（HighlightRuleScreen），弹框已全部迁移 showCompose 系（W5.1 MC-7）。
 */
class HighlightRuleActivity :
    VMBaseActivity<ViewBinding, HighlightRuleViewModel>() {

    override val binding: ViewBinding by lazy { composeShell(this) }
    override val viewModel by viewModels<HighlightRuleViewModel>()

    // Compose 桥接状态（双轨过渡：列表/搜索在 Compose 侧渲染）
    private var composeRules by mutableStateOf(listOf<HighlightRule>())
    private var composeSearchQuery by mutableStateOf("")

    /** R27：包文件选择器（SAF；导入包走「解压校验 → 全通过才写存储」） */
    private val packPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importRulesFromPack(it) }
    }

    // ui-theme-governance-polish P6：管理族宿主接入背景透明度（1.5 封闭清单成员）
    override fun manageBackgroundAlphaEnabled(): Boolean = true

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.root.attachComposeContent {
            LegadoTheme {
                HighlightRuleScreen(
                    rules = composeRules,
                    searchQuery = composeSearchQuery,
                    onSearchQueryChange = { composeSearchQuery = it },
                    onBack = { finish() },
                    onAdd = { showDialogFragment(HighlightRuleEditDialog.create(pattern = "")) },
                    onGroupManage = { showGroupManageDialog() },
                    onPreset = { showPresetRuleDialog() },
                    onRestoreDefault = { showRestoreDefaultDialog() },
                    onImport = { importRules() },
                    onExport = { exportRules() },
                    onItemClick = { edit(it) },
                    onEnableToggle = { rule, enabled ->
                        // copy 新实例触发重组：原地修改后同实例回流列表，强跳过模式按引用比较会跳过行重组（对齐全项目 copy 先例）
                        viewModel.update(rule.copy(enabled = enabled))
                    },
                    onDelete = { showDeleteDialog(it) },
                    onToTop = { viewModel.toTop(it) },
                    onToBottom = { viewModel.toBottom(it) }
                )
            }
        }
        observeData()
    }

    private fun observeData() {
        viewModel.rulesLiveData.observe(this) {
            composeRules = it
        }
    }

    private fun showGroupManageDialog() {
        showDialogFragment(HighlightRuleGroupManageDialog(
            onChanged = { _, _ -> viewModel.loadRules() }
        ))
    }

    private fun showPresetRuleDialog() {
        showDialogFragment(HighlightPresetRuleDialog(
            onAddRule = { rule ->
                viewModel.update(rule)
                toastOnUi(getString(R.string.highlight_rule_preset_added_toast, rule.name))
            }
        ))
    }

    // W5.1：AlertDialog→Compose 弹框基线（MC-7）
    private fun showRestoreDefaultDialog() {
        showComposeChoiceListDialog(
            title = getString(R.string.highlight_rule_restore_title) + "\n" + getString(R.string.highlight_rule_restore_message),
            labels = listOf(
                getString(R.string.highlight_rule_restore_merge),
                getString(R.string.highlight_rule_restore_overwrite)
            )
        ) { index ->
            when (index) {
                0 -> {
                    viewModel.restoreDefaults(RestoreMode.MERGE)
                    toastOnUi(R.string.highlight_rule_restore_merged_toast)
                }
                else -> confirmOverwrite()
            }
        }
    }

    private fun confirmOverwrite() {
        showComposeConfirmDialog(
            title = getString(R.string.highlight_rule_restore_overwrite_confirm_title),
            message = getString(R.string.highlight_rule_restore_overwrite_confirm_message),
            positiveText = getString(R.string.highlight_rule_restore_overwrite_confirm_ok),
            negativeText = getString(android.R.string.cancel),
            dangerPositive = true,
            onPositive = {
                viewModel.restoreDefaults(RestoreMode.OVERWRITE)
                toastOnUi(R.string.highlight_rule_restore_overwritten_toast)
            }
        )
    }

    private fun showDeleteDialog(rule: HighlightRule) {
        showComposeConfirmDialog(
            title = getString(R.string.sure_del),
            message = rule.getDisplayName(),
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = { viewModel.delete(rule) }
        )
    }

    private fun importRules() {
        // R27（B6）：两条通道并存 —— 剪贴板 JSON（少量规则快速粘贴）/ 包文件（整套分发，含校验）
        showComposeChoiceListDialog(
            title = getString(R.string.import_highlight_rule),
            labels = listOf(
                getString(R.string.highlight_rule_import_from_clipboard),
                getString(R.string.highlight_rule_import_from_pack)
            )
        ) { index ->
            if (index == 0) importRulesFromClipboard() else pickPackFile()
        }
    }

    private fun importRulesFromClipboard() {
        val clipText = getClipText()
        if (clipText.isNullOrBlank()) {
            toastOnUi(R.string.highlight_rule_import_clipboard_empty)
            return
        }
        kotlin.runCatching {
            GSON.fromJsonArray<HighlightRule>(clipText).getOrNull()?.let { imported ->
                if (imported.isEmpty()) {
                    toastOnUi(R.string.highlight_rule_import_invalid)
                    return
                }
                val added = HighlightRulePack.commit(this, imported)
                if (added == 0) {
                    toastOnUi(R.string.highlight_rule_import_all_exist)
                    return
                }
                viewModel.loadRules()
                toastOnUi(getString(R.string.highlight_rule_import_done, added))
            } ?: toastOnUi(R.string.highlight_rule_import_invalid)
        }.onFailure {
            toastOnUi(getString(R.string.highlight_rule_import_failed, it.message))
        }
    }

    /** R27：包文件导入（SAF 选择 → 解压校验 → 全通过才写入存储） */
    private fun pickPackFile() {
        packPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }

    private fun importRulesFromPack(uri: Uri) {
        kotlin.runCatching {
            val temp = File(cacheDir, "highlightRulePack_import.zip")
            contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { out -> input.copyTo(out) }
            } ?: throw HighlightRulePack.PackException("无法读取所选文件")
            // 解压+校验（复用 R21 防护）；任何失败都在 commit 之前抛出 ⇒ 存储零写入
            val imported = HighlightRulePack.importFromZip(this, temp)
            temp.delete()
            val added = HighlightRulePack.commit(this, imported)
            viewModel.loadRules()
            if (added == 0) {
                toastOnUi(R.string.highlight_rule_import_all_exist)
            } else {
                toastOnUi(getString(R.string.highlight_rule_import_done, added))
            }
        }.onFailure {
            toastOnUi(getString(R.string.highlight_rule_import_failed, it.message))
        }
    }

    private fun exportRules() {
        // R27：导出同样两通道（剪贴板可核对内容 / 包文件可分发）
        val rules = HighlightRuleStore.load(this)
        if (rules.isEmpty()) {
            toastOnUi(R.string.highlight_rule_export_empty)
            return
        }
        showComposeChoiceListDialog(
            title = getString(R.string.export_highlight_rule),
            labels = listOf(
                getString(R.string.highlight_rule_export_to_clipboard),
                getString(R.string.highlight_rule_export_to_pack)
            )
        ) { index ->
            if (index == 0) exportRulesToClipboard(rules) else exportRulesToPack(rules)
        }
    }

    private fun exportRulesToClipboard(rules: List<HighlightRule>) {
        val json = HighlightRulePack.rulesJson(rules)
        // F50 导出回执可视化：原实现"静默写剪贴板 + 一句 toast"，用户无法核对导出了什么内容；
        // 改为弹框展示可核对内容（messageInContent 走可滚动内容区，长规则集不撑破弹框）+ 显式复制动作
        showComposeConfirmDialog(
            title = getString(R.string.export_success),
            message = json,
            positiveText = getString(R.string.copy_text),
            negativeText = getString(R.string.close),
            messageInContent = true,
            onPositive = {
                sendToClip(json)
                toastOnUi(getString(R.string.highlight_rule_export_done, rules.size))
            }
        )
    }

    /** R27：导出包文件到应用外部目录，并给出可核对回执（路径 + 条数） */
    private fun exportRulesToPack(rules: List<HighlightRule>) {
        kotlin.runCatching {
            val file = HighlightRulePack.exportToFile(rules)
            showComposeConfirmDialog(
                title = getString(R.string.export_success),
                message = getString(R.string.highlight_rule_export_pack_done, rules.size, file.absolutePath),
                positiveText = getString(R.string.copy_text),
                negativeText = getString(R.string.close),
                onPositive = {
                    sendToClip(file.absolutePath)
                    toastOnUi(getString(R.string.highlight_rule_export_done, rules.size))
                }
            )
        }.onFailure {
            toastOnUi(getString(R.string.highlight_rule_import_failed, it.message))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ReadBook.upHighlightRules()
    }

    private fun edit(rule: HighlightRule) {
        showDialogFragment(HighlightRuleEditDialog.edit(rule.id))
    }

    /** 供 HighlightRuleEditDialog 保存后刷新列表 */
    fun refreshList() {
        viewModel.loadRules()
    }
}
