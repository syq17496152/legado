package io.legado.app.ui.book.toc.rule

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.databinding.ActivityTxtTocRuleBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.association.ImportTxtTocRuleDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.theme.LegadoTheme
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

class TxtTocRuleActivity : VMBaseActivity<ActivityTxtTocRuleBinding, TxtTocRuleViewModel>(),
    TxtTocRuleEditDialog.Callback {

    override val viewModel by viewModels<TxtTocRuleViewModel>()
    override val binding by viewBinding(ActivityTxtTocRuleBinding::inflate)

    private val importTocRuleKey = "tocRuleUrl"

    // Compose 桥接状态
    private var composeItems by mutableStateOf(listOf<TxtTocRuleDisplayItem>())
    private var composeSelectionCount by mutableStateOf(0)
    private var currentRules = listOf<TxtTocRule>()
    private val selectedIds = linkedSetOf<Long>()

    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        showDialogFragment(ImportTxtTocRuleDialog(it))
    }
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportTxtTocRuleDialog(uri.toString()))
        }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            alert(R.string.export_success) {
                if (uri.toString().isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary())
                }
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = getString(R.string.path)
                    editView.setText(uri.toString())
                }
                customView { alertBinding.root }
                okButton {
                    sendToClip(uri.toString())
                }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeHost()
        initData()
    }

    private fun initComposeHost() {
        binding.composeHost.setContent {
            LegadoTheme {
                TxtTocRuleScreen(
                    items = composeItems,
                    isLoading = false,
                    topMenuActions = buildTopMenuActions(),
                    selMenuActions = buildSelMenuActions(),
                    selectionCount = composeSelectionCount,
                    onBack = { finish() },
                    onItemClick = { editRule(it) },
                    onItemLongClick = { delRule(it) },
                    onToggleSelect = { index, checked -> toggleSelect(index, checked) },
                    onSelectAll = { selectAll(it) },
                    onRevertSelection = { revertSelection() },
                    onDeleteSelection = { delSourceDialog() },
                    onToggleEnable = { index, checked -> upEnable(index, checked) },
                    onItemMenuActions = { buildItemMenuActions(it) },
                    onMove = { _, _ -> },
                    onOrderCommitted = { commitOrder(it) }
                )
            }
        }
    }

    private fun initData() {
        lifecycleScope.launch {
            appDb.txtTocRuleDao.observeAll().catch {
                AppLog.put("TXT目录规则界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect { tocRules ->
                currentRules = tocRules
                selectedIds.retainAll(tocRules.map { it.id })
                refreshComposeItems()
            }
        }
    }

    private fun refreshComposeItems() {
        composeItems = currentRules.map { rule ->
            TxtTocRuleDisplayItem(
                id = rule.id,
                name = rule.name,
                example = rule.example,
                enable = rule.enable,
                isSelected = rule.id in selectedIds
            )
        }
        composeSelectionCount = selectedIds.size
    }

    private val selectedRules: List<TxtTocRule>
        get() = currentRules.filter { it.id in selectedIds }

    private fun buildTopMenuActions(): List<MenuAction> = listOf(
        MenuAction(
            icon = Icons.Default.Add,
            title = getString(R.string.add),
            onClick = { showDialogFragment(TxtTocRuleEditDialog()) }
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
            onClick = { showHelp("txtTocRuleHelp") }
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
                            "exportTxtTocRule.json",
                            GSON.toJson(selection).toByteArray(),
                            "application/json"
                        )
                    }
                }
            )
        )
    }

    private fun buildItemMenuActions(index: Int): List<MenuAction> {
        val rule = currentRules.getOrNull(index) ?: return emptyList()
        return listOf(
            MenuAction(
                icon = Icons.Default.VerticalAlignTop,
                title = getString(R.string.to_top),
                onClick = { viewModel.toTop(rule) }
            ),
            MenuAction(
                icon = Icons.Default.VerticalAlignBottom,
                title = getString(R.string.to_bottom),
                onClick = { viewModel.toBottom(rule) }
            ),
            MenuAction(
                icon = Icons.Default.Delete,
                title = getString(R.string.delete),
                tint = androidx.compose.ui.graphics.Color.Unspecified,
                onClick = { delRule(index) }
            )
        )
    }

    /** 点击行/编辑按钮 → 打开编辑对话框 */
    private fun editRule(index: Int) {
        currentRules.getOrNull(index)?.let {
            showDialogFragment(TxtTocRuleEditDialog(it.id))
        }
    }

    /** 长按行 → 删除确认 */
    private fun delRule(index: Int) {
        currentRules.getOrNull(index)?.let {
            del(it)
        }
    }

    private fun del(source: TxtTocRule) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + source.name)
            noButton()
            yesButton {
                selectedIds.remove(source.id)
                viewModel.del(source)
            }
        }
    }

    private fun toggleSelect(index: Int, checked: Boolean) {
        val rule = currentRules.getOrNull(index) ?: return
        if (checked) {
            selectedIds.add(rule.id)
        } else {
            selectedIds.remove(rule.id)
        }
        refreshComposeItems()
    }

    private fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            selectedIds.addAll(currentRules.map { it.id })
        } else {
            selectedIds.clear()
        }
        refreshComposeItems()
    }

    private fun revertSelection() {
        val selectedSet = selectedIds.toSet()
        selectedIds.clear()
        currentRules.forEach { rule ->
            if (rule.id !in selectedSet) {
                selectedIds.add(rule.id)
            }
        }
        refreshComposeItems()
    }

    private fun upEnable(index: Int, checked: Boolean) {
        currentRules.getOrNull(index)?.let {
            viewModel.update(it.copy(enable = checked))
        }
    }

    private fun delSourceDialog() {
        val selection = selectedRules
        if (selection.isEmpty()) return
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            yesButton {
                selectedIds.clear()
                viewModel.del(*selection.toTypedArray())
            }
            noButton()
        }
    }

    /** 拖拽排序结束：按新顺序重排 serialNumber 并持久化 */
    private fun commitOrder(ids: List<Long>) {
        if (ids.size != currentRules.size) return
        val currentIds = currentRules.map { it.id }
        if (ids == currentIds) return
        val idToRule = currentRules.associateBy { it.id }
        val reordered = ids.mapNotNull { idToRule[it] }
        if (reordered.size != currentRules.size) return
        val updated = reordered.mapIndexed { index, rule ->
            rule.copy(serialNumber = index + 1)
        }
        viewModel.update(*updated.toTypedArray())
    }

    @SuppressLint("InflateParams")
    private fun showImportDialog() {
        val aCache = ACache.get(cacheDir = false)
        val defaultUrl = "https://gitee.com/fisher52/YueDuJson/raw/master/myTxtChapterRule.json"
        val cacheUrls: MutableList<String> = aCache
            .getAsString(importTocRuleKey)
            ?.splitNotBlank(",")
            ?.toMutableList()
            ?: mutableListOf()
        if (!cacheUrls.contains(defaultUrl)) {
            cacheUrls.add(0, defaultUrl)
        }
        alert(titleResource = R.string.import_on_line) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url"
                editView.setFilterValues(cacheUrls)
                editView.delCallBack = {
                    cacheUrls.remove(it)
                    aCache.put(importTocRuleKey, cacheUrls.joinToString(","))
                }
            }
            customView { alertBinding.root }
            okButton {
                val text = alertBinding.editView.text?.toString()
                text?.let {
                    if (it.isAbsUrl() && !cacheUrls.contains(it)) {
                        cacheUrls.add(0, it)
                        aCache.put(importTocRuleKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportTxtTocRuleDialog(it))
                }
            }
            cancelButton()
        }
    }

    override fun saveTxtTocRule(txtTocRule: TxtTocRule) {
        viewModel.save(txtTocRule)
    }

}
