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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.material3.MaterialTheme
import io.legado.app.ui.theme.bodyTertiary

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
    bannerContent: (@Composable () -> Unit)? = null,
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
                        fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                        lineHeight = 18.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = 10.dp, end = 16.dp)
                    )
                }
                // F136②：页内回执条（固定位，不随列表滚动；无回执时零占位）
                bannerContent?.invoke()
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
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = info,
                    color = palette.settings.secondaryText,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
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
    applyLoading: Boolean = false,
    editLoading: Boolean = false,
    busyText: String = "",
    leadingContent: (@Composable () -> Unit)? = null
) {
    AppManagementCard(
        palette = palette,
        modifier = modifier.fillMaxWidth(),
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        // F136①：远端套装下载期间的卡片顶部细进度条（阶段态，无需百分比）
        if (applyLoading || editLoading) {
            LinearProgressIndicator(
                color = palette.miuix.accent,
                trackColor = palette.miuix.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
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
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = info,
                    color = palette.settings.secondaryText,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
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
                        text = if (applyLoading) busyText else applyText,
                        palette = palette.miuix,
                        selected = isActive,
                        enabled = !applyLoading && !editLoading,
                        loading = applyLoading,
                        onClick = onApply
                    )
                    if (canEdit) {
                        AppPackageManageActionButton(
                            text = if (editLoading) busyText else editText,
                            palette = palette.miuix,
                            enabled = !applyLoading && !editLoading,
                            loading = editLoading,
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
    enabled: Boolean = true,
    loading: Boolean = false,
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
            .clickable(enabled = enabled, onClick = onClick),
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
            // F136①：忙态按钮自带转圈，避免「点了没反应」的重复点击
            if (loading) {
                CircularProgressIndicator(
                    color = content,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = text,
                color = content.copy(alpha = if (enabled) 1f else 0.45f),
                fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
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
                            // 顶栏包 §1.2 路径 B：图标双源同链透传（与 AppManagementMoreActionButton 同口径）
                            icon = action.icon,
                            iconRes = action.iconRes,
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
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
