package io.legado.app.ui.config

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.AppListSpacing
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementMoreActionButton
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
private val BUBBLE_ITEM_MIN_HEIGHT = 86.dp
private val BUBBLE_PREVIEW_BOX = 64.dp

@Composable
private fun bubbleActionButtonMinHeight() = (38f * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp

@Composable
internal fun BubbleManageScreen(
    entries: List<BubblePackageManager.Entry>,
    summary: String,
    activeDirName: String,
    forceSoftwareBubble: Boolean,
    previewBitmapProvider: suspend (BubblePackageManager.Entry, Int, Boolean) -> Bitmap?,
    onForceSoftwareBubbleChange: (Boolean) -> Unit,
    onApply: (BubblePackageManager.Entry) -> Unit,
    onEdit: (BubblePackageManager.Entry) -> Unit,
    onMoreActions: (BubblePackageManager.Entry) -> List<AppManagementMenuAction>,
    onAddClick: () -> Unit
) {
    val palette = rememberAppManagementPalette()

    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(
            fontFamily = palette.settings.bodyFontFamily
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.settings.page)
        ) {
        // Summary
        if (summary.isNotBlank()) {
            Text(
                text = summary,
                color = palette.settings.secondaryText,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }

        AppManagementListRow(
            title = "强制使用软件气泡",
            subtitle = "识别正文图片形式的段评入口，并使用当前气泡样式渲染",
            palette = palette,
            switchChecked = forceSoftwareBubble,
            onSwitchChange = onForceSoftwareBubbleChange,
            onClick = { onForceSoftwareBubbleChange(!forceSoftwareBubble) }
        )

        // List
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(AppListSpacing.Normal)
        ) {
            itemsIndexed(
                items = entries,
                key = { _, entry -> entry.dirName }
            ) { _, entry ->
                BubbleItemRow(
                    entry = entry,
                    active = activeDirName == entry.dirName,
                    palette = palette,
                    previewBitmapProvider = previewBitmapProvider,
                    onApply = { onApply(entry) },
                    onEdit = { onEdit(entry) },
                    moreActions = onMoreActions(entry)
                )
            }
        }

        // Add button
        LegadoMiuixActionButton(
            text = "添加",
            palette = palette.miuix,
            onClick = onAddClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            minHeight = 48.dp
        )

            Spacer(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun BubbleItemRow(
    entry: BubblePackageManager.Entry,
    active: Boolean,
    palette: AppManagementPalette,
    previewBitmapProvider: suspend (BubblePackageManager.Entry, Int, Boolean) -> Bitmap?,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    moreActions: List<AppManagementMenuAction>
) {
    AppManagementCard(
        palette = palette,
        modifier = Modifier
            .fillMaxWidth(),
        insidePadding = PaddingValues(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = BUBBLE_ITEM_MIN_HEIGHT),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Preview
            Surface(
                modifier = Modifier.size(BUBBLE_PREVIEW_BOX),
                shape = RoundedCornerShape(8.dp),
                color = Color.Transparent,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                val density = LocalDensity.current
                val sidePx = with(density) { BUBBLE_PREVIEW_BOX.roundToPx() }
                val isNightTheme = AppConfig.isNightTheme
                val previewColor = if (isNightTheme) {
                    entry.config.nightNormalColor
                } else {
                    entry.config.dayNormalColor
                }
                val bitmap by produceState<Bitmap?>(
                    initialValue = null,
                    entry.dirName,
                    entry.config.updatedAt,
                    entry.remoteUpdatedAt,
                    entry.config.svgTemplate.hashCode(),
                    entry.config.svgTemplate.length,
                    previewColor,
                    sidePx,
                    isNightTheme
                ) {
                    value = previewBitmapProvider(entry, sidePx, isNightTheme)
                }
                val currentBitmap = bitmap
                if (currentBitmap != null) {
                    Image(
                        bitmap = currentBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize())
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = entry.config.name,
                    color = palette.settings.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = buildItemInfo(entry, active),
                    color = palette.settings.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Action buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionTextButton(
                        text = if (active) "已应用" else "应用",
                        palette = palette.miuix,
                        accent = true,
                        onClick = onApply
                    )
                    val canEdit = entry.source == BubblePackageManager.Source.LOCAL ||
                        entry.source == BubblePackageManager.Source.BOTH
                    if (canEdit) {
                        ActionTextButton(
                            text = "编辑",
                            palette = palette.miuix,
                            onClick = onEdit
                        )
                    }
                    AppManagementMoreActionButton(
                        actionsProvider = { moreActions },
                        palette = palette,
                        contentDescription = "更多"
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionTextButton(
    text: String,
    palette: LegadoMiuixPalette,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    val actionRadius = palette.actionRadius ?: LocalContext.current.composeActionRadius()
    val minHeight = bubbleActionButtonMinHeight()
    Surface(
        modifier = Modifier
            .defaultMinSize(minHeight = minHeight)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(actionRadius),
        color = if (accent) palette.accent.copy(alpha = 0.14f) else palette.surfaceVariant,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Text(
            text = text,
            color = if (accent) palette.accent else palette.primaryText,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .defaultMinSize(minHeight = minHeight)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun buildItemInfo(
    entry: BubblePackageManager.Entry,
    active: Boolean
): String = buildString {
    if (active) append("已应用 · ")
    append(sourceLabel(entry.source))
    append(" · ")
    val time = maxOf(entry.config.updatedAt, entry.remoteUpdatedAt)
    append(if (time > 0L) dateFormat.format(Date(time)) else "内置")
}

private fun sourceLabel(source: BubblePackageManager.Source): String {
    return when (source) {
        BubblePackageManager.Source.BUILTIN -> "内置"
        BubblePackageManager.Source.LOCAL -> "本地"
        BubblePackageManager.Source.REMOTE -> "云端"
        BubblePackageManager.Source.BOTH -> "本地 + 云端"
    }
}
