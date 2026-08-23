package io.legado.app.ui.widget.compose

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.widget.ImageButton
import android.widget.ImageView
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.rememberThemeUiPalette
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.titleTextColor
import io.legado.app.lib.theme.toThemeTextColorOrNull
import io.legado.app.utils.ColorUtils
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composePanelRadius
import io.legado.app.ui.widget.ModernActionPopup

@Immutable
data class AppSettingPalette(
    val page: Color,
    val row: Int,
    val rowPressed: Int,
    val divider: Color,
    val bottomBar: Int,
    val bottomBarText: Color,
    val border: Int?,
    val primaryText: Color,
    val secondaryText: Color,
    val accent: Color,
    val danger: Color,
    val disabledText: Color,
    val onAccent: Color,
    val panelRadiusPx: Float,
    val bodyFontFamily: FontFamily,
    val titleFontFamily: FontFamily,
    val themeSignature: String
)

@Immutable
data class AppManagementPalette(
    val settings: AppSettingPalette,
    val miuix: LegadoMiuixPalette
)

@Immutable
data class AppManagementMenuAction(
    val text: CharSequence,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    val onClick: () -> Unit
)

@Composable
fun rememberAppSettingPalette(): AppSettingPalette {
    val context = LocalContext.current
    val dialogStyle = rememberAppDialogStyle()
    val themeUiPalette = rememberThemeUiPalette()
    val rowBaseColor = themeUiPalette.cardColor
    // 文字/强调前景色按实际背景明暗推导，避免随 night 标志产生深底深字/浅底白字
    val customUiText = AppConfig.uiFontColor.toThemeTextColorOrNull()
    val customTitleText = AppConfig.titleFontColor.toThemeTextColorOrNull()
    val secondaryText = if (customUiText != null) {
        Color(customUiText).copy(alpha = 0.72f)
    } else {
        Color(context.secondaryTextColor)
    }
    val page = Color(context.backgroundColor)
    val row = UiCorner.surfaceColor(rowBaseColor)
    val rowPressed = UiCorner.surfaceColor(rowBaseColor, pressed = true)
    val divider = Color(themeUiPalette.dividerColor)
    val bottomBarBase = if (themeUiPalette.hasCustomCardColor || themeUiPalette.hasCustomMutedColor) {
        themeUiPalette.mutedColor
    } else {
        context.bottomBackground
    }
    val bottomBarText = Color(customUiText ?: context.primaryTextColor)
    val border = UiCorner.panelBorderColor(context)
    val primaryText = Color(customTitleText ?: customUiText ?: context.titleTextColor)
    val accentArgb = context.accentColor
    val accent = Color(accentArgb)
    val onAccent = if (ColorUtils.isColorLight(accentArgb)) Color.Black else Color.White
    val panelRadiusPx = UiCorner.panelRadius(context)
    return remember(
        page,
        row,
        rowPressed,
        divider,
        bottomBarBase,
        bottomBarText,
        border,
        primaryText,
        secondaryText,
        accent,
        onAccent,
        dialogStyle.danger,
        panelRadiusPx,
        dialogStyle.bodyFontFamily,
        dialogStyle.titleFontFamily,
        themeUiPalette.signature
    ) {
        AppSettingPalette(
            page = page,
            row = row,
            rowPressed = rowPressed,
            divider = divider,
            bottomBar = bottomBarBase,
            bottomBarText = bottomBarText,
            border = border,
            primaryText = primaryText,
            secondaryText = secondaryText,
            accent = accent,
            danger = dialogStyle.danger,
            disabledText = secondaryText.copy(alpha = 0.48f),
            onAccent = onAccent,
            panelRadiusPx = panelRadiusPx,
            bodyFontFamily = dialogStyle.bodyFontFamily,
            titleFontFamily = dialogStyle.titleFontFamily,
            themeSignature = themeUiPalette.signature
        )
    }
}

