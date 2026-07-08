package io.legado.app.ui.autoTask

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityAutoTaskBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.model.AutoTaskRule
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.ACache
import io.legado.app.utils.CronSchedule
import io.legado.app.utils.applyTint
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * F-P1-1 自动任务列表页
 *
 * 借鉴自阅读T (skybbk1001/legadoT)，参照本项目 BookSourceActivity 的 RecyclerView 初始化模式。
 * 不使用 setupManagePage（本项目不存在），直接用 LinearLayoutManager + ItemTouchHelper + DragSelectTouchHelper。
 *
 * 依赖（后续任务创建，本文件不实现）：
 * - [AutoTaskEditActivity] 任务编辑页（menu_add / iv_edit 跳转）
 * - [ImportAutoTaskDialog] 导入任务对话框（menu_import_local / menu_import_onLine 触发）
 * - [AutoTaskLogDialog] 任务日志对话框（menu_log / item menu_item_log 触发）
 */
class AutoTaskActivity : VMBaseActivity<ActivityAutoTaskBinding, AutoTaskViewModel>(),
    AutoTaskAdapter.CallBack,
    PopupMenu.OnMenuItemClickListener,
    SelectActionBar.CallBack,
    SearchView.OnQueryTextListener {

    override val viewModel: AutoTaskViewModel by viewModels()
    override val binding: ActivityAutoTaskBinding by viewBinding(ActivityAutoTaskBinding::inflate)
    private val adapter: AutoTaskAdapter by lazy { AutoTaskAdapter(this, this) }
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private val itemTouchCallback by lazy { ItemTouchCallback(adapter) }
    private val importRecordKey = "autoTaskRecordKey"
    private var allRules: List<AutoTaskRule> = emptyList()
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
        initView()
        initSearchView()
        initSelectActionBar()
        observeData()
        bindImportResult()
        viewModel.refresh()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.auto_task, menu)
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add -> startActivity(AutoTaskEditActivity.startIntent(this))
            R.id.menu_import_local -> importDoc.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }
            R.id.menu_import_onLine -> showImportDialog()
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initView() = binding.run {
        recyclerView.setEdgeEffectColor(primaryColor)
        recyclerView.addItemDecoration(VerticalDivider(this@AutoTaskActivity))
        recyclerView.recycledViewPool.setMaxRecycledViews(0, 15)
        recyclerView.layoutManager = LinearLayoutManager(this@AutoTaskActivity)
        recyclerView.adapter = adapter
        itemTouchCallback.isCanDrag = true
        // 拖拽多选（参照 BookSourceActivity.initRecyclerView）
        val dragSelectTouchHelper =
            DragSelectTouchHelper(adapter.dragSelectCallback).setSlideArea(16, 50)
        dragSelectTouchHelper.attachToRecyclerView(recyclerView)
        dragSelectTouchHelper.activeSlideSelect()
        // 注意：需先判断选择，故 ItemTouchHelper 在其后附加
        ItemTouchHelper(itemTouchCallback).attachToRecyclerView(recyclerView)
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.queryHint = getString(R.string.search)
        searchView.setOnQueryTextListener(this)
    }

    private fun initSelectActionBar() {
        binding.selectActionBar.setMainActionText(R.string.delete)
        binding.selectActionBar.inflateMenu(R.menu.auto_task_sel)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        binding.selectActionBar.setCallBack(this)
        upCountView()
    }

    private fun observeData() {
        lifecycleScope.launch {
            viewModel.rulesFlow.collectLatest {
                allRules = it
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val query = searchView.query?.toString()?.trim().orEmpty()
        val filtered = if (query.isEmpty()) {
            allRules
        } else {
            allRules.filter { it.name.contains(query, ignoreCase = true) }
        }
        adapter.setItems(filtered, adapter.diffItemCallBack)
        invalidateOptionsMenu()
        upCountView()
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

    // SearchView.OnQueryTextListener
    override fun onQueryTextSubmit(query: String?): Boolean {
        return false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        applyFilter()
        return false
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
                    viewModel.updateCron(adapter.selection.map { it.id }, cron)
                } else {
                    toastOnUi(R.string.auto_task_cron_invalid)
                }
            }
            cancelButton()
        }
    }

    // AutoTaskAdapter.CallBack
    override fun edit(task: AutoTaskRule) {
        startActivity(AutoTaskEditActivity.startIntent(this, task.id))
    }

    override fun delete(task: AutoTaskRule) {
        alert(R.string.draw) {
            setMessage(getString(R.string.auto_task_delete) + "\n" + task.name)
            noButton()
            yesButton { viewModel.delete(task) }
        }
    }

    override fun toggle(task: AutoTaskRule, enabled: Boolean) {
        viewModel.save(task.copy(enable = enabled))
    }

    override fun showLog(task: AutoTaskRule) {
        showDialogFragment(AutoTaskLogDialog(task.id, task.name))
    }

    override fun upOrder(items: List<AutoTaskRule>) {
        viewModel.saveOrder(items)
    }

    override fun upCountView() {
        binding.selectActionBar.upCountView(adapter.selection.size, adapter.itemCount)
    }

    // SelectActionBar.CallBack
    override fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            adapter.selectAll()
        } else {
            adapter.revertSelection()
        }
    }

    override fun revertSelection() {
        adapter.revertSelection()
    }

    override fun onClickSelectBarMainAction() {
        if (adapter.selection.isEmpty()) return
        alert(R.string.draw, R.string.sure_del) {
            yesButton { viewModel.delete(adapter.selection.map { it.id }) }
            noButton()
        }
    }

    // SelectActionBar menu item click
    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_enable_selection -> viewModel.updateEnabled(adapter.selection.map { it.id }, true)
            R.id.menu_disable_selection -> viewModel.updateEnabled(adapter.selection.map { it.id }, false)
            R.id.menu_export_selection -> viewModel.exportSelection(adapter.selection.map { it.id }) { file ->
                exportResult.launch {
                    mode = HandleFileContract.EXPORT
                    fileData = HandleFileContract.FileData(
                        "autoTaskSelection.json",
                        file,
                        "application/json"
                    )
                }
            }
            R.id.menu_batch_cron -> showBatchCronDialog()
        }
        return true
    }
}
