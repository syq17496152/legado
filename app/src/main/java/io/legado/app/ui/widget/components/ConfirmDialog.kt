package io.legado.app.ui.widget.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import io.legado.app.ui.widget.compose.AppUiTokens

/**
 * 确认对话框（L2 Dialog 族）。
 * - 文案全部由调用方以字符串资源传入（§6.1 禁硬编码中文）
 * - destructive = true 时确认按钮用 danger 色（危险语义色单源 #D44848，AD-14）
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String? = null,
    confirmText: String,
    cancelText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    modifier: Modifier = Modifier
) {
    // 弹层取色单源（AD-06）：文字/强调色走 AppDialogStyle 直色（ThemeStore 链），禁 M3 派生色
    val style = AppUiTokens.dialogStyle()
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        text = text?.let {
            {
                Text(
                    text = it,
                    textAlign = TextAlign.Start,
                    color = style.secondaryText
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmText,
                    color = if (destructive) {
                        style.danger
                    } else {
                        style.accent
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = cancelText)
            }
        }
    )
}
