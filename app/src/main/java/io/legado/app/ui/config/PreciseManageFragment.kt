package io.legado.app.ui.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.ui.book.storage.StorageManageActivity
import io.legado.app.ui.download.DownloadManageActivity
import io.legado.app.ui.file.FileManageActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.urlrecord.UrlRecordActivity
import io.legado.app.utils.startActivity

/**
 * 精准管理聚合入口（网址记录/存储管理/下载管理/文件管理）
 * L-E5 S2 改造：内容区 Compose 化（PreciseManageScreen），顶栏由 ConfigActivity 提供
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
                        onDownloadManageClick = { startActivity<DownloadManageActivity>() },
                        onFileManageClick = { startActivity<FileManageActivity>() }
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
