package io.legado.app.ui.main.my

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.widget.compose.AppSettingPalette
import io.legado.app.ui.widget.compose.AppSettingSectionTitle
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.appSettingPanelBackground
import io.legado.app.ui.widget.compose.appSettingRowDecoration
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.ui.widget.components.MetricGrid
import io.legado.app.ui.widget.components.MetricItem

internal enum class MySettingsRowKind {
    Action,
    ThemeMode,
    WebService
}

internal data class MySettingsThemeOption(
    val value: String,
    val label: String,
    val summary: String = ""
)

internal data class MySettingsSubSearchItem(
    val ownerKey: String,
    val title: String,
    val summary: String,
    val key: String,
    val ownerConfigTag: String
) {
    val searchText: String = listOf(title, summary, key).joinToString(" ").lowercase()
}

internal data class MySettingsRowModel(
    val key: String,
    val title: String,
    val summary: String? = null,
    val kind: MySettingsRowKind = MySettingsRowKind.Action,
    val danger: Boolean = false
)

internal data class MySettingsSectionModel(
    val title: String,
    val rows: List<MySettingsRowModel>
)

internal data class MyWebServiceUiState(
    val checked: Boolean,
    val summary: String
)

private data class VisibleSection(
    val title: String,
    val rows: List<VisibleRow>
)

private data class VisibleRow(
    val row: MySettingsRowModel,
    val summary: String,
    val searchTarget: MySettingsSubSearchItem?
)

private val SettingsHorizontalPadding = 12.dp

@Composable
internal fun MySettingsScreen(
    sections: List<MySettingsSectionModel>,
    subSearchItems: List<MySettingsSubSearchItem>,
    searchQuery: String,
    metrics: List<MetricItem> = emptyList(),
    themeModeLabel: String,
    webServiceState: MyWebServiceUiState,
    onThemeModeClick: () -> Unit,
    onWebServiceCheckedChange: (Boolean) -> Unit,
    onWebServiceClick: () -> Unit,
    onRowClick: (String, MySettingsSubSearchItem?) -> Unit
) {
    val colors = rememberAppSettingPalette()
    val panelRadiusPx = colors.panelRadiusPx
    val visibleSections = buildVisibleSections(
        sections = sections,
        subSearchItems = subSearchItems,
        searchQuery = searchQuery,
        webServiceState = webServiceState,
        themeModeLabel = themeModeLabel,
    )

    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = colors.bodyFontFamily)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.page)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = dimensionResource(R.dimen.main_content_bottom_bar_padding) + 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 用户反馈（2026-08-22）：隐藏头部四框统计信息，代码保留（metrics/MetricGrid 完整存在），后期优化恢复
                // if (metrics.isNotEmpty()) {
                //     item("metrics") {
                //         MetricGrid(
                //             metrics = metrics,
                //             modifier = Modifier.padding(horizontal = SettingsHorizontalPadding)
                //         )
                //     }
                // }
                if (false) {
                    item("metrics") {
                        MetricGrid(
                            metrics = metrics,
                            modifier = Modifier.padding(horizontal = SettingsHorizontalPadding)
                        )
                    }
                }
                items(visibleSections, key = { it.title }) { section ->
                    SettingsSectionCard(
                        section = section,
                        colors = colors,
                        panelRadiusPx = panelRadiusPx,
                        themeModeLabel = themeModeLabel,
                        webServiceState = webServiceState,
                        onThemeModeClick = onThemeModeClick,
                        onWebServiceCheckedChange = onWebServiceCheckedChange,
                        onWebServiceClick = onWebServiceClick,
                        onRowClick = onRowClick
                    )
                }
                if (visibleSections.isEmpty()) {
                    item("empty") {
                        EmptySettingsFrame(
                            colors = colors,
                            panelRadiusPx = panelRadiusPx
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    section: VisibleSection,
    colors: AppSettingPalette,
    panelRadiusPx: Float,
    themeModeLabel: String,
    webServiceState: MyWebServiceUiState,
    onThemeModeClick: () -> Unit,
    onWebServiceCheckedChange: (Boolean) -> Unit,
    onWebServiceClick: () -> Unit,
    onRowClick: (String, MySettingsSubSearchItem?) -> Unit
) {
    val context = LocalContext.current
    val panelImage = remember(context, panelRadiusPx, colors.themeSignature) {
        UiCorner.panelImageDrawable(context, panelRadiusPx)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsHorizontalPadding)
            .appSettingPanelBackground(
                normalColor = colors.row,
                panelImage = panelImage,
                borderColor = colors.border,
                radiusPx = panelRadiusPx
            )
    ) {
        AppSettingSectionTitle(title = section.title, palette = colors)
        section.rows.forEachIndexed { index, item ->
            val isLastRowInSection = index == section.rows.lastIndex
            val showDivider = !isLastRowInSection
            when (item.row.kind) {
                MySettingsRowKind.ThemeMode -> SettingsActionRow(
                    item = item.copy(summary = themeModeLabel),
                    colors = colors,
                    panelRadiusPx = panelRadiusPx,
                    isFirst = false,
                    isLast = isLastRowInSection,
                    showDivider = showDivider,
                    onClick = onThemeModeClick
                )

                MySettingsRowKind.WebService -> WebServiceRow(
                    item = item,
                    state = webServiceState,
                    colors = colors,
                    panelRadiusPx = panelRadiusPx,
                    isFirst = false,
                    isLast = isLastRowInSection,
                    showDivider = showDivider,
                    onCheckedChange = onWebServiceCheckedChange,
                    onClick = onWebServiceClick
                )

                MySettingsRowKind.Action -> SettingsActionRow(
                    item = item,
                    colors = colors,
                    panelRadiusPx = panelRadiusPx,
                    isFirst = false,
                    isLast = isLastRowInSection,
                    showDivider = showDivider,
                    onClick = { onRowClick(item.row.key, item.searchTarget) }
                )
            }
        }
    }
}

@Composable
private fun SettingsActionRow(
    item: VisibleRow,
    colors: AppSettingPalette,
    panelRadiusPx: Float,
    isFirst: Boolean,
    isLast: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val textColor = if (item.row.danger) colors.danger else colors.primaryText
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .appSettingRowDecoration(
                pressed = pressed,
                pressedColor = colors.rowPressed,
                dividerColor = colors.divider,
                showDivider = showDivider,
                radiusPx = panelRadiusPx,
                isFirst = isFirst,
                isLast = isLast,
                danger = item.row.danger,
                dangerColor = colors.danger
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.row.title,
                color = textColor,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.summary.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = item.summary,
                    color = colors.secondaryText,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun WebServiceRow(
    item: VisibleRow,
    state: MyWebServiceUiState,
    colors: AppSettingPalette,
    panelRadiusPx: Float,
    isFirst: Boolean,
    isLast: Boolean,
    showDivider: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .appSettingRowDecoration(
                pressed = pressed,
                pressedColor = colors.rowPressed,
                dividerColor = colors.divider,
                showDivider = showDivider,
                radiusPx = panelRadiusPx,
                isFirst = isFirst,
                isLast = isLast
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.row.title,
                color = colors.primaryText,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.summary,
                color = colors.secondaryText,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        LegadoMiuixSwitch(
            checked = state.checked,
            onCheckedChange = onCheckedChange,
            palette = colors.toMiuixPalette()
        )
    }
}

@Composable
private fun EmptySettingsFrame(
    colors: AppSettingPalette,
    panelRadiusPx: Float
) {
    val context = LocalContext.current
    val panelImage = remember(context, panelRadiusPx, colors.themeSignature) {
        UiCorner.panelImageDrawable(context, panelRadiusPx)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsHorizontalPadding)
            .appSettingPanelBackground(
                normalColor = colors.row,
                panelImage = panelImage,
                borderColor = colors.border,
                radiusPx = panelRadiusPx
            )
    ) {
        AppSettingSectionTitle(title = "搜索结果", palette = colors)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 60.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = "没有匹配的设置",
                color = colors.secondaryText,
                fontSize = 14.sp
            )
        }
    }
}

