package io.legado.app.ui.book.source.manage

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
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
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.components.AppConfirmDialog
import io.legado.app.ui.widget.compose.AppSettingPalette
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
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
                        androidx.appcompat.app.AlertDialog.Builder(this@SourceQualityReportActivity)
                            .setTitle(R.string.quality_report_delete)
                            .setMessage(getString(R.string.quality_report_delete_confirm, keys.size))
                            .setPositiveButton(R.string.ok) { _, _ ->
                                AppLog.putDebugWithTag(
                                    QualityCheckSession.LOG_TAG,
                                    "UI 删除确认: count=${keys.size}",
                                    level = AppLog.Level.INFO
                                )
                                viewModel.deleteSelected(this@SourceQualityReportActivity, session, keys, keyOf) { path ->
                                    // 删除反馈闭环：toast 明示删除数+备份路径
                                    toastOnUi(getString(R.string.quality_report_deleted_toast, keys.size, path))
                                    selected.value = emptySet()
                                }
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
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

                // 四态筛选 chips（P5 白话）
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
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

                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(filteredResults, key = { _, (s, _) -> keyOf(s) }) { _, (source, report) ->
                        val url = keyOf(source)
                        ReportRow(
                            name = (source as? BookSource)?.bookSourceName
                                ?: (source as RssSource).sourceName,
                            report = report,
                            checked = selected.value.contains(url),
                            settings = settings,
                            onToggle = {
                                selected.value = if (selected.value.contains(url)) {
                                    selected.value - url
                                } else {
                                    selected.value + url
                                }
                            }
                        )
                        HorizontalDivider(
                            color = settings.divider,
                            modifier = Modifier.padding(start = 52.dp)
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

    @Composable
    private fun ReportRow(
        name: String,
        report: SourceQualityReport,
        checked: Boolean,
        settings: AppSettingPalette,
        onToggle: () -> Unit
    ) {
        val userState = SourceQualityScorer.toUserState(report)
        val stateText = when (userState) {
            SourceQualityScorer.UserState.USABLE -> stringResource(R.string.import_check_state_usable)
            SourceQualityScorer.UserState.FAILED -> stringResource(R.string.import_check_state_failed)
            SourceQualityScorer.UserState.SUSPECT -> stringResource(R.string.import_check_state_suspect)
            SourceQualityScorer.UserState.UNTESTED -> stringResource(R.string.import_check_state_untested)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onToggle() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = settings.accent,
                        uncheckedColor = settings.disabledText,
                        checkmarkColor = settings.onAccent
                    )
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    // 源名称主文字色（真机反馈：暗色下名称黑色不可见=M3 onSurface 未跟随主题）
                    color = settings.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
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
            val reasons = mutableListOf<String>()
            reasons.add(stateText)
            report.suspectReasons.forEach { reasons.add(it.name) }
            report.dimensions.values.firstOrNull { it.state == io.legado.app.model.DimState.FAIL }?.let {
                reasons.add(it.evidence)
            }
            Text(
                text = reasons.filter { it.isNotBlank() }.joinToString(" | ").take(50),
                style = MaterialTheme.typography.bodySmall,
                color = settings.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 52.dp)
            )
        }
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
