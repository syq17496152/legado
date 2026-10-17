package io.legado.app.ui.adapter

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.legado.app.R
import io.legado.app.ui.widget.compose.AppDialogStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme
import io.legado.app.ui.theme.bodyTertiary

@Composable
internal fun SourceFolderOptionGrid(
    items: List<SourceFolderSelectItem>,
    style: AppDialogStyle,
    spec: SourceFolderConfigSpec
) {
    var expandedKey by remember { mutableStateOf<String?>(null) }
    var panelVisible by remember { mutableStateOf(false) }
    var transitionVersion by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val expandedItem = items.firstOrNull { it.key == expandedKey }
    fun dismissPanel() {
        val version = ++transitionVersion
        panelVisible = false
        scope.launch {
            delay(SOURCE_FOLDER_PANEL_DISMISS_MS)
            if (transitionVersion == version) {
                expandedKey = null
            }
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spec.gridGap)
    ) {
        items.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spec.gridGap)
            ) {
                rowItems.forEach { item ->
                    SourceFolderSelectTile(
                        item = item,
                        expanded = item.key == expandedKey,
                        style = style,
                        spec = spec,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (expandedKey == item.key) {
                                dismissPanel()
                            } else {
                                transitionVersion++
                                panelVisible = false
                                expandedKey = item.key
                            }
                        }
                    )
                }
                if (rowItems.size < 2) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
    expandedItem?.let { item ->
        Popup(
            alignment = Alignment.Center,
            onDismissRequest = { dismissPanel() },
            properties = PopupProperties(focusable = true)
        ) {
            LaunchedEffect(item.key) {
                panelVisible = true
            }
            SourceFolderChoicePopupPanel(
                visible = panelVisible,
                item = item,
                style = style,
                spec = spec,
                onSelected = {
                    item.onSelected(it)
                    dismissPanel()
                }
            )
        }
    }
}

@Composable
private fun SourceFolderSelectTile(
    item: SourceFolderSelectItem,
    expanded: Boolean,
    style: AppDialogStyle,
    spec: SourceFolderConfigSpec,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val selected = item.options.firstOrNull { it.value == item.selectedValue }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "sourceFolderSelectTileArrow"
    )
    Surface(
        modifier = modifier
            .height(spec.tileHeight)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(style.actionRadius),
        color = if (expanded) style.accent.copy(alpha = 0.10f) else style.surface,
        contentColor = style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.label,
                    color = style.secondaryText,
                    fontSize = MaterialTheme.typography.labelSmall.fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = selected?.label.orEmpty(),
                    color = if (expanded) style.accent else style.primaryText,
                    fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
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
}

@Composable
private fun SourceFolderChoicePopupPanel(
    visible: Boolean,
    item: SourceFolderSelectItem,
    style: AppDialogStyle,
    spec: SourceFolderConfigSpec,
    onSelected: (Int) -> Unit
) {
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = SOURCE_FOLDER_PANEL_ANIMATION_MS),
        label = "sourceFolderChoicePopup"
    )
    val columns = 2
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
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = item.label,
                color = style.primaryText,
                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                fontFamily = style.titleFontFamily,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            item.options.chunked(columns).forEach { rowOptions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    rowOptions.forEach { option ->
                        SourceFolderChoiceChip(
                            option = option,
                            selected = option.value == item.selectedValue,
                            style = style,
                            spec = spec,
                            modifier = Modifier.weight(1f),
                            onClick = { onSelected(option.value) }
                        )
                    }
                    repeat(columns - rowOptions.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceFolderChoiceChip(
    option: SourceFolderConfigOption,
    selected: Boolean,
    style: AppDialogStyle,
    spec: SourceFolderConfigSpec,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(spec.choiceHeight)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(style.actionRadius),
        color = if (selected) style.accent.copy(alpha = 0.14f) else style.fieldSurface,
        contentColor = if (selected) style.accent else style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = option.label,
                color = if (selected) style.accent else style.primaryText,
                fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
