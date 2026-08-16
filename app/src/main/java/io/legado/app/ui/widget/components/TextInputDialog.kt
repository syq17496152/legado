package io.legado.app.ui.widget.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.legado.app.R

/**
 * 单行文本输入弹窗（公共组件库三期 Dialog 族）。
 *
 * 规格：ui-standards §3.4 `TextInputDialog`（task 12.32，from Legado_Max TextInputDialog）
 * - M3 `AlertDialog` 卡 **18dp** 圆角（M3 默认）
 * - title `titleLarge` / `OutlinedTextField` label `bodyMedium` / 输入框 `surfaceVariant`
 * - 确定/取消 `TextButton` ≥48dp
 * - 自动请求焦点，Enter 确认
 *
 * @param title 弹窗标题
 * @param initial 初始文本
 * @param label 输入框标签（可空，默认不显示）
 * @param confirmText 确认按钮文字
 * @param onConfirm 确认回调（返回输入文本）
 * @param onDismiss 取消/关闭回调
 */
@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    label: String? = null,
    confirmText: String = stringResource(R.string.ok),
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = label?.let { { Text(text = it, style = MaterialTheme.typography.bodyMedium) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(text.trim()) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }) {
                Text(
                    text = confirmText,
                    color = MaterialTheme.colorScheme.primary,
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

    // 自动请求焦点
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}