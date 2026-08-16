package io.legado.app.ui.autoTask

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityAutoTaskBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.AutoTaskRule
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.utils.ACache
import io.legado.app.utils.CronSchedule
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自动任务列表页（S2 列表族）
 * Compose 化：S2 列表族壳层 AutoTaskScreen，数据观察/搜索过滤/选择状态/导入导出/日志/编辑跳转逻辑保留 Activity
 */
class AutoTaskActivity : VMBaseActivity<ActivityAutoTaskBinding, AutoTaskViewModel>() {

    override val viewModel: AutoTaskViewModel by viewModels()
    override val binding: ActivityAutoTaskBinding by viewBinding(ActivityAutoTaskBinding::inflate)

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val importRecordKey = "autoTaskRecordKey"
    private var allRules: List<AutoTaskRule> = emptyList()

    // Compose 桥接状态
    private var composeItems by mutableStateOf(listOf<AutoTaskDisplayItem>())
    private var isLoading by mutableStateOf(true)
    private var searchKey by mutableStateOf("")
    private var composeSelectionCount by mutableStateOf(0)
    private val selectedIds = linkedSetOf<String>()

    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportAutoTaskDialog(uri.toString()))
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
        observeData()
        bindImportResult()
        viewModel.refresh()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun initComposeHost() {
        binding.composeHost.setContent {
            LegadoTheme {
                AutoTaskScreen(
                    items = composeItems,
                    isLoading = isLoading,
                    searchKey = searchKey,
                    onSearchChange = {
                        searchKey = it
                        applyFilter()
                    },
                    topMenuActions = buildTopMenuActions(),
                    selMenuActions = buildSelMenuActions(),
                    selectionCount = composeSelectionCount,
                    onBack = { finish() },
                    onItemClick = { index -> edit(index) },
                    onToggleSelect = { index, checked -> toggleSelect(index, checked) },
                    onSelectAll = { selectAll(it) },
                    onRevertSelection = { revertSelection() },
                    onDeleteSelection = { deleteSelection() },
                    onToggleEnable = { index, enabled -> toggleEnable(index, enabled) },
                    onLogin = { index -> login(index) },
                    onShowLog = { index -> showLog(index) },
                    onDelete = { index -> delete(index) },
                    onMove = { _, _ -> },
                    onOrderCommitted = { ids -> commitOrder(ids) }
                )
            }
        }
    }

    private fun buildTopMenuActions(): List<MenuAction> {
        return listOf(
            MenuAction(Icons.Default.Add, getString(R.string.auto_task_add)) {
                startActivity(AutoTaskEditActivity.startIntent(this))
            },
            MenuAction(Icons.Default.FileUpload, getString(R.string.import_local)) {
                importDoc.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("txt", "json")
                }
            },
            MenuAction(Icons.Default.Link, getString(R.string.import_on_line)) {
                showImportDialog()
            },
            MenuAction(Icons.Default.Info, getString(R.string.log)) {
                showDialogFragment<AppLogDialog>()
            }
        )
    }

    private fun buildSelMenuActions(): List<MenuAction> {
        return listOf(
            MenuAction(Icons.Default.Schedule, getString(R.string.auto_task_batch_cron)) {
                showBatchCronDialog()
            },
            MenuAction(Icons.Default.CheckCircle, getString(R.string.enable_selection)) {
                viewModel.updateEnabled(selectedIds.toList(), true)
            },
            MenuAction(Icons.Default.Cancel, getString(R.string.disable_selection)) {
                viewModel.updateEnabled(selectedIds.toList(), false)
            },
            MenuAction(Icons.Default.FileDownload, getString(R.string.export_selection)) {
                viewModel.exportSelection(selectedIds.toList()) { file ->
                    exportResult.launch {
                        mode = HandleFileContract.EXPORT
                        fileData = HandleFileContract.FileData(
                            "autoTaskSelection.json",
                            file,
                            "application/json"
                        )
                    }
                }
            }
        )
    }

    private fun observeData() {
        lifecycleScope.launch {
            viewModel.rulesFlow.collectLatest {
                allRules = it
                isLoading = false
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val query = searchKey.trim()
        val filtered = if (query.isEmpty()) {
            allRules
        } else {
            allRules.filter { it.name.contains(query, ignoreCase = true) }
        }
        selectedIds.retainAll(filtered.map { it.id })
        composeItems = filtered.map { it.toDisplayItem() }
        composeSelectionCount = selectedIds.size
    }

    private fun bindImportResult() {
        supportFragmentManager.setFragmentResultListener(
            ImportAutoTaskDialog.RESULT_KEY,
            this
        ) { _, _ ->
            viewModel.refresh()
        }
    }

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
                    showDialogFragment(ImportAutoTaskDialog(it))
                }
            }
            cancelButton()
        }
    }

    private fun showBatchCronDialog() {
        val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = getString(R.string.auto_task_cron)
        }
        alert(titleResource = R.string.auto_task_batch_cron) {
            customView { alertBinding.root }
            okButton {
                val cron = alertBinding.editView.text?.toString()?.trim().orEmpty()
                if (cron.isNotBlank() && CronSchedule.parse(cron) != null) {
                    viewModel.updateCron(selectedIds.toList(), cron)
                } else {
                    toastOnUi(R.string.auto_task_cron_invalid)
                }
            }
            cancelButton()
        }
    }

    // ---- 选择状态 ----
    private fun toggleSelect(index: Int, checked: Boolean) {
        val item = currentItems().getOrNull(index) ?: return
        if (checked) selectedIds.add(item.id) else selectedIds.remove(item.id)
        upCountView()
    }

    private fun selectAll(selectAll: Boolean) {
        val items = currentItems()
        if (selectAll) {
            selectedIds.addAll(items.map { it.id })
        } else {
            selectedIds.removeAll(items.map { it.id })
        }
        upCountView()
    }

    private fun revertSelection() {
        currentItems().forEach {
            if (selectedIds.contains(it.id)) selectedIds.remove(it.id) else selectedIds.add(it.id)
        }
        upCountView()
    }

    private fun upCountView() {
        composeSelectionCount = selectedIds.size
        composeItems = composeItems.map { it.copy(isSelected = it.id in selectedIds) }
    }

    private fun currentItems(): List<AutoTaskRule> = allRules

    private fun selectedItems(): List<AutoTaskRule> =
        allRules.filter { it.id in selectedIds }

    private fun deleteSelection() {
        val selection = selectedItems()
        if (selection.isEmpty()) return
        alert(R.string.draw, R.string.sure_del) {
            yesButton { viewModel.delete(selection.map { it.id }) }
            noButton()
        }
    }

    // ---- 条目操作 ----
    private fun edit(index: Int) {
        val item = currentItems().getOrNull(index) ?: return
        startActivity(AutoTaskEditActivity.startIntent(this, item.id))
    }

    private fun toggleEnable(index: Int, enabled: Boolean) {
        val item = currentItems().getOrNull(index) ?: return
        viewModel.save(item.copy(enable = enabled))
    }

    private fun delete(index: Int) {
        val item = currentItems().getOrNull(index) ?: return
        alert(R.string.draw) {
            setMessage(getString(R.string.auto_task_delete) + "\n" + item.name)
            noButton()
            yesButton { viewModel.delete(item) }
        }
    }

    private fun showLog(index: Int) {
        val item = currentItems().getOrNull(index) ?: return
        showDialogFragment(AutoTaskLogDialog(item.id, item.name))
    }

    private fun login(index: Int) {
        val item = currentItems().getOrNull(index) ?: return
        startActivity<SourceLoginActivity> {
            putExtra("type", "autoTask")
            putExtra("key", item.id)
        }
    }

    private fun commitOrder(ids: List<String>) {
        val idOrder = ids.withIndex().associate { (i, id) -> id to i }
        val reordered = allRules.sortedBy { idOrder[it.id] ?: Int.MAX_VALUE }
        viewModel.saveOrder(reordered)
    }

    // ---- 展示转换 ----
    private fun AutoTaskRule.toDisplayItem(): AutoTaskDisplayItem = AutoTaskDisplayItem(
        id = id,
        name = name.ifBlank { id },
        enabled = enable,
        summary = buildSummary(),
        hasLogin = !loginUrl.isNullOrBlank(),
        isSelected = id in selectedIds
    )

    private fun AutoTaskRule.buildSummary(): String {
        val cron = cron?.trim().orEmpty().ifBlank { "-" }
        val status = when {
            !lastError.isNullOrBlank() ->
                getString(R.string.auto_task_last_error, lastError)
            lastRunAt > 0L ->
                getString(R.string.auto_task_last_run, timeFormat.format(Date(lastRunAt)))
            else -> getString(R.string.auto_task_not_run)
        }
        return getString(R.string.auto_task_item_summary, cron, status)
    }
}