@Composable
fun rememberAppManagementPalette(): AppManagementPalette {
    val context = LocalContext.current
    val colors = rememberAppSettingPalette()
    val panelRadius = context.composePanelRadius()
    val actionRadius = context.composeActionRadius()
    return remember(colors, panelRadius, actionRadius) {
        AppManagementPalette(
            settings = colors,
            miuix = LegadoMiuixPalette(
                accent = colors.accent,
                surface = colors.page,
                surfaceVariant = Color(colors.row),
                primaryText = colors.primaryText,
                secondaryText = colors.secondaryText,
                danger = colors.danger,
                onAccent = colors.onAccent,
                panelRadius = panelRadius,
                actionRadius = actionRadius
            )
        )
    }
}

@Composable
fun AppManagementLazyColumn(
    palette: AppManagementPalette,
    modifier: Modifier = Modifier,
    state: androidx.compose.foundation.lazy.LazyListState =
        androidx.compose.foundation.lazy.rememberLazyListState(),
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(AppListSpacing.Normal),
    showFastScroller: Boolean = true,
    content: LazyListScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            content = content
        )
        ComposeLazyListFastScroller(
            state = state,
            enabled = showFastScroller,
            modifier = Modifier.align(Alignment.CenterEnd),
            touchTargetWidth = AppConfig.fastScrollerTouchTargetDp.dp
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppManagementCard(
    palette: AppManagementPalette,
    modifier: Modifier = Modifier,
    insidePadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    enabled: Boolean = true,
    drawPanelImage: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val panelRadiusPx = palette.settings.panelRadiusPx
    val panelImage = if (drawPanelImage) {
        remember(context, panelRadiusPx, palette.settings.themeSignature) {
            UiCorner.panelImageDrawable(context, panelRadiusPx)
        }
    } else {
        null
    }
    val clickableModifier = when {
        onClick != null && onLongClick != null -> Modifier.combinedClickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick
        )
        onClick != null -> Modifier.clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
        else -> Modifier
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .appSettingPanelBackground(
                normalColor = if (pressed) palette.settings.rowPressed else palette.settings.row,
                panelImage = panelImage.takeIf { drawPanelImage },
                borderColor = palette.settings.border,
                radiusPx = panelRadiusPx
            )
            .then(clickableModifier)
            .padding(insidePadding),
        content = content
    )
}

