package io.legado.app.ui.config.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.widget.components.SettingsSearchBar
import io.legado.app.ui.widget.compose.AppSettingPalette
import io.legado.app.ui.widget.compose.AppSettingSectionTitle
import io.legado.app.ui.widget.compose.AppThemedStepperSlider
import io.legado.app.ui.widget.compose.LegadoResourceIcon
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.appSettingPanelBackground
import io.legado.app.ui.widget.compose.appSettingRowDecoration
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.theme.bodyTertiary

private val PanelHorizontalPadding = 12.dp

/** 目标行定位成功后的锚点高亮时长（OPTIMIZATION 优化 2：2s 渐隐脉冲） */
internal const val TargetHighlightDurationMs = 2000

/**
 * 设置页渲染框架（全部 [ComposeSettingFragment] 子类共用）。
 *
 * 本框架承载三类页面级优化（`docs/UI/config/compose-setting/OPTIMIZATION.md`）：
 * 1. **页内检索**（P1）：[searchActive] 激活后按 key/searchKeys/title/summary 过滤，
 *    命中关键词高亮 + 计数 + 空态；机制复用外部定位的数据结构，数据零新增。
 * 2. **外部定位反馈闭环**（P2）：定位成功 → [highlightTargetKey] 对应行 accent 描边脉冲；
 *    定位失败 → 由调用方给回执（见 `ComposeSettingFragment`）。
 * 3. **Choice 单一渲染路径**（P0）：所有 Choice 行统一走 `showComposeChoiceListDialog`，
 *    已删除原锚点 Popup 双路径（行为可预期性）。
 */
@Composable
fun SettingSpecScreen(
    page: SettingPageSpec,
    scrollTargetKey: String?,
    drawPanelImage: Boolean = true,
    searchActive: Boolean = false,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    onSearchClose: () -> Unit = {},
    highlightTargetKey: String? = null,
    onTargetReady: (String) -> Unit,
    onTargetMissing: () -> Unit,
    onItemClick: (SettingItemSpec) -> Unit
) {
    val colors = rememberAppSettingPalette()
    val panelRadiusPx = colors.panelRadiusPx
    val allSections = remember(page) {
        page.sections.mapNotNull { section ->
            val rows = section.items.filter { it.visible }
            rows.takeIf { it.isNotEmpty() }?.let {
                SettingSectionSpec(title = section.title, items = it)
            }
        }
    }
    val query = searchQuery.trim()
    val filtering = searchActive && query.isNotEmpty()
    val sections = remember(allSections, filtering, query) {
        if (!filtering) {
            allSections
        } else {
            allSections.mapNotNull { section ->
                val matched = section.items.filter { it.matchesQuery(query) }
                matched.takeIf { it.isNotEmpty() }?.let {
                    SettingSectionSpec(title = section.title, items = it)
                }
            }
        }
    }
    val matchCount = if (filtering) sections.sumOf { it.items.size } else -1

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val targetTitleOffsetPx = with(density) { 38.dp.roundToPx() }
    val targetRowOffsetPx = with(density) { 72.dp.roundToPx() }
    LaunchedEffect(scrollTargetKey, allSections, searchActive) {
        val targetKey = scrollTargetKey?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (searchActive) return@LaunchedEffect
        var targetSectionIndex = -1
        var targetRowIndex = -1
        allSections.forEachIndexed { sectionIndex, section ->
            if (targetSectionIndex >= 0) return@forEachIndexed
            val rowIndex = section.items.indexOfFirst {
                it.key == targetKey || targetKey in it.searchKeys
            }
            if (rowIndex >= 0) {
                targetSectionIndex = sectionIndex
                targetRowIndex = rowIndex
            }
        }
        if (targetSectionIndex >= 0) {
            val section = allSections[targetSectionIndex]
            val scrollOffset = targetRowIndex * targetRowOffsetPx +
                if (section.title.isNullOrBlank()) 0 else targetTitleOffsetPx
            listState.animateScrollToItem(targetSectionIndex, scrollOffset)
            onTargetReady(targetKey)
        } else {
            onTargetMissing()
        }
    }

    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = colors.bodyFontFamily)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.page)
                .navigationBarsPadding()
        ) {
            if (searchActive) {
                SettingSearchHeader(
                    query = searchQuery,
                    matchCount = matchCount,
                    colors = colors,
                    onQueryChange = onSearchQueryChange,
                    onClose = onSearchClose
                )
            }
            if (filtering && matchCount == 0) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.settings_search_no_result),
                        color = colors.secondaryText,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(
                        sections,
                        key = { index, section ->
                            "$index:${section.title}:${section.items.firstOrNull()?.key}"
                        }
                    ) { _, section ->
                        SettingSectionPanel(
                            section = section,
                            colors = colors,
                            panelRadiusPx = panelRadiusPx,
                            drawPanelImage = drawPanelImage,
                            highlightQuery = if (filtering) query else "",
                            highlightTargetKey = highlightTargetKey,
                            onItemClick = onItemClick
                        )
                    }
                }
            }
        }
    }
}

