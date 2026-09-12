package io.legado.app.ui.book.source.debug

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.core.content.FileProvider
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivitySourceDebugBinding
import io.legado.app.model.Debug
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.TopBarActionRow
import io.legado.app.ui.theme.LegadoTheme
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
 * debug-page-redesign：书源调试页宿主（UI 全量 Compose，原 SearchView/help/recyclerView 移除）。
 * 保留能力：intent 传源 key、扫码选关键字（QrCodeResult，扫码即按搜索调试——与旧版行为一致）、
 * 弹框全文（TextDialog TEXT 模式）、导出日志（log-compliance-cleanup 批次E 成果并入顶栏菜单）。
 */
class BookSourceDebugActivity : VMBaseActivity<ActivitySourceDebugBinding, BookSourceDebugModel>() {

    override val binding by viewBinding(ActivitySourceDebugBinding::inflate)
    override val viewModel by viewModels<BookSourceDebugModel>()

    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        // 扫码结果 = 搜索关键字，直接按搜索目标启动调试（旧版 startSearch 行为对齐）
        it?.let { key -> viewModel.startDebug(key) { toastOnUi("未获取到书源") } }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initTopBar()
        initComposeHost()
        viewModel.init(intent.getStringExtra("key")) {}
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
                BookSourceDebugScreen(
                    sourceName = viewModel.sourceName,
                    examples = viewModel.examples,
                    onStart = { key -> viewModel.startDebug(key) { toastOnUi("未获取到书源") } },
                    onCancel = { viewModel.stopDebug() },
                    onShowFull = { title, content ->
                        showDialogFragment(TextDialog(title, content, TextDialog.Mode.TEXT))
                    },
                )
            }
        }
    }

    /** 导出当前调试会话日志（批次E）：复制全文 / 分享 txt 文件（FileProvider 复用 LogActivity 模式） */
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
