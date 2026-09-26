package io.legado.app.ui.book.character.compose

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.R
import io.legado.app.data.entities.AiGeneratedImage
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.rememberThemeUiPalette
import io.legado.app.ui.widget.compose.rememberAppSettingPalette

/**
 * AI 图库头像选择网格（CF 6.2：原 `RecyclerView` + `GalleryAvatarAdapter` + `item_ai_generated_image.xml`
 * 换装为 Compose `LazyVerticalGrid`）。
 *
 * 视觉/行为等价口径（逐项对齐原实现）：
 * - 网格：原 `GridLayoutManager(3)` + item 根 `layout_margin=6dp` ⇒ `LazyVerticalGrid(Fixed(3))` +
 *   `contentPadding 6dp` + `spacedBy 12dp`（相邻 item 边距 6+6）；
 * - 卡片：原 item 根 `CardView`（`cardCornerRadius=14dp` 被代码覆盖为 `UiCorner.scaledDp(12f)`、
 *   `cardElevation=0`、底色 `themeCardColorOrDefault()`）⇒ Compose `clip(12dp×scale)` + `cardColor` 面 token，无阴影；
 * - 图片：174dp 高、`scaleType=CENTER_CROP`、Glide `error(image_loading_error)`（用 `AndroidView` 承载
 *   `ImageView`，保证与全站 Compose 图片加载同一条 Glide 管线）；
 * - 状态胶囊（右上）：26dp 最小高 / 11sp / 水平 9dp 内边距；文案为「已收藏 / 临时」；字色
 *   `favorite ? accent : primaryText`；底色取 `cardColor`（与原 `UiCorner.actionSelector(默认=cardColor, 按下=muted)` 的默认态一致）、
 *   圆角 `UiCorner.actionRadius`；
 * - 名称：9dp 顶距、14sp 粗体、单行省略（原 `applyUiSectionTitleStyle`）；简介：4dp 顶距、12sp、
 *   最多 2 行、`secondaryText`（原 `applyUiLabelStyle` + `secondaryTextColor`）；
 * - 原 `tvSelected` 在 `convert` 中恒为 `GONE` ⇒ Compose 侧不渲染（不存在选中态）；
 * - 点击整卡回吐 `AiGeneratedImage`（与原 `itemView.setOnClickListener` 一致）。
 *
 * 取色说明：全部来自主题面 token（`ThemeUiPalette.cardColor` / `AppSettingPalette.primaryText|secondaryText|accent`），
 * 不新增硬编码色（合 `theme-consistency-iron-rule.md`）。
 */
@Composable
fun AiAvatarPickerGrid(
    items: List<AiGeneratedImage>,
    onPick: (AiGeneratedImage) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items, key = { it.id }) { item ->
            AiAvatarCard(item = item, onPick = onPick)
        }
    }
}

@Composable
private fun AiAvatarCard(
    item: AiGeneratedImage,
    onPick: (AiGeneratedImage) -> Unit
) {
    val context = LocalContext.current
    val themeUiPalette = rememberThemeUiPalette()
    val palette = rememberAppSettingPalette()
    val cardShape = RoundedCornerShape(UiCorner.scaledDp(12f).dp)
    val badgeShape = RoundedCornerShape(UiCorner.actionRadius(context).dp)
    val cardColor = Color(themeUiPalette.cardColor)

    Column(
        modifier = Modifier
            .clip(cardShape)
            .background(cardColor)
            .clickable { onPick(item) }
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(174.dp)
        ) {
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                },
                update = { iv ->
                    ImageLoader.load(context, item.localPath)
                        .error(R.drawable.image_loading_error)
                        .centerCrop()
                        .into(iv)
                },
                modifier = Modifier.fillMaxSize()
            )
            Text(
                text = stringResource(
                    if (item.favorite) R.string.in_favorites else R.string.ai_image_gallery_temporary
                ),
                color = if (item.favorite) palette.accent else palette.primaryText,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(badgeShape)
                    .background(cardColor)
                    .heightIn(min = 26.dp)
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            )
        }
        Text(
            text = item.name,
            color = palette.primaryText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 9.dp)
        )
        Text(
            text = avatarPromptText(item),
            color = palette.secondaryText,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
    }
}

/** 简介行：`书名 · 章节 · 角色 · 提示词前 48 字`，空项跳过（原 `convert` 的 `buildList` 口径）。 */
private fun avatarPromptText(item: AiGeneratedImage): String = buildList {
    item.bookName.takeIf { it.isNotBlank() }?.let(::add)
    item.chapterTitle.takeIf { it.isNotBlank() }?.let(::add)
    item.characterName.takeIf { it.isNotBlank() }?.let(::add)
    add(item.prompt.replace(Regex("\\s+"), " ").take(48))
}.joinToString(" · ")