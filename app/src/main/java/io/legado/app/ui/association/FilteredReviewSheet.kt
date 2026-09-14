package io.legado.app.ui.association

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.widget.components.AppModalBottomSheet

/**
 * 过滤复核弹层（import-source-quality-filter P3/P8）：
 * 被过滤源列表（勾选）+ 导入勾选的源 / 导入并禁用 / 复制列表兜底；
 * 全选恢复由宿主触发二次确认（onRestoreAll）。
 * 书源/订阅源两条导入链路共用（ImportBookSourceDialog / ImportRssSourceDialog）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilteredReviewSheet(
    outcome: ImportCheckOutcome,
    onRestore: (List<Int>, Boolean) -> Unit,
    onRestoreAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val flags = remember(outcome) { mutableStateOf(List(outcome.filtered.size) { false }) }
    val allSelected = flags.value.isNotEmpty() && flags.value.all { it }

    AppModalBottomSheet(onDismiss = onDismiss) {
        Text(
            text = stringResource(
                R.string.import_check_filtered_summary, outcome.imported, outcome.filtered.size
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
        ) {
            itemsIndexed(outcome.filtered) { index, item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            flags.value = flags.value.toMutableList().also { it[index] = !it[index] }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Checkbox(checked = flags.value[index], onCheckedChange = { checked ->
                        flags.value = flags.value.toMutableList().also { it[index] = checked }
                    })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${item.reason} · ${item.score}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            OutlinedButton(
                onClick = {
                    // 复制被过滤源列表兜底（P3）
                    val text = outcome.filtered.joinToString("\n") { "${it.name} | ${it.reason}" }
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("filtered", text))
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .height(48.dp)
                    .fillMaxWidth()
            ) {
                Text(stringResource(R.string.import_check_copy_filtered))
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            val selected = flags.value.withIndex().filter { it.value }.map { outcome.filtered[it.index].index }
            Button(
                onClick = {
                    if (allSelected) {
                        // 全选恢复触发二次确认（P3，由宿主处理）
                        onRestoreAll()
                    } else if (selected.isNotEmpty()) {
                        onRestore(selected, false)
                    }
                },
                shape = RoundedCornerShape(12.dp),
                enabled = selected.isNotEmpty(),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .weight(1f)
            ) {
                Text(stringResource(R.string.import_check_restore_all), maxLines = 1)
            }
            OutlinedButton(
                onClick = { if (selected.isNotEmpty()) onRestore(selected, true) },
                shape = RoundedCornerShape(12.dp),
                enabled = selected.isNotEmpty(),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .weight(1f)
            ) {
                Text(stringResource(R.string.import_check_import_disabled), maxLines = 1)
            }
        }
    }
}
