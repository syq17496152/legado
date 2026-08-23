package io.legado.app.ui.config.compose

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.compose.AppSettingPalette
import io.legado.app.ui.widget.compose.AppSettingSectionTitle
import io.legado.app.ui.widget.compose.AppThemedStepperSlider
import io.legado.app.ui.widget.compose.LegadoResourceIcon
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.appSettingPanelBackground
import io.legado.app.ui.widget.compose.appSettingRowDecoration
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.ui.widget.compose.toMiuixPalette

private val PanelHorizontalPadding = 12.dp

@Composable
fun SettingSpecScreen(
    page: SettingPageSpec,
    scrollTargetKey: String?,
    drawPanelImage: Boolean = true,
    onTargetReady: (String) -> Unit,
    onTargetMissing: () -> Unit,
    onItemClick: (SettingItemSpec) -> Unit
) {
    val colors = rememberAppSettingPalette()
    val panelRadiusPx = colors.panelRadiusPx
    val sections = remember(page) {
        page.sections.mapNotNull { section ->
            val rows = section.items.filter { it.visible }
            rows.takeIf { it.isNotEmpty() }?.let {
                SettingSectionSpec(title = section.title, items = it)
            }
        }
    }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val targetTitleOffsetPx = with(density) { 38.dp.roundToPx() }
    val targetRowOffsetPx = with(density) { 72.dp.roundToPx() }
    LaunchedEffect(scrollTargetKey, sections) {
        val targetKey = scrollTargetKey?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        var targetSectionIndex = -1
        var targetRowIndex = -1
        sections.forEachIndexed { sectionIndex, section ->
            if (targetSectionIndex >= 0) return@forEachIndexed
            val rowIndex = section.items.indexOfFirst {
                it.key == targetKey || targetKey in it.searchKeys
            }
            if (rowIndex >= 0) {
                targetSectionIndex = sectionIndex
                targetRowIndex = rowIndex
            }
        }
        if (targetSectionIndex >= 0) {
            val section = sections[targetSectionIndex]
            val scrollOffset = targetRowIndex * targetRowOffsetPx +
                if (section.title.isNullOrBlank()) 0 else targetTitleOffsetPx
            listState.animateScrollToItem(targetSectionIndex, scrollOffset)
            onTargetReady(targetKey)
        } else {
            onTargetMissing()
        }
    }

    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = colors.bodyFontFamily)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(colors.page)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(
                sections,
                key = { index, section -> "${index}:${section.title}:${section.items.firstOrNull()?.key}" }
            ) { _, section ->
                SettingSectionPanel(
                    section = section,
                    colors = colors,
                    panelRadiusPx = panelRadiusPx,
                    drawPanelImage = drawPanelImage,
                    onItemClick = onItemClick
                )
            }
        }
    }
}

@Composable
private fun SettingSectionPanel(
    section: SettingSectionSpec,
    colors: AppSettingPalette,
    panelRadiusPx: Float,
    drawPanelImage: Boolean,
    onItemClick: (SettingItemSpec) -> Unit
) {
    val context = LocalContext.current
    val panelImage = if (drawPanelImage) {
        remember(context, panelRadiusPx, colors.themeSignature) {
            UiCorner.panelImageDrawable(context, panelRadiusPx)
        }
    } else {
        null
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PanelHorizontalPadding)
            .appSettingPanelBackground(
                normalColor = colors.row,
                panelImage = panelImage,
                borderColor = colors.border,
                radiusPx = panelRadiusPx
            )
    ) {
        AppSettingSectionTitle(title = section.title, palette = colors)
        section.items.forEachIndexed { index, item ->
            SettingRow(
                item = item,
                colors = colors,
                panelRadiusPx = panelRadiusPx,
                showDivider = index != section.items.lastIndex,
                isLast = index == section.items.lastIndex,
                onItemClick = onItemClick
            )
        }
    }
}

