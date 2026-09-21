package io.legado.app.ui.widget.compose

import io.legado.app.ui.widget.components.AppShapes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.bookshelf.compose.BookListCardSurface
import io.legado.app.ui.main.bookshelf.compose.BookshelfListPalette
import io.legado.app.ui.main.bookshelf.compose.BookshelfListRenderConfig
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.BookIntroUtils
import androidx.compose.material3.MaterialTheme

private val NormalSearchBookListItemHeight = 136.dp

@Composable
fun SearchBookListItem(
    book: SearchBook,
    inBookshelf: Boolean,
    rounded: Boolean,
    renderConfig: BookshelfListRenderConfig,
    lifecycle: Lifecycle,
    onClick: () -> Unit,
    onPreview: (Rect?) -> Unit,
    modifier: Modifier = Modifier,
    fragment: Fragment? = null,
    showOriginCount: Boolean = false,
    /**
     * F46：行尾「更多动作」入口（可选）。非空时行尾渲染一个 ⋮ 触控区（40dp），
     * 点击回传由调用方弹上下文菜单。**默认 null ⇒ 既有调用点零改动**（发现分类页首次启用）。
     */
    onMore: (() -> Unit)? = null,
    /**
     * F75：紧凑密度（可选）。开启后隐藏简介行并切换到共享紧凑卡度量
     * （`classicCompactMinHeight` / `roundedCompactMinHeight`，见 BookListCardComponents）。
     * **默认 false ⇒ 既有调用点零改动**（搜书页首次启用）。
     */
    compact: Boolean = false
) {
    val palette = renderConfig.palette
    val haptic = LocalHapticFeedback.current
    val coverBounds = remember(book.bookUrl, book.origin, book.coverUrl) {
        mutableStateOf<Rect?>(null)
    }
    BookListCardSurface(
        rounded = rounded,
        compact = compact,
        renderConfig = renderConfig,
        // 紧凑态不叠加本页最小高度（136dp），交由共享紧凑度量
        // （classicCompactMinHeight / roundedCompactMinHeight）决定，避免两处高度口径打架
        modifier = if (rounded || compact) modifier else modifier.heightIn(min = NormalSearchBookListItemHeight),
        onClick = onClick,
        onLongClick = {
            // F48：长按弹预览前给触觉确认（预览是「先看后决定」的隐性能力，无反馈时用户不知是否触发成功）
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onPreview(coverBounds.value)
        }
    ) { metrics ->
        Box(modifier = Modifier.width(metrics.coverWidth)) {
            BookCoverImage(
                book = book,
                modifier = Modifier
                    .width(metrics.coverWidth),
                style = if (rounded) CoverImageView.CoverStyle.LIST else CoverImageView.CoverStyle.COMPACT,
                loadOnlyWifi = AppConfig.loadCoverOnlyWifi,
                fragment = fragment,
                lifecycle = lifecycle,
                preferThumb = true,
                onBoundsChanged = { coverBounds.value = it }
            )
            if (inBookshelf) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(palette.accent)
                        .size(10.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        SearchBookListText(
            book = book,
            rounded = rounded,
            compact = compact,
            palette = palette,
            showOriginCount = showOriginCount,
            modifier = Modifier.weight(1f)
        )
        if (onMore != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onMore),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.more),
                    tint = palette.secondaryText,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun SearchBookListText(
    book: SearchBook,
    rounded: Boolean,
    compact: Boolean,
    palette: BookshelfListPalette,
    showOriginCount: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = book.name,
                color = palette.primaryText,
                fontSize = if (rounded) 16.sp else 15.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = palette.titleFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            val originCount = book.origins.size
            if (showOriginCount && originCount > 1) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(AppShapes.rounded(10))
                        .background(palette.accent.copy(alpha = 0.16f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = originCount.toString(),
                        color = palette.accent,
                        fontSize = MaterialTheme.typography.labelSmall.fontSize,
                        fontFamily = palette.bodyFontFamily,
                        maxLines = 1
                    )
                }
            }
        }
        Spacer(modifier = Modifier.size(if (rounded) 4.dp else 1.dp))
        Text(
            text = context.getString(R.string.author_show, book.author),
            color = palette.secondaryText,
            fontSize = if (rounded) 13.sp else 12.sp,
            fontFamily = palette.bodyFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val kinds = remember(book.kind) { book.getKindList() }
        if (kinds.isNotEmpty()) {
            Spacer(modifier = Modifier.size(if (rounded) 4.dp else 1.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(if (rounded) 6.dp else 4.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                kinds.forEach { kind ->
                    SearchBookKindChip(
                        text = kind,
                        rounded = rounded,
                        palette = palette
                    )
                }
            }
        }
        val lasted = book.latestChapterTitle
        if (!lasted.isNullOrBlank()) {
            Spacer(modifier = Modifier.size(if (rounded) 5.dp else 2.dp))
            Text(
                text = context.getString(R.string.lasted_show, lasted),
                color = palette.secondaryText,
                fontSize = if (rounded) 13.sp else 12.sp,
                fontFamily = palette.bodyFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        val intro = remember(book.intro) { BookIntroUtils.listIntro(book.intro) }
        // F75：紧凑密度隐藏简介行（扫书名+源数决策，简介是噪音），标题/作者/分类/最新章节保留
        if (!compact && !intro.isNullOrBlank()) {
            Spacer(modifier = Modifier.size(if (rounded) 7.dp else 2.dp))
            Text(
                text = intro,
                modifier = Modifier.fillMaxWidth(),
                color = if (rounded) palette.secondaryText else palette.primaryText,
                fontSize = if (rounded) 13.sp else 12.sp,
                lineHeight = if (rounded) 18.sp else 16.sp,
                fontFamily = palette.bodyFontFamily,
                maxLines = if (rounded) 3 else 2,
                softWrap = true,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SearchBookKindChip(
    text: String,
    rounded: Boolean,
    palette: BookshelfListPalette,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        color = palette.accent,
        fontSize = MaterialTheme.typography.labelSmall.fontSize,
        fontFamily = palette.bodyFontFamily,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(if (rounded) palette.actionRadius else 6.dp))
            .background(palette.accent.copy(alpha = 0.12f))
            .padding(
                horizontal = if (rounded) 8.dp else 6.dp,
                vertical = if (rounded) 3.dp else 1.dp
            )
    )
}
