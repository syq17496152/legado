package io.legado.app.ui.main.ai

import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.data.entities.AiGeneratedImage
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.compose.rememberAppManagementPalette

/**
 * AI 图库 Compose 实现（my-compose-full W6.2，替代原 View 网格+动态 chips）。
 * AppManagementScaffold 承载顶栏/搜索/批量底栏；内容区为筛选 chips（FlowRow）+ 2 列图片网格。
 */
data class GalleryChipUi(
    val label: String,
    val tag: String,
    val selected: Boolean
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun AiImageGalleryScreen(
    chips: List<GalleryChipUi>,
    images: List<AiGeneratedImage>,
    selectedIds: Set<String>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    pageTitle: String,
    onChipClick: (String) -> Unit,
    onImageClick: (AiGeneratedImage) -> Unit,
    onImageLongClick: (AiGeneratedImage) -> Unit,
    onSelectAll: () -> Unit,
    onBatchGroup: () -> Unit,
    onBatchDelete: () -> Unit,
    onBatchCancel: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = rememberAppManagementPalette()
    AppManagementScaffold(
        title = pageTitle,
        selectedCount = selectedIds.size,
        totalCount = images.size,
        palette = palette,
        searchQuery = searchQuery,
        searchHint = stringResource(R.string.search),
        onSearchChange = onSearchChange,
        onBack = onBack,
        bottomActions = listOf(
            AppManagementAction(
                text = stringResource(R.string.select_all),
                onClick = onSelectAll
            ),
            AppManagementAction(
                text = stringResource(R.string.ai_image_group),
                onClick = onBatchGroup
            ),
            AppManagementAction(
                text = stringResource(R.string.delete),
                danger = true,
                onClick = onBatchDelete
            ),
            AppManagementAction(
                text = stringResource(R.string.cancel),
                onClick = onBatchCancel
            )
        )
    ) { _ ->
        if (images.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = stringResource(R.string.ai_image_gallery_empty),
                    color = palette.settings.secondaryText,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        } else {
            Column(modifier = modifier.fillMaxSize()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    chips.forEach { chip ->
                        GalleryChip(
                            label = chip.label,
                            selected = chip.selected,
                            palette = palette,
                            onClick = { onChipClick(chip.tag) }
                        )
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(images, key = { it.id }) { image ->
                        GalleryImageCell(
                            image = image,
                            selected = image.id in selectedIds,
                            palette = palette,
                            onClick = { onImageClick(image) },
                            onLongClick = { onImageLongClick(image) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryChip(
    label: String,
    selected: Boolean,
    palette: AppManagementPalette,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = if (selected) palette.settings.accent else palette.settings.secondaryText,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(17.dp))
            .background(
                if (selected) Color(palette.settings.row) else palette.settings.divider
            )
            .border(
                width = 1.dp,
                color = if (selected) palette.settings.accent else Color.Transparent,
                shape = RoundedCornerShape(17.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryImageCell(
    image: AiGeneratedImage,
    selected: Boolean,
    palette: AppManagementPalette,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .clip(shape)
            .background(Color(palette.settings.row))
            .then(
                if (selected) Modifier.border(2.dp, palette.settings.accent, shape)
                else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(modifier = Modifier.aspectRatio(1f)) {
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                },
                update = { iv ->
                    Glide.with(iv)
                        .load(image.localPath)
                        .error(R.drawable.image_loading_error)
                        .centerCrop()
                        .into(iv)
                },
                modifier = Modifier.fillMaxSize()
            )
            if (selected) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = palette.settings.accent,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                )
            }
        }
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = image.name,
                color = palette.settings.primaryText,
                fontSize = 14.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildImageSubtitle(image),
                color = palette.settings.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                text = if (image.favorite) {
                    stringResource(R.string.in_favorites)
                } else {
                    stringResource(R.string.ai_image_gallery_temporary)
                },
                color = if (image.favorite) palette.settings.accent else palette.settings.primaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private fun buildImageSubtitle(item: AiGeneratedImage): String {
    return buildList {
        if (item.bookName.isNotBlank()) {
            add(item.bookName)
        }
        if (item.chapterTitle.isNotBlank()) {
            add(item.chapterTitle)
        }
        if (item.characterName.isNotBlank()) {
            add(item.characterName)
        }
        add(item.prompt.replace(Regex("\\s+"), " ").take(72))
    }.joinToString(" · ")
}
