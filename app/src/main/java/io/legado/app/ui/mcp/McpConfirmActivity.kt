package io.legado.app.ui.mcp

import android.os.Bundle
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.theme.initLegadoComposeTheme
import io.legado.app.ui.theme.setLegadoContent
import io.legado.app.web.mcp.McpConfirmGate

/**
 * MCP 端侧确认宿主（Web/MCP 产品化二期 · REQ-2-501）。
 *
 * 仅负责把 [McpConfirmGate] 的待确认请求（title + message）展示到手机上，由人物理确认：
 * 点「确认」回传 true，点「拒绝」/返回键/取消回传 false，随后结束自身。
 *
 * 视觉沿用钉住的确认对话框族规格（titleLarge 标题 / bodyMedium 正文 / primary 确认钮 /
 * 按钮 >=48dp 触控高度，见 ui/widget/components/AppConfirmDialog）；因闸门文案来自外部
 * 请求、可能较长，正文额外加了滚动与最大高度约束（同 AppTextDialog 的 480dp 上限）。
 * 颜色一律走 MaterialTheme.colorScheme（主题 token），无硬编码色值。
 *
 * ⚠️ 注册说明：需在 main manifest 声明 `<activity android:name=".ui.mcp.McpConfirmActivity"
 * android:exported="false" />`（仅由 [McpConfirmGate] 以 FLAG_ACTIVITY_NEW_TASK 内部拉起）。
 */
class McpConfirmActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        initLegadoComposeTheme()
        super.onCreate(savedInstanceState)
        // 返回键兜底：视为拒绝，避免闸门挂起到超时
        onBackPressedDispatcher.addCallback(this) { reject() }

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()

        setLegadoContent {
            McpConfirmDialog(
                title = title,
                message = message,
                onConfirm = {
                    McpConfirmGate.resolve(true)
                    finish()
                },
                onDismiss = { reject() },
            )
        }
    }

    /** 拒绝路径统一出口（拒绝按钮 / 返回键 / 点外部取消）。 */
    private fun reject() {
        McpConfirmGate.resolve(false)
        finish()
    }

    companion object {
        const val EXTRA_TITLE = "mcp_confirm_title"
        const val EXTRA_MESSAGE = "mcp_confirm_message"
    }
}

/**
 * 端侧确认对话框：复用确认对话框族的视觉规格，正文支持多行滚动。
 */
@Composable
private fun McpConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.confirm),
                    color = MaterialTheme.colorScheme.primary,
                    // 保证按钮 >=48dp 触控高度
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.cancel),
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                )
            }
        },
    )
}