@Composable
private fun SettingRow(
    item: SettingItemSpec,
    colors: AppSettingPalette,
    panelRadiusPx: Float,
    showDivider: Boolean,
    isLast: Boolean,
    onItemClick: (SettingItemSpec) -> Unit
) {
    if (item is SettingSliderSpec) {
        SettingSliderRow(
            item = item,
            colors = colors,
            panelRadiusPx = panelRadiusPx,
            showDivider = showDivider,
            isLast = isLast
        )
        return
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val dialogStyle = rememberAppDialogStyle()
    var choiceAnchor by remember(item.key) { mutableStateOf<View?>(null) }
    var choicePopupHandle by remember(item.key) { mutableStateOf<ModernActionPopup.Handle?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            choicePopupHandle?.dismiss()
            choicePopupHandle = null
        }
    }
    fun openChoicePopup(choice: SettingChoiceSpec) {
        val anchor = choiceAnchor
        if (anchor == null) {
            onItemClick(choice)
            return
        }
        choicePopupHandle = ModernActionPopup.show(
            anchor = anchor,
            actions = choice.toPopupActions(),
            previousPopup = choicePopupHandle
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .appSettingRowDecoration(
                pressed = pressed,
                pressedColor = colors.rowPressed,
                dividerColor = colors.divider,
                showDivider = showDivider,
                radiusPx = panelRadiusPx,
                isLast = isLast
            )
            .settingRowClick(
                item = item,
                interactionSource = interactionSource,
                onItemClick = { clickedItem ->
                    if (clickedItem is SettingChoiceSpec) {
                        openChoicePopup(clickedItem)
                    } else {
                        onItemClick(clickedItem)
                    }
                }
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        SettingText(
            item = item,
            colors = colors,
            modifier = Modifier.weight(1f)
        )
        when (item) {
            is SettingSwitchSpec -> {
                Spacer(modifier = Modifier.width(12.dp))
                val palette = dialogStyle.toMiuixPalette()
                LegadoMiuixSwitch(
                    checked = item.checked,
                    onCheckedChange = item.onCheckedChange,
                    palette = palette,
                    enabled = item.enabled
                )
            }

            is SettingChoiceSpec -> {
                Spacer(modifier = Modifier.width(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(dialogStyle.actionRadius))
                        .background(colors.accent.copy(alpha = if (item.enabled) 0.10f else 0.05f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    item.selectedOption?.iconName?.let { iconName ->
                        LegadoResourceIcon(
                            iconName = iconName,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = item.selectedLabel.toString(),
                        color = if (item.enabled) colors.accent else colors.disabledText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    AndroidView(
                        factory = { context ->
                            View(context).apply {
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                isClickable = false
                                isFocusable = false
                            }
                        },
                        update = { view -> choiceAnchor = view },
                        modifier = Modifier.size(1.dp)
                    )
                }
            }

            is SettingSliderSpec -> Unit
            is SettingActionSpec -> Unit
        }
    }
}

private fun SettingChoiceSpec.toPopupActions(): List<ModernActionPopup.Action> {
    return options.map { option ->
        ModernActionPopup.Action(
            title = option.label.toString(),
            description = option.description?.toString(),
            iconName = option.iconName,
            checked = option.value == selectedValue,
            enabled = enabled
        ) {
            onSelected(option.value)
        }
    }
}

private fun Modifier.settingRowClick(
    item: SettingItemSpec,
    interactionSource: MutableInteractionSource,
    onItemClick: (SettingItemSpec) -> Unit
): Modifier {
    val action = item as? SettingActionSpec
    val onLongClick = action?.onLongClick
    return if (onLongClick != null) {
        combinedClickable(
            enabled = item.enabled,
            interactionSource = interactionSource,
            indication = null,
            role = item.clickRole(),
            onClick = { onItemClick(item) },
            onLongClick = onLongClick
        )
    } else {
        clickable(
            enabled = item.enabled,
            interactionSource = interactionSource,
            indication = null,
            role = item.clickRole(),
            onClick = { onItemClick(item) }
        )
    }
}

private fun SettingItemSpec.clickRole(): Role {
    return when (this) {
        is SettingSwitchSpec -> Role.Switch
        is SettingActionSpec,
        is SettingChoiceSpec -> Role.Button
        is SettingSliderSpec -> Role.Button
    }
}

@Composable
private fun SettingSliderRow(
    item: SettingSliderSpec,
    colors: AppSettingPalette,
    panelRadiusPx: Float,
    showDivider: Boolean,
    isLast: Boolean
) {
    val palette = rememberAppDialogStyle().toMiuixPalette()
    val sliderValue = item.value.coerceIn(item.valueRange)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 72.dp)
            .appSettingRowDecoration(
                pressed = false,
                pressedColor = colors.rowPressed,
                dividerColor = colors.divider,
                showDivider = showDivider,
                radiusPx = panelRadiusPx,
                isLast = isLast
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingText(
                item = item,
                colors = colors,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = item.valueFormatter(sliderValue),
                color = if (item.enabled) colors.accent else colors.disabledText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        AppThemedStepperSlider(
            value = sliderValue,
            range = item.valueRange,
            onValueChange = { item.onValueChange(it.coerceIn(item.valueRange)) },
            palette = palette,
            modifier = Modifier.fillMaxWidth(),
            enabled = item.enabled,
            step = item.step,
            onValueChangeFinished = item.onValueChangeFinished
        )
    }
}

@Composable
private fun SettingText(
    item: SettingItemSpec,
    colors: AppSettingPalette,
    modifier: Modifier = Modifier
) {
    val titleColor = if (item.enabled) colors.primaryText else colors.disabledText
    val summaryColor = if (item.enabled) colors.secondaryText else colors.disabledText
    Column(modifier = modifier) {
        Text(
            text = item.title.toString(),
            color = titleColor,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        item.summary?.takeIf { it.isNotBlank() }?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = it.toString(),
                color = summaryColor,
                fontSize = 14.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
