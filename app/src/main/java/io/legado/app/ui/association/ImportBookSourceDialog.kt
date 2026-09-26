package io.legado.app.ui.association

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.model.CheckDepth
import io.legado.app.model.ImportCheck
import io.legado.app.model.SourceQualityScorer
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.ImportItem
import io.legado.app.ui.widget.components.ImportSourceSheet
import io.legado.app.ui.widget.components.ImportState
import io.legado.app.ui.widget.components.AppConfirmDialog
import io.legado.app.ui.widget.components.AppModalBottomSheet
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.showComposeTextFormDialogWithChecks
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi

/**
 * 导入书源弹出窗口（S6 支干样板：改用 [ImportSourceSheet] Compose 组件渲染）。
 *
 * 业务逻辑（importSource/importSelect/comparisonSource/分组）全部保留在 [ImportBookSourceViewModel]，
 * 本类仅做 ViewModel 状态到 Compose 的桥接：
 *  - LiveData（successLiveData/errorLiveData）→ Fragment 级 Compose 状态字段（mutableStateOf）
 *  - 普通 ArrayList（allSources/checkSources/selectStatus/newSourceStatus/updateSourceStatus）→ remember 派生 + SnapshotStateList 镜像
 */
class ImportBookSourceDialog() : ComposeDialogFragment(),
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

    private val viewModel by viewModels<ImportBookSourceViewModel>()

    /** 编辑 CodeDialog 保存后递增，驱动 items 重新派生 */
    private val editTick = mutableIntStateOf(0)

    /** LiveData → Compose 状态桥接（Fragment 主线程写入，触发重组） */
    private val successCount = mutableStateOf<Int?>(null)
    private val errorLive = mutableStateOf<String?>(null)

    /** 合集下载进度（已完成/总数），来自 progressLiveData 桥接（spinner-fix delta 2026-09-05） */
    private val progressState = mutableStateOf<Pair<Int, Int>?>(null)

    /** 校验进度（已校验/总数/已过滤），来自 checkProgressLiveData 桥接（import-source-quality-filter） */
    private val checkProgressState = mutableStateOf<Triple<Int, Int, Int>?>(null)

    /** 3.3.1：被硬阻断（全空配置）的书源数量，仅在标题追加回执 */
    private val emptyIgnoredCount = mutableIntStateOf(0)

    /** 复核窗口数据（过滤结果非空时弹出） */
    private var reviewOutcome by mutableStateOf<ImportCheckOutcome?>(null)

    /** 全选恢复二次确认（P3） */
    private var showRestoreAllConfirm by mutableStateOf(false)

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
                    ImportBookSourceSheetContent()
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
        viewModel.progressLiveData.observe(viewLifecycleOwner) {
            progressState.value = it
        }
        viewModel.checkProgressLiveData.observe(viewLifecycleOwner) {
            checkProgressState.value = it
        }
        viewModel.emptyConfigCount.observe(viewLifecycleOwner) {
            emptyIgnoredCount.intValue = it
        }
        viewModel.importSource(source)
    }

    @Composable
    private fun ImportBookSourceSheetContent() {
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
                val l1 = viewModel.l1Reports.getOrNull(index)
                val filtered = l1?.deterministicFail == true
                val state = when {
                    filtered -> ImportState.FILTERED
                    local == null -> ImportState.NEW
                    s.lastUpdateTime > local.lastUpdateTime -> ImportState.UPDATE
                    else -> ImportState.EXIST
                }
                val detail = if (filtered) {
                    l1?.dimensions?.values?.firstOrNull { it.state == io.legado.app.model.DimState.FAIL }?.evidence
                } else null
                ImportItem(s.bookSourceName, s.bookSourceComment, state, detail)
            }
        }

        val loading = successCount.value == null && errorLive.value == null
        // spinner-fix delta 2026-09-05：进度挂 title 展示（ImportSourceSheet 不新增参数）
        // import-source-quality-filter：校验进度优先于合集下载进度
        val sheetTitle = when {
            checkProgressState.value != null && viewModel.checkRunning -> {
                val (done, total, filtered) = checkProgressState.value!!
                getString(R.string.import_check_imported_batch, done, total, filtered)
            }
            progressState.value != null -> {
                val (done, total) = progressState.value!!
                "${getString(R.string.import_book_source)} · ${getString(R.string.import_fetching_progress, done, total)}"
            }
            else -> getString(R.string.import_book_source)
        }
        // 3.3.1：全空书源被硬阻断时在标题追加回执（否则用户会以为「源少了」）
        val sheetTitleWithIgnored = if (emptyIgnoredCount.intValue > 0) {
            "$sheetTitle · ${getString(R.string.import_empty_source_ignored, emptyIgnoredCount.intValue)}"
        } else {
            sheetTitle
        }
        val errorMsg = errorLive.value ?: if (successCount.value != null && successCount.value == 0) {
            getString(R.string.wrong_format)
        } else {
            null
        }

        /** 按 newSourceStatus/updateSourceStatus 过滤的反向选择（选择新增/选择更新） */
        val toggleSelectionBy: (List<Boolean>) -> Unit = { status ->
            val allSelected = status.indexOfFirst { it }.let { first ->
                // 目标集合全部已选 → 取消；否则全选
                first != -1 && status.indices.none { status[it] && !viewModel.selectStatus[it] }
            }
            status.forEachIndexed { index, b ->
                if (b) {
                    viewModel.selectStatus[index] = !allSelected
                    if (index < selectFlags.size) selectFlags[index] = !allSelected
                }
            }
        }

        val menuActions = listOf(
            MenuAction(Icons.Default.Add, getString(R.string.diy_source_group)) {
                alertCustomGroup()
            },
            MenuAction(
                Icons.Default.Add,
                getString(R.string.select_new_source),
                checked = viewModel.isSelectAllNew
            ) {
                toggleSelectionBy(viewModel.newSourceStatus)
            },
            MenuAction(
                Icons.Default.Edit,
                getString(R.string.select_update_source),
                checked = viewModel.isSelectAllUpdate
            ) {
                toggleSelectionBy(viewModel.updateSourceStatus)
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
            title = sheetTitleWithIgnored,
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
            onDismiss = { attemptDismiss() },
            menuActions = menuActions,
            loading = loading,
            errorMsg = errorMsg
        )

        // 导入校验：过滤复核窗口（P3 防橡皮图章 + P8 导入即禁用）
        reviewOutcome?.let { outcome ->
            FilteredReviewSheet(
                outcome = outcome,
                onRestore = { indexes, autoDisable ->
                    reviewOutcome = null
                    val waitDialog = WaitDialog(requireContext())
                    waitDialog.show()
                    viewModel.restoreFiltered(indexes, autoDisable) {
                        waitDialog.dismiss()
                        toastOnUi(getString(R.string.import_check_filtered_summary, outcome.imported, indexes.size))
                        dismissAllowingStateLoss()
                    }
                },
                onRestoreAll = {
                    // P3：全选恢复二次确认（reviewOutcome 保留供确认框读取）
                    showRestoreAllConfirm = true
                },
                onDismiss = { reviewOutcome = null }
            )
        }

        // P3：全选恢复二次确认（弹框族基线：统一 AppConfirmDialog，禁止裸 M3 AlertDialog）
        if (showRestoreAllConfirm) {
            val outcome = reviewOutcome
            AppConfirmDialog(
                title = stringResource(R.string.import_check_filtered_title),
                body = stringResource(R.string.import_check_restore_all_confirm, outcome?.filtered?.size ?: 0),
                confirmText = stringResource(R.string.ok),
                dismissText = stringResource(R.string.cancel),
                onConfirm = {
                    showRestoreAllConfirm = false
                    outcome?.let {
                        reviewOutcome = null
                        val waitDialog = WaitDialog(requireContext())
                        waitDialog.show()
                        viewModel.restoreFiltered(it.filtered.map { f -> f.index }, false) {
                            waitDialog.dismiss()
                            toastOnUi(getString(R.string.import_check_filtered_summary, it.imported, it.filtered.size))
                            dismissAllowingStateLoss()
                        }
                    }
                },
                onDismiss = { showRestoreAllConfirm = false }
            )
        }
    }

    /**
     * 弹框关闭拦截：校验进行中弹确认（P2 防全损：直接取消 / 导入已通过源）
     */
    private fun attemptDismiss() {
        if (viewModel.checkRunning) {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.import_check_stop_or_cancel)
                .setPositiveButton(R.string.import_check_stop_pass) { _, _ ->
                    viewModel.cancelCheck(landPassed = true)
                    dismissAllowingStateLoss()
                }
                .setNegativeButton(R.string.import_check_stop_cancel) { _, _ ->
                    viewModel.cancelCheck(landPassed = false)
                    dismissAllowingStateLoss()
                }
                .show()
        } else {
            dismissAllowingStateLoss()
        }
    }

    private fun doImport() {
        // 导入校验分流（import-source-quality-filter）：开启且深度>L1 走校验导入
        if (ImportCheck.enabled && ImportCheck.depth != CheckDepth.L1) {
            doImportWithCheck()
            return
        }
        val waitDialog = WaitDialog(requireContext())
        waitDialog.show()
        viewModel.importSelect {
            waitDialog.dismiss()
            dismissAllowingStateLoss()
        }
    }

    private fun doImportWithCheck() {
        checkProgressState.value = null
        val waitDialog = WaitDialog(requireContext())
        waitDialog.show()
        viewModel.importSelectWithCheck(
            onProgress = { _, _, _ -> },
            onComplete = { outcome ->
                waitDialog.dismiss()
                when {
                    outcome == null -> Unit // 校验异常/取消：保留列表供重试
                    outcome.networkDown -> {
                        toastOnUi(getString(R.string.import_check_network_down, outcome.unCheckedCount))
                        dismissAllowingStateLoss()
                    }
                    outcome.filtered.isEmpty() -> dismissAllowingStateLoss()
                    else -> reviewOutcome = outcome
                }
            }
        )
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
            GSON.fromJsonObject<BookSource>(code).getOrNull()?.let { source ->
                viewModel.allSources[it] = source
                editTick.intValue++
            }
        }
    }

}