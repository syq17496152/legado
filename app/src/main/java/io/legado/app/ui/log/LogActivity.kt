package io.legado.app.ui.log

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.FileProvider
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.databinding.ActivityLogManageBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.delete
import io.legado.app.utils.find
import io.legado.app.utils.getFile
import io.legado.app.utils.list
import io.legado.app.utils.openInputStream
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.init.appCtx
import java.io.File
import java.io.FileFilter

/**
 * 日志管理中心（log-system-upgrade AD-04）：全屏 4 Tab（应用日志/崩溃日志/文件日志/堆转储）。
 * 状态 owner = LogManageState（本 Activity 持有），Screen 纯展示 + 回调；
 * 扫描/删除/清除全部走 Coroutine.async IO 线程；查看走尾部截断（AD-05）；删除逐文件容错（AD-06）。
 */
class LogActivity : BaseActivity<ActivityLogManageBinding>() {

    companion object {
        // 跨页跳转直达指定 Tab（ordinal：0 应用/1 崩溃/2 文件/3 堆转储），如精准管理页「崩溃日志」入口传 1
        const val EXTRA_INITIAL_TAB = "initialTab"
        const val TAB_CRASH = 1

        // AD-05: 大文件查看尾部截断行数
        private const val TAIL_LINES = 500
    }

    override val binding by viewBinding(ActivityLogManageBinding::inflate)

    private val state = LogManageState()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        state.tab = LogTab.entries[intent.getIntExtra(EXTRA_INITIAL_TAB, 0)
            .coerceIn(0, LogTab.entries.lastIndex)]
        binding.composeHost.setContent {
            LegadoTheme {
                LogManageScreen(
                    state = state,
                    onBack = { finish() },
                    onRefresh = { refreshAll() },
                    onDeleteAppLogs = { deleteAppLogs(it) },
                    onDeleteFiles = { deleteFiles(it) },
                    onClearAll = { clearAll() },
                    onExport = { LogExporter.saveLog() },
                    onShareFile = { shareFile(it) },
                    onViewFile = { viewFile(it) },
                    onViewAppLog = { viewAppLog(it) },
                    onCopyAppLog = { copyAppLog(it) }
                )
            }
        }
        refreshAll()
    }

    // ===================== 数据扫描 =====================

    private fun refreshAll() {
        Coroutine.async {
            Triple(scanCrashFiles(), scanDir("logs"), scanDir("heapDump"))
        }.onSuccess { (crash, logs, heap) ->
            state.crashFiles = crash
            state.logFiles = logs
            state.heapFiles = heap
            state.appLogs = AppLog.logs
        }.onError {
            AppLog.putDebugWithTag(
                AppLog.TAG_DATA, "日志扫描失败: ${it.localizedMessage}", it, AppLog.Level.WARN
            )
        }
    }

    /** 崩溃目录扫描（对齐 CrashLogsDialog：externalCache/crash + 备份目录/crash 副本，按名去重） */
    private fun scanCrashFiles(): List<LogFileItem> {
        val list = arrayListOf<LogFileItem>()
        appCtx.externalCacheDir
            ?.getFile("crash")
            ?.listFiles(FileFilter { it.isFile })
            ?.forEach { list.add(LogFileItem(it.name, it.length(), file = it)) }
        val backupPath = AppConfig.backupPath
        if (!backupPath.isNullOrEmpty()) {
            FileDoc.fromUri(Uri.parse(backupPath), true)
                .find("crash")
                ?.list { !it.isDir }
                ?.forEach { list.add(LogFileItem(it.name, it.size, doc = it)) }
        }
        return list.sortedByDescending { it.name }.distinctBy { it.name }
    }

    private fun scanDir(name: String): List<LogFileItem> =
        appCtx.externalCacheDir
            ?.getFile(name)
            ?.listFiles()
            ?.filter { it.isFile }
            ?.map { LogFileItem(it.name, it.length(), file = it) }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    // ===================== 删除与清除 =====================

    private fun deleteAppLogs(entries: List<AppLog.LogEntry>) {
        AppLog.removeLogs(entries)
        state.selectedAppLogs = emptyList()
        state.selecting = false
        state.appLogs = AppLog.logs
    }

    private fun deleteFiles(items: List<LogFileItem>) {
        Coroutine.async {
            items.forEach { item ->
                // AD-06: 逐文件容错，占用中文件失败不中断（7 天自动清理兜底）
                kotlin.runCatching { item.file?.delete() }
                item.doc?.delete()
            }
        }.onSuccess {
            state.selectedFileKeys = emptySet()
            state.selecting = false
            refreshAll()
        }.onError {
            toastOnUi(it.localizedMessage)
        }
    }

    private fun clearAll() {
        Coroutine.async {
            AppLog.clear()
            var cleared = 0
            var skipped = 0
            fun deleteIn(dir: File?) {
                dir?.listFiles()?.forEach { file ->
                    if (kotlin.runCatching { file.delete() }.getOrDefault(false)) cleared++
                    else skipped++
                }
            }
            // logs 目录（含 .lck 锁文件）/ crash / heapDump（AD-06）
            deleteIn(appCtx.externalCacheDir?.getFile("logs"))
            deleteIn(appCtx.externalCacheDir?.getFile("crash"))
            deleteIn(appCtx.externalCacheDir?.getFile("heapDump"))
            // 备份目录 crash 副本
            AppConfig.backupPath?.let { backupPath ->
                FileDoc.fromUri(Uri.parse(backupPath), true).find("crash")?.delete()
            }
            Pair(cleared, skipped)
        }.onSuccess { (cleared, skipped) ->
            toastOnUi(getString(R.string.log_cleared, cleared, skipped))
            refreshAll()
        }.onError {
            AppLog.put("一键清除日志失败\n${it.localizedMessage}", it)
            toastOnUi(it.localizedMessage)
        }
    }

    // ===================== 查看与分享 =====================

    /** 应用日志详情：完整消息 + Throwable 堆栈 */
    private fun viewAppLog(entry: AppLog.LogEntry) {
        val text = entry.message + (entry.throwable?.let { "\n\n${it.stackTraceToString()}" } ?: "")
        showDialogFragment(TextDialog(entry.level.name, text))
    }

    /** 文件查看：尾部 TAIL_LINES 行截断（AD-05），防大文件 OOM */
    private fun viewFile(item: LogFileItem) {
        Coroutine.async {
            val stream = item.file?.inputStream()
                ?: item.doc?.openInputStream()?.getOrNull()
                ?: return@async null
            val lines = stream.bufferedReader().use { it.readLines().takeLast(TAIL_LINES) }
            val content = lines.joinToString("\n")
            if (lines.size >= TAIL_LINES) {
                getString(R.string.log_truncated, TAIL_LINES) + "\n\n" + content
            } else {
                content
            }
        }.onSuccess { content ->
            content?.let { showDialogFragment(TextDialog(item.name, it)) }
        }.onError {
            toastOnUi(it.localizedMessage)
        }
    }

    private fun shareFile(item: LogFileItem) {
        val file = item.file ?: return
        kotlin.runCatching {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileProvider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        }.onFailure {
            toastOnUi(it.localizedMessage)
        }
    }

    private fun copyAppLog(entry: AppLog.LogEntry) {
        sendToClip(entry.message)
        toastOnUi(getString(R.string.log_copy_done))
    }

}
