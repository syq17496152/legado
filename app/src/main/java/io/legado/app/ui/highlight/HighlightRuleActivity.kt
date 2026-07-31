package io.legado.app.ui.highlight

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.ItemTouchHelper
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityHighlightRuleBinding
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.config.HighlightRule
import io.legado.app.ui.book.read.config.HighlightRuleStore
import io.legado.app.ui.book.read.config.RestoreMode
import io.legado.app.ui.highlight.edit.HighlightRuleEditDialog
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.GSON
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.fromJsonArray

/**
 * F-P1-2 高亮规则管理页（借鉴阅读T，适配 SharedPreferences 存储）
 * F-P1-2 Phase 8 蛋蛋Max 补齐：分组管理 + 预设规则 + 导入导出
 */
class HighlightRuleActivity :
    VMBaseActivity<ActivityHighlightRuleBinding, HighlightRuleViewModel>(),
    HighlightRuleAdapter.CallBack {

    override val binding by viewBinding(ActivityHighlightRuleBinding::inflate)
    override val viewModel by viewModels<HighlightRuleViewModel>()
    private val adapter by lazy { HighlightRuleAdapter(this, this) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initRecyclerView()
        observeData()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.highlight_rule, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add_highlight_rule ->
                showDialogFragment(HighlightRuleEditDialog.create(pattern = ""))
            R.id.menu_group_manage -> showGroupManageDialog()
            R.id.menu_preset_rule -> showPresetRuleDialog()
            R.id.menu_restore_default -> showRestoreDefaultDialog()
            R.id.menu_import_highlight_rule -> importRules()
            R.id.menu_export_highlight_rule -> exportRules()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initRecyclerView() {
        binding.recyclerView.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.recyclerView.applyNavigationBarPadding()
        binding.recyclerView.adapter = adapter
        val callback = ItemTouchCallback(adapter).apply { isCanDrag = true }
        ItemTouchHelper(callback).attachToRecyclerView(binding.recyclerView)
    }

    private fun observeData() {
        viewModel.rulesLiveData.observe(this) {
            adapter.setItems(it, adapter.diffItemCallBack)
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
                toastOnUi("已添加预设规则：${rule.name}")
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

    private fun importRules() {
        val clipText = getClipText()
        if (clipText.isNullOrBlank()) {
            toastOnUi("剪贴板为空")
            return
        }
        kotlin.runCatching {
            GSON.fromJsonArray<HighlightRule>(clipText).getOrNull()?.let { imported ->
                if (imported.isEmpty()) {
                    toastOnUi("剪贴板内容不是有效的高亮规则 JSON")
                    return
                }
                val current = HighlightRuleStore.load(this).toMutableList()
                val existingIds = current.map { it.id }.toSet()
                val toAdd = imported.filter { it.id !in existingIds }
                if (toAdd.isEmpty()) {
                    toastOnUi("导入完成，无新增规则（全部已存在）")
                    return
                }
                current.addAll(toAdd)
                HighlightRuleStore.save(this, current)
                viewModel.loadRules()
                toastOnUi("已导入 ${toAdd.size} 条规则")
            } ?: toastOnUi("剪贴板内容不是有效的 JSON")
        }.onFailure {
            toastOnUi("导入失败：${it.message}")
        }
    }

    private fun exportRules() {
        val rules = HighlightRuleStore.load(this)
        if (rules.isEmpty()) {
            toastOnUi("暂无规则可导出")
            return
        }
        sendToClip(GSON.toJson(rules))
        toastOnUi("已复制 ${rules.size} 条规则到剪贴板")
    }

    override fun onResume() {
        super.onResume()
        adapter.upResumed(true)
    }

    override fun onPause() {
        super.onPause()
        adapter.upResumed(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        ReadBook.upHighlightRules()
    }

    override fun update(vararg rule: HighlightRule) = viewModel.update(*rule)

    override fun delete(rule: HighlightRule) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.sure_del) + "\n" + rule.getDisplayName())
            .setNegativeButton(R.string.no, null)
            .setPositiveButton(R.string.yes) { _, _ -> viewModel.delete(rule) }
            .show()
    }

    override fun edit(rule: HighlightRule) {
        showDialogFragment(HighlightRuleEditDialog.edit(rule.id))
    }

    override fun toTop(rule: HighlightRule) = viewModel.toTop(rule)

    override fun toBottom(rule: HighlightRule) = viewModel.toBottom(rule)

    override fun upOrder(items: List<HighlightRule>) = viewModel.upOrder(items)

    /** 供 HighlightRuleEditDialog 保存后刷新列表 */
    fun refreshList() {
        viewModel.loadRules()
    }
}
