package io.legado.app.ui.rss.source.debug

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.base.attachComposeContent
import io.legado.app.base.composeShell
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * debug-page-redesign：订阅源调试页宿主（UI 全量 Compose；原 AD-20 内核桥接撤销）。
 * 保留能力：intent 传源 key、弹框全文（TextDialog TEXT）、导出日志（批次E）。
 */
class RssSourceDebugActivity : VMBaseActivity<ViewBinding, RssSourceDebugModel>() {

    // M7 清壳：原 activity_rss_source_debug.xml 仅含两个 ComposeView（无 View 语义）
    override val binding: ViewBinding by lazy { composeShell(this) }
    override val viewModel by viewModels<RssSourceDebugModel>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeContent()
        viewModel.initData(intent.getStringExtra("key")) {}
    }

    /**
     * M7 清壳（2026-09-22）：原 `activity_rss_source_debug.xml` 是「ConstraintLayout +
     * compose_top_bar(wrap) + compose_host(0dp 占满剩余)」两个 ComposeView ⇒ 合并为**单一 Compose 树**
     * `Column { GlassTopAppBar(); Screen(weight(1f)) }`，与旧布局逐项等价
     * （顶栏 wrap_content；Screen 根为 `Box(modifier.fillMaxSize())` ⇒ 加 weight(1f) 即"占满剩余高度"）。
     * 换装后代码侧不再引用 R.layout，壳布局成为死资源。
     */
    private fun initComposeContent() {
        binding.root.attachComposeContent {
            LegadoTheme {
                Column {
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
                    RssSourceDebugScreen(
                        sourceName = viewModel.sourceName,
                        examples = viewModel.examples,
                        onStart = { key -> viewModel.startDebug(key) { toastOnUi("未获取到订阅源") } },
                        onCancel = { viewModel.stopDebug() },
                        onShowFull = { title, content ->
                            showDialogFragment(TextDialog(title, content, TextDialog.Mode.TEXT))
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
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
