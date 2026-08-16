package io.legado.app.ui.urlrecord

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.ConfirmDialog
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsSearchBar
import io.legado.app.ui.widget.components.ShelfListSkeleton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UrlRecordDisplayItem(
    val id: Long,
    val url: String,
    val domain: String,
    val method: String,
    val sourceName: String?,
    val timestamp: Long,
    val responseCode: Int,
    val duration: Long,
    val errorMsg: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UrlRecordScreen(
    items: List<UrlRecordDisplayItem>,
    isLoading: Boolean,
    recordEnabled: Boolean,
    searchKey: String,
    onSearchChange: (String) -> Unit,
    onToggleRecord: (Boolean) -> Unit,
    onFilterClick: () -> Unit,
    onClear7d: () -> Unit,
    onClear30d: () -> Unit,
    onClearAll: () -> Unit,
    onItemClick: (UrlRecordDisplayItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var moreMenuVisible by remember { mutableStateOf(false) }
    var clearType by remember { mutableStateOf<ClearType?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        GlassTopAppBar(
            title = stringResource(R.string.url_record),
            navIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onNavClick = onBack,
            actions = {
                Box {
                    IconButton(onClick = { moreMenuVisible = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    AppDropdownMenu(
                        expanded = moreMenuVisible,
                        onDismiss = { moreMenuVisible = false },
                        actions = listOf(
                            MenuAction(
                                icon = Icons.Default.History,
                                title = stringResource(R.string.record_url_switch),
                                checked = recordEnabled,
                                onClick = {
                                    moreMenuVisible = false
                                    onToggleRecord(!recordEnabled)
                                }
                            ),
                            MenuAction(
                                icon = Icons.Default.FilterList,
                                title = stringResource(R.string.url_record_filter),
                                onClick = {
                                    moreMenuVisible = false
                                    onFilterClick()
                                }
                            ),
                            MenuAction(
                                icon = Icons.Default.DeleteSweep,
                                title = stringResource(R.string.clear_7_days_ago),
                                onClick = {
                                    moreMenuVisible = false
                                    clearType = ClearType.SEVEN_DAYS
                                }
                            ),
                            MenuAction(
                                icon = Icons.Default.DeleteSweep,
                                title = stringResource(R.string.clear_30_days_ago),
                                onClick = {
                                    moreMenuVisible = false
                                    clearType = ClearType.THIRTY_DAYS
                                }
                            ),
                            MenuAction(
                                icon = Icons.Default.DeleteForever,
                                title = stringResource(R.string.clear_all_records),
                                onClick = {
                                    moreMenuVisible = false
                                    clearType = ClearType.ALL
                                }
                            )
                        )
                    )
                }
            }
        )

        SettingsSearchBar(
            query = searchKey,
            onQueryChange = onSearchChange,
            placeholder = stringResource(R.string.search)
        )

        when {
            isLoading && items.isEmpty() -> ShelfListSkeleton(compact = true)
            items.isEmpty() -> EmptyStatePlaceholder(
                icon = Icons.Default.History,
                title = stringResource(R.string.url_record_empty),
                modifier = Modifier.fillMaxSize()
            )
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                    UrlRecordItemRow(
                        item = item,
                        onClick = { onItemClick(item) }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        thickness = 0.5.dp
                    )
                }
            }
        }
    }

    // 清除记录确认
    clearType?.let { type ->
        ConfirmDialog(
            title = stringResource(R.string.clear),
            text = stringResource(R.string.sure_del),
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            destructive = true,
            onConfirm = {
                clearType = null
                when (type) {
                    ClearType.SEVEN_DAYS -> onClear7d()
                    ClearType.THIRTY_DAYS -> onClear30d()
                    ClearType.ALL -> onClearAll()
                }
            },
            onDismiss = { clearType = null }
        )
    }
}

private enum class ClearType { SEVEN_DAYS, THIRTY_DAYS, ALL }

@Composable
private fun UrlRecordItemRow(
    item: UrlRecordDisplayItem,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.method,
                style = MaterialTheme.typography.labelSmall,
                color = methodColor(item.method),
                modifier = Modifier.padding(3.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = statusText(item),
                style = MaterialTheme.typography.labelSmall,
                color = statusColor(item),
                modifier = Modifier.padding(3.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${item.duration}ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(3.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = formatTime(item.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(3.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = item.url,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.domain,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            item.sourceName?.takeIf { it.isNotBlank() }?.let { sourceName ->
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = sourceName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun statusText(item: UrlRecordDisplayItem): String =
    if (item.errorMsg.isNullOrBlank()) "${item.responseCode}" else stringResource(R.string.url_record_error)

@Composable
private fun methodColor(method: String): Color = when (method) {
    // 方法协议色（原 GET 蓝 0xFF1E88E5/POST 紫 0xFF8E24AA/PUT 橙 0xFFF57C00/DELETE 红 0xFFE53935）
    // 无 M3 语义槽位可精确映射，统一收敛为 tertiary（方法语义色），登记豁免见 audit-v10-consistency.md §3.3
    "GET" -> MaterialTheme.colorScheme.tertiary
    "POST" -> MaterialTheme.colorScheme.tertiary
    "PUT" -> MaterialTheme.colorScheme.tertiary
    "DELETE" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun statusColor(item: UrlRecordDisplayItem): Color = when {
    // 语义状态色：2xx 成功→primary，4xx 警告→tertiary，其他错误→error（M3 语义色收敛，原 0xFF43A047/0xFFFB8C00/0xFFE53935）
    item.errorMsg.isNullOrBlank() && item.responseCode in 200..299 -> MaterialTheme.colorScheme.primary
    item.errorMsg.isNullOrBlank() && item.responseCode in 400..499 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

@Composable
private fun formatTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    if (diff < 48 * 60 * 60 * 1000) {
        return when {
            diff < 60 * 1000 -> stringResource(R.string.just_now)
            diff < 60 * 60 * 1000 -> stringResource(R.string.minutes_ago, diff / (60 * 1000))
            diff < 24 * 60 * 60 * 1000 -> stringResource(R.string.hours_ago, diff / (60 * 60 * 1000))
            else -> stringResource(R.string.days_ago, diff / (24 * 60 * 60 * 1000))
        }
    }
    val dateStr = remember(timestamp) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
    return dateStr
}
