package io.legado.app.ui.association

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.TextFields
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
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.databinding.DialogCustomGroupBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.ImportItem
import io.legado.app.ui.widget.components.ImportSourceSheet
import io.legado.app.ui.widget.components.ImportState
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导入替换净化规则弹出窗口（S6 支干样板：改用 [ImportSourceSheet] Compose 组件渲染）。
 *
 * 业务逻辑（import/importSelect/comparisonSource/分组）全部保留在 [ImportReplaceRuleViewModel]，
 * 本类仅做 ViewModel 状态到 Compose 的桥接。
 */
class ImportReplaceRuleDialog() : BaseDialogFragment(R.layout.dialog_import_sheet),
    CodeDialog.Callback {

    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("source", source)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    private val viewModel by viewModels<ImportReplaceRuleViewModel>()

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
    @SuppressLint("NotifyDataSetChanged")
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
                ImportReplaceRuleSheetContent()
            }
        }
        viewModel.import(source)
    }

    @Composable
    private fun ImportReplaceRuleSheetContent() {
        var keepName by remember { mutableStateOf(AppConfig.importKeepName) }

        val selectFlags = remember { viewModel.selectStatus.toList().toMutableStateList() }
        LaunchedEffect(successCount.value) {
            if (successCount.value != null) {
                selectFlags.clear()
                selectFlags.addAll(viewModel.selectStatus)
            }
        }

        val items = remember(successCount.value, editTick.intValue) {
            viewModel.allRules.mapIndexed { index, r ->
                val local = viewModel.checkRules.getOrNull(index)
                val state = when {
                    local == null -> ImportState.NEW
                    r.pattern != local.pattern
                            || r.replacement != local.replacement
                            || r.isRegex != local.isRegex
                            || r.scope != local.scope -> ImportState.UPDATE
                    else -> ImportState.EXIST
                }
                val name = if (r.group.isNullOrBlank()) r.name else "${r.name}(${r.group})"
                ImportItem(name, null, state)
            }
        }

        val loading = successCount.value == null && errorLive.value == null
        val errorMsg = errorLive.value ?: if (successCount.value != null && successCount.value == 0) {
            getString(R.string.wrong_format)
        } else {
            null
        }

        val menuActions = listOf(
            MenuAction(Icons.Default.Add, getString(R.string.diy_source_group)) {
                alertCustomGroup()
            },
            MenuAction(
                Icons.Default.TextFields,
                getString(R.string.keep_original_name),
                checked = keepName
            ) {
                keepName = !keepName
                putPrefBoolean(PreferKey.importKeepName, keepName)
            }
        )

        ImportSourceSheet(
            title = getString(R.string.import_replace_rule),
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
            menuActions = menuActions,
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
        val rule = viewModel.allRules.getOrNull(index) ?: return
        showDialogFragment(
            CodeDialog(
                GSON.toJson(rule),
                disableEdit = false,
                requestId = index.toString()
            )
        )
    }

    private fun alertCustomGroup() {
        lifecycleScope.launch(IO) {
            val groups = appDb.replaceRuleDao.allGroups()
            withContext(Main) {
                alert(R.string.diy_edit_source_group) {
                    val alertBinding = DialogCustomGroupBinding.inflate(layoutInflater).apply {
                        textInputLayout.setHint(R.string.group_name)
                        editView.setFilterValues(groups.toList())
                        editView.dropDownHeight = 180.dpToPx()
                    }
                    customView {
                        alertBinding.root
                    }
                    okButton {
                        viewModel.isAddGroup = alertBinding.swAddGroup.isChecked
                        viewModel.groupName = alertBinding.editView.text?.toString()
                    }
                    cancelButton()
                }
            }
        }
    }

    override fun onCodeSave(code: String, requestId: String?) {
        requestId?.toInt()?.let {
            GSON.fromJsonObject<ReplaceRule>(code).getOrNull()?.let { rule ->
                viewModel.allRules[it] = rule
                editTick.intValue++
            }
        }
    }

}