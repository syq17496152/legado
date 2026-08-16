package io.legado.app.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Copyright
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.SettingsClickRow
import io.legado.app.ui.widget.components.SettingsSection

/**
 * 关于页 Compose 展示区（L-C20 关于，S6 展示 + S2 列表管理页范式）。
 * 顶栏（返回 + 分享/评分）+ 应用摘要卡（应用名 + 描述 + 公众号 primary 高亮可复制）+
 * 功能列表（开源贡献者/更新日志/检查更新/发邮件 + 其他分组：崩溃日志/保存日志/堆转储/隐私政策/许可证/免责声明）。
 * 业务逻辑（openUrl/showMdFile/checkUpdate/sendMail/saveLog 等）由宿主 Activity 承接。
 */
@Composable
fun AboutScreen(
    versionName: String,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onScoring: () -> Unit,
    onContributors: () -> Unit,
    onUpdateLog: () -> Unit,
    onCheckUpdate: () -> Unit,
    onMail: () -> Unit,
    onLicense: () -> Unit,
    onDisclaimer: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onCopyGzh: () -> Unit,
    onCrashLog: () -> Unit,
    onSaveLog: () -> Unit,
    onCreateHeapDump: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        GlassTopAppBar(
            title = stringResource(R.string.about),
            navIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onNavClick = onBack,
            actions = {
                IconButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share))
                }
                IconButton(onClick = onScoring) {
                    Icon(Icons.Filled.Star, contentDescription = stringResource(R.string.scoring))
                }
            }
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { AboutSummaryCard(onCopyGzh = onCopyGzh) }
            item {
                SettingsClickRow(
                    icon = Icons.Filled.Groups,
                    title = stringResource(R.string.contributors),
                    subtitle = stringResource(R.string.contributors_summary_sigma),
                    onClick = onContributors
                )
            }
            item {
                SettingsClickRow(
                    icon = Icons.Filled.Update,
                    title = stringResource(R.string.update_log),
                    value = "${stringResource(R.string.version)} $versionName",
                    onClick = onUpdateLog
                )
            }
            item {
                SettingsClickRow(
                    icon = Icons.Filled.Refresh,
                    title = stringResource(R.string.check_update),
                    onClick = onCheckUpdate
                )
            }
            item {
                SettingsClickRow(
                    icon = Icons.Filled.Email,
                    title = stringResource(R.string.mail),
                    onClick = onMail
                )
            }
            item { SettingsSection(title = stringResource(R.string.other)) {} }
            item {
                SettingsClickRow(
                    icon = Icons.Filled.BugReport,
                    title = stringResource(R.string.crash_log),
                    onClick = onCrashLog
                )
            }
            item {
                SettingsClickRow(
                    icon = Icons.Filled.Save,
                    title = stringResource(R.string.save_log),
                    onClick = onSaveLog
                )
            }
            item {
                SettingsClickRow(
                    icon = Icons.Filled.Memory,
                    title = stringResource(R.string.create_heap_dump),
                    onClick = onCreateHeapDump
                )
            }
            item {
                SettingsClickRow(
                    icon = Icons.Filled.Policy,
                    title = stringResource(R.string.privacy_policy),
                    onClick = onPrivacyPolicy
                )
            }
            item {
                SettingsClickRow(
                    icon = Icons.Filled.Copyright,
                    title = stringResource(R.string.license),
                    onClick = onLicense
                )
            }
            item {
                SettingsClickRow(
                    icon = Icons.Filled.Warning,
                    title = stringResource(R.string.disclaimer),
                    onClick = onDisclaimer
                )
            }
            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }
}

/**
 * 应用摘要卡：应用名 + 描述 + 公众号（primary 高亮，点击复制）。
 * 对齐原 activity_about.xml ll_about 摘要区，公众号高亮由 Spannable 改为主题色 Text。
 * 简化说明：公共 SummaryCard（成就卡语义）已于 2026-08-16 作为孤儿组件删除，
 * 其 API 无法承载「公众号 primary 高亮 + 点击复制」行，保留私有实现。
 */
@Composable
private fun AboutSummaryCard(
    onCopyGzh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .then(
                Modifier.clickable(onClick = onCopyGzh)
            )
    ) {
        Text(
            text = stringResource(R.string.app_name_sigma),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.about_description_sigma),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Campaign,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.legado_gzh),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
