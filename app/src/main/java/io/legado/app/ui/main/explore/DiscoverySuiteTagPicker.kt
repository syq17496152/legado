package io.legado.app.ui.main.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.ui.main.bookshelf.compose.BookshelfListRenderConfig
import io.legado.app.ui.theme.bodyTertiary

/**
 * 套件编辑器的**分组多选标签选择器**（方向 B2）。
 *
 * 取代原先「`AndroidView` 内嵌 inflate `item_find_book.xml` 复刻源站发现条目」的标签选择方式：
 * 后者的认知负担来自"复刻源站条目"这一形态本身（把勾选标签包装成逛源站），且为 View/Compose 双栈混用。
 * 分组数据 `group` 由 [buildSourceTagOptions] 在派生期算好（零新增数据源）。
 *
 * 取色：一律走套件面板 palette（面 token）+ [appSettingPanelBackground]（与套件页/管理页同族），
 * 禁止 `MaterialTheme.colorScheme.*` 派生色。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiscoverySuiteTagPicker(
    tags: List<DiscoverySuiteTagOption>,
    selectedKeys: Set<String>,
    singleSelection: Boolean,
    renderConfig: BookshelfListRenderConfig,
    onToggle: (DiscoverySuiteTagOption) -> Unit
) {
    val palette = renderConfig.palette
    val groups = remember(tags) { tags.groupBy { it.group }.entries.toList() }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        groups.forEach { entry ->
            val group = entry.key
            val groupTags = entry.value
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (group.isNotBlank()) {
                    Text(
                        text = "$group · ${groupTags.size}",
                        fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                        fontWeight = FontWeight.Medium,
                        fontFamily = palette.bodyFontFamily,
                        color = palette.secondaryText
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    groupTags.forEach { option ->
                        TagOptionChip(
                            text = option.tagTitle,
                            selected = option.key in selectedKeys,
                            singleSelection = singleSelection,
                            renderConfig = renderConfig,
                            onClick = { onToggle(option) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TagOptionChip(
    text: String,
    selected: Boolean,
    singleSelection: Boolean,
    renderConfig: BookshelfListRenderConfig,
    onClick: () -> Unit
) {
    val palette = renderConfig.palette
    // 复用管理页既有 chip 载体（SuiteThemeChipSurface），不新造形态（theme 门禁：同语义必须复用）
    SuiteThemeChipSurface(
        selected = selected,
        renderConfig = renderConfig,
        height = if (singleSelection) 36.dp else 34.dp,
        horizontalPadding = 12.dp,
        onClick = onClick
    ) {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = palette.bodyFontFamily,
            color = if (selected) palette.accent else palette.primaryText
        )
    }
}
