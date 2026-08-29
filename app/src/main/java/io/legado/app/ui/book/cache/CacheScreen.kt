package io.legado.app.ui.book.cache

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isLocal
import io.legado.app.model.CacheBook
import io.legado.app.ui.theme.bodyTertiary
import io.legado.app.ui.theme.bodySecondary

/**
 * 缓存列表页纯 Compose 壳层：宿主（CacheActivity）提供数据源与回调，本 Screen 仅自绘列表。
 *
 * 增量刷新语义（原 adapter.notifyItemChanged(bookUrl)）由 [refreshTickOf] 承载：
 * 宿主按 bookUrl 维护刷新 tick（mutableStateMapOf），tick 变化只会触发对应 item 重组，
 * 重组时读取最新的缓存章节数 / 导出进度 / 导出消息，等价 Diff 局部更新。
 */
@Composable
fun CacheScreen(
    books: List<Book>,
    refreshTickOf: (bookUrl: String) -> Long,
    cacheChaptersOf: (bookUrl: String) -> HashSet<String>?,
    exportMsgOf: (bookUrl: String) -> String?,
    exportProgressOf: (bookUrl: String) -> Int?,
    onDownloadToggle: (Book) -> Unit,
    onExport: (Book) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        itemsIndexed(books, key = { _, book -> book.bookUrl }) { _, book ->
            CacheBookItemRow(
                book = book,
                // 订阅该条目局部刷新 tick：值变化触发本 item 重组
                refreshTick = refreshTickOf(book.bookUrl),
                cacheChapterCount = if (book.isLocal) null else cacheChaptersOf(book.bookUrl)?.size,
                exportMsg = exportMsgOf(book.bookUrl),
                exportProgress = exportProgressOf(book.bookUrl),
                onDownloadToggle = { onDownloadToggle(book) },
                onExport = { onExport(book) }
            )
        }
    }
}

@Composable
private fun CacheBookItemRow(
    book: Book,
    refreshTick: Long,
    cacheChapterCount: Int?,
    exportMsg: String?,
    exportProgress: Int?,
    onDownloadToggle: () -> Unit,
    onExport: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = MaterialTheme.typography.bodySecondary.fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.author_show, book.getRealAuthor()),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (book.isLocal) stringResource(R.string.local_book)
                    else cacheChapterCount?.let { stringResource(R.string.download_count, it, book.totalChapterNum) }
                        ?: stringResource(R.string.loading),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!book.isLocal) {
                IconButton(onClick = onDownloadToggle) {
                    val running = CacheBook.cacheBookMap[book.bookUrl]?.isStop() == false
                    Icon(
                        imageVector = if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.start),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Text(
                text = stringResource(R.string.export),
                color = MaterialTheme.colorScheme.primary,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clickable(onClick = onExport)
            )
        }
        // 导出消息 / 导出进度条（原 tvMsg + progressExport）
        when {
            exportMsg != null -> Text(
                text = exportMsg,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                modifier = Modifier.padding(top = 4.dp)
            )
            exportProgress != null -> LinearProgressIndicator(
                progress = {
                    (exportProgress ?: 0).coerceIn(0, book.totalChapterNum).toFloat() /
                        maxOf(book.totalChapterNum, 1)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
        }
    }
}
