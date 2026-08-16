package io.legado.app.ui.association

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.DictRule
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.ImportItem
import io.legado.app.ui.widget.components.ImportSourceSheet
import io.legado.app.ui.widget.components.ImportState
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment

/**
 * 导入净化规则弹出窗口（S6 支干样板：改用 [ImportSourceSheet] Compose 组件渲染）。
 *
 * 业务逻辑（importSource/importSelect/comparisonSource）全部保留在 [ImportDictRuleViewModel]，
 * 本类仅做 ViewModel 状态到 Compose 的桥接。
 */
class ImportDictRuleDialog() : BaseDialogFragment(R.layout.dialog_import_sheet),
    CodeDialog.Callback {

    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("source", source)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    private val viewModel by viewModels<ImportDictRuleViewModel>()

    /** 编辑 CodeDialog 保存后递增，驱动 items 重新派生 */
    private val editTick = mutableIntStateOf(0)

    /** LiveData → Compose 状态桥接 */
    private val successCount = mutableStateOf<Int?>(null)
    private val errorLive = mutableStateOf<String?>(null)

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (arguments?.getBoolean("finishOnDismiss") == true) {
            activity?.finish()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val source = arguments?.getString("source")
        if (source.isNullOrEmpty()) {
            dismiss()
            return
        }
        viewModel.successLiveData.observe(viewLifecycleOwner) {
            successCount.value = it
        }
        viewModel.errorLiveData.observe(viewLifecycleOwner) {
            errorLive.value = it
        }
        val composeView = view.findViewById<ComposeView>(R.id.compose_view)
        composeView.setContent {
            LegadoTheme {
                ImportDictRuleSheetContent()
            }
        }
        viewModel.importSource(source)
    }

    @Composable
    private fun ImportDictRuleSheetContent() {
        val selectFlags = remember { viewModel.selectStatus.toList().toMutableStateList() }
        LaunchedEffect(successCount.value) {
            if (successCount.value != null) {
                selectFlags.clear()
                selectFlags.addAll(viewModel.selectStatus)
            }
        }

        val items = remember(successCount.value, editTick.intValue) {
            viewModel.allSources.mapIndexed { index, r ->
                val local = viewModel.checkSources.getOrNull(index)
                val state = if (local == null) ImportState.NEW else ImportState.EXIST
                ImportItem(r.name, null, state)
            }
        }

        val loading = successCount.value == null && errorLive.value == null
        val errorMsg = errorLive.value ?: if (successCount.value != null && successCount.value == 0) {
            getString(R.string.wrong_format)
        } else {
            null
        }

        ImportSourceSheet(
            title = getString(R.string.import_dict_rule),
            items = items,
            selected = selectFlags,
            showComment = false,
            onToggleSelect = { index ->
                if (index < viewModel.selectStatus.size && index < selectFlags.size) {
                    viewModel.selectStatus[index] = !viewModel.selectStatus[index]
                    selectFlags[index] = viewModel.selectStatus[index]
                }
            },
            onToggleSelectAll = {
                val all = viewModel.isSelectAll
                viewModel.selectStatus.forEachIndexed { i, _ ->
                    viewModel.selectStatus[i] = !all
                    if (i < selectFlags.size) selectFlags[i] = !all
                }
            },
            onEditItem = { index -> openCodeDialog(index) },
            onImport = { doImport() },
            onDismiss = { dismissAllowingStateLoss() },
            menuActions = emptyList(),
            loading = loading,
            errorMsg = errorMsg
        )
    }

    private fun doImport() {
        val waitDialog = WaitDialog(requireContext())
        waitDialog.show()
        viewModel.importSelect {
            waitDialog.dismiss()
            dismissAllowingStateLoss()
        }
    }

    private fun openCodeDialog(index: Int) {
        val rule = viewModel.allSources.getOrNull(index) ?: return
        showDialogFragment(
            CodeDialog(
                GSON.toJson(rule),
                disableEdit = false,
                requestId = index.toString()
            )
        )
    }

    override fun onCodeSave(code: String, requestId: String?) {
        requestId?.toInt()?.let {
            GSON.fromJsonObject<DictRule>(code).getOrNull()?.let { rule ->
                viewModel.allSources[it] = rule
                editTick.intValue++
            }
        }
    }

}