package io.legado.app.ui.widget.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * 编辑对话框字段模型。
 * @param label 字段标签（调用方传字符串资源）
 * @param initial 初始值
 * @param hint 占位提示（可空）
 * @param singleLine 是否单行（false 为多行文本）
 * @param isPassword 是否密码输入（单行时生效）
 */
data class EditField(
    val label: String,
    val initial: String = "",
    val hint: String? = null,
    val singleLine: Boolean = true,
    val isPassword: Boolean = false
)

/**
 * 多字段编辑对话框（L2 Dialog 族）：OutlinedTextField 列表，确认时按序回调全部字段值。
 * 字段值状态保存在调用侧（受控：仅确认时回调，不实时上抛）。
 */
@Composable
fun AppEditDialog(
    title: String,
    fields: List<EditField>,
    confirmText: String,
    cancelText: String,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val values = remember(fields) { mutableStateListOf(*fields.map { it.initial }.toTypedArray()) }
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                fields.forEachIndexed { index, field ->
                    OutlinedTextField(
                        value = values[index],
                        onValueChange = { values[index] = it },
                        label = { Text(text = field.label) },
                        placeholder = field.hint?.let { { Text(text = it) } },
                        singleLine = field.singleLine,
                        visualTransformation = if (field.isPassword) {
                            PasswordVisualTransformation()
                        } else {
                            androidx.compose.ui.text.input.VisualTransformation.None
                        },
                        keyboardOptions = if (field.isPassword) {
                            KeyboardOptions(imeAction = ImeAction.Done)
                        } else {
                            KeyboardOptions.Default
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(values.toList()) }) {
                Text(
                    text = confirmText,
                    color = MaterialTheme.colorScheme.primary
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
