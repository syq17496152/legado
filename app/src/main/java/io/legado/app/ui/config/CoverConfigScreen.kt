package io.legado.app.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.widget.components.SettingsCard
import io.legado.app.ui.widget.components.SettingsClickRow
import io.legado.app.ui.widget.components.SettingsSection
import io.legado.app.ui.widget.components.SettingsToggleRow

/**
 * L-E3 封面配置（S2 配置列表页）。
 *
 * 内容区全 Compose：仅Wifi加载封面开关 / 封面规则行（→CoverRuleConfigDialog）/
 * 始终显示默认封面开关 / 日·夜两组（默认封面选图行 + 显示书名 + 显示作者，作者依赖书名 enabled）。
 */
@Composable
fun CoverConfigScreen(
    loadCoverOnlyWifi: Boolean,
    useDefaultCover: Boolean,
    coverShowName: Boolean,
    coverShowAuthor: Boolean,
    coverShowNameN: Boolean,
    coverShowAuthorN: Boolean,
    defaultCoverSummary: String,
    defaultCoverDarkSummary: String,
    onLoadCoverOnlyWifiChange: (Boolean) -> Unit,
    onUseDefaultCoverChange: (Boolean) -> Unit,
    onCoverRuleClick: () -> Unit,
    onCoverShowNameChange: (Boolean) -> Unit,
    onCoverShowAuthorChange: (Boolean) -> Unit,
    onCoverShowNameNChange: (Boolean) -> Unit,
    onCoverShowAuthorNChange: (Boolean) -> Unit,
    onDefaultCoverClick: () -> Unit,
    onDefaultCoverDarkClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
    ) {
        SettingsCard(modifier = Modifier.fillMaxWidth()) {
            SettingsToggleRow(
                icon = Icons.Default.Wifi,
                title = stringResource(R.string.only_wifi),
                subtitle = stringResource(R.string.only_wifi_summary),
                checked = loadCoverOnlyWifi,
                onCheckedChange = onLoadCoverOnlyWifiChange
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            SettingsClickRow(
                icon = Icons.Default.Rule,
                title = stringResource(R.string.cover_rule),
                subtitle = stringResource(R.string.cover_rule_summary),
                onClick = onCoverRuleClick
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            SettingsToggleRow(
                icon = Icons.Default.Image,
                title = stringResource(R.string.use_default_cover),
                subtitle = stringResource(R.string.use_default_cover_s),
                checked = useDefaultCover,
                onCheckedChange = onUseDefaultCoverChange
            )
        }

        // 日模式：默认封面 + 书名/作者显隐开关
        SettingsSection(title = stringResource(R.string.day), modifier = Modifier.fillMaxWidth()) {
            SettingsCard(modifier = Modifier.fillMaxWidth()) {
                SettingsClickRow(
                    icon = Icons.Default.Image,
                    title = stringResource(R.string.default_cover),
                    value = defaultCoverSummary,
                    onClick = onDefaultCoverClick
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                SettingsToggleRow(
                    icon = Icons.Default.TextFields,
                    title = stringResource(R.string.cover_show_name),
                    subtitle = stringResource(R.string.cover_show_name_summary),
                    checked = coverShowName,
                    onCheckedChange = onCoverShowNameChange
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                SettingsToggleRow(
                    icon = Icons.Default.Person,
                    title = stringResource(R.string.cover_show_author),
                    subtitle = stringResource(R.string.cover_show_author_summary),
                    checked = coverShowAuthor,
                    enabled = coverShowName,
                    onCheckedChange = onCoverShowAuthorChange
                )
            }
        }

        // 夜模式：默认封面 + 书名/作者显隐开关
        SettingsSection(title = stringResource(R.string.night), modifier = Modifier.fillMaxWidth()) {
            SettingsCard(modifier = Modifier.fillMaxWidth()) {
                SettingsClickRow(
                    icon = Icons.Default.Image,
                    title = stringResource(R.string.default_cover),
                    value = defaultCoverDarkSummary,
                    onClick = onDefaultCoverDarkClick
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                SettingsToggleRow(
                    icon = Icons.Default.TextFields,
                    title = stringResource(R.string.cover_show_name),
                    subtitle = stringResource(R.string.cover_show_name_summary),
                    checked = coverShowNameN,
                    onCheckedChange = onCoverShowNameNChange
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                SettingsToggleRow(
                    icon = Icons.Default.Person,
                    title = stringResource(R.string.cover_show_author),
                    subtitle = stringResource(R.string.cover_show_author_summary),
                    checked = coverShowAuthorN,
                    enabled = coverShowNameN,
                    onCheckedChange = onCoverShowAuthorNChange
                )
            }
        }
    }
}
