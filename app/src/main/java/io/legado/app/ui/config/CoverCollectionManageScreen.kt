package io.legado.app.ui.config

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.ui.widget.compose.releaseComposeImage
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.help.config.CoverCollectionManager
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.AppListSpacing
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementMoreActionButton
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppManagementPalette

@Composable
internal fun CoverCollectionManageScreen(
    isNight: Boolean,
    entries: List<CoverCollectionManager.Entry>,
    onTabChanged: (Boolean) -> Unit,
    onItemClick: (CoverCollectionManager.Entry) -> Unit,
    itemActions: (CoverCollectionManager.Entry) -> List<AppManagementMenuAction>,
    onAddClick: () -> Unit
) {
    val context = LocalContext.current
    val palette = rememberAppManagementPalette()
    val actionRadius = palette.miuix.actionRadius ?: context.composeActionRadius()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.settings.page)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // Tab bar
        CoverCollectionTabBar(
            isNight = isNight,
            palette = palette,
            onTabChanged = onTabChanged
        )
        Spacer(modifier = Modifier.height(8.dp))
        // List
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(AppListSpacing.Normal)
        ) {
            itemsIndexed(entries, key = { _, entry -> "${entry.collection.id}_${entry.source}" }) { _, entry ->
                CoverCollectionItemRow(
                    entry = entry,
                    palette = palette,
                    onClick = { onItemClick(entry) },
                    moreActions = itemActions(entry)
                )
            }
        }
        // Add button
        LegadoMiuixActionButton(
            text = stringResource(R.string.cover_collection_add),
            palette = palette.miuix,
            onClick = onAddClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            cornerRadius = actionRadius
        )
    }
}

@Composable
private fun CoverCollectionTabBar(
    isNight: Boolean,
    palette: AppManagementPalette,
    onTabChanged: (Boolean) -> Unit
) {
    AppManagementCard(
        palette = palette,
        modifier = Modifier
            .fillMaxWidth(),
        insidePadding = PaddingValues(4.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TabButton(
                text = stringResource(R.string.theme_day),
                selected = !isNight,
                palette = palette,
                onClick = { onTabChanged(false) },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(4.dp))
            TabButton(
                text = stringResource(R.string.theme_night),
                selected = isNight,
                palette = palette,
                onClick = { onTabChanged(true) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TabButton(
    text: String,
    selected: Boolean,
    palette: AppManagementPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val actionRadius = palette.miuix.actionRadius ?: LocalContext.current.composeActionRadius()
    androidx.compose.material3.Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(actionRadius),
        color = if (selected) palette.settings.accent.copy(alpha = 0.14f) else Color.Transparent,
        contentColor = if (selected) palette.settings.accent else palette.settings.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Text(
            text = text,
            color = if (selected) palette.settings.accent else palette.settings.primaryText,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CoverCollectionItemRow(
    entry: CoverCollectionManager.Entry,
    palette: AppManagementPalette,
    onClick: () -> Unit,
    moreActions: List<AppManagementMenuAction>
) {
    val context = LocalContext.current
    val collection = entry.collection
    val sourceText = when (entry.source) {
        CoverCollectionManager.Source.LOCAL -> stringResource(R.string.theme_source_local)
        CoverCollectionManager.Source.REMOTE -> stringResource(R.string.theme_source_remote)
        CoverCollectionManager.Source.BOTH -> stringResource(R.string.theme_source_both)
    }
    val infoText = "${stringResource(R.string.cover_collection_images_count, collection.images.size)} · $sourceText"

    AppManagementCard(
        palette = palette,
        modifier = Modifier
            .fillMaxWidth(),
        onClick = onClick,
        insidePadding = PaddingValues(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Preview image
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                },
                update = { imageView ->
                    Glide.with(imageView.context.applicationContext ?: imageView.context)
                        .load(collection.images.firstOrNull())
                        .centerCrop()
                        .into(imageView)
                },
                onRelease = { it.releaseComposeImage() },
                modifier = Modifier
                    .size(width = 54.dp, height = 72.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            // Text info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = collection.name,
                    color = palette.settings.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = infoText,
                    color = palette.settings.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            AppManagementMoreActionButton(
                actionsProvider = { moreActions },
                palette = palette,
                contentDescription = stringResource(R.string.more)
            )
        }
    }
}
