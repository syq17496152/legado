package io.legado.app.ui.widget.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 数字选择对话框（L2 Dialog 族）：Slider + 数值输入框，确认时回调合法范围内的整数。
 * 输入框允许自由键入；确认时钳制到 [range] 内（越界自动取最近端点）。
 */
@Composable
fun AppNumberPickerDialog(
    title: String,
    value: Int,
    range: IntRange,
    confirmText: String,
    cancelText: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    neutralText: String? = null,
    onNeutral: (() -> Unit)? = null
) {
    val min = range.first.toFloat()
    val max = range.last.toFloat()
    var sliderValue by rememberSaveable { mutableFloatStateOf(value.coerceIn(range).toFloat()) }
    var textValue by rememberSaveable { mutableStateOf(value.toString()) }
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Slider(
                    value = sliderValue,
                    onValueChange = {
                        sliderValue = it
                        textValue = it.toInt().toString()
                    },
                    valueRange = min..max,
                    steps = if (max - min > 1) (max - min - 1).toInt() else 0,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it.filter { c -> c.isDigit() || c == '-' } },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                )
                Text(
                    text = "${min.toInt()} - ${max.toInt()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = textValue.toIntOrNull()
                val result = when {
                    parsed == null -> sliderValue.toInt().coerceIn(range)
                    parsed < range.first -> range.first
                    parsed > range.last -> range.last
                    else -> parsed
                }
                onConfirm(result)
            }) {
                Text(
                    text = confirmText,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (neutralText != null && onNeutral != null) {
                    TextButton(onClick = onNeutral) {
                        Text(
                            text = neutralText,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(text = cancelText)
                }
            }
        }
    )
}
