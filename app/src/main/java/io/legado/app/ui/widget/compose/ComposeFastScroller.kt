package io.legado.app.ui.widget.compose

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.help.config.AppConfig
import my.nanihadesuka.compose.InternalLazyColumnScrollbar
import my.nanihadesuka.compose.InternalLazyVerticalGridScrollbar
import my.nanihadesuka.compose.ScrollbarLayoutSide
import my.nanihadesuka.compose.ScrollbarSelectionActionable
import my.nanihadesuka.compose.ScrollbarSelectionMode
import my.nanihadesuka.compose.ScrollbarSettings

@Composable
fun ComposeLazyListFastScroller(
    state: LazyListState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minThumbHeight: Dp = 44.dp,
    touchTargetWidth: Dp = AppConfig.fastScrollerTouchTargetDp.dp,
    dragHotZoneWidth: Dp = touchTargetWidth
) {
    val totalItems = state.layoutInfo.totalItemsCount
    val visibleItems = state.layoutInfo.visibleItemsInfo.size
    if (!enabled || totalItems <= visibleItems || visibleItems <= 0 || totalItems <= 0) return
    val settings = rememberLegadoScrollbarSettings(
        minThumbHeight = minThumbHeight,
        viewportMainAxisPx = state.layoutInfo.viewportSize.height
    )
    InternalLazyColumnScrollbar(
        state = state,
        modifier = modifier
            .fillMaxHeight()
            .width(maxOf(touchTargetWidth, dragHotZoneWidth)),
        settings = settings
    )
}

@Composable
fun ComposeLazyGridFastScroller(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minThumbHeight: Dp = 44.dp,
    touchTargetWidth: Dp = AppConfig.fastScrollerTouchTargetDp.dp,
    dragHotZoneWidth: Dp = touchTargetWidth
) {
    val totalItems = state.layoutInfo.totalItemsCount
    val visibleItems = state.layoutInfo.visibleItemsInfo.size
    if (!enabled || totalItems <= visibleItems || visibleItems <= 0 || totalItems <= 0) return
    val settings = rememberLegadoScrollbarSettings(
        minThumbHeight = minThumbHeight,
        viewportMainAxisPx = state.layoutInfo.viewportSize.height
    )
    InternalLazyVerticalGridScrollbar(
        state = state,
        modifier = modifier
            .fillMaxHeight()
            .width(maxOf(touchTargetWidth, dragHotZoneWidth)),
        settings = settings
    )
}

@Composable
private fun rememberLegadoScrollbarSettings(
    minThumbHeight: Dp,
    viewportMainAxisPx: Int
): ScrollbarSettings {
    val palette = rememberAppManagementPalette()
    val minThumbPx = with(LocalDensity.current) { minThumbHeight.toPx() }
    val minThumbFraction = if (viewportMainAxisPx > 0) {
        (minThumbPx / viewportMainAxisPx).coerceIn(0.045f, 0.12f)
    } else {
        0.07f
    }
    val maxThumbFraction = maxOf(minThumbFraction, 0.18f)
    return ScrollbarSettings(
        enabled = true,
        side = ScrollbarLayoutSide.End,
        alwaysShowScrollbar = false,
        scrollbarPadding = 4.dp,
        thumbThickness = 6.dp,
        thumbShape = CircleShape,
        thumbMinLength = minThumbFraction,
        thumbMaxLength = maxThumbFraction,
        thumbUnselectedColor = palette.settings.secondaryText.copy(alpha = 0.54f),
        thumbSelectedColor = palette.settings.secondaryText.copy(alpha = 0.82f),
        selectionMode = ScrollbarSelectionMode.Thumb,
        selectionActionable = ScrollbarSelectionActionable.WhenVisible,
        hideDelayMillis = 900,
        hideDisplacement = 10.dp,
        durationAnimationMillis = 160
    )
}
