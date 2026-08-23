package io.legado.app.ui.widget.compose

import android.view.Gravity
import android.widget.TextView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.R
import io.legado.app.ui.widget.ModernActionPopup

@Composable
fun AppPackageManageScreen(
    isNightMode: Boolean,
    summaryText: String,
    addText: String,
    onSwitchDayNight: (Boolean) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    showDayNightTabs: Boolean = true,
    headerContent: LazyListScope.(AppManagementPalette) -> Unit = {},
    listContent: LazyListScope.(AppManagementPalette) -> Unit
) {
    val palette = rememberAppManagementPalette()
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = palette.settings.bodyFontFamily)
    ) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = palette.settings.page,
            contentColor = palette.settings.primaryText
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                if (showDayNightTabs) {
                    AppPackageManageTabs(
                        isNightMode = isNightMode,
                        palette = palette,
                        onSwitch = onSwitchDayNight
                    )
                }
                if (summaryText.isNotBlank()) {
                    Text(
                        text = summaryText,
                        color = palette.settings.secondaryText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = 10.dp, end = 16.dp)
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(AppListSpacing.Normal)
                ) {
                    headerContent(palette)
                    listContent(palette)
                }
                LegadoMiuixActionButton(
                    text = addText,
                    palette = palette.miuix,
                    onClick = onAdd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    primary = false,
                    cornerRadius = palette.miuix.actionRadius,
                    minHeight = 46.dp
                )
            }
        }
    }
}

@Composable
fun AppPackageManageSettingCard(
    title: String,
    info: String,
    valueText: String,
    palette: AppManagementPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppManagementCard(
        palette = palette,
        modifier = modifier.fillMaxWidth(),
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = palette.settings.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = info,
                    color = palette.settings.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            AppPackageManageActionButton(
                text = valueText,
                palette = palette.miuix,
                selected = false,
                onClick = onClick
            )
        }
    }
}

@Composable
fun AppPackageManageItemCard(
    title: String,
    info: String,
    isActive: Boolean,
    canEdit: Boolean,
    applyText: String,
    editText: String,
    moreActions: List<AppManagementMenuAction>,
    palette: AppManagementPalette,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null
) {
    AppManagementCard(
        palette = palette,
        modifier = modifier.fillMaxWidth(),
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leadingContent?.let {
                it()
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    color = palette.settings.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = info,
                    color = palette.settings.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppPackageManageActionButton(
                        text = applyText,
                        palette = palette.miuix,
                        selected = isActive,
                        onClick = onApply
                    )
                    if (canEdit) {
                        AppPackageManageActionButton(
                            text = editText,
                            palette = palette.miuix,
                            onClick = onEdit
                        )
                    }
                    AppPackageManageMoreButton(
                        actionsProvider = { moreActions },
                        palette = palette,
                        text = stringResource(R.string.more)
                    )
                }
            }
        }
    }
}

@Composable
fun AppPackageManageActionButton(
    text: String,
    palette: LegadoMiuixPalette,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    val radius = palette.actionRadius ?: 12.dp
    val background = if (selected) {
        palette.accent.copy(alpha = 0.14f)
    } else {
        palette.surfaceVariant
    }
    val content = if (selected) palette.accent else palette.primaryText
    Surface(
        modifier = modifier
            .widthIn(min = 72.dp)
            .height(34.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(radius),
        color = background,
        contentColor = content,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                color = content,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AppPackageManageMoreButton(
    actionsProvider: () -> List<AppManagementMenuAction>,
    palette: AppManagementPalette,
    text: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var popupHandle by remember { mutableStateOf<ModernActionPopup.Handle?>(null) }
    val actionRadius = palette.miuix.actionRadius ?: 12.dp
    DisposableEffect(Unit) {
        onDispose {
            popupHandle?.dismiss()
            popupHandle = null
        }
    }
    AndroidView(
        factory = { viewContext ->
            TextView(viewContext).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                isSingleLine = true
                textSize = 13f
                minWidth = (72 * resources.displayMetrics.density).toInt()
                minHeight = (34 * resources.displayMetrics.density).toInt()
            }
        },
        update = { button ->
            button.text = text
            button.setTextColor(palette.settings.primaryText.toArgb())
            button.typeface = context.uiTypeface()
            button.background = UiCorner.actionSelector(
                palette.miuix.surfaceVariant.toArgb(),
                palette.settings.rowPressed,
                (actionRadius.value * context.resources.displayMetrics.density)
            )
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
        modifier = modifier
            .width(72.dp)
            .height(34.dp)
    )
}

@Composable
private fun AppPackageManageTabs(
    isNightMode: Boolean,
    palette: AppManagementPalette,
    onSwitch: (Boolean) -> Unit
) {
    AppManagementCard(
        palette = palette,
        modifier = Modifier.fillMaxWidth(),
        insidePadding = PaddingValues(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AppPackageManageTabButton(
                text = stringResource(R.string.theme_day),
                selected = !isNightMode,
                palette = palette,
                onClick = { onSwitch(false) },
                modifier = Modifier.weight(1f)
            )
            AppPackageManageTabButton(
                text = stringResource(R.string.theme_night),
                selected = isNightMode,
                palette = palette,
                onClick = { onSwitch(true) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun AppPackageManageTabButton(
    text: String,
    selected: Boolean,
    palette: AppManagementPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(34.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(palette.miuix.actionRadius ?: 12.dp),
        color = if (selected) palette.settings.accent.copy(alpha = 0.14f) else Color.Transparent,
        contentColor = if (selected) palette.settings.accent else palette.settings.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                color = if (selected) palette.settings.accent else palette.settings.primaryText,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
