package io.legado.app.ui.main.my

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import io.legado.app.ui.widget.components.GlassTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.dao.BookShelfDisplay
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.manga.ReadMangaActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.video.VideoPlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.legado.app.ui.theme.bodyTertiary

/**
 * 特色播放列表页（8.11 特色入口）：从书架按类型筛选出 视频 与 图片/漫画 书，
 * 点击后按类型进入对应播放器（视频→VideoPlayerActivity，图片书且启用漫画 UI→ReadMangaActivity，其余→阅读页）。
 * 解决视频/图片/漫画三类媒体书无法在“我的”页无参直达的问题。
 */
class MyFeatureBooksActivity : ComponentActivity() {

    private var videos by mutableStateOf(emptyList<BookShelfDisplay>())
    private var images by mutableStateOf(emptyList<BookShelfDisplay>())
    private var loading by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LegadoTheme {
                MyFeatureBooksScreen(
                    videos = videos,
                    images = images,
                    loading = loading,
                    onBack = { finish() },
                    onOpen = ::openBook
                )
            }
        }
        loadBooks()
    }

    private fun loadBooks() {
        lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) {
                runCatching {
                    appDb.bookDao.flowShelfAll().first()
                }.getOrDefault(emptyList())
            }
            videos = all.filter { it.isVideo }
            images = all.filter { it.isImage && !it.isVideo }
            loading = false
        }
    }

    private fun openBook(book: BookShelfDisplay) {
        startActivity(
            Intent(
                this,
                when {
                    book.isVideo -> VideoPlayerActivity::class.java
                    !book.isLocal && book.isImage && AppConfig.showMangaUi -> ReadMangaActivity::class.java
                    else -> ReadBookActivity::class.java
                }
            ).apply { putExtra("bookUrl", book.bookUrl) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyFeatureBooksScreen(
    videos: List<BookShelfDisplay>,
    images: List<BookShelfDisplay>,
    loading: Boolean,
    onBack: () -> Unit,
    onOpen: (BookShelfDisplay) -> Unit
) {
    Scaffold(
        topBar = {
            // H3: 原生 M3 TopAppBar → GlassTopAppBar（primaryColor 主色 + 顶栏管理 TopBarConfig）
            GlassTopAppBar(
                title = stringResource(R.string.my_feature_books),
                navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onNavClick = onBack
            )
        }
    ) { innerPadding ->
        when {
            loading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.loading),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize
                    )
                }
            }

            videos.isEmpty() && images.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.my_feature_books_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    if (videos.isNotEmpty()) {
                        item(key = "video_header") {
                            SectionHeader(text = stringResource(R.string.my_feature_video))
                        }
                        items(videos, key = { it.bookUrl }) { book ->
                            FeatureBookRow(book = book, onClick = { onOpen(book) })
                        }
                    }
                    if (images.isNotEmpty()) {
                        item(key = "image_header") {
                            SectionHeader(text = stringResource(R.string.my_feature_image))
                        }
                        items(images, key = { it.bookUrl }) { book ->
                            FeatureBookRow(book = book, onClick = { onOpen(book) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp)
    )
}

@Composable
private fun FeatureBookRow(
    book: BookShelfDisplay,
    onClick: () -> Unit
) {
    val readText = formatReadTime(book.durChapterTime)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = book.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (readText.isNotBlank()) {
                Text(
                    text = readText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        Text(
            text = book.author,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

private fun formatReadTime(ms: Long): String {
    if (ms <= 0) return ""
    return runCatching {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
    }.getOrNull().orEmpty()
}
