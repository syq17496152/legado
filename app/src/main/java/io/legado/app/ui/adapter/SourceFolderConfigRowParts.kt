package io.legado.app.ui.adapter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixSlider
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.toMiuixPalette
import kotlin.math.roundToInt
import androidx.compose.material3.MaterialTheme

@Composable
internal fun SourceFolderAscendingRow(
    label: String,
    checked: Boolean,
    style: AppDialogStyle,
    onCheckedChange: (Boolean) -> Unit
) {
    // 升降序开关行（rss-classic-layout-align S2-a）：style 配色，无硬编码色
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(style.actionRadius),
        color = style.surface,
        contentColor = style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .clickable { onCheckedChange(!checked) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = style.primaryText,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (checked) "↑" else "↓",
                color = style.accent,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(end = 8.dp)
            )
            // 开关主题化（ui-theme-governance-polish P1）：换托管 LegadoMiuixSwitch + style palette，
            // 替换脱离取色基线的裸 M3 Switch（colorScheme 直读不随 ThemeStore主题色变化）
            LegadoMiuixSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                palette = style.toMiuixPalette()
            )
        }
    }
}

@Composable
internal fun SourceFolderSliderRow(
    title: String,
    value: Int,
    range: IntRange,
    style: AppDialogStyle,
    onValueChange: (Int) -> Unit
) {
    val palette = style.toMiuixPalette()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(style.actionRadius),
        color = style.surface,
        contentColor = style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    color = style.primaryText,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = style.accent.copy(alpha = 0.12f),
                    contentColor = style.accent,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Text(
                        text = value.toString(),
                        color = style.accent,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                }
            }
            LegadoMiuixSlider(
                value = value.toFloat(),
                onValueChange = {
                    onValueChange(it.roundToInt().coerceIn(range.first, range.last))
                },
                palette = palette,
                modifier = Modifier.height(28.dp),
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = (range.last - range.first - 1).coerceAtLeast(0)
            )
        }
    }
}

@Composable
internal fun SourceFolderFooterActions(
    style: AppDialogStyle,
    cancelLabel: String,
    applyLabel: String,
    onCancel: () -> Unit,
    onApply: () -> Unit
) {
    val palette = style.toMiuixPalette()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegadoMiuixActionButton(
            text = cancelLabel,
            palette = palette,
            onClick = onCancel,
            modifier = Modifier.width(92.dp),
            cornerRadius = style.actionRadius
        )
        Spacer(modifier = Modifier.width(10.dp))
        LegadoMiuixActionButton(
            text = applyLabel,
            palette = palette,
            onClick = onApply,
            modifier = Modifier.width(108.dp),
            primary = true,
            cornerRadius = style.actionRadius
        )
    }
}