private fun buildVisibleSections(
    sections: List<MySettingsSectionModel>,
    subSearchItems: List<MySettingsSubSearchItem>,
    searchQuery: String,
    webServiceState: MyWebServiceUiState,
    themeModeLabel: String
): List<VisibleSection> {
    val query = searchQuery.trim().lowercase()
    return sections.mapNotNull { section ->
        val rows = section.rows.mapNotNull { row ->
            val summary = row.effectiveSummary(webServiceState, themeModeLabel)
            val matchedSubItems = if (query.isBlank()) {
                emptyList()
            } else {
                subSearchItems.filter { item ->
                    item.ownerKey == row.key && item.searchText.contains(query)
                }
            }
            val visible = query.isBlank()
                || row.title.lowercase().contains(query)
                || summary.lowercase().contains(query)
                || row.key.lowercase().contains(query)
                || matchedSubItems.isNotEmpty()
            if (visible) {
                VisibleRow(
                    row = row,
                    summary = matchedSubItems.firstOrNull()?.title ?: summary,
                    searchTarget = matchedSubItems.firstOrNull()
                )
            } else {
                null
            }
        }
        rows.takeIf { it.isNotEmpty() }?.let { VisibleSection(section.title, it) }
    }
}

private fun MySettingsRowModel.effectiveSummary(
    webServiceState: MyWebServiceUiState,
    themeModeLabel: String
): String {
    return when (kind) {
        MySettingsRowKind.ThemeMode -> themeModeLabel
        MySettingsRowKind.WebService -> webServiceState.summary
        MySettingsRowKind.Action -> summary.orEmpty()
    }
}

private fun AppSettingPalette.toMiuixPalette(): LegadoMiuixPalette {
    return LegadoMiuixPalette(
        accent = accent,
        surface = Color(row),
        surfaceVariant = Color(row),
        primaryText = primaryText,
        secondaryText = secondaryText,
        danger = danger,
        onAccent = onAccent
    )
}
