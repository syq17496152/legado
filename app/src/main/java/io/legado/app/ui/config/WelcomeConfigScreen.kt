package io.legado.app.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.widget.components.SettingsCard
import io.legado.app.ui.widget.components.SettingsClickRow
import io.legado.app.ui.widget.components.SettingsSection
import io.legado.app.ui.widget.components.SettingsToggleRow

/**
 * L-E6 欢迎配置（S2 配置列表页）。
 *
 * 内容区全 Compose：欢迎页显示时间（Slider 0-800）/ 自定义欢迎开关 /
 * 日/夜背景图行（点击选图/删除，summary 显示当前路径或「选择图片」）。
 * 文字/图标开关设计文档留空，未实现。
 */
@Composable
fun WelcomeConfigScreen(
    showTime: Int,
    customWelcome: Boolean,
    welcomeImageSummary: String,
    welcomeImageDarkSummary: String,
    onShowTimeChange: (Int) -> Unit,
    onCustomWelcomeChange: (Boolean) -> Unit,
    onWelcomeImageClick: () -> Unit,
    onWelcomeImageDarkClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var sliderValue by remember { mutableFloatStateOf(showTime.toFloat()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
    ) {
        SettingsCard(modifier = Modifier.fillMaxWidth()) {
            // 欢迎页显示时间（Slider 0-800，0=不显示欢迎页）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.welcome_show_time),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.welcome_show_time_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = sliderValue.toInt().toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onShowTimeChange(sliderValue.toInt()) },
                valueRange = 0f..800f,
                colors = SliderDefaults.colors(
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    thumbColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            SettingsToggleRow(
                icon = Icons.Default.Image,
                title = stringResource(R.string.custom_welcome),
                subtitle = stringResource(R.string.custom_welcome_summary),
                checked = customWelcome,
                onCheckedChange = onCustomWelcomeChange
            )
        }

        // 日模式：背景图片行
        SettingsSection(title = stringResource(R.string.day), modifier = Modifier.fillMaxWidth()) {
            SettingsCard(modifier = Modifier.fillMaxWidth()) {
                SettingsClickRow(
                    icon = Icons.Default.Image,
                    title = stringResource(R.string.background_image),
                    value = welcomeImageSummary,
                    onClick = onWelcomeImageClick
                )
            }
        }

        // 夜模式：背景图片行
        SettingsSection(title = stringResource(R.string.night), modifier = Modifier.fillMaxWidth()) {
            SettingsCard(modifier = Modifier.fillMaxWidth()) {
                SettingsClickRow(
                    icon = Icons.Default.Image,
                    title = stringResource(R.string.background_image),
                    value = welcomeImageDarkSummary,
                    onClick = onWelcomeImageDarkClick
                )
            }
        }
    }
}
