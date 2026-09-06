package io.legado.app.ui.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.ui.book.cache.CacheManageActivity
import io.legado.app.ui.book.storage.StorageManageActivity
import io.legado.app.ui.download.DownloadManageActivity
import io.legado.app.ui.file.FileManageActivity
import io.legado.app.ui.log.LogActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.urlrecord.UrlRecordActivity
import io.legado.app.utils.startActivity

/**
 * 精准管理聚合入口（数据管理：网址记录/存储管理/缓存管理/下载管理/文件管理 + 日志与诊断：日志管理）
 * L-E5 S2 改造：内容区 Compose 化（PreciseManageScreen），顶栏由 ConfigActivity 提供
 * cache-entry-relocate：新增缓存管理行回调；诊断三件套（曾自 AboutFragment 迁入，现已收口至日志管理页）
 * log-system-upgrade：saveLog/copyHeapDump 逻辑抽取至 ui/log/LogExporter；
 * 用户反馈收口——崩溃日志/保存日志/创建堆转储菜单移除，功能收口至日志管理页右上角溢出菜单
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
                        onLogManageClick = { startActivity<LogActivity>() }
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.precise_manage)
    }
}
