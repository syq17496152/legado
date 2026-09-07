package io.legado.app.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.ImageView
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import androidx.compose.foundation.ExperimentalFoundationApi

/**
 * 封面图集详情页 Compose 实现（my-compose-full W4.2，替代原 View Grid+RecyclerAdapter）。
 * 3 列图片墙 + 长按删除确认（删除链路经宿主回调，Screen 不直接触达 Manager）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CoverCollectionDetailScreen(
    collectionName: String,
    images: List<String>,
    onBack: () -> Unit,
    onImportImages: () -> Unit,
    onDeleteImage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = rememberAppManagementPalette()
    AppManagementScaffold(
        title = collectionName,
        selectedCount = 0,
        totalCount = images.size,
        palette = palette,
        onBack = onBack,
        topActions = listOf(
            AppManagementAction(
                text = stringResource(R.string.cover_collection_import_images),
                iconRes = R.drawable.ic_import,
                onClick = onImportImages
            )
        )
    ) { _ ->
        if (images.isEmpty()) {
            androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = stringResource(R.string.cover_collection_empty),
                    color = palette.settings.secondaryText,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(images, key = { it }) { imagePath ->
                    CoverImageCell(
                        imagePath = imagePath,
                        palette = palette,
                        onDelete = { onDeleteImage(imagePath) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverImageCell(
    imagePath: String,
    palette: AppManagementPalette,
    onDelete: () -> Unit
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .aspectRatio(0.75f)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = {}, onLongClick = onDelete)
    ) {
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
            },
            update = { iv ->
                Glide.with(iv).load(imagePath).centerCrop().into(iv)
            },
            modifier = Modifier
                .fillMaxSize()
                .background(Color(palette.settings.row))
        )
    }
}
