package io.legado.app.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.widget.components.SettingsCard
import io.legado.app.ui.widget.components.SettingsClickRow

/**
 * L-E5 精准管理（S2 配置列表页）：聚合入口 4 项（URL记录/存储管理/下载管理/文件管理）。
 *
 * 内容区全 Compose：SettingsCard 卡片 + SettingsClickRow 跳转行，顶栏由 ConfigActivity 提供。
 */
@Composable
fun PreciseManageScreen(
    onUrlRecordClick: () -> Unit,
    onStorageManageClick: () -> Unit,
    onDownloadManageClick: () -> Unit,
    onFileManageClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
    ) {
        SettingsCard(modifier = Modifier.fillMaxWidth()) {
            SettingsClickRow(
                icon = Icons.Default.History,
                title = stringResource(R.string.url_record),
                subtitle = stringResource(R.string.url_record_summary),
                onClick = onUrlRecordClick
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
            SettingsClickRow(
                icon = Icons.Default.Storage,
                title = stringResource(R.string.storage_manage),
                subtitle = stringResource(R.string.storage_manage_summary),
                onClick = onStorageManageClick
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
            SettingsClickRow(
                icon = Icons.Default.Download,
                title = stringResource(R.string.download_manage),
                subtitle = stringResource(R.string.download_manage_summary),
                onClick = onDownloadManageClick
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
            SettingsClickRow(
                icon = Icons.Default.Folder,
                title = stringResource(R.string.file_manage),
                subtitle = stringResource(R.string.file_manage_summary),
                onClick = onFileManageClick
            )
        }
    }
}
