package io.legado.app.ui.rss.article.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Feed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.ShelfGridSkeleton
import io.legado.app.ui.widget.components.ShelfListSkeleton
import io.legado.app.ui.widget.compose.AppPageSpacing

/**
 * 分页 footer（design-b3-d4-flagship §2.5）：hasMore → 加载圈；error 非空 → 页脚错误行+整行点击重试
 * （原 LoadMoreView.error 语义，不引入新弹层）；到底 → "没有更多"。
 */
@Composable
internal fun ListFooter(
    hasMore: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(AppPageSpacing.PageHorizontal),
        contentAlignment = Alignment.Center,
    ) {
        when {
            error != null -> Text(
                text = stringResource(R.string.load_error_retry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onRetry),
            )
            hasMore -> CircularProgressIndicator(Modifier.size(24.dp))
            else -> Text(
                text = stringResource(R.string.no_more),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 默认空态（design-b3-d4-flagship §2.5 DefaultEmptyContent）：委托 L1 EmptyStatePlaceholder
 * （components.md「全站列表/网格页空态统一使用，禁止页面各自实现」，选用阶梯 L1 复用优先）。
 */
@Composable
internal fun DefaultEmptyContent(modifier: Modifier = Modifier) {
    EmptyStatePlaceholder(
        icon = Icons.AutoMirrored.Outlined.Feed,
        title = stringResource(R.string.rss_article_list_empty),
        modifier = modifier,
    )
}

/**
 * 骨架屏（design-b3-d4-flagship §2.5）：首屏 articles.isEmpty() && isRefreshing 时渲染 6 行/格
 * shimmer 占位——复用 L1 ShelfListSkeleton/ShelfGridSkeleton（rememberInfiniteTransition + surfaceVariant
 * 呼吸式，与 W10 修订语义等价，禁第三方 shimmer 库/私有复制）。
 */
@Composable
internal fun ArticleListSkeleton(style: RssArticleListStyle, modifier: Modifier = Modifier) {
    when (style) {
        RssArticleListStyle.LIST -> ShelfListSkeleton(itemCount = 6, modifier = modifier)
        RssArticleListStyle.GRID_2 -> ShelfGridSkeleton(columns = 2, itemCount = 6, modifier = modifier)
        RssArticleListStyle.GRID_3 -> ShelfGridSkeleton(columns = 3, itemCount = 6, modifier = modifier)
        RssArticleListStyle.MASONRY -> ShelfGridSkeleton(columns = 2, itemCount = 6, modifier = modifier)
    }
}
