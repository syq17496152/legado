package io.legado.app.ui.autoTask

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.model.AutoTaskRule
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.showDialogFragment

/**
 * 自动任务批量导入弹框（Compose 化，ViewModel 受控渲染）。
 * 原 View 版继承 BaseDialogFragment + dialog_recycler_view 承载列表/loading/底部栏；
 * 迁移后继承 [ComposeDialogFragment]，解析链路（importSourceAwait 四分支）与
 * ImportAutoTaskViewModel 不变，UI 改为受控渲染：loading（CircularProgressIndicator）/
 * 错误占位 / 列表行（勾选-状态-打开）/ 底部（全选计数-取消-导入）。
 * 简化说明：行勾选冲突处理改为「整行点击切换 + Checkbox 只读」，替代原 isPressed 判定；
 * CodeDialog / WaitDialog 按设计保留原样复用。
 */
class ImportAutoTaskDialog() : ComposeDialogFragment(),
    CodeDialog.Callback {

    companion object {
        const val RESULT_KEY = "auto_task_imported"
    }

    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("source", source)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    private val viewModel by viewModels<ImportAutoTaskViewModel>()

    // Compose 桥接状态（属性级，参照 UrlRecordActivity 模式）
    private var loading by mutableStateOf(true)
    private var errorMsg by mutableStateOf<String?>(null)
    private var importDone by mutableStateOf(false)
    private var tasks by mutableStateOf(listOf<AutoTaskRule>())
    private var checks by mutableStateOf(listOf<AutoTaskRule?>())
    private var selects by mutableStateOf(listOf<Boolean>())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ImportAutoTaskContent()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.errorLiveData.observe(viewLifecycleOwner) { error ->
            errorMsg = error
            loading = false
        }
        viewModel.successLiveData.observe(viewLifecycleOwner) { count ->
            loading = false
            if (count > 0) {
                syncListState()
                importDone = true
            } else {
                errorMsg = getString(R.string.wrong_format)
            }
        }
        val source = arguments?.getString("source")
        if (source.isNullOrEmpty()) {
            dismiss()
            return
        }
        viewModel.importSource(source)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (arguments?.getBoolean("finishOnDismiss") == true) {
            activity?.finish()
        }
    }

    @Composable
    private fun ImportAutoTaskContent() {
        val style = rememberAppDialogStyle()
        AppDialogFrame(
            title = stringResource(R.string.import_auto_task),
            content = {
                when {
                    loading -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = style.accent
                        )
                    }

                    errorMsg != null -> Text(
                        text = errorMsg.orEmpty(),
                        color = style.secondaryText,
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    importDone -> LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(tasks) { index, task ->
                            ImportTaskRow(
                                index = index,
                                task = task,
                                checked = selects.getOrNull(index) ?: false,
                                accent = style.accent,
                                primaryText = style.primaryText,
                                secondaryText = style.secondaryText,
                                onToggle = { toggleSelect(index) },
                                onOpen = { showCodeDialog(index) }
                            )
                        }
                    }

                    else -> Text(
                        text = stringResource(R.string.wrong_format),
                        color = style.secondaryText,
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            actions = {
                val palette = style.toMiuixPalette()
                if (importDone) {
                    LegadoMiuixActionButton(
                        text = selectToggleText(),
                        palette = palette,
                        onClick = { toggleSelectAll() },
                        cornerRadius = style.actionRadius
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                LegadoMiuixActionButton(
                    text = stringResource(R.string.cancel),
                    palette = palette,
                    onClick = { dismissAllowingStateLoss() },
                    cornerRadius = style.actionRadius
                )
                if (importDone) {
                    Spacer(modifier = Modifier.width(8.dp))
                    LegadoMiuixActionButton(
                        text = stringResource(R.string.ok),
                        palette = palette,
                        onClick = { doImport() },
                        primary = true,
                        cornerRadius = style.actionRadius
                    )
                }
            }
        )
    }

    @Composable
    private fun ImportTaskRow(
        index: Int,
        task: AutoTaskRule,
        checked: Boolean,
        accent: androidx.compose.ui.graphics.Color,
        primaryText: androidx.compose.ui.graphics.Color,
        secondaryText: androidx.compose.ui.graphics.Color,
        onToggle: () -> Unit,
        onOpen: () -> Unit
    ) {
        val localTask = checks.getOrNull(index)
        val statusText = when {
            localTask == null -> stringResource(R.string.import_status_new)
            task != localTask -> stringResource(R.string.import_status_update)
            else -> stringResource(R.string.import_status_exist)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = accent)
            )
            Text(
                text = task.name.ifBlank { task.id },
                color = primaryText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = statusText,
                color = secondaryText,
                fontSize = 12.sp
            )
            TextButton(onClick = { onOpen() }) {
                Text(
                    text = stringResource(R.string.open),
                    color = accent,
                    fontSize = 13.sp
                )
            }
        }
    }

    private fun toggleSelect(index: Int) {
        if (index !in viewModel.selectStatus.indices) return
        viewModel.selectStatus[index] = !viewModel.selectStatus[index]
        selects = viewModel.selectStatus.toList()
    }

    private fun toggleSelectAll() {
        val selectAll = viewModel.isSelectAll
        viewModel.selectStatus.indices.forEach { i ->
            if (viewModel.selectStatus[i] != !selectAll) {
                viewModel.selectStatus[i] = !selectAll
            }
        }
        selects = viewModel.selectStatus.toList()
    }

    private fun selectToggleText(): String {
        return if (viewModel.isSelectAll) {
            getString(R.string.select_cancel_count, viewModel.selectCount, viewModel.allTasks.size)
        } else {
            getString(R.string.select_all_count, viewModel.selectCount, viewModel.allTasks.size)
        }
    }

    private fun syncListState() {
        tasks = viewModel.allTasks.toList()
        checks = viewModel.checkTasks.toList()
        selects = viewModel.selectStatus.toList()
    }

    private fun showCodeDialog(index: Int) {
        val task = viewModel.allTasks.getOrNull(index) ?: return
        showDialogFragment(
            CodeDialog(
                GSON.toJson(task),
                disableEdit = false,
                requestId = index.toString()
            )
        )
    }

    private fun doImport() {
        val waitDialog = WaitDialog(requireContext())
        waitDialog.show()
        viewModel.importSelect {
            waitDialog.dismiss()
            parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf("refresh" to true))
            dismissAllowingStateLoss()
        }
    }

    override fun onCodeSave(code: String, requestId: String?) {
        requestId?.toInt()?.let { index ->
            GSON.fromJsonObject<AutoTaskRule>(code).getOrNull()?.let { task ->
                viewModel.updateTaskAt(index, task)
                syncListState()
            }
        }
    }
}