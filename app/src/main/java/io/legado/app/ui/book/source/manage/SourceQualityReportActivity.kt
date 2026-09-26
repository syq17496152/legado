package io.legado.app.ui.book.source.manage

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.model.QualityCheckSession
import io.legado.app.model.QualityReportApplier
import io.legado.app.model.SourceQualityReport
import io.legado.app.model.SourceQualityScorer
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementLazyColumn
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.components.AppConfirmDialog
import io.legado.app.ui.widget.compose.AppSettingPalette
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch

/**
 * 源质量体检结果页（import-source-quality-filter 场景2）
 *
 * 真机反馈修复（2026-09-13）：
 * - 样式对齐管理族：AppManagementScaffold 单源体系（顶栏/底栏/全选反选/palette 同源）
 * - 暂停/续跑：万条体检可暂停，恢复从断点续跑
 * - 删除反馈闭环：确认框明示"备份+删除"，执行后 toast "已删除 N 条 + 备份路径"，列表即时刷新
 * - 备份目录：Backup.backupPath/quality_backup（与整体备份同域）
 */
class SourceQualityReportActivity : AppCompatActivity() {

    private val viewModel by viewModels<SourceQualityReportViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val type = intent.getStringExtra("type") ?: QualityCheckSession.TAG_BOOK
        viewModel.init(type)
        AppLog.putDebugWithTag(
            QualityCheckSession.LOG_TAG,
            "结果页创建: type=$type",
            level = AppLog.Level.INFO
        )
        setContent {
            LegadoTheme {
                SourceQualityReportScreen(type = type, onBack = { finish() })
            }
        }
    }

    @Composable
    private fun SourceQualityReportScreen(type: String, onBack: () -> Unit) {
        val session: QualityCheckSession<*> = if (type == QualityCheckSession.TAG_BOOK) {
            QualityCheckSession.bookSession
        } else {
            QualityCheckSession.rssSession
        }
        val state by session.state.collectAsState()
        var filter by remember { mutableStateOf<SourceQualityScorer.UserState?>(null) }
        var showLowScoreOnly by remember { mutableStateOf(false) }
        // 选中键集：不得以 state.results 为 remember key（进度/暂停时 results copy 新实例会重置选中，
        // 真机反馈实锤：暂停后选中的源被清空→底栏消失→"全选不生效"）
        val selected = remember { mutableStateOf(setOf<String>()) }
        // "应用结果"确认框（quality-check-unify：体检报告→失效分组/weight 落库）
        var showApplyConfirm by remember { mutableStateOf(false) }
        // F70 删除回执条：删除后页内展示（条数 to 备份路径），替代一次性 toast
        var deleteReceipt by remember { mutableStateOf<Pair<Int, String>?>(null) }
        // F69 结论卡计数：基于全部结果（非筛选后）统计，筛选时数字不跳动
        val summary = remember(state.results) {
            var usable = 0
            var suspect = 0
            var failed = 0
            var untested = 0
            state.results.forEach { (_, report) ->
                when (SourceQualityScorer.toUserState(report)) {
                    SourceQualityScorer.UserState.USABLE -> usable++
                    SourceQualityScorer.UserState.SUSPECT -> suspect++
                    SourceQualityScorer.UserState.FAILED -> failed++
                    SourceQualityScorer.UserState.UNTESTED -> untested++
                }
            }
            listOf(usable, suspect, failed, untested)
        }
        // 结果行 URL 键（稳定主键，删除/刷新后映射不漂移）
        val keyOf: (Any?) -> String = { s ->
            (s as? BookSource)?.bookSourceUrl ?: ((s as? RssSource)?.sourceUrl ?: "")
        }
        val palette = rememberAppManagementPalette()

        val filteredResults = state.results
            .filter { (source, report) ->
                val userState = SourceQualityScorer.toUserState(report)
                val filterOk = filter == null || userState == filter
                val lowScoreOk = !showLowScoreOnly ||
                    (userState != SourceQualityScorer.UserState.FAILED && report.score < 60)
                filterOk && lowScoreOk
            }
            .sortedByDescending { it.second.score }

        // 暂停/继续（真机反馈：万条体检需暂停）
        val pauseLabel = if (session.hasPaused()) {
            stringResource(R.string.quality_report_resume)
        } else {
            stringResource(R.string.quality_report_pause)
        }

        AppManagementScaffold(
            title = stringResource(R.string.quality_report_title),
            selectedCount = selected.value.size,
            totalCount = filteredResults.size,
            onBack = onBack,
            bottomActions = listOf(
                // quality-check-unify：应用结果（体检报告→失效分组/weight 落库，收编"校验所选"能力）
                AppManagementAction(
                    text = stringResource(R.string.quality_report_apply),
                    onClick = {
                        val keys = selected.value
                        when {
                            keys.isEmpty() -> Unit
                            state.running -> toastOnUi(getString(R.string.quality_report_apply_running))
                            else -> showApplyConfirm = true
                        }
                    }
                ),
                // P4：禁用为主按钮
                AppManagementAction(
                    text = stringResource(R.string.quality_report_disable),
                    primary = true,
                    onClick = {
                        val keys = selected.value
                        if (keys.isNotEmpty()) {
                            AppLog.putDebugWithTag(
                                QualityCheckSession.LOG_TAG,
                                "UI 禁用点击: count=${keys.size}",
                                level = AppLog.Level.INFO
                            )
                            viewModel.disableSelected(session, keys, keyOf) {
                                toastOnUi(getString(R.string.quality_report_disabled_toast, keys.size))
                                selected.value = emptySet()
                            }
                        }
                    }
                ),
                AppManagementAction(
                    text = stringResource(R.string.quality_report_delete),
                    danger = true,
                    onClick = {
                        val keys = selected.value
                        if (keys.isEmpty()) return@AppManagementAction
                        // S6：确认框明示"先备份再删除"
                        // 弹窗族收口（B2.1 M1 弹窗收尾）：原 androidx.appcompat AlertDialog.Builder
                        // 改走 Compose 弹窗族，destructive 语义由 dangerPositive 统一到 AppSemanticColors.Danger
                        showComposeConfirmDialog(
                            title = getString(R.string.quality_report_delete),
                            message = getString(R.string.quality_report_delete_confirm, keys.size),
                            positiveText = getString(R.string.ok),
                            negativeText = getString(R.string.cancel),
                            dangerPositive = true,
                            onPositive = {
                                AppLog.putDebugWithTag(
                                    QualityCheckSession.LOG_TAG,
                                    "UI 删除确认: count=${keys.size}",
                                    level = AppLog.Level.INFO
                                )
                                viewModel.deleteSelected(this@SourceQualityReportActivity, session, keys, keyOf) { path ->
                                    // F70：删除回执由 toast 升级为页内结果条（路径可复制）
                                    deleteReceipt = keys.size to path
                                    selected.value = emptySet()
                                }
                            }
                        )
                    }
                )
            ),
            onSelectAll = {
                // 全选语义对齐管理族基准（TxtTocRule/DictRule：onSelectAll(!allSelected)）：
                // 未全选→全选；已全选→清空。
                // 真机 bug 修复：原实现分支写反（选中 1 条后点"全选"反而清空选中→"全选不生效"）
                val before = selected.value.size
                val allKeys = filteredResults.map { keyOf(it.first) }.toSet()
                val willSelectAll = allKeys.isEmpty() || !selected.value.containsAll(allKeys)
                selected.value = if (willSelectAll) allKeys else emptySet()
                AppLog.putDebugWithTag(
                    QualityCheckSession.LOG_TAG,
                    "全选点击: before=$before after=${selected.value.size} 可见=${allKeys.size}",
                    level = AppLog.Level.INFO
                )
            },
            onInvertSelection = {
                val before = selected.value.size
                selected.value = filteredResults.map { keyOf(it.first) }.toSet() - selected.value
                AppLog.putDebugWithTag(
                    QualityCheckSession.LOG_TAG,
                    "反选点击: before=$before after=${selected.value.size}",
                    level = AppLog.Level.INFO
                )
            }
        ) { palette ->
            // UI 规范归位（2026-09-13 用户批评：暗色下文字黑色看不清）：
            // 内容区取色必须走 AppManagementPalette 直色（ThemeStore 链），
            // 禁止 MaterialTheme.colorScheme（M3 派生色不随主题背景，H9/H11 铁律）
            val settings = palette.settings
            Column(modifier = Modifier.fillMaxSize()) {
                // 进度条（体检中）+ 显式暂停/继续按钮（真机反馈：暂停不能藏在右上角三点菜单里）
                if (state.running || session.hasPaused()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = if (state.running) {
                                stringResource(
                                    R.string.quality_report_progress, state.checked, state.total, state.failedCount
                                )
                            } else {
                                stringResource(R.string.quality_report_paused_hint, state.checked, state.total)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = settings.secondaryText
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            LinearProgressIndicator(
                                progress = { if (state.total > 0) state.checked.toFloat() / state.total else 0f },
                                color = settings.accent,
                                trackColor = Color(settings.row),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 6.dp)
                            )
                            // 暂停/继续按钮：管理族底栏同款 LegadoMiuixActionButton（禁止 M3 OutlinedButton 裸色）
                            LegadoMiuixActionButton(
                                text = pauseLabel,
                                palette = palette.miuix,
                                onClick = {
                                    if (state.running) {
                                        AppLog.putDebugWithTag(
                                            QualityCheckSession.LOG_TAG,
                                            "UI 暂停点击: 已检=${state.checked}/${state.total}",
                                            level = AppLog.Level.INFO
                                        )
                                        viewModel.pause(session)
                                    } else if (session.hasPaused()) {
                                        AppLog.putDebugWithTag(
                                            QualityCheckSession.LOG_TAG,
                                            "UI 续跑点击",
                                            level = AppLog.Level.INFO
                                        )
                                        viewModel.resume(session)
                                    }
                                },
                                primary = true,
                                minWidth = 72.dp,
                                minHeight = 34.dp,
                                insidePadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }

                // F69 一次性结论卡（顶部）：可用/可疑/失败/未校验 四态计数，点按复用筛选
                SummaryCard(summary = summary, filter = filter, settings = settings) {
                    filter = if (filter == it) null else it
                }

                // 四态筛选 chips（P5 白话；含 F69 复用的"可用"chip，横向可滚动防溢出）
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    FilterChip(stringResource(R.string.import_check_state_usable), filter == SourceQualityScorer.UserState.USABLE, settings) {
                        filter = if (filter == SourceQualityScorer.UserState.USABLE) null else SourceQualityScorer.UserState.USABLE
                    }
                    FilterChip(stringResource(R.string.import_check_state_failed), filter == SourceQualityScorer.UserState.FAILED, settings) {
                        filter = if (filter == SourceQualityScorer.UserState.FAILED) null else SourceQualityScorer.UserState.FAILED
                    }
                    FilterChip(stringResource(R.string.import_check_state_suspect), filter == SourceQualityScorer.UserState.SUSPECT, settings) {
                        filter = if (filter == SourceQualityScorer.UserState.SUSPECT) null else SourceQualityScorer.UserState.SUSPECT
                    }
                    FilterChip(stringResource(R.string.import_check_state_untested), filter == SourceQualityScorer.UserState.UNTESTED, settings) {
                        filter = if (filter == SourceQualityScorer.UserState.UNTESTED) null else SourceQualityScorer.UserState.UNTESTED
                    }
                    FilterChip(stringResource(R.string.import_check_low_score), showLowScoreOnly, settings) {
                        showLowScoreOnly = !showLowScoreOnly
                    }
                }

                // F70 删除回执条：页内结果条（替代一次性 toast），路径只读 + 一键复制
                deleteReceipt?.let { (count, path) ->
                    ResultReceiptBar(
                        count = count,
                        path = path,
                        settings = settings,
                        miuixPalette = palette.miuix,
                        onCopy = {
                            sendToClip(path)
                            toastOnUi(getString(R.string.quality_report_path_copied))
                        },
                        onDismiss = { deleteReceipt = null }
                    )
                }

                // 列表容器收敛（2026-09-26）：与书源管理同容器（项间距 8dp + 快速滚动条 + 导航栏内边距），
                // 行间距不再由页内自绘分隔线承载
                AppManagementLazyColumn(
                    palette = palette,
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(filteredResults, key = { _, (s, _) -> keyOf(s) }) { _, (source, report) ->
                        val url = keyOf(source)
                        ReportRow(
                            name = (source as? BookSource)?.bookSourceName
                                ?: (source as RssSource).sourceName,
                            report = report,
                            checked = selected.value.contains(url),
                            palette = palette,
                            onToggle = {
                                selected.value = if (selected.value.contains(url)) {
                                    selected.value - url
                                } else {
                                    selected.value + url
                                }
                            }
                        )
                    }
                }
            }
        }

        // 应用结果确认框（弹框族基线 AppConfirmDialog）
        if (showApplyConfirm) {
            val applyCount = selected.value.size
            AppConfirmDialog(
                title = stringResource(R.string.quality_report_apply),
                body = stringResource(R.string.quality_report_apply_confirm, applyCount),
                confirmText = stringResource(R.string.ok),
                dismissText = stringResource(R.string.cancel),
                onConfirm = {
                    showApplyConfirm = false
                    val keys = selected.value
                    AppLog.putDebugWithTag(
                        QualityCheckSession.LOG_TAG,
                        "UI 应用结果确认: count=$applyCount",
                        level = AppLog.Level.INFO
                    )
                    viewModel.applyResults(session, keys) { applied ->
                        toastOnUi(getString(R.string.quality_report_applied_toast, applied))
                    }
                },
                onDismiss = { showApplyConfirm = false }
            )
        }
    }

    /**
     * F69 体检完成结论卡：一次性给出「可用/可疑/失败/未校验」四态计数，
     * 免去用户逐行点数。点按某一态复用下方筛选 chips 的同一 filter 状态。
     */
    @Composable
    private fun SummaryCard(
        summary: List<Int>,
        filter: SourceQualityScorer.UserState?,
        settings: AppSettingPalette,
        onStateClick: (SourceQualityScorer.UserState) -> Unit
    ) {
        if (summary.sum() == 0) return
        val successColor = colorResource(R.color.success)
        Surface(
            color = Color(settings.row),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                SummaryStat(
                    label = stringResource(R.string.import_check_state_usable),
                    count = summary[0],
                    color = successColor,
                    selected = filter == SourceQualityScorer.UserState.USABLE,
                    settings = settings
                ) { onStateClick(SourceQualityScorer.UserState.USABLE) }
                SummaryStat(
                    label = stringResource(R.string.import_check_state_suspect),
                    count = summary[1],
                    color = settings.secondaryText,
                    selected = filter == SourceQualityScorer.UserState.SUSPECT,
                    settings = settings
                ) { onStateClick(SourceQualityScorer.UserState.SUSPECT) }
                SummaryStat(
                    label = stringResource(R.string.import_check_state_failed),
                    count = summary[2],
                    color = settings.danger,
                    selected = filter == SourceQualityScorer.UserState.FAILED,
                    settings = settings
                ) { onStateClick(SourceQualityScorer.UserState.FAILED) }
                SummaryStat(
                    label = stringResource(R.string.import_check_state_untested),
                    count = summary[3],
                    color = settings.disabledText,
                    selected = filter == SourceQualityScorer.UserState.UNTESTED,
                    settings = settings
                ) { onStateClick(SourceQualityScorer.UserState.UNTESTED) }
            }
        }
    }

    @Composable
    private fun SummaryStat(
        label: String,
        count: Int,
        color: Color,
        selected: Boolean,
        settings: AppSettingPalette,
        onClick: () -> Unit
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) settings.accent else settings.secondaryText,
                maxLines = 1
            )
        }
    }

    /**
     * F70 删除备份回执条：替代一次性 toast（易错过、路径无法复制），
     * 页内常驻直到用户关闭；路径只读展示 + 一键复制。
     */
    @Composable
    private fun ResultReceiptBar(
        count: Int,
        path: String,
        settings: AppSettingPalette,
        miuixPalette: LegadoMiuixPalette,
        onCopy: () -> Unit,
        onDismiss: () -> Unit
    ) {
        Surface(
            color = Color(settings.row),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.quality_report_deleted_title, count),
                        style = MaterialTheme.typography.bodyMedium,
                        color = settings.primaryText,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(R.string.close),
                        style = MaterialTheme.typography.labelMedium,
                        color = settings.secondaryText,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = path,
                        style = MaterialTheme.typography.bodySmall,
                        color = settings.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    LegadoMiuixActionButton(
                        text = stringResource(R.string.quality_report_copy_path),
                        palette = miuixPalette,
                        onClick = onCopy,
                        minWidth = 64.dp,
                        minHeight = 30.dp,
                        insidePadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun FilterChip(
        text: String,
        selected: Boolean,
        settings: AppSettingPalette,
        onClick: () -> Unit
    ) {
        // 取色基线：选中=accent/onAccent，未选中=row/secondaryText（禁止 colorScheme.container 族）
        Surface(
            shape = MaterialTheme.shapes.small,
            color = if (selected) settings.accent else Color(settings.row),
            modifier = Modifier.clickable {
                AppLog.putDebugWithTag(
                    QualityCheckSession.LOG_TAG,
                    "筛选切换: text=$text selected=${!selected}",
                    level = AppLog.Level.INFO
                )
                onClick()
            }
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) settings.onAccent else settings.secondaryText,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                maxLines = 1
            )
        }
    }

    /**
     * 体检结果行。
     *
     * **行组件收敛（2026-09-26）**：原为**自绘裸 `Column`**（行高/外边距/自绘 Checkbox 自成一套），
     * 与「我的」管理族其余列表页的 Miuix Card 行（`AppManagementListRow`）观感不一致。
     * 现统一换该单源行：勾选槽 → `selected`/`onToggleSelection`，得分 → `trailingBeforeSwitch`，
     * 原因串 → `subtitle`（行内左侧不再手写 52dp 缩进）。取色全部随 `AppManagementPalette`。
     */
    @Composable
    private fun ReportRow(
        name: String,
        report: SourceQualityReport,
        checked: Boolean,
        palette: AppManagementPalette,
        onToggle: () -> Unit
    ) {
        val settings = palette.settings
        val userState = SourceQualityScorer.toUserState(report)
        val stateText = when (userState) {
            SourceQualityScorer.UserState.USABLE -> stringResource(R.string.import_check_state_usable)
            SourceQualityScorer.UserState.FAILED -> stringResource(R.string.import_check_state_failed)
            SourceQualityScorer.UserState.SUSPECT -> stringResource(R.string.import_check_state_suspect)
            SourceQualityScorer.UserState.UNTESTED -> stringResource(R.string.import_check_state_untested)
        }
        val reasons = mutableListOf<String>()
        reasons.add(stateText)
        report.suspectReasons.forEach { reasons.add(it.name) }
        report.dimensions.values.firstOrNull { it.state == io.legado.app.model.DimState.FAIL }?.let {
            reasons.add(it.evidence)
        }
        AppManagementListRow(
            title = name,
            subtitle = reasons.filter { it.isNotBlank() }.joinToString(" | ").take(50),
            palette = palette,
            titleMaxLines = 1,
            subtitleMaxLines = 1,
            // 与书源管理基线行同高（56dp）；不叠面板纹理图（BookSourceScreen 同口径）
            minHeight = 56.dp,
            drawPanelImage = false,
            selected = checked,
            onToggleSelection = onToggle,
            // 整行点按即切换勾选（与原自绘 Column 的整行 clickable 行为一致）
            onClick = onToggle,
            trailingBeforeSwitch = {
                Text(
                    text = "${report.score} · ${(report.coverage * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (userState == SourceQualityScorer.UserState.FAILED) {
                        settings.danger
                    } else {
                        settings.secondaryText
                    }
                )
            }
        )
    }
}

