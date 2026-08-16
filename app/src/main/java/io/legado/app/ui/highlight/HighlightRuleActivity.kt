package io.legado.app.ui.highlight

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityHighlightRuleBinding
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
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.fromJsonArray

/**
 * F-P1-2 高亮规则管理页（借鉴阅读T，适配 SharedPreferences 存储）
 * F-P1-2 Phase 8 蛋蛋Max 补齐：分组管理 + 预设规则 + 导入导出
 *
 * L-C5 枝叶页：全 Compose 接管（HighlightRuleScreen），对话框族保留既有 DialogFragment/AlertDialog。
 */
class HighlightRuleActivity :
    VMBaseActivity<ActivityHighlightRuleBinding, HighlightRuleViewModel>() {

    override val binding by viewBinding(ActivityHighlightRuleBinding::inflate)
    override val viewModel by viewModels<HighlightRuleViewModel>()

    // Compose 桥接状态（双轨过渡：列表/搜索在 Compose 侧渲染）
    private var composeRules by mutableStateOf(listOf<HighlightRule>())
    private var composeSearchQuery by mutableStateOf("")

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeHost.setContent {
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
                        rule.enabled = enabled
                        viewModel.update(rule)
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

    private fun showRestoreDefaultDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.highlight_rule_restore_title)
            .setMessage(R.string.highlight_rule_restore_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.highlight_rule_restore_overwrite) { _, _ -> confirmOverwrite() }
            .setPositiveButton(R.string.highlight_rule_restore_merge) { _, _ ->
                viewModel.restoreDefaults(RestoreMode.MERGE)
                toastOnUi(R.string.highlight_rule_restore_merged_toast)
            }
            .show()
    }

    private fun confirmOverwrite() {
        AlertDialog.Builder(this)
            .setTitle(R.string.highlight_rule_restore_overwrite_confirm_title)
            .setMessage(R.string.highlight_rule_restore_overwrite_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.highlight_rule_restore_overwrite_confirm_ok) { _, _ ->
                viewModel.restoreDefaults(RestoreMode.OVERWRITE)
                toastOnUi(R.string.highlight_rule_restore_overwritten_toast)
            }
            .show()
    }

    private fun showDeleteDialog(rule: HighlightRule) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.sure_del) + "\n" + rule.getDisplayName())
            .setNegativeButton(R.string.no, null)
            .setPositiveButton(R.string.yes) { _, _ -> viewModel.delete(rule) }
            .show()
    }

    private fun importRules() {
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
                val current = HighlightRuleStore.load(this).toMutableList()
                val existingIds = current.map { it.id }.toSet()
                val toAdd = imported.filter { it.id !in existingIds }
                if (toAdd.isEmpty()) {
                    toastOnUi(R.string.highlight_rule_import_all_exist)
                    return
                }
                current.addAll(toAdd)
                HighlightRuleStore.save(this, current)
                viewModel.loadRules()
                toastOnUi(getString(R.string.highlight_rule_import_done, toAdd.size))
            } ?: toastOnUi(R.string.highlight_rule_import_invalid)
        }.onFailure {
            toastOnUi(getString(R.string.highlight_rule_import_failed, it.message))
        }
    }

    private fun exportRules() {
        val rules = HighlightRuleStore.load(this)
        if (rules.isEmpty()) {
            toastOnUi(R.string.highlight_rule_export_empty)
            return
        }
        sendToClip(GSON.toJson(rules))
        toastOnUi(getString(R.string.highlight_rule_export_done, rules.size))
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
