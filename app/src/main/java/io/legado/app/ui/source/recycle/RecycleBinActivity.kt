package io.legado.app.ui.source.recycle

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.SourceRecycleBin
import io.legado.app.databinding.ActivityRecycleBinBinding
import io.legado.app.help.source.SourceRecycleBinHelp
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.showHelp
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 源回收站页（precise-manage：sourceRecycleBinDao 数据）
 * Compose 化：S2 列表族壳层 RecycleBinScreen，数据观察/选择状态/恢复冲突检测/删除/清空逻辑保留 Activity
 */
class RecycleBinActivity : VMBaseActivity<ActivityRecycleBinBinding, RecycleBinViewModel>() {

    override val viewModel by viewModels<RecycleBinViewModel>()
    override val binding by viewBinding(ActivityRecycleBinBinding::inflate)

    // 原始实体列表（删除/恢复需完整实体）
    private var currentItems = listOf<SourceRecycleBin>()

    // Compose 桥接状态
    private var composeItems by mutableStateOf(listOf<RecycleBinDisplayItem>())
    private var isLoading by mutableStateOf(true)
    private var composeSelectionCount by mutableStateOf(0)
    private var pendingRestoreItems by mutableStateOf(listOf<RecycleBinDisplayItem>())
    private val selectedIds = linkedSetOf<Long>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeHost()
        observeData()
    }

    private fun initComposeHost() {
        binding.composeHost.setContent {
            LegadoTheme {
                RecycleBinScreen(
                    items = composeItems,
                    isLoading = isLoading,
                    pendingRestoreItems = pendingRestoreItems,
                    selectionCount = composeSelectionCount,
                    onBack = { finish() },
                    onToggleSelect = { index, checked -> toggleSelect(index, checked) },
                    onSelectAll = { selectAll(it) },
                    onRevertSelection = { revertSelection() },
                    onRestoreSelection = { restoreSelection() },
                    onDeleteSelection = { deleteSelection() },
                    onRestore = { checkRestore(it) },
                    onDelete = { item -> deleteItem(item) },
                    onConfirmRestoreOverwrite = { confirmRestoreOverwrite() },
                    onDismissRestoreOverwrite = { pendingRestoreItems = emptyList() },
                    onEmptyRecycleBin = { viewModel.empty() },
                    onHelp = { showHelp("SourceRecycleBinHelp") }
                )
            }
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            appDb.sourceRecycleBinDao.flowAll().catch {
                AppLog.put("回收站获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect { entities ->
                currentItems = entities
                selectedIds.retainAll(entities.map { it.id })
                composeItems = entities.map { it.toDisplayItem() }
                composeSelectionCount = selectedIds.size
                isLoading = false
            }
        }
    }

    private fun SourceRecycleBin.toDisplayItem() = RecycleBinDisplayItem(
        id = id,
        name = name.ifBlank { key },
        type = type,
        deletedAt = deletedAt,
        isSelected = id in selectedIds
    )

    private fun toggleSelect(index: Int, checked: Boolean) {
        val item = currentItems.getOrNull(index) ?: return
        if (checked) selectedIds.add(item.id) else selectedIds.remove(item.id)
        upCountView()
    }

    private fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            selectedIds.addAll(currentItems.map { it.id })
        } else {
            selectedIds.removeAll(currentItems.map { it.id })
        }
        upCountView()
    }

    private fun revertSelection() {
        currentItems.forEach {
            if (selectedIds.contains(it.id)) selectedIds.remove(it.id) else selectedIds.add(it.id)
        }
        upCountView()
    }

    private fun upCountView() {
        composeSelectionCount = selectedIds.size
        // 同步刷新 item 勾选态
        composeItems = composeItems.map { it.copy(isSelected = it.id in selectedIds) }
    }

    private fun selectedItems(): List<SourceRecycleBin> =
        currentItems.filter { it.id in selectedIds }

    private fun restoreSelection() {
        val selection = selectedItems()
        if (selection.isEmpty()) return
        lifecycleScope.launch {
            val conflictItems = withContext(IO) {
                selection.filter { SourceRecycleBinHelp.hasConflict(it) }
            }
            if (conflictItems.isEmpty()) {
                selection.forEach { viewModel.restore(it, false) }
            } else {
                pendingRestoreItems = conflictItems.map { it.toDisplayItem() }
            }
        }
    }

    private fun checkRestore(item: RecycleBinDisplayItem) {
        val entity = currentItems.find { it.id == item.id } ?: return
        lifecycleScope.launch {
            val conflict = withContext(IO) { SourceRecycleBinHelp.hasConflict(entity) }
            if (conflict) {
                pendingRestoreItems = listOf(entity.toDisplayItem())
            } else {
                viewModel.restore(entity, false)
            }
        }
    }

    private fun confirmRestoreOverwrite() {
        val items = pendingRestoreItems
        pendingRestoreItems = emptyList()
        items.forEach { item ->
            currentItems.find { it.id == item.id }?.let { viewModel.restore(it, true) }
        }
    }

    private fun deleteItem(item: RecycleBinDisplayItem) {
        currentItems.find { it.id == item.id }?.let { viewModel.delete(it) }
    }

    private fun deleteSelection() {
        val selection = selectedItems()
        if (selection.isEmpty()) return
        viewModel.delete(*selection.toTypedArray())
    }
}
