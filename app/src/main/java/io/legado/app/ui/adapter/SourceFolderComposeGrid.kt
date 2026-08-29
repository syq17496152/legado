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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.rememberThemeUiPalette
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.model.BookCover
import io.legado.app.ui.widget.components.AppShapes
import io.legado.app.ui.widget.compose.rememberAppSettingPalette

/**
 * 订阅/书源文件夹目录 Compose 网格（对齐书架文件夹 FolderGroupGridContent 样式）。
 *
 * config-needs-restart-fix 对齐改造：
 * - 间距由 sourceMargin 单源驱动（contentPadding/spacedBy 全 margin，原硬编码 12/8/12+12/16 不生效问题修复）
 * - 取色归位：封面底色 cardColor 直色、图标 tint secondaryText（清除 M3 派生色）
 * - 分组名对齐书架：12sp + titleTypeface + Medium + minLines=2 + top 6dp；showBookname==1 显示（K7 语义）
 *
 * 数据模型用 [FolderItem]（groupKey/groupLabel/isSpecial）+ 封面 URL 缓存 map。
 */
@Composable
fun SourceFolderComposeGrid(
    items: List<FolderItem>,
    covers: Map<String, String?>,
    spanCount: Int,
    margin: Int,
    onFolderClick: (FolderItem) -> Unit,
    onFolderLongClick: (FolderItem) -> Unit,
) {
    val context = LocalContext.current
    val themeUiPalette = rememberThemeUiPalette()
    val palette = rememberAppSettingPalette()
    val coverBg = Color(UiCorner.surfaceColor(themeUiPalette.cardColor))
    val m = margin.coerceAtLeast(2).dp
    LazyVerticalGrid(
        columns = GridCells.Fixed(spanCount),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = m, top = m, end = m, bottom = m),
        horizontalArrangement = Arrangement.spacedBy(m),
        verticalArrangement = Arrangement.spacedBy(m),
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
                        .aspectRatio(0.75f)
                        .clip(AppShapes.Chip)
                        .background(coverBg),
                ) {
                    SourceFolderCover(
                        cover = covers[item.groupKey],
                        coverBg = coverBg,
                        secondaryText = palette.secondaryText,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // showBookname 语义（K7 修正后）：1=显示名字，与书架一致
                if (AppConfig.showBookname == 1) {
                    Text(
                        text = item.groupLabel,
                        maxLines = 2,
                        minLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                        fontFamily = FontFamily(context.titleTypeface()),
                        color = palette.primaryText,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * 文件夹封面：有自定义封面时加载 BookCover，否则 FolderOpen 图标（取色归位 AD-07 同型）。
 */
@Composable
private fun SourceFolderCover(
    cover: String?,
    coverBg: Color,
    secondaryText: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(
        modifier = modifier.background(coverBg),
    ) {
        if (cover.isNullOrBlank()) {
            Icon(
                imageVector = Icons.Filled.FolderOpen,
                contentDescription = null,
                tint = secondaryText,
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
