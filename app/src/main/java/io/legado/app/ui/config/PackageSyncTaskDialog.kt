package io.legado.app.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import io.legado.app.R
import io.legado.app.ui.book.cache.WebDavTaskManager
import io.legado.app.ui.book.cache.WebDavTaskState
import io.legado.app.ui.book.cache.WebDavTaskStatus
import io.legado.app.ui.book.cache.WebDavTaskType
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.showDialogFragment

/**
 * 云端同步任务进度弹框（my-compose-full W6.3：原 AndroidAlertBuilder+编程式 View 列表
 * 重写为 ComposeDialogFragment 基线，状态经 WebDavTaskManager.states collectAsState 定向刷新）。
 */
fun AppCompatActivity.showPackageSyncTaskDialog(types: Set<WebDavTaskType>) {
    val dialog = PackageSyncTaskDialog()
    dialog.arguments = Bundle().apply {
        putSerializable(ARG_TYPES, ArrayList(types))
    }
    showDialogFragment(dialog)
}

private const val ARG_TYPES = "types"

class PackageSyncTaskDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    @Suppress("UNCHECKED_CAST")
    private fun taskTypes(): Set<WebDavTaskType> {
        val list = arguments?.getSerializable(ARG_TYPES) as? ArrayList<WebDavTaskType>
        return list?.toSet() ?: emptySet()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val types = taskTypes()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                val states by WebDavTaskManager.states.collectAsState()
                val tasks = states.values
                    .filter { it.type in types }
                    .sortedWith(compareBy<WebDavTaskState> { it.status.sortOrder() }.thenBy { it.bookName })
                AppDialogFrame(
                    title = stringResource(R.string.package_sync_task_title),
                    content = {
                        if (tasks.isEmpty()) {
                            Text(
                                text = stringResource(R.string.package_sync_task_empty),
                                color = style.secondaryText,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp)
                            )
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(tasks, key = { "${it.type}|${it.bookName}" }) { task ->
                                    PackageSyncTaskRow(task = task, style = style)
                                }
                            }
                        }
                    },
                    actions = {
                        val palette = style.toMiuixPalette()
                        LegadoMiuixActionButton(
                            text = stringResource(android.R.string.ok),
                            palette = palette,
                            onClick = { dismissAllowingStateLoss() }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun PackageSyncTaskRow(
    task: WebDavTaskState,
    style: io.legado.app.ui.widget.compose.AppDialogStyle
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(style.fieldSurface)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = task.bookName,
                color = style.primaryText,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(task.type.titleRes()),
                color = style.accent,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(style.accent.copy(alpha = 0.1f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 7.dp)
        ) {
            if (task.active) {
                // 进行中任务复用 View 进度圈（22dp 小尺寸下与原视觉一致）
                AndroidView(
                    factory = { ctx ->
                        ProgressBar(ctx, null, android.R.attr.progressBarStyleSmall).apply {
                            isIndeterminate = true
                        }
                    },
                    modifier = Modifier
                        .size(22.dp)
                        .padding(end = 6.dp)
                )
            }
            Text(
                text = stringResource(
                    R.string.package_sync_task_line,
                    stringResource(task.status.titleRes()),
                    task.message
                ),
                color = task.status.textColor(style),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun WebDavTaskType.titleRes(): Int {
    return when (this) {
        WebDavTaskType.THEME_PACKAGE_UPLOAD -> R.string.package_sync_task_type_theme
        WebDavTaskType.TOP_BAR_PACKAGE_UPLOAD -> R.string.package_sync_task_type_top_bar
        WebDavTaskType.NAVIGATION_BAR_PACKAGE_UPLOAD -> R.string.package_sync_task_type_navigation_bar
        WebDavTaskType.BUBBLE_PACKAGE_UPLOAD -> R.string.package_sync_task_type_bubble
        WebDavTaskType.CACHE_UPLOAD -> R.string.package_sync_task_type_cache_upload
        WebDavTaskType.CACHE_DOWNLOAD -> R.string.package_sync_task_type_cache_download
    }
}

private fun WebDavTaskStatus.titleRes(): Int {
    return when (this) {
        WebDavTaskStatus.PENDING -> R.string.package_sync_task_status_pending
        WebDavTaskStatus.RUNNING -> R.string.package_sync_task_status_running
        WebDavTaskStatus.COMPLETED -> R.string.package_sync_task_status_completed
        WebDavTaskStatus.CANCELLED -> R.string.package_sync_task_status_cancelled
        WebDavTaskStatus.FAILED -> R.string.package_sync_task_status_failed
    }
}

private fun WebDavTaskStatus.sortOrder(): Int {
    return when (this) {
        WebDavTaskStatus.RUNNING -> 0
        WebDavTaskStatus.PENDING -> 1
        WebDavTaskStatus.FAILED -> 2
        WebDavTaskStatus.CANCELLED -> 3
        WebDavTaskStatus.COMPLETED -> 4
    }
}

private fun WebDavTaskStatus.textColor(style: io.legado.app.ui.widget.compose.AppDialogStyle): Color {
    return when (this) {
        WebDavTaskStatus.FAILED -> Color(0xFFC43636)
        WebDavTaskStatus.RUNNING -> style.accent
        else -> style.secondaryText
    }
}