/** 页内检索头部：搜索框 + 取消 + 命中计数（OPTIMIZATION 优化 1，新增文案见 strings）。 */
@Composable
private fun SettingSearchHeader(
    query: String,
    matchCount: Int,
    colors: AppSettingPalette,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            SettingsSearchBar(
                query = query,
                onQueryChange = onQueryChange,
                placeholder = stringResource(R.string.settings_search),
                focusRequester = focusRequester,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClose) {
                Text(
                    text = stringResource(R.string.cancel),
                    color = colors.accent,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize
                )
            }
        }
        if (matchCount >= 0) {
            Text(
                text = stringResource(R.string.settings_search_match_count, matchCount),
                color = colors.secondaryText,
                fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)
            )
        }
    }
}

/** 命中关键词高亮（accent + 加粗），无查询时原样返回。 */
private fun highlightMatches(text: CharSequence, query: String, color: Color): AnnotatedString {
    if (query.isEmpty()) return AnnotatedString(text.toString())
    val source = text.toString()
    return buildAnnotatedString {
        var cursor = 0
        while (cursor < source.length) {
            val index = source.indexOf(query, cursor, ignoreCase = true)
            if (index < 0) {
                append(source.substring(cursor))
                break
            }
            append(source.substring(cursor, index))
            withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) {
                append(source.substring(index, index + query.length))
            }
            cursor = index + query.length
        }
    }
}

@Composable
private fun SettingSectionPanel(
    section: SettingSectionSpec,
    colors: AppSettingPalette,
    panelRadiusPx: Float,
    drawPanelImage: Boolean,
    highlightQuery: String,
    highlightTargetKey: String?,
    onItemClick: (SettingItemSpec) -> Unit
) {
    val context = LocalContext.current
    val panelImage = if (drawPanelImage) {
        remember(context, panelRadiusPx, colors.themeSignature) {
            UiCorner.panelImageDrawable(context, panelRadiusPx)
        }
    } else {
        null
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PanelHorizontalPadding)
            .appSettingPanelBackground(
                normalColor = colors.row,
                panelImage = panelImage,
                borderColor = colors.border,
                radiusPx = panelRadiusPx
            )
    ) {
        AppSettingSectionTitle(title = section.title, palette = colors)
        section.items.forEachIndexed { index, item ->
            SettingRow(
                item = item,
                colors = colors,
                panelRadiusPx = panelRadiusPx,
                showDivider = index != section.items.lastIndex,
                isLast = index == section.items.lastIndex,
                highlightQuery = highlightQuery,
                highlighted = highlightTargetKey != null && item.key == highlightTargetKey,
                onItemClick = onItemClick
            )
        }
    }
}