@Composable
fun AppManagementListRow(
    title: String,
    palette: AppManagementPalette,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean = false,
    selectionVisible: Boolean = true,
    animatedSelection: Boolean = false,
    reserveSelectionSlot: Boolean = false,
    onToggleSelection: (() -> Unit)? = null,
    switchChecked: Boolean? = null,
    onSwitchChange: ((Boolean) -> Unit)? = null,
    titleMaxLines: Int = 2,
    subtitleMaxLines: Int = 1,
    minHeight: Dp = 0.dp,
    drawPanelImage: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    moreActions: List<AppManagementMenuAction> = emptyList(),
    onDelete: (() -> Unit)? = null,
    moreIndicatorColor: Color? = null,
    headerContent: (@Composable ColumnScope.() -> Unit)? = null,
    trailingBeforeSwitch: (@Composable RowScope.() -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null
) {
    AppManagementCard(
        palette = palette,
        modifier = modifier.fillMaxWidth(),
        insidePadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        drawPanelImage = drawPanelImage,
        onClick = onClick,
        onLongClick = onLongClick
    ) {
        headerContent?.invoke(this)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (minHeight > 0.dp) {
                        Modifier.defaultMinSize(minHeight = minHeight)
                    } else {
                        Modifier
                    }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leadingContent?.invoke()
            AppManagementSelectionSlot(
                visible = selectionVisible && onToggleSelection != null,
                animated = animatedSelection,
                reserveSpace = reserveSelectionSlot,
                selected = selected,
                palette = palette,
                onToggleSelection = onToggleSelection
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = palette.settings.primaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = palette.settings.bodyFontFamily,
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        color = palette.settings.secondaryText,
                        fontSize = 12.sp,
                        fontFamily = palette.settings.bodyFontFamily,
                        maxLines = subtitleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            trailingBeforeSwitch?.invoke(this)
            if (switchChecked != null && onSwitchChange != null) {
                LegadoMiuixSwitch(
                    checked = switchChecked,
                    onCheckedChange = onSwitchChange,
                    palette = palette.miuix
                )
            }
            onEdit?.let {
                AppManagementIconAction(
                    iconRes = R.drawable.ic_edit,
                    contentDescription = stringResource(R.string.edit),
                    tint = palette.settings.primaryText,
                    onClick = it
                )
            }
            if (moreActions.isNotEmpty()) {
                Box(
                    modifier = Modifier.size(36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AppManagementMoreActionButton(
                        actionsProvider = { moreActions },
                        palette = palette,
                        contentDescription = stringResource(R.string.more_menu),
                        modifier = Modifier.fillMaxSize()
                    )
                    moreIndicatorColor?.let { color ->
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(color)
                        )
                    }
                }
            } else {
                onMore?.let {
                    Box(
                        modifier = Modifier.size(36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AppManagementIconAction(
                            iconRes = R.drawable.ic_more_vert,
                            contentDescription = stringResource(R.string.more_menu),
                            tint = palette.settings.primaryText,
                            onClick = it
                        )
                        moreIndicatorColor?.let { color ->
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(color)
                            )
                        }
                    }
                }
            }
            onDelete?.let {
                AppManagementIconAction(
                    iconRes = R.drawable.ic_outline_delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = palette.settings.danger,
                    onClick = it
                )
            }
        }
    }
}

@Composable
private fun AppManagementSelectionSlot(
    visible: Boolean,
    animated: Boolean,
    reserveSpace: Boolean,
    selected: Boolean,
    palette: AppManagementPalette,
    onToggleSelection: (() -> Unit)?
) {
    if (!animated && !reserveSpace && !visible) {
        return
    }
    val targetWidth = if (visible || reserveSpace) 36.dp else 0.dp
    val width by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = tween(durationMillis = 140),
        label = "managementSelectionSlotWidth"
    )
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 140),
        label = "managementSelectionSlot"
    )
    Box(
        modifier = Modifier.width(if (animated) width else targetWidth),
        contentAlignment = Alignment.CenterStart
    ) {
        if (visible || progress > 0.01f) {
            AppManagementCheckbox(
                selected = selected,
                palette = palette,
                onToggleSelection = onToggleSelection.takeIf { visible },
                modifier = Modifier.graphicsLayer {
                    alpha = if (animated) progress else 1f
                    translationX = if (animated) (1f - progress) * -8.dp.toPx() else 0f
                }
            )
        }
    }
}

@Composable
private fun AppManagementCheckbox(
    selected: Boolean,
    palette: AppManagementPalette,
    onToggleSelection: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val checkedProgress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "managementCheckboxChecked"
    )
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.settings.accent.copy(alpha = checkedProgress))
            .border(
                width = 1.2.dp,
                color = if (selected) {
                    palette.settings.accent
                } else {
                    palette.settings.secondaryText.copy(alpha = 0.46f)
                },
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(
                enabled = onToggleSelection != null,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onToggleSelection?.invoke() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_check),
            contentDescription = null,
            tint = palette.settings.onAccent,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer {
                    alpha = checkedProgress
                }
        )
    }
}

