package io.legado.app.ui.config

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import io.legado.app.R
import io.legado.app.help.config.ShareNoteTemplateManager
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.AppPackageManageActionButton
import io.legado.app.ui.widget.compose.AppPackageManageItemCard
import io.legado.app.ui.widget.compose.AppPackageManageScreen
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val noteTemplateDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

@Composable
internal fun ShareNoteTemplateManageScreen(
    entries: List<ShareNoteTemplateManager.Entry>,
    activeDirName: String,
    shareStyle: ShareNoteTemplateManager.ShareStyle,
    previewFiles: Map<String, File>,
    onApply: (ShareNoteTemplateManager.Entry) -> Unit,
    onStyleChange: (ShareNoteTemplateManager.ShareStyle) -> Unit,
    onEdit: (ShareNoteTemplateManager.Entry) -> Unit,
    onMoreActions: (ShareNoteTemplateManager.Entry) -> List<AppManagementMenuAction>,
    onAddClick: () -> Unit
) {
    val applyText = stringResource(R.string.theme_apply)
    val appliedText = stringResource(R.string.theme_applied_state)
    val editText = stringResource(R.string.edit)
    AppPackageManageScreen(
        isNightMode = false,
        summaryText = "管理正文长按摘录分享图片使用的 HTML 模板。预览只显示模板头部，分享时会生成完整图片。",
        addText = stringResource(R.string.theme_add),
        onSwitchDayNight = {},
        onAdd = onAddClick,
        showDayNightTabs = false,
        headerContent = { palette ->
            item {
                ShareNoteStyleQuickCard(
                    shareStyle = shareStyle,
                    palette = palette,
                    onStyleChange = onStyleChange
                )
            }
        }
    ) { palette ->
        items(entries, key = { it.dirName }) { entry ->
            val active = activeDirName == entry.dirName
            AppPackageManageItemCard(
                title = entry.meta.name,
                info = buildInfoText(entry),
                isActive = active,
                canEdit = entry.source == ShareNoteTemplateManager.Source.LOCAL,
                applyText = if (active) appliedText else applyText,
                editText = editText,
                moreActions = onMoreActions(entry),
                palette = palette,
                onApply = { onApply(entry) },
                onEdit = { onEdit(entry) },
                leadingContent = {
                    ShareNoteTemplatePreview(
                        previewFile = previewFiles[entry.dirName],
                        palette = palette
                    )
                }
            )
        }
    }
}

@Composable
private fun ShareNoteStyleQuickCard(
    shareStyle: ShareNoteTemplateManager.ShareStyle,
    palette: AppManagementPalette,
    onStyleChange: (ShareNoteTemplateManager.ShareStyle) -> Unit
) {
    AppManagementCard(
        palette = palette,
        modifier = Modifier.fillMaxWidth(),
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = "分享样式",
            color = palette.settings.primaryText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "快速切换摘录分享图片的配色和字体，预览与分享图片会同步更新。",
            color = palette.settings.secondaryText,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(modifier = Modifier.size(10.dp))
        Text(
            text = "配色",
            color = palette.settings.secondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ShareNoteTemplateManager.stylePalettes.forEach { stylePalette ->
                AppPackageManageActionButton(
                    text = stylePalette.name,
                    palette = palette.miuix,
                    selected = stylePalette.id == shareStyle.paletteId,
                    onClick = { onStyleChange(shareStyle.copy(paletteId = stylePalette.id)) }
                )
            }
        }
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = "字体",
            color = palette.settings.secondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ShareNoteTemplateManager.fontFamilies.forEach { font ->
                AppPackageManageActionButton(
                    text = ShareNoteTemplateManager.fontLabel(font),
                    palette = palette.miuix,
                    selected = font == shareStyle.fontFamily,
                    onClick = { onStyleChange(shareStyle.copy(fontFamily = font)) }
                )
            }
        }
    }
}

@Composable
internal fun ShareNoteTemplatePreview(
    previewFile: File?,
    modifier: Modifier = Modifier,
    palette: AppManagementPalette = rememberAppManagementPalette()
) {
    Box(
        modifier = modifier
            .size(width = 78.dp, height = 110.dp)
            .clip(RoundedCornerShape(palette.miuix.actionRadius ?: 8.dp))
            .background(palette.miuix.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (previewFile != null && previewFile.exists() && previewFile.length() > 0L) {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                },
                update = { imageView ->
                    Glide.with(imageView)
                        .load(previewFile)
                        .signature(
                            ObjectKey("${previewFile.absolutePath}:${previewFile.length()}:${previewFile.lastModified()}")
                        )
                        .centerCrop()
                        .into(imageView)
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = "预览",
                color = palette.settings.secondaryText,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun buildInfoText(entry: ShareNoteTemplateManager.Entry): String {
    val source = when (entry.source) {
        ShareNoteTemplateManager.Source.BUILTIN -> "内置"
        ShareNoteTemplateManager.Source.LOCAL -> "本地"
    }
    val time = entry.meta.updatedAt.takeIf { it > 0L }?.let {
        noteTemplateDateFormat.format(Date(it))
    }
    return listOfNotNull(
        entry.meta.canvasLabel(),
        entry.meta.sizeLabel(),
        source,
        time
    ).joinToString(" · ")
}
