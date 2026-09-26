package io.legado.app.ui.urlrecord

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.widget.components.ConfirmDialog
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.ShelfListSkeleton
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementLazyColumn
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
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

/**
 * URL 访问记录页（S2 列表管理页，位于「我的 → 精准管理」子树）。
 *
 * **2026-09-26 行/壳/容器「三件套」收敛（用户裁定：我的子页/子子页必须同脚手架同行）**：
 * ①壳层由页内自绘 `GlassTopAppBar` + `SettingsSearchBar` + 私有溢出菜单 → `AppManagementScaffold`
 * （搜索槽 / 顶栏动作分级 / 返回位全部由壳单源承载，与本族其余管理页一致）；
 * ②列表容器 `LazyColumn` + 行间自绘 0.5dp 分隔线 → `AppManagementLazyColumn`
 * （项间距 8dp + 快速滚动条 + 导航栏内边距；**行间分隔线清零**，与书源管理一致）；
 * ③行由自绘裸 `Column` → `AppManagementListRow`（圆角/底色/外边距/行高单源），
 * 原「方法/状态/耗时/时间」头行改挂共享行的 `headerContent` 槽（`url` = 标题、`domain` = 副标题、
 * 来源名 = 行尾），信息一条不减。
 */
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
    var clearType by remember { mutableStateOf<ClearType?>(null) }

    val palette = rememberAppManagementPalette()
    // 顶栏动作（与原自绘溢出菜单逐字同源：记录开关 checked / 过滤 / 三档清除）
    val menuActions = listOf(
        MenuAction(
            icon = Icons.Default.History,
            title = stringResource(R.string.record_url_switch),
            checked = recordEnabled,
            onClick = { onToggleRecord(!recordEnabled) }
        ),
        MenuAction(
            icon = Icons.Default.FilterList,
            title = stringResource(R.string.url_record_filter),
            onClick = onFilterClick
        ),
        MenuAction(
            icon = Icons.Default.DeleteSweep,
            title = stringResource(R.string.clear_7_days_ago),
            onClick = { clearType = ClearType.SEVEN_DAYS }
        ),
        MenuAction(
            icon = Icons.Default.DeleteSweep,
            title = stringResource(R.string.clear_30_days_ago),
            onClick = { clearType = ClearType.THIRTY_DAYS }
        ),
        MenuAction(
            icon = Icons.Default.DeleteForever,
            title = stringResource(R.string.clear_all_records),
            onClick = { clearType = ClearType.ALL }
        )
    )

    AppManagementScaffold(
        title = stringResource(R.string.url_record),
        selectedCount = 0,
        totalCount = items.size,
        modifier = modifier,
        palette = palette,
        searchQuery = searchKey,
        searchHint = stringResource(R.string.search),
        onSearchChange = onSearchChange,
        onBack = onBack,
        topActions = buildList {
            menuActions.filter { it.alwaysShow }.forEach { action ->
                add(
                    AppManagementAction(
                        text = action.title,
                        icon = action.icon,
                        // 图标双源同链透传（漏传 iconRes ⇒ iconRes-only 动作静默退化成三点图标）
                        iconRes = action.iconRes,
                        onClick = action.onClick
                    )
                )
            }
            val overflow = menuActions.filter { !it.alwaysShow }
            if (overflow.isNotEmpty()) {
                add(
                    AppManagementAction(
                        text = stringResource(R.string.more_menu),
                        menuActions = {
                            overflow.map { action ->
                                AppManagementMenuAction(
                                    text = action.title,
                                    icon = action.icon,
                                    iconRes = action.iconRes,
                                    checked = action.checked == true,
                                    onClick = action.onClick
                                )
                            }
                        }
                    )
                )
            }
        }
    ) { _ ->
        when {
            isLoading && items.isEmpty() -> ShelfListSkeleton(compact = true)
            items.isEmpty() -> EmptyStatePlaceholder(
                icon = Icons.Default.History,
                title = stringResource(R.string.url_record_empty),
                modifier = Modifier.fillMaxSize()
            )
            else -> AppManagementLazyColumn(palette = palette) {
                itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                    UrlRecordItemRow(
                        item = item,
                        palette = palette,
                        onClick = { onItemClick(item) }
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

/**
 * URL 记录行：容器/取色/圆角/外边距由共享行 `AppManagementListRow` 单源决定，
 * 本函数只映射「头行（方法/状态/耗时 + 相对时间）+ URL 标题 + 域名副标题 + 来源名行尾」。
 */
@Composable
private fun UrlRecordItemRow(
    item: UrlRecordDisplayItem,
    palette: AppManagementPalette,
    onClick: () -> Unit
) {
    val settings = palette.settings
    AppManagementListRow(
        title = item.url,
        subtitle = item.domain,
        palette = palette,
        titleMaxLines = 1,
        subtitleMaxLines = 1,
        // 与书源管理基线行同高（56dp）；不叠面板纹理图（BookSourceScreen 同口径）
        minHeight = 56.dp,
        drawPanelImage = false,
        onClick = onClick,
        headerContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                    color = settings.secondaryText,
                    modifier = Modifier.padding(3.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = formatTime(item.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = settings.secondaryText,
                    modifier = Modifier.padding(3.dp)
                )
            }
        },
        trailingBeforeSwitch = {
            item.sourceName?.takeIf { it.isNotBlank() }?.let { sourceName ->
                Text(
                    text = sourceName,
                    style = MaterialTheme.typography.labelMedium,
                    color = settings.accent
                )
            }
        }
    )
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