@Composable
fun AppManagementMoreActionButton(
    actionsProvider: () -> List<AppManagementMenuAction>,
    palette: AppManagementPalette,
    modifier: Modifier = Modifier,
    iconRes: Int = R.drawable.ic_more_vert,
    contentDescription: String? = null,
    tint: Color = palette.settings.primaryText
) {
    var popupHandle by remember { mutableStateOf<ModernActionPopup.Handle?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            popupHandle?.dismiss()
            popupHandle = null
        }
    }
    AndroidView(
        factory = { context ->
            ImageButton(context).apply {
                val padding = (8 * resources.displayMetrics.density).toInt()
                background = null
                scaleType = ImageView.ScaleType.CENTER
                setPadding(padding, padding, padding, padding)
            }
        },
        update = { button ->
            button.setImageResource(iconRes)
            button.contentDescription = contentDescription
            button.setColorFilter(tint.toArgb())
            button.setOnClickListener { view ->
                val actions = actionsProvider().filter { it.text.isNotBlank() }
                if (actions.isEmpty()) return@setOnClickListener
                popupHandle = ModernActionPopup.show(
                    anchor = view,
                    actions = actions.map { action ->
                        ModernActionPopup.Action(
                            title = action.text.toString(),
                            checked = action.checked,
                            enabled = action.enabled,
                            invoke = action.onClick
                        )
                    },
                    previousPopup = popupHandle
                )
            }
        },
        modifier = modifier.size(36.dp)
    )
}

@Composable
fun AppManagementIconAction(
    iconRes: Int,
    contentDescription: String?,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(36.dp)
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun AppSettingSectionTitle(
    title: CharSequence?,
    palette: AppSettingPalette,
    modifier: Modifier = Modifier
) {
    if (title.isNullOrBlank()) return
    Text(
        text = title.toString(),
        color = palette.accent,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = palette.titleFontFamily,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
    )
}

fun Modifier.appSettingPanelBackground(
    normalColor: Int,
    panelImage: Drawable?,
    borderColor: Int?,
    radiusPx: Float
): Modifier {
    return drawWithCache {
        val path = Path()
        val strokeWidth = 1f
        val inset = strokeWidth / 2f
        val rect = RectF(inset, inset, size.width - inset, size.height - inset)
        path.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = normalColor
        }
        val strokePaint = borderColor?.let { color ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                this.strokeWidth = strokeWidth
                this.color = color
            }
        }
        onDrawBehind {
            drawIntoCanvas { canvas ->
                val nativeCanvas = canvas.nativeCanvas
                nativeCanvas.drawPath(path, fillPaint)
                panelImage?.let { drawable ->
                    drawable.bounds = Rect(0, 0, size.width.toInt(), size.height.toInt())
                    drawable.draw(nativeCanvas)
                }
                strokePaint?.let { nativeCanvas.drawPath(path, it) }
            }
        }
    }
}

fun Modifier.appSettingRowDecoration(
    pressed: Boolean,
    pressedColor: Int,
    dividerColor: Color,
    showDivider: Boolean,
    radiusPx: Float,
    isFirst: Boolean = false,
    isLast: Boolean,
    danger: Boolean = false,
    dangerColor: Color = Color.Transparent
): Modifier {
    return drawWithCache {
        val path = Path()
        val rect = RectF(0f, 0f, size.width, size.height)
        val top = if (isFirst) radiusPx else 0f
        val bottom = if (isLast) radiusPx else 0f
        path.addRoundRect(
            rect,
            floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom),
            Path.Direction.CW
        )
        val pressedPaint = if (pressed) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = pressedColor
            }
        } else {
            null
        }
        val dangerPaint = if (danger) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = dangerColor.copy(alpha = 0.10f).toArgb()
            }
        } else {
            null
        }
        val dividerInset = 16.dp.toPx()
        onDrawBehind {
            if (dangerPaint != null || pressedPaint != null) {
                drawIntoCanvas { canvas ->
                    val nativeCanvas = canvas.nativeCanvas
                    dangerPaint?.let { nativeCanvas.drawPath(path, it) }
                    pressedPaint?.let { nativeCanvas.drawPath(path, it) }
                }
            }
            if (showDivider) {
                val y = size.height - 1f
                drawLine(
                    color = dividerColor,
                    start = Offset(dividerInset, y),
                    end = Offset(size.width - dividerInset, y),
                    strokeWidth = 1f
                )
            }
        }
    }
}
