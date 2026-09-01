package io.legado.app.ui.rss.article.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.RssArticle
import io.legado.app.ui.widget.compose.AppPageSpacing

/**
 * 文章条目三形态（design-b3-d4-flagship §2.3）：ListRow（style 0/1）/ GridCell（style 2/4）/
 * MasonryCell（style 3）。已读态标题降级 onSurfaceVariant（对齐原 read payload 刷新语义）。
 * combinedClickable 长按当前页无行为（原五代均无长按），仅作 D7 复用预留（§2.3 注 ④）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ArticleItem(
    article: RssArticle,
    style: RssArticleListStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val titleColor = if (article.read) colorScheme.onSurfaceVariant else colorScheme.onSurface
    when (style) {
        RssArticleListStyle.LIST -> Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = AppPageSpacing.PageHorizontal, vertical = AppPageSpacing.CardGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = article.title.orEmpty(),
                    color = titleColor,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = article.pubDate.orEmpty(),
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // 非网格形态：图缺失即隐藏（对齐原 hideWhenBlank=true）
            RssArticleCover(
                article = article,
                hideWhenBlank = true,
                modifier = Modifier
                    .padding(start = AppPageSpacing.CardGap)
                    .size(width = 96.dp, height = 64.dp)
                    .clip(MaterialTheme.shapes.small),
            )
        }

        RssArticleListStyle.GRID_2, RssArticleListStyle.GRID_3 -> Column(
            modifier = modifier
                .clip(MaterialTheme.shapes.small)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(4.dp),
        ) {
            // 网格形态：占位常显（二代行为；四代原行为差异见 §4-7，原样保留）
            RssArticleCover(
                article = article,
                hideWhenBlank = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(MaterialTheme.shapes.small),
            )
            Text(
                text = article.title.orEmpty(),
                color = titleColor,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = AppPageSpacing.ItemGapInline, horizontal = 4.dp),
            )
        }

        RssArticleListStyle.MASONRY -> Column(
            modifier = modifier
                .padding(horizontal = AppPageSpacing.ItemGapInline, vertical = AppPageSpacing.CardGap)
                .clip(MaterialTheme.shapes.medium)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            MasonryCover(
                article = article,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = article.title.orEmpty(),
                color = titleColor,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(AppPageSpacing.ItemGapInline),
            )
        }
    }
}

/**
 * 稳定 key：origin|sort|link 组合唯一（与 DAO 主键对齐）；重复时退化为 order 保唯一
 * （§2.3 注 ①：碰撞守卫在 items 装配前一次性预计算，key lambda 内零副作用）。
 */
internal fun buildListKeyGuard(articles: List<RssArticle>): Map<String, Int> =
    articles.groupingBy { "${it.origin}|${it.sort}|${it.link}" }.eachCount()

internal fun RssArticle.stableListKey(guard: Map<String, Int>): String {
    val base = "$origin|$sort|$link"
    return if ((guard[base] ?: 0) > 1) "$base|$order" else base
}
