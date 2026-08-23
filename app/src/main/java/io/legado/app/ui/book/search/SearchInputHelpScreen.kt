package io.legado.app.ui.book.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.appSettingPanelBackground
import io.legado.app.ui.widget.compose.rememberAppManagementPalette

/**
 * 搜索页「输入帮助区」(书架命中 + 搜索历史)的 Compose 实现，
 * 复用与目录/设置一致的 [rememberAppManagementPalette] 主题色板，随主题(日夜/强调色/面板圆角)走。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchInputHelpScreen(
    bookshelfBooks: List<Book>,
    historyKeywords: List<SearchKeyword>,
    onBookClick: (Book) -> Unit,
    onHistoryClick: (String) -> Unit,
    onHistoryDelete: (SearchKeyword) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    LegadoComposeTheme {
        val palette = rememberAppManagementPalette()
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (bookshelfBooks.isNotEmpty()) {
                SearchHelpCard(
                    palette = palette,
                    title = stringResource(R.string.bookshelf)
                ) {
                    SearchChipFlow {
                        bookshelfBooks.forEach { book ->
                            SearchChip(
                                palette = palette,
                                text = book.name,
                                onClick = { onBookClick(book) }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            SearchHelpCard(
                palette = palette,
                title = stringResource(R.string.searchHistory),
                modifier = Modifier.weight(1f),
                headerAction = {
                    if (historyKeywords.isNotEmpty()) {
                        SearchChip(
                            palette = palette,
                            text = stringResource(R.string.clear),
                            onClick = onClearHistory
                        )
                    }
                }
            ) {
                if (historyKeywords.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.search_history_empty),
                            color = palette.settings.secondaryText,
                            fontSize = 13.sp,
                            fontFamily = palette.settings.bodyFontFamily
                        )
                    }
                } else {
                    SearchChipFlow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        historyKeywords.forEach { keyword ->
                            SearchChip(
                                palette = palette,
                                text = keyword.word,
                                onClick = { onHistoryClick(keyword.word) },
                                onLongClick = { onHistoryDelete(keyword) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHelpCard(
    palette: AppManagementPalette,
    title: String,
    modifier: Modifier = Modifier,
    headerAction: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val radiusDp = with(LocalDensity.current) { palette.settings.panelRadiusPx.toDp() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radiusDp))
            .appSettingPanelBackground(
                normalColor = palette.settings.row,
                panelImage = null,
                borderColor = palette.settings.border,
                radiusPx = palette.settings.panelRadiusPx
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = palette.settings.primaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = palette.settings.titleFontFamily,
                modifier = Modifier.weight(1f)
            )
            headerAction?.invoke()
        }
        Spacer(modifier = Modifier.height(10.dp))
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchChipFlow(
    modifier: Modifier = Modifier,
    content: @Composable FlowRowScope.() -> Unit
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchChip(
    palette: AppManagementPalette,
    text: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val chipRadiusPx = with(LocalDensity.current) { 14.dp.toPx() }
    Box(
        modifier = Modifier
            .heightIn(min = 30.dp)
            .clip(RoundedCornerShape(14.dp))
            .appSettingPanelBackground(
                normalColor = palette.settings.rowPressed,
                panelImage = null,
                borderColor = palette.settings.border,
                radiusPx = chipRadiusPx
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = palette.settings.primaryText,
            fontSize = 12.sp,
            fontFamily = palette.settings.bodyFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}