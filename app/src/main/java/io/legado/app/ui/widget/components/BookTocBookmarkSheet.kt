package io.legado.app.ui.widget.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R

/**
 * 目录/书签双 Tab 底部面板（对应 AD-16/AD-17 阅读浮层形态，MoRealm ChapterBookmarkPanel 改 M3）。
 * 章节/书签数据由调用方以 title 列表注入，点击回调传回下标。
 * 由调用方通过 AppModalBottomSheet 包裹展示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookTocBookmarkSheet(
    chapterList: List<String> = emptyList(),
    bookmarkTitles: List<String> = emptyList(),
    onChapterClick: (Int) -> Unit = {},
    onBookmarkClick: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxWidth()) {
        TabRow(
            selectedTabIndex = selectedTab,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text(stringResource(R.string.source_tab_toc)) },
                icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text(stringResource(R.string.bookmark)) },
                icon = { Icon(Icons.Default.Bookmark, contentDescription = null) }
            )
        }

        if (selectedTab == 0) {
            LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
                itemsIndexed(chapterList) { index, title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChapterClick(index) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
                itemsIndexed(bookmarkTitles) { index, title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onBookmarkClick(index) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}