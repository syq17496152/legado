package io.legado.app.ui.about

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.LogUtils
import io.legado.app.utils.showDialogFragment
import java.util.Date

/**
 * 应用日志（View→Compose 迁移：RecyclerView → LazyColumn，AppDialogFrame 统一弹框样式）
 */
class AppLogDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                var logs by remember { mutableStateOf(AppLog.logs) }
                var currentFilter by remember { mutableStateOf<AppLog.Level?>(null) }
                var filterMenuExpanded by remember { mutableStateOf(false) }
                val filteredLogs = remember(logs, currentFilter) {
                    if (currentFilter == null) {
                        logs
                    } else {
                        logs.filter { it.level == currentFilter }
                    }
                }
                val style = rememberAppDialogStyle()
                AppDialogFrame(
                    title = stringResource(R.string.log),
                    scrollContent = false,
                    content = {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 460.dp)
                        ) {
                            items(filteredLogs) { entry ->
                                AppLogRow(
                                    entry = entry,
                                    style = style,
                                    onClick = {
                                        entry.throwable?.let {
                                            showDialogFragment(
                                                TextDialog("Log", it.stackTraceToString())
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    },
                    actions = {
                        val palette = style.toMiuixPalette()
                        Box {
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.filter),
                                palette = palette,
                                onClick = { filterMenuExpanded = true },
                                cornerRadius = style.actionRadius
                            )
                            AppDropdownMenu(
                                expanded = filterMenuExpanded,
                                onDismiss = { filterMenuExpanded = false },
                                actions = listOf(
                                    MenuAction(
                                        icon = Icons.Filled.List,
                                        title = stringResource(R.string.filter_all),
                                        checked = currentFilter == null,
                                        onClick = {
                                            filterMenuExpanded = false
                                            currentFilter = null
                                        }
                                    ),
                                    MenuAction(
                                        icon = Icons.Filled.ErrorOutline,
                                        title = stringResource(R.string.filter_error),
                                        checked = currentFilter == AppLog.Level.ERROR,
                                        onClick = {
                                            filterMenuExpanded = false
                                            currentFilter = AppLog.Level.ERROR
                                        }
                                    ),
                                    MenuAction(
                                        icon = Icons.Filled.WarningAmber,
                                        title = stringResource(R.string.filter_warn),
                                        checked = currentFilter == AppLog.Level.WARN,
                                        onClick = {
                                            filterMenuExpanded = false
                                            currentFilter = AppLog.Level.WARN
                                        }
                                    ),
                                    MenuAction(
                                        icon = Icons.Filled.Info,
                                        title = stringResource(R.string.filter_info),
                                        checked = currentFilter == AppLog.Level.INFO,
                                        onClick = {
                                            filterMenuExpanded = false
                                            currentFilter = AppLog.Level.INFO
                                        }
                                    ),
                                    MenuAction(
                                        icon = Icons.Filled.BugReport,
                                        title = stringResource(R.string.filter_debug),
                                        checked = currentFilter == AppLog.Level.DEBUG,
                                        onClick = {
                                            filterMenuExpanded = false
                                            currentFilter = AppLog.Level.DEBUG
                                        }
                                    )
                                )
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.clear),
                            palette = palette,
                            onClick = {
                                AppLog.clear()
                                logs = emptyList()
                            },
                            cornerRadius = style.actionRadius
                        )
                    }
                )
            }
        }
    }

}

@Composable
private fun AppLogRow(
    entry: AppLog.LogEntry,
    style: AppDialogStyle,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = LogUtils.logTimeFormat.format(Date(entry.time)),
            color = style.secondaryText,
            fontSize = MaterialTheme.typography.bodySmall.fontSize
        )
        Text(
            text = formatLogMessage(entry),
            color = style.primaryText,
            fontSize = MaterialTheme.typography.bodyMedium.fontSize
        )
    }
}

private fun formatLogMessage(item: AppLog.LogEntry): String {
    val prefix = when (item.level) {
        AppLog.Level.ERROR -> "[E] "
        AppLog.Level.WARN -> "[W] "
        AppLog.Level.INFO -> "[I] "
        AppLog.Level.DEBUG -> "[D] "
    }
    return prefix + item.message
}
