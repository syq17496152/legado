package io.legado.app.ui.main.bookshelf

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.legado.app.R
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixSlider
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.toMiuixPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.material3.MaterialTheme

@Composable
internal fun BookshelfDisplaySummaryCard(
    title: String,
    items: List<BookshelfSwitchItem>,
    style: AppDialogStyle,
    spec: BookshelfPremiumSpec
) {
    var expanded by remember { mutableStateOf(false) }
    var panelVisible by remember { mutableStateOf(false) }
    var transitionVersion by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val activeItems = items.filter { it.checked }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = BOOKSHELF_PANEL_ANIMATION_MS),
        label = "bookshelfDisplayArrow"
    )
    fun dismissPanel() {
        val version = ++transitionVersion
        panelVisible = false
        scope.launch {
            delay(BOOKSHELF_PANEL_DISMISS_MS)
            if (transitionVersion == version) {
                expanded = false
            }
        }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (expanded) {
                    dismissPanel()
                } else {
                    transitionVersion++
                    expanded = true
                }
            },
        shape = RoundedCornerShape(style.actionRadius),
        color = style.surface,
        contentColor = style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = style.secondaryText,
                    fontSize = MaterialTheme.typography.labelSmall.fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (activeItems.isEmpty()) {
                        BookshelfSummaryChip(
                            text = "未启用",
                            active = false,
                            style = style
                        )
                    } else {
                        activeItems.forEach { item ->
                            BookshelfSummaryChip(
                                text = item.summaryLabel,
                                active = true,
                                style = style
                            )
                        }
                    }
                }
            }
            Icon(
                painter = painterResource(id = R.drawable.ic_expand_more),
                contentDescription = null,
                tint = if (expanded) style.accent else style.secondaryText,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = rotation }
            )
        }
    }
    if (expanded) {
        Popup(
            alignment = Alignment.Center,
            onDismissRequest = { dismissPanel() },
            properties = PopupProperties(focusable = true)
        ) {
            LaunchedEffect(Unit) {
                panelVisible = true
            }
            BookshelfDisplayPopupPanel(
                visible = panelVisible,
                title = title,
                items = items,
                style = style,
                spec = spec,
                onDismiss = { dismissPanel() }
            )
        }
    }
}

@Composable
private fun BookshelfSummaryChip(
    text: String,
    active: Boolean,
    style: AppDialogStyle
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (active) style.accent.copy(alpha = 0.13f) else style.fieldSurface,
        contentColor = if (active) style.accent else style.secondaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Text(
            text = text,
            color = if (active) style.accent else style.secondaryText,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun BookshelfDisplayPopupPanel(
    visible: Boolean,
    title: String,
    items: List<BookshelfSwitchItem>,
    style: AppDialogStyle,
    spec: BookshelfPremiumSpec,
    onDismiss: () -> Unit
) {
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = BOOKSHELF_PANEL_ANIMATION_MS),
        label = "bookshelfDisplayPopup"
    )
    Surface(
        modifier = Modifier
            .width(spec.popupWidth)
            .graphicsLayer {
                alpha = progress
                scaleX = 0.96f + 0.04f * progress
                scaleY = 0.96f + 0.04f * progress
                translationY = (1f - progress) * 12f
            },
        shape = RoundedCornerShape(style.panelRadius),
        color = style.surface,
        contentColor = style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = title,
                color = style.primaryText,
                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                fontFamily = style.titleFontFamily,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            items.forEach { item ->
                BookshelfDisplaySwitchRow(
                    item = item,
                    style = style
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                LegadoMiuixActionButton(
                    text = stringResource(android.R.string.ok),
                    palette = style.toMiuixPalette(),
                    onClick = onDismiss,
                    primary = true,
                    minWidth = 76.dp,
                    minHeight = 36.dp,
                    insidePadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    cornerRadius = style.actionRadius
                )
            }
        }
    }
}

@Composable
private fun BookshelfDisplaySwitchRow(
    item: BookshelfSwitchItem,
    style: AppDialogStyle
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(style.actionRadius))
            .clickable { item.onCheckedChange(!item.checked) }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = item.label,
            modifier = Modifier.weight(1f),
            color = if (item.checked) style.primaryText else style.secondaryText,
            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
            fontWeight = if (item.checked) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // 开关视觉统一（ui-theme-governance-polish P1）：私有 MiniSwitch 迁移至公共
        // LegadoMiuixSwitch compact 模式（38x22 无阴影）+ stroke 描边，取色走统一基线
        LegadoMiuixSwitch(
            checked = item.checked,
            onCheckedChange = item.onCheckedChange,
            palette = style.toMiuixPalette(),
            stroke = style.stroke,
            compact = true
        )
    }
}

@Composable
internal fun BookshelfSliderRow(
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
internal fun BookshelfFooterActions(
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
