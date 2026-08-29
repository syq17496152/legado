package io.legado.app.ui.dict.rule

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.DictRule
import io.legado.app.databinding.ActivityDictRuleBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.association.ImportDictRuleDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.launch
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class DictRuleActivity : VMBaseActivity<ActivityDictRuleBinding, DictRuleViewModel>() {

    override val viewModel by viewModels<DictRuleViewModel>()
    override val binding by viewBinding(ActivityDictRuleBinding::inflate)

    private val importRecordKey = "dictRuleUrls"

    // Compose 桥接状态
    private var composeItems by mutableStateOf(listOf<DictRuleDisplayItem>())
    private var composeSelectionCount by mutableStateOf(0)
    private var currentRules = listOf<DictRule>()
    private val selectedNames = linkedSetOf<String>()

    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        showDialogFragment(ImportDictRuleDialog(it))
    }
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportDictRuleDialog(uri.toString()))
        }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showComposeTextInputDialog(
                title = getString(R.string.export_success),
                message = if (uri.toString().isAbsUrl()) DirectLinkUpload.getSummary() else null,
                hint = getString(R.string.path),
                initialValue = uri.toString(),
                readOnly = true,
                positiveText = getString(R.string.copy_text),
                onPositive = {
                    sendToClip(uri.toString())
                }
            )
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeHost()
        initData()
    }

    private fun initComposeHost() {
        binding.composeHost.setContent {
            LegadoTheme {
                DictRuleScreen(
                    items = composeItems,
                    isLoading = false,
                    topMenuActions = buildTopMenuActions(),
                    selMenuActions = buildSelMenuActions(),
                    selectionCount = composeSelectionCount,
                    onBack = { finish() },
                    onItemClick = { editRule(it) },
                    onToggleSelect = { index, checked -> toggleSelect(index, checked) },
                    onSelectAll = { selectAll(it) },
                    onRevertSelection = { revertSelection() },
                    onDeleteSelection = { delSelectionDialog() },
                    onToggleEnable = { index, checked -> upEnable(index, checked) },
                    onEdit = { editRule(it) },
                    onDelete = { delRule(it) },
                    onMove = { _, _ -> },
                    onOrderCommitted = { commitOrder(it) }
                )
            }
        }
    }

    private fun initData() {
        lifecycleScope.launch {
            appDb.dictRuleDao.flowAll().catch {
                AppLog.put("字典规则获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect { rules ->
                currentRules = rules
                selectedNames.retainAll(rules.map { it.name })
                refreshComposeItems()
            }
        }
    }

    private fun refreshComposeItems() {
        composeItems = currentRules.map { rule ->
            DictRuleDisplayItem(
                name = rule.name,
                enabled = rule.enabled,
                isSelected = rule.name in selectedNames
            )
        }
        composeSelectionCount = selectedNames.size
    }

    private val selectedRules: List<DictRule>
        get() = currentRules.filter { it.name in selectedNames }

    private fun buildTopMenuActions(): List<MenuAction> = listOf(
        MenuAction(
            icon = Icons.Default.Add,
            title = getString(R.string.add),
            // topbar-icon-semantics-fix 3.3：新增恢复一级图标（原版 dict_rule.xml menu_add always）
            alwaysShow = true,
            onClick = { showDialogFragment<DictRuleEditDialog>() }
        ),
        MenuAction(
            icon = Icons.Default.FileUpload,
            title = getString(R.string.import_local),
            onClick = {
                importDoc.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("txt", "json")
                }
            }
        ),
        MenuAction(
            icon = Icons.Default.Link,
            title = getString(R.string.import_on_line),
            onClick = { showImportDialog() }
        ),
        MenuAction(
            icon = Icons.Default.QrCode,
            title = getString(R.string.import_by_qr_code),
            onClick = { qrCodeResult.launch() }
        ),
        MenuAction(
            icon = Icons.Default.SettingsBackupRestore,
            title = getString(R.string.import_default_rule),
            onClick = { viewModel.importDefault() }
        ),
        MenuAction(
            icon = Icons.Default.Help,
            title = getString(R.string.help),
            onClick = { showHelp("dictRuleHelp") }
        )
    )

    private fun buildSelMenuActions(): List<MenuAction> {
        val selection = selectedRules
        return listOf(
            MenuAction(
                icon = Icons.Default.CheckCircle,
                title = getString(R.string.enable_selection),
                onClick = { viewModel.enableSelection(*selection.toTypedArray()) }
            ),
            MenuAction(
                icon = Icons.Default.Cancel,
                title = getString(R.string.disable_selection),
                onClick = { viewModel.disableSelection(*selection.toTypedArray()) }
            ),
            MenuAction(
                icon = Icons.Default.FileDownload,
                title = getString(R.string.export_selection),
                onClick = {
                    exportResult.launch {
                        mode = HandleFileContract.EXPORT
                        fileData = HandleFileContract.FileData(
                            "exportDictRule.json",
                            GSON.toJson(selection).toByteArray(),
                            "application/json"
                        )
                    }
                }
            )
        )
    }

    /** 点击行/编辑按钮 → 打开编辑对话框 */
    private fun editRule(index: Int) {
        currentRules.getOrNull(index)?.let {
            showDialogFragment(DictRuleEditDialog(it.name))
        }
    }

    /** 删除按钮/长按 → 删除确认 */
    private fun delRule(index: Int) {
        currentRules.getOrNull(index)?.let {
            del(it)
        }
    }

    private fun del(rule: DictRule) {
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.sure_del) + "\n" + rule.name,
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = {
                selectedNames.remove(rule.name)
                viewModel.delete(rule)
            }
        )
    }

    private fun toggleSelect(index: Int, checked: Boolean) {
        val rule = currentRules.getOrNull(index) ?: return
        if (checked) {
            selectedNames.add(rule.name)
        } else {
            selectedNames.remove(rule.name)
        }
        refreshComposeItems()
    }

    private fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            selectedNames.addAll(currentRules.map { it.name })
        } else {
            selectedNames.clear()
        }
        refreshComposeItems()
    }

    private fun revertSelection() {
        val selectedSet = selectedNames.toSet()
        selectedNames.clear()
        currentRules.forEach { rule ->
            if (rule.name !in selectedSet) {
                selectedNames.add(rule.name)
            }
        }
        refreshComposeItems()
    }

    private fun upEnable(index: Int, checked: Boolean) {
        currentRules.getOrNull(index)?.let {
            viewModel.update(it.copy(enabled = checked))
        }
    }

    private fun delSelectionDialog() {
        val selection = selectedRules
        if (selection.isEmpty()) return
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.sure_del),
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = {
                selectedNames.clear()
                viewModel.delete(*selection.toTypedArray())
            }
        )
    }

    /** 拖拽排序结束：按新顺序重排 sortNumber 并持久化（覆盖原 swap + upOrder 语义） */
    private fun commitOrder(names: List<String>) {
        if (names.size != currentRules.size) return
        val currentNames = currentRules.map { it.name }
        if (names == currentNames) return
        val nameToRule = currentRules.associateBy { it.name }
        val reordered = names.mapNotNull { nameToRule[it] }
        if (reordered.size != currentRules.size) return
        val updated = reordered.mapIndexed { index, rule ->
            rule.copy(sortNumber = index + 1)
        }
        viewModel.update(*updated.toTypedArray())
    }

    @SuppressLint("InflateParams")
    private fun showImportDialog() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls: MutableList<String> = aCache
            .getAsString(importRecordKey)
            ?.splitNotBlank(",")
            ?.toMutableList() ?: mutableListOf()
        alert(titleResource = R.string.import_on_line) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url"
                editView.setFilterValues(cacheUrls)
                editView.delCallBack = {
                    cacheUrls.remove(it)
                    aCache.put(importRecordKey, cacheUrls.joinToString(","))
                }
            }
            customView { alertBinding.root }
            okButton {
                val text = alertBinding.editView.text?.toString()
                text?.let {
                    if (it.isAbsUrl() && !cacheUrls.contains(it)) {
                        cacheUrls.add(0, it)
                        aCache.put(importRecordKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(
                        ImportDictRuleDialog(it)
                    )
                }
            }
            cancelButton()
        }
    }
}
