package io.legado.app.ui.video

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.RssEpisode
import io.legado.app.model.VideoPlay
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.BookCoverImage
import io.legado.app.ui.widget.compose.ComposeDialogFragment

/**
 * 视频书源详情底部抽屉（video-booksource-multiroute AD-06）
 *
 * 书源模式专属：封面/书名/作者/简介（Book 数据）+ 线路 Tab + 集数列表。
 * 订阅源模式不注入入口（无详情数据，UI 零退化）。
 * 切线路/选集动作与悬浮选择器同源（VideoPlay.switchToRoute / playRssEpisode，
 * 书源模式内部已按源类型分派到卷章切片/章节播放链），选择后关闭抽屉起播。
 *
 * ui-standards：ComposeDialogFragment + AppDialogFrame 规范壳 + LegadoTheme 取色，
 * 禁硬编码色值；简介渲染 TextView 纯文本（Book.intro 富文本由书详情页承载）。
 */
class VideoBookDetailSheet : ComposeDialogFragment() {

    /** 抽屉动作回调（VideoFragment 注入，与悬浮选择器同一动作源） */
    interface Callback {
        fun onDetailRouteSelected(routeIndex: Int)
        fun onDetailEpisodeSelected(episodeIndex: Int, episode: RssEpisode)
    }

    private var actionCallback: Callback? = null

    fun setCallback(callback: Callback?): VideoBookDetailSheet = apply { actionCallback = callback }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    AppDialogFrame(
                        title = stringResource(R.string.video_book_detail_title),
                        scrollContent = false,
                        content = { DetailContent() },
                        actions = {}
                    )
                }
            }
        }
    }

    @Composable
    private fun DetailContent() {
        val book = VideoPlay.book ?: return
        val routes = VideoPlay.rssRoutes ?: return
        var selectedRoute by remember { mutableIntStateOf(VideoPlay.rssRouteIndex) }
        val palette = MaterialTheme.colorScheme
        Column(Modifier.padding(horizontal = 16.dp)) {
            // 上半区：封面 + 书名 + 作者
            Row(verticalAlignment = Alignment.Top) {
                BookCoverImage(
                    book = book,
                    modifier = Modifier
                        .width(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        text = book.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (book.getRealAuthor().isNotEmpty()) {
                        Text(
                            text = book.getRealAuthor(),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            // 简介区（可滚动，最多 160dp）
            if (book.intro?.isNotBlank() == true) {
                Text(
                    text = book.intro!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onSurfaceVariant,
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
            // 线路 Tab（多线路时显示）
            if (routes.size > 1) {
                Text(
                    text = stringResource(R.string.video_route_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(routes.size) { index ->
                        val selected = index == selectedRoute
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (selected) palette.primary
                                    else palette.surfaceVariant
                                )
                                .clickable {
                                    selectedRoute = index
                                    actionCallback?.onDetailRouteSelected(index)
                                    dismiss()
                                }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = routes.getOrNull(index)?.name ?: "",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) palette.onPrimary
                                else palette.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            // 集数列表（当前线路）
            val episodes = routes.getOrNull(selectedRoute)?.episodes.orEmpty()
            Text(
                text = stringResource(R.string.video_episode_label),
                style = MaterialTheme.typography.labelMedium,
                color = palette.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 64.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
            ) {
                items(episodes) { episode ->
                    val selected = episode.title == VideoPlay.rssEpisodes
                        ?.getOrNull(VideoPlay.rssEpisodeIndex)?.title
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) palette.primary.copy(alpha = 0.15f)
                                else palette.surfaceVariant
                            )
                            .clickable {
                                val idx = episodes.indexOf(episode)
                                if (idx >= 0) {
                                    actionCallback?.onDetailEpisodeSelected(idx, episode)
                                }
                                dismiss()
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = episode.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) palette.primary else palette.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
