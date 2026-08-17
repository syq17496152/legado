package io.legado.app.ui.widget.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import io.legado.app.R
import io.legado.app.utils.ColorUtils

/**
 * 主题色选择弹层（L2，L-E2 主题设置页专用）：
 * - 预置色板：与原 ColorPickerDialog 同源 MATERIAL_COLORS（用户熟悉的 MD 色系）
 * - 自定义：HSL 三滑块（色相轨道彩虹渐变/饱和度/明度），实时活预览（MoRealm 思路）
 * - 确认后回调 ARGB（不透明）
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerSheet(
    title: String,
    initialColor: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val initialHsv = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(initialColor, initialHsv)
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1] * 100f) }
    var lightness by remember { mutableFloatStateOf(initialHsv[2] * 100f) }
    var pickedPreset by remember { mutableStateOf<Int?>(null) }

    val currentColor = pickedPreset ?: hslToColor(hue, saturation / 100f, lightness / 100f)

    AppModalBottomSheet(onDismiss = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            // 标题 + 当前色预览
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(currentColor))
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "#${Integer.toHexString(currentColor).uppercase().padStart(6, '0').takeLast(6)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 预置色板（网格 6 列）
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(ColorPickerDialog.MATERIAL_COLORS.toList()) { preset ->
                    val selected = pickedPreset == preset
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(preset))
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = CircleShape
                            )
                            .clickable {
                                pickedPreset = preset
                                val hsl = FloatArray(3)
                                androidx.core.graphics.ColorUtils.colorToHSL(preset, hsl)
                                hue = hsl[0]
                                saturation = hsl[1] * 100f
                                lightness = hsl[2] * 100f
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = if (ColorUtils.isColorLight(preset)) Color.Black else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 自定义 HSL 三滑块（拖动即取消预置选中，转为自定义色）
            HslSlider(
                label = stringResource(R.string.color_hue),
                value = hue,
                valueRange = 0f..360f,
                trackBrush = Brush.horizontalGradient(rainbowStops()),
                onValueChange = {
                    hue = it
                    pickedPreset = null
                }
            )
            HslSlider(
                label = stringResource(R.string.color_saturation),
                value = saturation,
                valueRange = 0f..100f,
                trackBrush = Brush.horizontalGradient(
                    listOf(
                        Color(hslToColor(hue, 0f, lightness / 100f)),
                        Color(hslToColor(hue, 1f, lightness / 100f))
                    )
                ),
                onValueChange = {
                    saturation = it
                    pickedPreset = null
                }
            )
            HslSlider(
                label = stringResource(R.string.color_lightness),
                value = lightness,
                valueRange = 0f..100f,
                trackBrush = Brush.horizontalGradient(
                    listOf(
                        Color.Black,
                        Color(hslToColor(hue, saturation / 100f, 0.5f)),
                        Color.White
                    )
                ),
                onValueChange = {
                    lightness = it
                    pickedPreset = null
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { onConfirm(ColorUtils.withAlpha(currentColor, 1f)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(text = stringResource(R.string.ok))
            }
        }
    }
}

@Composable
private fun HslSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    trackBrush: Brush,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label  ${value.toInt()}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .height(28.dp),
            contentAlignment = Alignment.Center
        ) {
            // 渐变轨道 + 透明轨道 Slider（滑块浮于渐变之上）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(trackBrush)
            )
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun hslToColor(h: Float, s: Float, l: Float): Int {
    val hsl = floatArrayOf(h, s, l)
    return androidx.core.graphics.ColorUtils.HSLToColor(hsl)
}

private fun rainbowStops(): List<Color> =
    (0..360 step 30).map { Color(hslToColor(it.toFloat(), 1f, 0.5f)) }
