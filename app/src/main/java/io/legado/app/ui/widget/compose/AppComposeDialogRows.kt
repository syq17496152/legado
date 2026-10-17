package io.legado.app.ui.widget.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.theme.bodySecondary

@Composable
fun AppDialogSwitchRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                color = if (checked) style.primaryText else style.secondaryText,
                fontSize = MaterialTheme.typography.bodySecondary.fontSize,
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(10.dp))
            LegadoMiuixSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                palette = palette
            )
        }
    }
}

@Composable
fun AppDialogOptionGroup(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = style.accent,
            fontSize = MaterialTheme.typography.bodySecondary.fontSize,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 6.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { index, label ->
                LegadoMiuixChoiceRow(
                    text = label,
                    selected = selectedIndex == index,
                    palette = palette,
                    onClick = { onSelected(index) },
                    minHeight = 40.dp,
                    compact = true
                )
            }
        }
    }
}

@Composable
fun AppDialogSliderRow(
    title: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showStepper: Boolean = false,
    step: Int = 1,
    valueFormatter: (Int) -> String = { it.toString() },
    onValueChangeFinished: (() -> Unit)? = null
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val safeStep = step.coerceAtLeast(1)
    val safeValue = value.coerceIn(range)
    val displayValue = valueFormatter(safeValue)
    LegadoMiuixCard(
        modifier = modifier.fillMaxWidth(),
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(
            horizontal = if (compact) 11.dp else 13.dp,
            vertical = if (compact) 8.dp else 10.dp
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    color = style.primaryText,
                    fontSize = if (compact) 13.sp else 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = displayValue,
                    color = style.secondaryText,
                    fontSize = if (compact) 12.sp else 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(if (compact) 4.dp else 6.dp))
            AppThemedStepperSlider(
                value = safeValue,
                range = range,
                onValueChange = { onValueChange(it.coerceIn(range)) },
                palette = palette,
                step = safeStep,
                onValueChangeFinished = onValueChangeFinished
            )
        }
    }
}

data class AppDialogSliderItem(
    val title: String,
    val value: Int,
    val range: IntRange,
    val onValueChange: (Int) -> Unit,
    val showStepper: Boolean = false,
    val step: Int = 1,
    val valueFormatter: (Int) -> String = { it.toString() },
    val onValueChangeFinished: (() -> Unit)? = null
)

@Composable
fun AppDialogSliderGrid(
    items: List<AppDialogSliderItem>,
    modifier: Modifier = Modifier,
    minTwoColumnWidth: Dp = 330.dp
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val twoColumns = maxWidth >= minTwoColumnWidth
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (twoColumns) {
                items.chunked(2).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { item ->
                            AppDialogSliderRow(
                                title = item.title,
                                value = item.value,
                                range = item.range,
                                onValueChange = item.onValueChange,
                                modifier = Modifier.weight(1f),
                                compact = true,
                                showStepper = item.showStepper,
                                step = item.step,
                                valueFormatter = item.valueFormatter,
                                onValueChangeFinished = item.onValueChangeFinished
                            )
                        }
                        if (rowItems.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                items.forEach { item ->
                    AppDialogSliderRow(
                        title = item.title,
                        value = item.value,
                        range = item.range,
                        onValueChange = item.onValueChange,
                        showStepper = item.showStepper,
                        step = item.step,
                        valueFormatter = item.valueFormatter,
                        onValueChangeFinished = item.onValueChangeFinished
                    )
                }
            }
        }
    }
}
