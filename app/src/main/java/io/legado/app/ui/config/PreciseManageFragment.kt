package io.legado.app.ui.config

import android.os.Bundle
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.CrashHandler
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.ui.about.CrashLogsDialog
import io.legado.app.ui.book.cache.CacheManageActivity
import io.legado.app.ui.book.storage.StorageManageActivity
import io.legado.app.ui.download.DownloadManageActivity
import io.legado.app.ui.file.FileManageActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.urlrecord.UrlRecordActivity
import io.legado.app.utils.FileDoc
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.delete
import io.legado.app.utils.externalCache
import io.legado.app.utils.find
import io.legado.app.utils.list
import io.legado.app.utils.openInputStream
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.delay
import splitties.init.appCtx
import java.io.File

/**
 * 精准管理聚合入口（数据管理：网址记录/存储管理/缓存管理/下载管理/文件管理 + 日志与诊断：崩溃日志/保存日志/创建堆转储）
 * L-E5 S2 改造：内容区 Compose 化（PreciseManageScreen），顶栏由 ConfigActivity 提供
 * cache-entry-relocate：新增缓存管理行回调；诊断三件套自 AboutFragment 逐字节平移（行为零变化）
 */
class PreciseManageFragment : Fragment() {

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                LegadoTheme {
                    PreciseManageScreen(
                        onUrlRecordClick = { startActivity<UrlRecordActivity>() },
                        onStorageManageClick = { startActivity<StorageManageActivity>() },
                        onCacheManageClick = { startActivity<CacheManageActivity>() },
                        onDownloadManageClick = { startActivity<DownloadManageActivity>() },
                        onFileManageClick = { startActivity<FileManageActivity>() },
                        onCrashLogClick = { showDialogFragment<CrashLogsDialog>() },
                        onSaveLogClick = { saveLog() },
                        onCreateHeapDumpClick = { createHeapDump() }
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.precise_manage)
    }

    // ===================== 日志与诊断（平移自 AboutFragment，逐字节一致） =====================

    private fun saveLog() {
        Coroutine.async {
            val backupPath = AppConfig.backupPath ?: let {
                appCtx.toastOnUi("未设置备份目录")
                return@async
            }
            if (!AppConfig.recordLog) {
                appCtx.toastOnUi("未开启日志记录，请去其他设置里打开记录日志")
                delay(3000)
            }
            val doc = FileDoc.fromUri(Uri.parse(backupPath), true)
            copyLogs(doc)
            copyHeapDump(doc)
            appCtx.toastOnUi("已保存至备份目录")
        }.onError {
            AppLog.put("保存日志出错\n${it.localizedMessage}", it, true)
        }
    }

    private fun createHeapDump() {
        Coroutine.async {
            val backupPath = AppConfig.backupPath ?: let {
                appCtx.toastOnUi("未设置备份目录")
                return@async
            }
            if (!AppConfig.recordHeapDump) {
                appCtx.toastOnUi("未开启堆转储记录，请去其他设置里打开记录堆转储")
                delay(3000)
            }
            appCtx.toastOnUi("开始创建堆转储")
            System.gc()
            CrashHandler.doHeapDump(true)
            val doc = FileDoc.fromUri(Uri.parse(backupPath), true)
            if (!copyHeapDump(doc)) {
                appCtx.toastOnUi("未找到堆转储文件")
            } else {
                appCtx.toastOnUi("已保存至备份目录")
            }
        }.onError {
            AppLog.put("保存堆转储失败\n${it.localizedMessage}", it)
        }
    }

    private fun copyLogs(doc: FileDoc) {
        val cacheDir = appCtx.externalCache
        val logFiles = File(cacheDir, "logs")
        val crashFiles = File(cacheDir, "crash")
        val logcatFile = File(cacheDir, "logcat.txt")

        dumpLogcat(logcatFile)

        val zipFile = File(cacheDir, "logs.zip")
        ZipUtils.zipFiles(arrayListOf(logFiles, crashFiles, logcatFile), zipFile)

        doc.find("logs.zip")?.delete()

        zipFile.inputStream().use { input ->
            doc.createFileIfNotExist("logs.zip").openOutputStream().getOrNull()
                ?.use {
                    input.copyTo(it)
                }
        }
        zipFile.delete()
    }

    private fun copyHeapDump(doc: FileDoc): Boolean {
        val heapFile = FileDoc.fromFile(File(appCtx.externalCache, "heapDump")).list()
            ?.firstOrNull() ?: return false
        doc.find("heapDump")?.delete()
        val heapDumpDoc = doc.createFolderIfNotExist("heapDump")
        heapFile.openInputStream().getOrNull()?.use { input ->
            heapDumpDoc.createFileIfNotExist(heapFile.name).openOutputStream().getOrNull()
                ?.use {
                    input.copyTo(it)
                }
        }
        return true
    }

    private fun dumpLogcat(file: File) {
        try {
            val process = Runtime.getRuntime().exec("logcat -d")
            file.outputStream().use {
                process.inputStream.copyTo(it)
            }
        } catch (e: Exception) {
            AppLog.put("保存Logcat失败\n$e", e)
        }
    }
}
