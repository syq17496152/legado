package io.legado.app.ui.association

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ToggleOn
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
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.RssSource
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.ImportItem
import io.legado.app.ui.widget.components.ImportSourceSheet
import io.legado.app.ui.widget.components.ImportState
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.showComposeTextFormDialogWithChecks
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.showDialogFragment

/**
 * 导入rss源弹出窗口（S6 支干样板：改用 [ImportSourceSheet] Compose 组件渲染）。
 *
 * 业务逻辑（importSource/importSelect/comparisonSource）全部保留在 [ImportRssSourceViewModel]，
 * 本类仅做 ViewModel 状态到 Compose 的桥接：
 *  - LiveData（successLiveData/errorLiveData）→ Fragment 级 Compose 状态字段（mutableStateOf）
 *  - 普通 ArrayList（allSources/checkSources/selectStatus）→ remember 派生 + SnapshotStateList 镜像
 */
class ImportRssSourceDialog() : ComposeDialogFragment(),
    CodeDialog.Callback {

    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("source", source)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    override val dialogTheme: Int = R.style.Theme_Legado_ComposeDialog_Bottom
    override val dialogGravity: Int = Gravity.BOTTOM
    override val dialogWindowAnimations: Int = R.style.AnimDialogBottom

    private val viewModel by viewModels<ImportRssSourceViewModel>()

    /** 编辑 CodeDialog 保存后递增，驱动 items 重新派生 */
    private val editTick = mutableIntStateOf(0)

    /** LiveData → Compose 状态桥接（Fragment 主线程写入，触发重组） */
    private val successCount = mutableStateOf<Int?>(null)
    private val errorLive = mutableStateOf<String?>(null)

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (arguments?.getBoolean("finishOnDismiss") == true) {
            activity?.finish()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("NotifyDataSetChanged")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    ImportRssSourceSheetContent()
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
    private fun ImportRssSourceSheetContent() {
        var keepName by remember { mutableStateOf(AppConfig.importKeepName) }
        var keepGroup by remember { mutableStateOf(AppConfig.importKeepGroup) }
        var keepEnable by remember { mutableStateOf(AppConfig.importKeepEnable) }
        var showComment by remember { mutableStateOf(AppConfig.importShowComment) }

        val selectFlags = remember { viewModel.selectStatus.toList().toMutableStateList() }
        LaunchedEffect(successCount.value) {
            if (successCount.value != null) {
                selectFlags.clear()
                selectFlags.addAll(viewModel.selectStatus)
            }
        }

        val items = remember(successCount.value, editTick.intValue) {
            viewModel.allSources.mapIndexed { index, s ->
                val local = viewModel.checkSources.getOrNull(index)
                val state = when {
                    local == null -> ImportState.NEW
                    s.lastUpdateTime > local.lastUpdateTime -> ImportState.UPDATE
                    else -> ImportState.EXIST
                }
                ImportItem(s.sourceName, s.sourceComment, state)
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
            },
            MenuAction(Icons.Default.Folder, getString(R.string.keep_group), checked = keepGroup) {
                keepGroup = !keepGroup
                putPrefBoolean(PreferKey.importKeepGroup, keepGroup)
            },
            MenuAction(Icons.Default.ToggleOn, getString(R.string.keep_enable), checked = keepEnable) {
                keepEnable = !keepEnable
                AppConfig.importKeepEnable = keepEnable
            },
            MenuAction(
                Icons.Default.Comment,
                getString(R.string.show_source_comment),
                checked = showComment
            ) {
                showComment = !showComment
                AppConfig.importShowComment = showComment
            }
        )

        ImportSourceSheet(
            title = getString(R.string.import_rss_source),
            items = items,
            selected = selectFlags,
            showComment = showComment,
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
        val source = viewModel.allSources.getOrNull(index) ?: return
        showDialogFragment(
            CodeDialog(
                GSON.toJson(source),
                disableEdit = false,
                requestId = index.toString()
            )
        )
    }

    private fun alertCustomGroup() {
        showComposeTextFormDialogWithChecks(
            title = getString(R.string.diy_edit_source_group),
            labels = listOf(getString(R.string.group_name)),
            initialValues = listOf(""),
            checkboxLabels = listOf(getString(R.string.add_group)),
            checkedIndices = emptySet(),
            positiveText = getString(android.R.string.ok),
            negativeText = getString(R.string.cancel),
            onPositive = { values, checks ->
                viewModel.isAddGroup = checks.getOrElse(0) { false }
                viewModel.groupName = values.getOrNull(0)?.trim().orEmpty()
            }
        )
    }

    override fun onCodeSave(code: String, requestId: String?) {
        requestId?.toInt()?.let {
            GSON.fromJsonObject<RssSource>(code).getOrNull()?.let { source ->
                viewModel.allSources[it] = source
                editTick.intValue++
            }
        }
    }

}
