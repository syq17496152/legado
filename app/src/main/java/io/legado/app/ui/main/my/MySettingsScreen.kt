package io.legado.app.ui.main.my

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import io.legado.app.ui.widget.components.AppShapes
import io.legado.app.ui.widget.components.MetricItem
import io.legado.app.ui.widget.components.highlightMatches
import androidx.compose.material3.MaterialTheme
import splitties.init.appCtx

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
    val rows: List<VisibleRow>,
    /**
     * LazyColumn 稳定 key（F31）：搜索置顶桶与分区桶会共存，标题不再唯一 ⇒ 用独立 key。
     */
    val key: String
)

private data class VisibleRow(
    val row: MySettingsRowModel,
    val summary: String,
    val searchTarget: MySettingsSubSearchItem?,
    /** F30：命中子项数（>1 时行尾显示「含 N 项」徽章，提示点击后还有别的子项） */
    val matchedSubItemCount: Int = 0
)

private val SettingsHorizontalPadding = 12.dp

@Composable
internal fun MySettingsScreen(
    sections: List<MySettingsSectionModel>,
    subSearchItems: List<MySettingsSubSearchItem>,
    searchQuery: String,
    metrics: List<MetricItem> = emptyList(),
    profileName: String = "",
    profileSubtitle: String = "",
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
                // F26：恢复个性化头部（原 if(false) 隐藏）。
                // ⚠️ 必须「项常驻 + 内容门控」：把 if 写在 item 注册处会**永久不出现**——
                // LazyColumn 的 item 列表在首次组合后不再重算（实测 metrics 0→4 时组合已重跑，
                // 但条件注册的 item 始终缺席）；而 item **内容**的重组是可靠的（同 Web 服务徽章）。
                item("profile") {
                    if (searchQuery.isBlank() && metrics.isNotEmpty()) {
                        MyProfileHeader(
                            metrics = metrics,
                            userName = profileName,
                            userSubtitle = profileSubtitle,
                            colors = colors,
                            panelRadiusPx = panelRadiusPx
                        )
                    }
                }
                items(visibleSections, key = { it.key }) { section ->
                    SettingsSectionCard(
                        section = section,
                        colors = colors,
                        panelRadiusPx = panelRadiusPx,
                        themeModeLabel = themeModeLabel,
                        webServiceState = webServiceState,
                        highlightQuery = searchQuery.trim(),
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

/**
 * 我的页个性化头部（F26）：头像 + 名称/版本 + 资产指标胶囊。
 *
 * 指标严格 ≤4 枚（书架书籍 / 使用书源 / 订阅源 / 累计阅读，口径见 MyFragment.buildMetricItems），
 * 不混运营推荐流（私人领地原则）；数据未就绪时由调用方跳过整块渲染，不出现空壳。
 */
@Composable
private fun MyProfileHeader(
    metrics: List<MetricItem>,
    userName: String,
    userSubtitle: String,
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
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(AppShapes.Circle)
                    .background(colors.accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.MenuBook,
                    contentDescription = null,
                    tint = colors.onAccent,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = userName,
                    color = colors.primaryText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (userSubtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = userSubtitle,
                        color = colors.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            metrics.take(4).forEach { metric ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(AppShapes.Button)
                        .background(Color(colors.rowPressed))
                        .padding(vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = metric.value,
                        color = colors.accent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = metric.label,
                        color = colors.secondaryText,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
    /** F30：非空时把命中片段标为 accent（空串 = 未搜索，渲染与原来完全一致） */
    highlightQuery: String,
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
                    highlightQuery = highlightQuery,
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
                    highlightQuery = highlightQuery,
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
                    highlightQuery = highlightQuery,
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
    highlightQuery: String,
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
                text = highlightMatches(item.row.title, highlightQuery, colors.accent),
                color = textColor,
                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.summary.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = highlightMatches(item.summary, highlightQuery, colors.accent),
                    color = colors.secondaryText,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // F30：命中多个子项时给出「含 N 项」，前置说明「点下去还会看到别的子项」（单命中不显示，避免仪式感）
        if (item.matchedSubItemCount > 1) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.settings_search_sub_item_count, item.matchedSubItemCount),
                color = colors.accent,
                fontSize = MaterialTheme.typography.labelMedium.fontSize,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(colors.accent.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
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
    highlightQuery: String,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = highlightMatches(item.row.title, highlightQuery, colors.accent),
                    color = colors.primaryText,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // 状态双通道：开关之外再给「● 运行中」徽章（仅靠开关变色时，运行态一眼认不出）
                if (state.checked) {
                    Spacer(modifier = Modifier.width(8.dp))
                    RunningBadge(colors)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = highlightMatches(state.summary, highlightQuery, colors.accent),
                color = colors.secondaryText,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
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
private fun RunningBadge(colors: AppSettingPalette) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(AppShapes.Capsule)
            .background(colors.accent.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(AppShapes.Circle)
                .background(colors.accent)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.web_service_running),
            color = colors.accent,
            fontSize = MaterialTheme.typography.labelMedium.fontSize,
            maxLines = 1
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
                fontSize = MaterialTheme.typography.bodyMedium.fontSize
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
    val filtered = sections.mapNotNull { section ->
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
                    searchTarget = matchedSubItems.firstOrNull(),
                    matchedSubItemCount = matchedSubItems.size
                )
            } else {
                null
            }
        }
        rows.takeIf { it.isNotEmpty() }?.let {
            VisibleSection(title = section.title, rows = it, key = "s:${section.title}")
        }
    }
    if (query.isBlank()) return filtered
    return pinExactTitleMatches(filtered, query)
}

/**
 * F31：精确命中置顶。
 *
 * 命中口径只认**标题完全匹配 / 前缀匹配**（强意图）；其余（摘要 / key / 子项命中）保持原分区顺序跟随。
 * 两桶都有内容时才分桶并给「精确匹配」组标题——**全部命中都精确时不分桶**，避免只有一组还加标题的仪式感。
 * 纯内存 O(n) 分桶，不引入相关度评分（4 路匹配口径不变）。
 */
private fun pinExactTitleMatches(
    sections: List<VisibleSection>,
    query: String
): List<VisibleSection> {
    val exact = mutableListOf<VisibleRow>()
    val rest = mutableListOf<VisibleSection>()
    sections.forEach { section ->
        val (hit, miss) = section.rows.partition { it.isExactTitleMatch(query) }
        exact += hit
        miss.takeIf { it.isNotEmpty() }?.let { rest += section.copy(rows = it) }
    }
    if (exact.isEmpty() || rest.isEmpty()) return sections
    return listOf(
        VisibleSection(
            title = appCtx.getString(R.string.settings_search_exact_group),
            rows = exact,
            key = "exact"
        )
    ) + rest
}

/** F31：标题完全匹配或前缀匹配（query 已 trim + lowercase） */
private fun VisibleRow.isExactTitleMatch(query: String): Boolean {
    val title = row.title.lowercase()
    return title == query || title.startsWith(query)
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
