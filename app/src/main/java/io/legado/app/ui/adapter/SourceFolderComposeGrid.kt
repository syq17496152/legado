package io.legado.app.ui.adapter

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.help.config.AppConfig
import io.legado.app.model.BookCover
import io.legado.app.ui.widget.components.AppShapes

/**
 * 订阅/书源文件夹目录 Compose 网格（对齐书架文件夹 FolderGroupGridContent 样式）。
 *
 * 视觉参数与书架完全一致：
 * - 封面 7:10（aspectRatio 0.7）+ AppShapes.Chip(8dp) 圆角 + surfaceContainerHigh 底色
 * - 无封面时 FolderOpen 图标 + onSurfaceVariant tint（跟随主题）
 * - 分组名 bodySmall + Medium 加粗 + onSurface，居中 2 行，TopPadding 8dp
 * - 网格间距：水平 12dp / 垂直 16dp + contentPadding(12,8,12)
 *
 * 数据模型用 [FolderItem]（groupKey/groupLabel/isSpecial）+ 封面 URL 缓存 map，
 * 与 View 版 SourceFolderAdapter 共用同一份数据源，仅渲染层替换为 Compose。
 */
@Composable
fun SourceFolderComposeGrid(
    items: List<FolderItem>,
    covers: Map<String, String?>,
    spanCount: Int,
    onFolderClick: (FolderItem) -> Unit,
    onFolderLongClick: (FolderItem) -> Unit,
) {
    val showBookname = AppConfig.showBookname
    LazyVerticalGrid(
        columns = GridCells.Fixed(spanCount),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(items, key = { it.groupKey }) { item ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onFolderClick(item) },
                        onLongClick = { onFolderLongClick(item) },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.7f)
                        .clip(AppShapes.Chip)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    SourceFolderCover(
                        cover = covers[item.groupKey],
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (showBookname != 1) {
                    Text(
                        text = item.groupLabel,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * 文件夹封面：有自定义封面时加载 BookCover，否则 FolderOpen 图标 + onSurfaceVariant（对齐书架 GroupCover）。
 */
@Composable
private fun SourceFolderCover(
    cover: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (cover.isNullOrBlank()) {
            Icon(
                imageVector = Icons.Filled.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp),
            )
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                },
                update = { view ->
                    BookCover.load(context, cover).into(view)
                },
            )
        }
    }
}