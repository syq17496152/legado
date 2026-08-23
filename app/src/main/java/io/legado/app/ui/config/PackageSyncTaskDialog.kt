package io.legado.app.ui.config

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.lib.dialogs.AndroidAlertBuilder
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.book.cache.WebDavTaskManager
import io.legado.app.ui.book.cache.WebDavTaskState
import io.legado.app.ui.book.cache.WebDavTaskStatus
import io.legado.app.ui.book.cache.WebDavTaskType
import io.legado.app.utils.applyTint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

fun AppCompatActivity.showPackageSyncTaskDialog(types: Set<WebDavTaskType>) {
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(4.dp(this@showPackageSyncTaskDialog), 4.dp(this@showPackageSyncTaskDialog), 4.dp(this@showPackageSyncTaskDialog), 4.dp(this@showPackageSyncTaskDialog))
    }
    val scrollView = ScrollView(this).apply {
        addView(
            content,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
    }
    val dialog = AndroidAlertBuilder(this).apply {
        setTitle(R.string.package_sync_task_title)
        customView { scrollView }
        positiveButton(android.R.string.ok, null)
    }.show().applyTint()
    var job: Job? = lifecycleScope.launch {
        WebDavTaskManager.states.collectLatest { states ->
            val tasks = states.values
                .filter { it.type in types }
                .sortedWith(compareBy<WebDavTaskState> { it.status.sortOrder() }.thenBy { it.bookName })
            content.renderPackageSyncTasks(tasks)
        }
    }
    dialog.setOnDismissListener {
        job?.cancel()
        job = null
    }
}

private fun LinearLayout.renderPackageSyncTasks(tasks: List<WebDavTaskState>) {
    removeAllViews()
    if (tasks.isEmpty()) {
        addView(emptyTaskView(context))
        return
    }
    tasks.forEach { task ->
        addView(taskRow(context, task))
    }
}

private fun emptyTaskView(context: Context): View {
    return TextView(context).apply {
        text = context.getString(R.string.package_sync_task_empty)
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(context.secondaryTextColor)
        typeface = context.uiTypeface()
        setPadding(12.dp(context), 24.dp(context), 12.dp(context), 24.dp(context))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}

private fun taskRow(context: Context, task: WebDavTaskState): View {
    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(14.dp(context), 12.dp(context), 14.dp(context), 12.dp(context))
        background = UiCorner.opaqueRounded(
            context.themeCardColorOrDefault(),
            UiCorner.actionRadius(context)
        )
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 8.dp(context)
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = task.bookName
                textSize = 15f
                setTextColor(context.primaryTextColor)
                typeface = context.uiTypeface()
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = context.getString(task.type.titleRes())
                textSize = 12f
                setTextColor(context.accentColor)
                typeface = context.uiTypeface()
                background = UiCorner.opaqueRounded(
                    context.accentColor.withAlpha(24),
                    UiCorner.actionRadius(context)
                )
                setPadding(8.dp(context), 3.dp(context), 8.dp(context), 3.dp(context))
            })
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 7.dp(context), 0, 0)
            if (task.active) {
                addView(ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
                    isIndeterminate = true
                    layoutParams = LinearLayout.LayoutParams(22.dp(context), 22.dp(context)).apply {
                        marginEnd = 6.dp(context)
                    }
                })
            }
            addView(TextView(context).apply {
                text = context.getString(R.string.package_sync_task_line, context.getString(task.status.titleRes()), task.message)
                textSize = 13f
                setTextColor(task.status.textColor(context))
                typeface = context.uiTypeface()
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        })
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

private fun WebDavTaskStatus.textColor(context: Context): Int {
    return when (this) {
        WebDavTaskStatus.FAILED -> Color.rgb(196, 54, 54)
        WebDavTaskStatus.RUNNING -> context.accentColor
        else -> context.secondaryTextColor
    }
}

private fun Int.withAlpha(alpha: Int): Int {
    return Color.argb(alpha.coerceIn(0, 255), Color.red(this), Color.green(this), Color.blue(this))
}

private fun Int.dp(context: Context): Int = (this * context.resources.displayMetrics.density).toInt()
