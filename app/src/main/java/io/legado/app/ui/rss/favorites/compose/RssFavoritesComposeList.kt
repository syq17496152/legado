package io.legado.app.ui.rss.favorites.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssStar
import io.legado.app.ui.rss.article.compose.RssArticleImageQuery
import io.legado.app.ui.rss.article.compose.RssArticleListRow
import io.legado.app.ui.rss.article.compose.RssArticleListStateHolder

/**
 * 订阅收藏列表（CF 6.2：原 `RssFavoritesAdapter` + `item_rss_article.xml` 换装 Compose）。
 *
 * 收藏页与文章列表**共用** `item_rss_article`（样式 0 行）⇒ 行实现复用
 * [RssArticleListRow] 单源（避免「同脚手架不同行」），差异只在两处：
 * ①取数通道走 `rssStarDao`（收藏表）②交互多一个**长按删除**（原 `setOnLongClickListener`）。
 *
 * 说明：`RssStar` 无已读态（原 Adapter 也未做已读着色）⇒ `read` 恒 false，行内取默认主文字色。
 *
 * 本页下拉刷新在宿主侧被显式关闭（`refreshLayout.isEnabled = false`）⇒ 不需要
 * 「Compose 列表是否已上滚」的回填回调（文章列表页才有该需要）。
 */
@Composable
fun RssStarComposeList(
    items: List<RssStar>,
    stateHolder: RssArticleListStateHolder,
    bottomPadding: Dp,
    onItemClick: (RssStar) -> Unit,
    onItemLongClick: (RssStar) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = stateHolder.linear!!,
        modifier = modifier,
        contentPadding = PaddingValues(bottom = bottomPadding)
    ) {
        itemsIndexed(
            items = items,
            key = { _, star -> "${star.origin}|${star.link}" }
        ) { _, star ->
            Column(modifier = Modifier.fillMaxWidth()) {
                RssArticleListRow(
                    title = star.title,
                    pubDate = star.pubDate,
                    read = false,
                    origin = star.origin,
                    link = star.link,
                    queryImage = RssStarImageQuery,
                    onClick = { onItemClick(star) },
                    onLongClick = { onItemLongClick(star) }
                )
                // 原 `VerticalDivider`（DividerItemDecoration）在相邻两项之间画线
                HorizontalDivider(
                    thickness = 1.dp,
                    color = colorResource(R.color.bg_divider_line)
                )
            }
        }
    }
}

/** 收藏表封面取数通道（原 `appDb.rssStarDao.getImage`，同为「主查询不含 image ⇒ 逐项单行查」） */
private val RssStarImageQuery: RssArticleImageQuery = { origin, link ->
    appDb.rssStarDao.getImage(origin, link)
}