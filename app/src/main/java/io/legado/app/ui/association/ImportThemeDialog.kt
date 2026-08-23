package io.legado.app.ui.association

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.ImportItem
import io.legado.app.ui.widget.components.ImportSourceSheet
import io.legado.app.ui.widget.components.ImportState
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.showDialogFragment

/**
 * 导入主题弹出窗口（S6 支干样板：改用 [ImportSourceSheet] Compose 组件渲染）。
 *
 * 业务逻辑（importSource/importSelect/comparisonSource）全部保留在 [ImportThemeViewModel]，
 * 本类仅做 ViewModel 状态到 Compose 的桥接。编辑项仅打开 [CodeDialog] 预览（与改造前一致，不落盘）。
 */
class ImportThemeDialog() : ComposeDialogFragment() {

    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("source", source)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    override val dialogTheme: Int = R.style.Theme_Legado_ComposeDialog_Bottom
    override val dialogGravity: Int = Gravity.BOTTOM
    override val dialogWindowAnimations: Int = R.style.AnimDialogBottom

    private val viewModel by viewModels<ImportThemeViewModel>()

    /** LiveData → Compose 状态桥接 */
    private val successCount = mutableStateOf<Int?>(null)
    private val errorLive = mutableStateOf<String?>(null)

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (arguments?.getBoolean("finishOnDismiss") == true) {
            activity?.finish()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    ImportThemeSheetContent()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
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
        viewModel.importSource(source)
    }

    @Composable
    private fun ImportThemeSheetContent() {
        val selectFlags = remember { viewModel.selectStatus.toList().toMutableStateList() }
        LaunchedEffect(successCount.value) {
            if (successCount.value != null) {
                selectFlags.clear()
                selectFlags.addAll(viewModel.selectStatus)
            }
        }

        val items = remember(successCount.value) {
            viewModel.allSources.mapIndexed { index, r ->
                val local = viewModel.checkSources.getOrNull(index)
                val state = when {
                    local == null -> ImportState.NEW
                    local != r -> ImportState.UPDATE
                    else -> ImportState.EXIST
                }
                ImportItem(r.themeName, null, state)
            }
        }

        val loading = successCount.value == null && errorLive.value == null
        val errorMsg = errorLive.value ?: if (successCount.value != null && successCount.value == 0) {
            getString(R.string.wrong_format)
        } else {
            null
        }

        ImportSourceSheet(
            title = getString(R.string.import_theme),
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
            onEditItem = { index ->
                viewModel.allSources.getOrNull(index)?.let { r ->
                    showDialogFragment(
                        CodeDialog(
                            GSON.toJson(r),
                            disableEdit = false
                        )
                    )
                }
            },
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

}
