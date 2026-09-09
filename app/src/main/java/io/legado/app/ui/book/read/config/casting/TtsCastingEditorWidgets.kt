package io.legado.app.ui.book.read.config.casting

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

/**
 * 编辑器共享小部件（从 TtsCastingEditorScreen 拆出，控制单文件 500 行红线内）
 */

/**
 * prosody 滑条（0=跟随全局，0.5~2.0 有效域）：0 侧吸附语义——拖到最小值以下视为跟随
 */
@Composable
internal fun ProsodySlider(
    label: String,
    value: Float,
    enabled: Boolean,
    onChange: (Float) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val following = value <= 0f
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = style.secondaryText
        )
        Spacer(Modifier.size(8.dp))
        LegadoMiuixSwitch(
            checked = !following,
            onCheckedChange = { custom ->
                onChange(if (custom) 1.0f else 0f)
            },
            palette = palette,
            enabled = enabled
        )
        Spacer(Modifier.size(8.dp))
        if (following) {
            Text(
                text = stringResource(R.string.tts_casting_prosody_follow),
                style = MaterialTheme.typography.bodySmall,
                color = style.secondaryText
            )
        } else {
            Slider(
                value = value.coerceIn(0.5f, 2.0f),
                onValueChange = onChange,
                valueRange = 0.5f..2.0f,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "%.2f".format(value),
                style = MaterialTheme.typography.labelSmall,
                color = style.secondaryText
            )
        }
    }
}
