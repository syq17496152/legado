package io.legado.app.ui.rss.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.SearchRssArticle
import io.legado.app.ui.theme.bodySecondary
import io.legado.app.ui.widget.components.AppShapes
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.BookCoverImage
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.ui.widget.image.CoverImageView

/**
 * 订阅源搜索结果列表 Compose 实现（my-compose-full W2.1），
 * 替代原 RssSearchAdapter（View 版已随 Compose 化移除）。
 * 复用 [rememberAppManagementPalette] 主题色板与 [BookCoverImage] 图片组件，
 * 与书源搜索（SearchResultScreen）/输入帮助区视觉同构、随主题（日夜/强调色/壁纸）走。
 *
 * 简化说明：View 版封面加载失败时隐藏槽位（gone），Compose 版失败显示默认图。
 * 已知上限：加载失败不再收起封面槽位；升级路径：BookCoverImage 增加 onFailure 隐藏回调。
 */
@Composable
fun RssSearchResultScreen(
    articles: List<SearchRssArticle>,
    isLoading: Boolean,
    hasSearched: Boolean,
    scrollToTopSignal: Int,
    onArticleClick: (SearchRssArticle) -> Unit,
    modifier: Modifier = Modifier
) {
    LegadoComposeTheme {
        val palette = rememberAppManagementPalette()
        val listState = rememberLazyListState()
        LaunchedEffect(scrollToTopSignal) {
            if (scrollToTopSignal > 0) listState.scrollToItem(0)
        }
        Box(modifier = modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 86.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(
                    items = articles,
                    // deduplicationKey 仅为 title+pubDate 聚合键，可能出现重名文章，追加下标保证 key 唯一
                    key = { index, item -> "${item.deduplicationKey()}#$index" }
                ) { _, item ->
                    RssSearchArticleListItem(
                        item = item,
                        palette = palette,
                        onClick = { onArticleClick(item) }
                    )
                }
            }
            if (hasSearched && !isLoading && articles.isEmpty()) {
                Text(
                    text = stringResource(R.string.rss_search_result_empty),
                    color = palette.settings.secondaryText,
                    fontSize = MaterialTheme.typography.bodySecondary.fontSize,
                    fontFamily = palette.settings.bodyFontFamily,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

@Composable
private fun RssSearchArticleListItem(
    item: SearchRssArticle,
    palette: AppManagementPalette,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 封面：携带 origin 参数（部分源需要 referer/cookie），image 为空时按 View 版语义隐藏
        if (!item.image.isNullOrBlank()) {
            BookCoverImage(
                path = item.image,
                name = null,
                author = null,
                sourceOrigin = item.origins.firstOrNull(),
                modifier = Modifier
                    .size(width = 80.dp, height = 110.dp)
                    .clip(AppShapes.rounded(4)),
                style = CoverImageView.CoverStyle.LIST,
                fillBounds = true
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 已读状态绿点（参考原 iv_read 策略）
                if (item.isRead) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(colorResource(R.color.md_green_600))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = item.title,
                    color = if (item.isRead) palette.settings.secondaryText else palette.settings.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = palette.settings.titleFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // 来源数角标（多源聚合显示 origins.size）
                if (item.origins.size > 1) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = item.origins.size.toString(),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
            // 发布时间（局部变量承接，实体属性可变无法 smart cast）
            val pubDate = item.pubDate
            if (!pubDate.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = pubDate,
                    color = palette.settings.secondaryText,
                    fontSize = 12.sp,
                    fontFamily = palette.settings.bodyFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 摘要
            val description = item.description
            if (!description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = description,
                    color = palette.settings.secondaryText,
                    fontSize = 12.sp,
                    fontFamily = palette.settings.bodyFontFamily,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
