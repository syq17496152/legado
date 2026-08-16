package io.legado.app.ui.widget.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 单选选项模型。
 * @param label 显示文本（调用方传字符串资源）
 * @param value 实际值
 */
data class SelectOption(
    val label: String,
    val value: String
)

/**
 * 单选对话框（L2 Dialog 族）：RadioButton 列表，选中后确认回调该选项。
 * 默认选中由 [selected]（value）指定；当前选项高亮。
 */
@Composable
fun AppSelectDialog(
    title: String,
    options: List<SelectOption>,
    selected: String? = null,
    confirmText: String,
    cancelText: String,
    onSelect: (SelectOption) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var current by remember(selected) { mutableStateOf(selected) }
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = current == option.value,
                                onClick = { current = option.value }
                            )
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = current == option.value,
                            onClick = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = option.label,
                            color = if (current == option.value) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                options.find { it.value == current }?.let { onSelect(it) }
            }) {
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