/**
 * 体检结果页 ViewModel：会话操作桥接
 */
class SourceQualityReportViewModel : ViewModel() {

    fun init(type: String) = Unit

    fun pause(session: QualityCheckSession<*>) = session.pause()

    fun resume(session: QualityCheckSession<*>) = session.resume()

    fun disableSelected(session: QualityCheckSession<*>, urls: Set<String>, keyOf: (Any) -> String, done: () -> Unit) {
        viewModelScope.launch {
            @Suppress("UNCHECKED_CAST")
            (session as QualityCheckSession<Any>).disableSelected(urls)
            done()
        }
    }

    fun deleteSelected(activity: SourceQualityReportActivity, session: QualityCheckSession<*>, urls: Set<String>, keyOf: (Any) -> String, done: (String) -> Unit) {
        viewModelScope.launch {
            @Suppress("UNCHECKED_CAST")
            val path = (session as QualityCheckSession<Any>).deleteSelected(activity, urls)
            done(path)
        }
    }

    /**
     * 应用体检结果（quality-check-unify 桥接）：按选中键集过滤结果，分流书源/订阅源落库
     */
    fun applyResults(session: QualityCheckSession<*>, urls: Set<String>, done: (Int) -> Unit) {
        viewModelScope.launch {
            val checkDomain = session.lastCheckDomain()
            // 结果行 URL 键（与 Screen 侧 keyOf 同语义）
            val keyOf: (Any) -> String = { s ->
                (s as? BookSource)?.bookSourceUrl ?: ((s as? RssSource)?.sourceUrl ?: "")
            }
            @Suppress("UNCHECKED_CAST")
            val results = (session as QualityCheckSession<Any>).state.value.results
                .filter { (s, _) -> keyOf(s) in urls }
            val applied = when (session.typeTag) {
                QualityCheckSession.TAG_BOOK -> {
                    @Suppress("UNCHECKED_CAST")
                    QualityReportApplier.applyBookReports(
                        results.filterIsInstance<Pair<BookSource, SourceQualityReport>>(),
                        checkDomain
                    )
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    QualityReportApplier.applyRssReports(
                        results.filterIsInstance<Pair<RssSource, SourceQualityReport>>(),
                        checkDomain
                    )
                }
            }
            done(applied)
        }
    }
}
