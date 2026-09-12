package io.legado.app.ui.rss.source.debug

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.core.content.FileProvider
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityRssSourceDebugBinding
import io.legado.app.model.Debug
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.TopBarActionRow
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * debug-page-redesign：订阅源调试页宿主（UI 全量 Compose；原 AD-20 内核桥接撤销）。
 * 保留能力：intent 传源 key、弹框全文（TextDialog TEXT）、导出日志（批次E）。
 */
class RssSourceDebugActivity : VMBaseActivity<ActivityRssSourceDebugBinding, RssSourceDebugModel>() {

    override val binding by viewBinding(ActivityRssSourceDebugBinding::inflate)
    override val viewModel by viewModels<RssSourceDebugModel>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initTopBar()
        initComposeHost()
        viewModel.initData(intent.getStringExtra("key")) {}
    }

    private fun initTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                GlassTopAppBar(
                    title = getString(R.string.debug_source),
                    navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                    onNavClick = { finish() },
                    actions = {
                        TopBarActionRow(
                            listOf(
                                MenuAction(title = "清空日志") { viewModel.clearLogs() },
                                MenuAction(title = getString(R.string.log_export_logs)) { exportDebugLog() },
                            )
                        )
                    },
                )
            }
        }
    }

    private fun initComposeHost() {
        binding.composeHost.setContent {
            LegadoTheme {
                RssSourceDebugScreen(
                    sourceName = viewModel.sourceName,
                    examples = viewModel.examples,
                    onStart = { key -> viewModel.startDebug(key) { toastOnUi("未获取到订阅源") } },
                    onCancel = { viewModel.stopDebug() },
                    onShowFull = { title, content ->
                        showDialogFragment(TextDialog(title, content, TextDialog.Mode.TEXT))
                    },
                )
            }
        }
    }

    /** 导出当前调试会话日志（批次E）：复制全文 / 分享 txt 文件（FileProvider） */
    private fun exportDebugLog() {
        val logs = Debug.getSessionLogs()
        if (logs.isBlank()) {
            toastOnUi("暂无调试日志")
            return
        }
        showComposeChoiceListDialog(
            title = getString(R.string.log_export_logs),
            labels = listOf("复制到剪贴板", "分享 txt 文件")
        ) { index ->
            when (index) {
                0 -> sendToClip(logs)
                1 -> shareDebugLog(logs)
            }
        }
    }

    private fun shareDebugLog(content: String) {
        kotlin.runCatching {
            val fileName = "sourceDebug_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt"
            val file = File(cacheDir, fileName)
            file.writeText(content)
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileProvider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.log_export_logs)))
        }.onFailure {
            toastOnUi(it.localizedMessage)
        }
    }
}