@Composable
private fun SettingRow(
    item: SettingItemSpec,
    colors: AppSettingPalette,
    panelRadiusPx: Float,
    showDivider: Boolean,
    isLast: Boolean,
    highlightQuery: String,
    highlighted: Boolean,
    onItemClick: (SettingItemSpec) -> Unit
) {
    if (item is SettingSliderSpec) {
        SettingSliderRow(
            item = item,
            colors = colors,
            panelRadiusPx = panelRadiusPx,
            showDivider = showDivider,
            isLast = isLast,
            highlightQuery = highlightQuery,
            highlighted = highlighted
        )
        return
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val dialogStyle = rememberAppDialogStyle()
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
                isLast = isLast
            )
            .targetHighlight(highlighted, colors.accent, panelRadiusPx)
            .settingRowClick(
                item = item,
                interactionSource = interactionSource,
                onItemClick = onItemClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        SettingText(
            item = item,
            colors = colors,
            highlightQuery = highlightQuery,
            modifier = Modifier.weight(1f)
        )
        when (item) {
            is SettingSwitchSpec -> {
                Spacer(modifier = Modifier.width(12.dp))
                val palette = dialogStyle.toMiuixPalette()
                LegadoMiuixSwitch(
                    checked = item.checked,
                    onCheckedChange = item.onCheckedChange,
                    palette = palette,
                    enabled = item.enabled
                )
            }

            is SettingChoiceSpec -> {
                Spacer(modifier = Modifier.width(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(dialogStyle.actionRadius))
                        .background(colors.accent.copy(alpha = if (item.enabled) 0.10f else 0.05f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    item.selectedOption?.iconName?.let { iconName ->
                        LegadoResourceIcon(
                            iconName = iconName,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = item.selectedLabel.toString(),
                        color = if (item.enabled) colors.accent else colors.disabledText,
                        fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            is SettingSliderSpec -> Unit
            is SettingActionSpec -> Unit
        }
    }
}

/**
 * 目标行定位锚点：2s accent 描边渐隐脉冲（OPTIMIZATION 优化 2）。
 * 走 [drawBehind] 在绘制阶段读 [Animatable] 值 ⇒ 仅失效绘制、不逐帧重组。
 */
@Composable
private fun Modifier.targetHighlight(
    highlighted: Boolean,
    color: Color,
    radiusPx: Float
): Modifier {
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(highlighted) {
        if (highlighted) {
            pulse.snapTo(1f)
            pulse.animateTo(0f, animationSpec = tween(durationMillis = TargetHighlightDurationMs))
        } else {
            pulse.snapTo(0f)
        }
    }
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { 2.dp.toPx() }
    return this.drawBehind {
        val alpha = pulse.value
        if (alpha <= 0.01f) return@drawBehind
        drawRoundRect(
            color = color.copy(alpha = alpha),
            cornerRadius = CornerRadius(radiusPx, radiusPx),
            style = Stroke(width = strokeWidthPx)
        )
    }
}

private fun Modifier.settingRowClick(
    item: SettingItemSpec,
    interactionSource: MutableInteractionSource,
    onItemClick: (SettingItemSpec) -> Unit
): Modifier {
    val action = item as? SettingActionSpec
    val onLongClick = action?.onLongClick
    return if (onLongClick != null) {
        combinedClickable(
            enabled = item.enabled,
            interactionSource = interactionSource,
            indication = null,
            role = item.clickRole(),
            onClick = { onItemClick(item) },
            onLongClick = onLongClick
        )
    } else {
        clickable(
            enabled = item.enabled,
            interactionSource = interactionSource,
            indication = null,
            role = item.clickRole(),
            onClick = { onItemClick(item) }
        )
    }
}

private fun SettingItemSpec.clickRole(): Role {
    return when (this) {
        is SettingSwitchSpec -> Role.Switch
        is SettingActionSpec,
        is SettingChoiceSpec -> Role.Button
        is SettingSliderSpec -> Role.Button
    }
}

@Composable
private fun SettingSliderRow(
    item: SettingSliderSpec,
    colors: AppSettingPalette,
    panelRadiusPx: Float,
    showDivider: Boolean,
    isLast: Boolean,
    highlightQuery: String,
    highlighted: Boolean
) {
    val palette = rememberAppDialogStyle().toMiuixPalette()
    val sliderValue = item.value.coerceIn(item.valueRange)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 72.dp)
            .appSettingRowDecoration(
                pressed = false,
                pressedColor = colors.rowPressed,
                dividerColor = colors.divider,
                showDivider = showDivider,
                radiusPx = panelRadiusPx,
                isLast = isLast
            )
            .targetHighlight(highlighted, colors.accent, panelRadiusPx)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingText(
                item = item,
                colors = colors,
                highlightQuery = highlightQuery,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = item.valueFormatter(sliderValue),
                color = if (item.enabled) colors.accent else colors.disabledText,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        AppThemedStepperSlider(
            value = sliderValue,
            range = item.valueRange,
            onValueChange = { item.onValueChange(it.coerceIn(item.valueRange)) },
            palette = palette,
            modifier = Modifier.fillMaxWidth(),
            enabled = item.enabled,
            step = item.step,
            onValueChangeFinished = item.onValueChangeFinished
        )
    }
}

@Composable
private fun SettingText(
    item: SettingItemSpec,
    colors: AppSettingPalette,
    highlightQuery: String,
    modifier: Modifier = Modifier
) {
    val titleColor = if (item.enabled) colors.primaryText else colors.disabledText
    val summaryColor = if (item.enabled) colors.secondaryText else colors.disabledText
    Column(modifier = modifier) {
        // 无检索时直接用原始 CharSequence（保留 AnnotatedString 的多行/角色着色）；
        // 检索时叠加关键词高亮（按字符串重新处理，行间着色让步于检索语义）
        val titleText: AnnotatedString = if (highlightQuery.isEmpty()) {
            AnnotatedString(item.title.toString())
        } else {
            highlightMatches(item.title, highlightQuery, colors.accent)
        }
        Text(
            text = titleText,
            color = titleColor,
            fontSize = MaterialTheme.typography.bodyLarge.fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        item.summary?.takeIf { it.isNotBlank() }?.let { summary ->
            Spacer(modifier = Modifier.height(8.dp))
            // 已是 AnnotatedString（如 AI 设置的角色分行摘要）直接复用，保留其分段着色
            val summaryText: AnnotatedString = when {
                highlightQuery.isNotEmpty() -> highlightMatches(summary, highlightQuery, colors.accent)
                summary is AnnotatedString -> summary
                else -> AnnotatedString(summary.toString())
            }
            Text(
                text = summaryText,
                color = summaryColor,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
