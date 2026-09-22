package io.legado.app.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.config.BookInfoComponentItem
import io.legado.app.help.config.BookInfoPageStyle
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.AppListSpacing
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import androidx.compose.material3.MaterialTheme
import io.legado.app.ui.theme.bodyTertiary
import io.legado.app.ui.theme.bodySecondary
import io.legado.app.ui.theme.bodyLargeX
import kotlin.math.roundToInt

/** 拖拽位移换算目标索引的近似行高（与既有拖拽实现同口径：固定行高近似，非逐行精确测量） */
private val DragRowHeightApprox = 84.dp

@Composable
internal fun BookInfoManageScreen(
    style: BookInfoPageStyle,
    components: List<BookInfoComponentItem>,
    onBack: () -> Unit,
    onStyleChanged: (BookInfoPageStyle) -> Unit,
    onComponentToggle: (Int, Boolean) -> Unit,
    onReset: () -> Unit,
    onMoveItem: (Int, Int) -> Unit
) {
    val palette = rememberAppManagementPalette()
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    var dragTotalY by remember { mutableStateOf(0f) }
    val itemHeightPx = with(LocalDensity.current) { DragRowHeightApprox.toPx() }

    // followup F5：统一管理族壳（AppManagementScaffold 平移，宿主 View TitleBar 已摘除）
    AppManagementScaffold(
        title = stringResource(R.string.book_info_manage),
        selectedCount = 0,
        totalCount = components.size,
        palette = palette,
        onBack = onBack
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.settings.page)
        ) {
            // Tab bar
            StyleTabBar(
                style = style,
                palette = palette,
                onStyleChanged = onStyleChanged
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Summary text：如实描述各样式与组件配置的关系（组件配置只对现代样式生效）
            val summaryText = when (style) {
                BookInfoPageStyle.CLASSIC -> stringResource(R.string.book_info_style_classic_hint)
                BookInfoPageStyle.IMMERSIVE_COMPOSE -> stringResource(R.string.book_info_style_immersive_hint)
                BookInfoPageStyle.MODERN_COMPOSE -> stringResource(R.string.book_info_components_hint)
            }
            Text(
                text = summaryText,
                color = palette.settings.secondaryText,
                fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                modifier = Modifier.padding(horizontal = 18.dp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            when (style) {
                // 经典样式：固定布局，只提供「切到现代样式编辑组件」动线
                BookInfoPageStyle.CLASSIC -> {
                    Spacer(modifier = Modifier.weight(1f))
                    GotoModernButton(palette = palette, onStyleChanged = onStyleChanged)
                }

                BookInfoPageStyle.IMMERSIVE_COMPOSE -> {
                    // Immersive info panel
                    AppManagementCard(
                        palette = palette,
                        modifier = Modifier
                            .fillMaxWidth(),
                        insidePadding = PaddingValues(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.book_info_style_immersive_title),
                            color = palette.settings.primaryText,
                            fontSize = MaterialTheme.typography.bodyLargeX.fontSize,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.book_info_style_immersive_desc),
                            color = palette.settings.secondaryText,
                            fontSize = 13.5.sp,
                            lineHeight = 20.sp,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        // F98（ui-subpage-optimization）：结构示意占位（封面通栏 + 浮层两栏）
                        ImmersiveStructurePreview(palette = palette)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.book_info_style_preview_note),
                            color = palette.settings.secondaryText,
                            fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // F98 原为「切回经典样式继续编辑组件」，该动线会全局改样式且指向不支持组件配置的样式，
                    // 现改为指向唯一支持组件配置的现代样式（book-info-modern-compose 4.4）
                    GotoModernButton(palette = palette, onStyleChanged = onStyleChanged)

                    Spacer(modifier = Modifier.weight(1f))
                }

                // 现代样式：组件显隐 + 长按拖拽排序（项目内唯一消费方）
                BookInfoPageStyle.MODERN_COMPOSE -> {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(AppListSpacing.Normal)
                    ) {
                        itemsIndexed(
                            items = components,
                            key = { _, item -> item.type.name }
                        ) { index, item ->
                            ComponentItemRow(
                                item = item,
                                palette = palette,
                                onCheckedChange = { checked ->
                                    onComponentToggle(index, checked)
                                },
                                onDragStart = {
                                    dragIndex = index
                                    dragTotalY = 0f
                                },
                                onDrag = { dragAmount ->
                                    dragTotalY += dragAmount
                                    dragIndex?.let { current ->
                                        val target = (current + (dragTotalY / itemHeightPx).roundToInt())
                                            .coerceIn(0, components.lastIndex)
                                        if (target != current) {
                                            onMoveItem(current, target)
                                            dragIndex = target
                                        }
                                    }
                                },
                                onDragEnd = {
                                    dragIndex = null
                                    dragTotalY = 0f
                                }
                            )
                        }
                    }

                    // Reset button
                    LegadoMiuixActionButton(
                        text = stringResource(R.string.reset),
                        palette = palette.miuix,
                        onClick = onReset,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                    )
                }
            }
        }
    }
}

