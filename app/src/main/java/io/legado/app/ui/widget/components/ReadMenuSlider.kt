package io.legado.app.ui.widget.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 阅读器进度/亮度 Slider 原语（S5 阅读器族，P2-reader §3）。
 *
 * M3 [Slider] 封装：activeTrack `primary` / inactiveTrack `surfaceVariant` / thumb `primary`；
 * 高度 ≥48dp（外层 `heightIn(min=48dp)`）。
 * 拖动行为：拖动开始时 [onDragStateChange](true)（外部据此将菜单 alpha 降至 ≤30% 实时预览），
 * 松手 [onValueChangeFinished] commit 后回调 [onDragStateChange](false) 恢复。
 * 规格：ui-standards §3.4 `ReadMenuSlider`（task 12.29，from HapeLee/MoRealm）。
 */
@Composable
fun ReadMenuSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    onDragStateChange: ((isDragging: Boolean) -> Unit)? = null,
) {
    var dragging by remember { mutableStateOf(false) }
    Slider(
        value = value,
        onValueChange = { v ->
            if (!dragging) {
                dragging = true
                onDragStateChange?.invoke(true)
            }
            onValueChange(v)
        },
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        colors = SliderDefaults.colors(
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            thumbColor = MaterialTheme.colorScheme.primary,
        ),
        onValueChangeFinished = {
            if (dragging) {
                dragging = false
                onDragStateChange?.invoke(false)
            }
            onValueChangeFinished?.invoke()
        },
    )
}
