package io.legado.app.ui.autoTask

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.model.AutoTask
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.LogUtils
import java.util.Date
import androidx.compose.material3.MaterialTheme

/**
 * 自动任务最近一次运行日志弹框（Compose 化）。
 * 原 View 版继承 BaseDialogFragment + dialog_recycler_view + app_log 菜单；
 * 迁移后继承 [ComposeDialogFragment]，复用 [AppDialogFrame] 统一主题/墨水屏/圆角/字体管理。
 * 简化说明：原 item 的 autoLink="web"（URL 可点击）在 Compose 无内置等价实现，
 * 改为 [SelectionContainer] 保持文本可选中复制；app_log 菜单保留给 AppLogDialog 共用。
 */
class AutoTaskLogDialog() : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Confirm

    constructor(taskId: String, taskName: String) : this() {
        arguments = Bundle().apply {
            putString("taskId", taskId)
            putString("taskName", taskName)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val taskId = arguments?.getString("taskId").orEmpty()
                val taskName = arguments?.getString("taskName").orEmpty()
                var logData by remember { mutableStateOf(queryLogData(taskId)) }
                val style = rememberAppDialogStyle()
                AppDialogFrame(
                    title = taskName.ifBlank { stringResource(R.string.log) },
                    content = {
                        Text(
                            text = LogUtils.logTimeFormat.format(Date(logData.first)),
                            color = style.secondaryText,
                            fontSize = MaterialTheme.typography.bodySmall.fontSize
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SelectionContainer {
                            Text(
                                text = logData.second,
                                color = style.primaryText,
                                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                                lineHeight = 20.sp
                            )
                        }
                    },
                    actions = {
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.clear),
                            palette = style.toMiuixPalette(),
                            onClick = {
                                AutoTask.update(taskId) {
                                    it.copy(lastLog = null, lastError = null, lastResult = null)
                                }
                                logData = System.currentTimeMillis() to
                                    getString(R.string.auto_task_not_run)
                            },
                            cornerRadius = style.actionRadius
                        )
                    }
                )
            }
        }
    }

    private fun queryLogData(taskId: String): Pair<Long, String> {
        val task = AutoTask.getRules().firstOrNull { it.id == taskId }
        val lastRunAt = task?.lastRunAt ?: 0L
        val message = task?.lastLog
            ?: task?.lastError
            ?: task?.lastResult
            ?: getString(R.string.auto_task_not_run)
        val time = if (lastRunAt > 0L) lastRunAt else System.currentTimeMillis()
        return time to message
    }
}