/** 「切到现代样式编辑组件」动线（唯一支持组件配置的样式） */
@Composable
private fun GotoModernButton(
    palette: AppManagementPalette,
    onStyleChanged: (BookInfoPageStyle) -> Unit
) {
    LegadoMiuixActionButton(
        text = stringResource(R.string.book_info_style_goto_modern),
        palette = palette.miuix,
        onClick = { onStyleChanged(BookInfoPageStyle.MODERN_COMPOSE) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    )
}

/**
 * 沉浸样式结构示意（F98）：封面通栏 + 下方浮层两栏（左封面信息 / 右元信息）。
 *
 * 仅用主题色块勾勒结构关系，**不做运行时真实渲染**（依赖详情页渲染上下文，成本不成比例）；
 * 页面另有「结构示意，实际效果以详情页为准」文案兜底，避免用户误当真实预览。
 */
@Composable
private fun ImmersiveStructurePreview(palette: AppManagementPalette) {
    val blockColor = Color(palette.settings.rowPressed)
    Column(modifier = Modifier.fillMaxWidth()) {
        // 封面通栏
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(blockColor)
        )
        Spacer(modifier = Modifier.height(8.dp))
        // 浮层两栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(blockColor)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(blockColor)
            )
        }
    }
}

@Composable
private fun StyleTabBar(
    style: BookInfoPageStyle,
    palette: AppManagementPalette,
    onStyleChanged: (BookInfoPageStyle) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StyleTabButton(
            text = stringResource(R.string.book_info_style_classic),
            selected = style == BookInfoPageStyle.CLASSIC,
            palette = palette,
            onClick = { onStyleChanged(BookInfoPageStyle.CLASSIC) },
            modifier = Modifier.weight(1f)
        )
        StyleTabButton(
            text = stringResource(R.string.book_info_style_immersive),
            selected = style == BookInfoPageStyle.IMMERSIVE_COMPOSE,
            palette = palette,
            onClick = { onStyleChanged(BookInfoPageStyle.IMMERSIVE_COMPOSE) },
            modifier = Modifier.weight(1f)
        )
        StyleTabButton(
            text = stringResource(R.string.book_info_style_modern),
            selected = style == BookInfoPageStyle.MODERN_COMPOSE,
            palette = palette,
            onClick = { onStyleChanged(BookInfoPageStyle.MODERN_COMPOSE) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StyleTabButton(
    text: String,
    selected: Boolean,
    palette: AppManagementPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (selected) {
        palette.settings.accent.copy(alpha = 0.14f)
    } else {
        palette.miuix.surfaceVariant
    }
    val textColor = if (selected) palette.settings.accent else palette.settings.secondaryText

    Box(
        modifier = modifier
            // heightIn(min)：大字号缩放下 Tab 文本撑开防截断
            .heightIn(min = 42.dp)
            .background(backgroundColor, RoundedCornerShape(palette.miuix.actionRadius ?: 12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ComponentItemRow(
    item: BookInfoComponentItem,
    palette: AppManagementPalette,
    onCheckedChange: (Boolean) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit
) {
    AppManagementCard(
        palette = palette,
        modifier = Modifier.fillMaxWidth(),
        insidePadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegadoMiuixSwitch(
                checked = item.enabled,
                onCheckedChange = onCheckedChange,
                palette = palette.miuix
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(item.type.titleRes),
                    color = palette.settings.primaryText,
                    fontSize = MaterialTheme.typography.bodySecondary.fontSize,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(item.type.hintRes),
                    color = palette.settings.secondaryText,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 长按拖拽手柄：与项目既有 7 处拖拽实现同型（detectDragGesturesAfterLongPress + change.consume）
            Icon(
                painter = painterResource(R.drawable.ic_arrange),
                contentDescription = stringResource(R.string.read_record_drag_sort),
                tint = palette.settings.secondaryText.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(24.dp)
                    .pointerInput(item.type) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { onDragStart() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.y)
                            },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() }
                        )
                    }
            )
        }
    }
}