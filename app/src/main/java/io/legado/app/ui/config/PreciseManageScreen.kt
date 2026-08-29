package io.legado.app.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.widget.components.SettingsCard
import io.legado.app.ui.widget.components.SettingsClickRow
import io.legado.app.ui.widget.compose.rememberAppSettingPalette

/**
 * L-E5 精准管理（S2 配置列表页）：聚合入口两张卡片。
 * 卡片1 数据管理（URL记录/存储管理/缓存管理/下载管理/文件管理）。
 * 卡片2 日志与诊断（崩溃日志/保存日志/创建堆转储，自 AboutFragment 迁入）。
 *
 * 内容区全 Compose：SettingsCard 卡片 + SettingsClickRow 跳转行，顶栏由 ConfigActivity 提供。
 */
@Composable
fun PreciseManageScreen(
    onUrlRecordClick: () -> Unit,
    onStorageManageClick: () -> Unit,
    onCacheManageClick: () -> Unit,
    onDownloadManageClick: () -> Unit,
    onFileManageClick: () -> Unit,
    onCrashLogClick: () -> Unit,
    onSaveLogClick: () -> Unit,
    onCreateHeapDumpClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // H9: 根背景直色（palette.page = ThemeStore 背景色），替代 M3 surface；divider 归位 palette.divider
    val palette = rememberAppSettingPalette()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.page)
            .verticalScroll(rememberScrollState())
    ) {
        SettingsCard(
            modifier = Modifier.fillMaxWidth(),
            title = stringResource(R.string.data_manage)
        ) {
            SettingsClickRow(
                icon = Icons.Default.History,
                title = stringResource(R.string.url_record),
                subtitle = stringResource(R.string.url_record_summary),
                onClick = onUrlRecordClick
            )
            HorizontalDivider(
                color = palette.divider.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
            SettingsClickRow(
                icon = Icons.Default.Storage,
                title = stringResource(R.string.storage_manage),
                subtitle = stringResource(R.string.storage_manage_summary),
                onClick = onStorageManageClick
            )
            HorizontalDivider(
                color = palette.divider.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
            SettingsClickRow(
                icon = Icons.Default.CloudSync,
                title = stringResource(R.string.cache_manage_title),
                subtitle = stringResource(R.string.cache_manage_summary),
                onClick = onCacheManageClick
            )
            HorizontalDivider(
                color = palette.divider.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
            SettingsClickRow(
                icon = Icons.Default.Download,
                title = stringResource(R.string.download_manage),
                subtitle = stringResource(R.string.download_manage_summary),
                onClick = onDownloadManageClick
            )
            HorizontalDivider(
                color = palette.divider.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
            SettingsClickRow(
                icon = Icons.Default.Folder,
                title = stringResource(R.string.file_manage),
                subtitle = stringResource(R.string.file_manage_summary),
                onClick = onFileManageClick
            )
        }
        SettingsCard(
            modifier = Modifier.fillMaxWidth(),
            title = stringResource(R.string.log_diagnostics)
        ) {
            SettingsClickRow(
                icon = Icons.Default.BugReport,
                title = stringResource(R.string.crash_log),
                onClick = onCrashLogClick
            )
            HorizontalDivider(
                color = palette.divider.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
            SettingsClickRow(
                icon = Icons.Default.SaveAlt,
                title = stringResource(R.string.save_log),
                onClick = onSaveLogClick
            )
            HorizontalDivider(
                color = palette.divider.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
            SettingsClickRow(
                icon = Icons.Default.Memory,
                title = stringResource(R.string.create_heap_dump),
                onClick = onCreateHeapDumpClick
            )
        }
    }
}
