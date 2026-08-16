package io.legado.app.ui.widget.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R

/**
 * 阅读设置 Sheet（S5 阶段3，AD-01，直接替换阅读设置入口）
 *
 * 核心设置：字号 / 行距 / 亮度（+自动） / 夜间 + 对齐 / 翻页 / 字体。
 * 纯 UI 壳组件，业务逻辑经 [ReaderMenuSheetAction] 回调外抛。
 * 为保全全部功能，Sheet 内提供「更多设置」入口，由回调打开原
 * ReadStyleDialog / MoreConfigDialog。
 */
data class ReaderMenuSheetState(
    val textSize: Int = 20,
    val lineSpacingExtra: Int = 12,
    val brightness: Int = 0,
    val brightnessAuto: Boolean = true,
    val isNightTheme: Boolean = false,
    val indentLabel: String = "",
    val pageAnimLabel: String = "",
    val fontLabel: String = "",
)

data class ReaderMenuSheetAction(
    val onDismiss: () -> Unit = {},
    val onTextSizeChange: (Int) -> Unit = {},
    val onLineSpacingChange: (Int) -> Unit = {},
    val onBrightnessAuto: () -> Unit = {},
    val onBrightnessChange: (Int) -> Unit = {},
    val onNightTheme: () -> Unit = {},
    val onIndentClick: () -> Unit = {},
    val onPageAnimClick: () -> Unit = {},
    val onFontClick: () -> Unit = {},
    val onMoreSettingClick: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderMenuSheet(
    state: ReaderMenuSheetState,
    action: ReaderMenuSheetAction,
) {
    AppModalBottomSheet(onDismiss = action.onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            // 核心设置：字号 / 行距
            SettingsSection(title = stringResource(R.string.text_size)) {
                SettingsCard {
                    SettingsSliderRow(
                        title = stringResource(R.string.text_size),
                        value = state.textSize.toFloat(),
                        valueRange = 5f..50f,
                        display = { it.toInt().toString() },
                        onValueChange = action.onTextSizeChange,
                    )
                    SettingsSliderRow(
                        title = stringResource(R.string.line_size),
                        value = state.lineSpacingExtra.toFloat(),
                        valueRange = 0f..40f,
                        display = { it.toInt().toString() },
                        onValueChange = action.onLineSpacingChange,
                    )
                }
            }

            // 核心设置：亮度 / 夜间
            SettingsSection(title = stringResource(R.string.brightness)) {
                SettingsCard {
                    SettingsToggleRow(
                        icon = null,
                        title = stringResource(R.string.brightness_auto),
                        checked = state.brightnessAuto,
                        onCheckedChange = { action.onBrightnessAuto() },
                    )
                    SettingsSliderRow(
                        title = stringResource(R.string.brightness),
                        value = state.brightness.toFloat(),
                        valueRange = 0f..255f,
                        display = { it.toInt().toString() },
                        onValueChange = action.onBrightnessChange,
                    )
                    SettingsToggleRow(
                        icon = null,
                        title = stringResource(R.string.dark_theme),
                        checked = state.isNightTheme,
                        onCheckedChange = { action.onNightTheme() },
                    )
                }
            }

            // 扩展：对齐 / 翻页 / 字体
            SettingsSection(title = stringResource(R.string.expand_setting)) {
                SettingsCard {
                    SettingsClickRow(
                        icon = null,
                        title = stringResource(R.string.text_indent),
                        value = state.indentLabel,
                        onClick = action.onIndentClick,
                    )
                    SettingsClickRow(
                        icon = null,
                        title = stringResource(R.string.page_anim),
                        value = state.pageAnimLabel,
                        onClick = action.onPageAnimClick,
                    )
                    SettingsClickRow(
                        icon = null,
                        title = stringResource(R.string.text_font),
                        value = state.fontLabel,
                        onClick = action.onFontClick,
                    )
                }
            }

            // 更多设置（保全全部功能）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = action.onMoreSettingClick) {
                    Text(stringResource(R.string.more_setting))
                }
            }
        }
    }
}

/**
 * 设置滑块行（标题 + 当前值 + Slider）。
 * 外层包 heightIn(min=48dp) 满足触控规格（§3.4 ReadMenuSlider）。
 */
@Composable
private fun SettingsSliderRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    onValueChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = display(value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(40.dp)
            )
        }
        Slider(
            value = value,
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                thumbColor = MaterialTheme.colorScheme.primary,
            )
        )
    }
}
