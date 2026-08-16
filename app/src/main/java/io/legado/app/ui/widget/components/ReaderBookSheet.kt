package io.legado.app.ui.widget.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import kotlinx.coroutines.launch

/**
 * 阅读器 目录/书签/高亮 三 Tab 底部面板（P2-reader §3 阅读器族，R2 演进）。
 *
 * 三 Tab 数据由调用方以 title 列表注入，点击回调传回下标；由调用方通过 [AppModalBottomSheet] 包裹展示。
 * Tab 选中 `primary` / 未选中 `onSurfaceVariant`；Tab 标签 `labelLarge`；列表项 `bodyLarge`，
 * 整行 ≥48dp（h16 v12）；列表 maxHeight **72%** 屏高（TabRow + [HorizontalPager] 滑动切换）。
 * 规格：ui-standards §3.4 `ReaderBookSheet`（task 12.2A，from HapeLee sheet/ReaderBookSheet）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderBookSheet(
    chapterList: List<String> = emptyList(),
    bookmarkTitles: List<String> = emptyList(),
    highlightTitles: List<String> = emptyList(),
    onChapterClick: (Int) -> Unit = {},
    onBookmarkClick: (Int) -> Unit = {},
    onHighlightClick: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(initialPage = 0) { 3 }
    val scope = rememberCoroutineScope()
    val listMaxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.72f

    Column(modifier = modifier.fillMaxWidth()) {
        TabRow(
            selectedTabIndex = pagerState.currentPage,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        ) {
            Tab(
                selected = pagerState.currentPage == 0,
                onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                text = {
                    Text(
                        text = stringResource(R.string.source_tab_toc),
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) }
            )
            Tab(
                selected = pagerState.currentPage == 1,
                onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                text = {
                    Text(
                        text = stringResource(R.string.bookmark),
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                icon = { Icon(Icons.Default.Bookmark, contentDescription = null) }
            )
            Tab(
                selected = pagerState.currentPage == 2,
                onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                text = {
                    Text(
                        text = stringResource(R.string.highlight),
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                icon = { Icon(Icons.Default.Highlight, contentDescription = null) }
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = listMaxHeight),
        ) { page ->
            when (page) {
                0 -> ReaderSheetList(chapterList, onChapterClick)
                1 -> ReaderSheetList(bookmarkTitles, onBookmarkClick)
                else -> ReaderSheetList(highlightTitles, onHighlightClick)
            }
        }
    }
}

@Composable
private fun ReaderSheetList(
    titles: List<String>,
    onClick: (Int) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        itemsIndexed(titles) { index, title ->
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick(index) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}
