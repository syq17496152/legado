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
import io.legado.app.ui.book.cache.CacheManageActivity
import io.legado.app.ui.book.storage.StorageManageActivity
import io.legado.app.ui.download.DownloadManageActivity
import io.legado.app.ui.file.FileManageActivity
import io.legado.app.ui.log.LogActivity
import io.legado.app.ui.log.LogExporter
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.urlrecord.UrlRecordActivity
import io.legado.app.utils.FileDoc
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.delay
import splitties.init.appCtx

/**
 * 精准管理聚合入口（数据管理：网址记录/存储管理/缓存管理/下载管理/文件管理 + 日志与诊断：崩溃日志/保存日志/创建堆转储）
 * L-E5 S2 改造：内容区 Compose 化（PreciseManageScreen），顶栏由 ConfigActivity 提供
 * cache-entry-relocate：新增缓存管理行回调；诊断三件套自 AboutFragment 迁入
 * log-system-upgrade：saveLog/copyHeapDump 逻辑抽取至 ui/log/LogExporter（与日志管理中心双宿主复用，行为零变化）
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
                        onLogManageClick = { startActivity<LogActivity>() },
                        onCrashLogClick = {
                            // log-system-upgrade：直达日志管理中心崩溃 Tab
                            startActivity<LogActivity> {
                                putExtra(LogActivity.EXTRA_INITIAL_TAB, LogActivity.TAB_CRASH)
                            }
                        },
                        onSaveLogClick = { LogExporter.saveLog() },
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

    // ===================== 日志与诊断（saveLog 已抽取至 LogExporter；createHeapDump 因含交互时序保留本地） =====================

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
            if (!LogExporter.copyHeapDump(doc)) {
                appCtx.toastOnUi("未找到堆转储文件")
            } else {
                appCtx.toastOnUi("已保存至备份目录")
            }
        }.onError {
            AppLog.put("保存堆转储失败\n${it.localizedMessage}", it)
        }
    }
}
