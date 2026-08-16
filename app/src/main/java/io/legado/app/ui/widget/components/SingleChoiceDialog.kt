package io.legado.app.ui.widget.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R

/**
 * 单选弹窗（公共组件库三期 Dialog 族）。
 *
 * 规格：ui-standards §3.4 `SingleChoiceDialog`（task 12.32，from Legado_Max SingleChoiceDialog）
 * - M3 `AlertDialog` 卡 **18dp** 圆角
 * - `LazyColumn` 包裹选项列表，整行点击
 * - 选项 `bodyLarge`；选中项 `primary` / 未选中 `onSurface`
 * - 项 ≥48dp；内容 maxHeight **70%** 屏高（LocalConfiguration）
 *
 * 受控组件：选中态由调用方 [selectedIndex] 提供，点击经 [onSelect] 回传。
 */
@Composable
fun SingleChoiceDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var currentIndex by remember(selectedIndex) { mutableStateOf(selectedIndex) }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.7f

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
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
            ) {
                itemsIndexed(options, key = { index, _ -> index }) { index, option ->
                    val selected = index == currentIndex
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = 48.dp)
                            .clickable { currentIndex = index }
                            .padding(horizontal = 16.dp)
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { currentIndex = index }
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(currentIndex) }) {
                Text(
                    text = stringResource(R.string.ok),
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
}