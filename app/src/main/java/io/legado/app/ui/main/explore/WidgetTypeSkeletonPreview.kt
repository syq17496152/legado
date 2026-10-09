package io.legado.app.ui.main.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.main.bookshelf.compose.BookshelfListRenderConfig
import io.legado.app.ui.theme.bodyTertiary

/**
 * 控件类型的「说明 + 约束徽标 + 静态骨架预览」（方向 B3）。
 *
 * 为何用**静态骨架**而非真实数据预览：真实预览需要目标标签的实际请求、分页、去重、封面预取与失败态，
 * 等于把发现页半条加载链搬进编辑器（AD-04 明确否决）。骨架零网络零状态，足以回答"这个类型长什么样"。
 * 取色一律走套件面板 palette（面 token），禁止 M3 派生色。
 */
@Composable
internal fun WidgetTypeInfoCard(
    type: String,
    renderConfig: BookshelfListRenderConfig
) {
    val palette = renderConfig.palette
    val constraint = widgetTargetsConstraint(type)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = widgetTypeDescription(type),
            fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
            fontFamily = palette.bodyFontFamily,
            color = palette.secondaryText
        )
        Text(
            text = widgetTypeConstraintLabel(constraint),
            fontSize = MaterialTheme.typography.labelSmall.fontSize,
            fontWeight = FontWeight.Medium,
            fontFamily = palette.bodyFontFamily,
            color = palette.accent
        )
        WidgetTypeSkeleton(type = type, renderConfig = renderConfig)
    }
}

@Composable
private fun widgetTypeDescription(type: String): String {
    return when (DiscoverySuiteWidgetType.sanitize(type)) {
        DiscoverySuiteWidgetType.TagBar.value -> stringResource(R.string.discovery_suite_type_desc_tag_bar)
        DiscoverySuiteWidgetType.RankButtons.value -> stringResource(R.string.discovery_suite_type_desc_rank_buttons)
        DiscoverySuiteWidgetType.RankedList.value -> stringResource(R.string.discovery_suite_type_desc_ranked_list)
        DiscoverySuiteWidgetType.WaterfallBooks.value -> stringResource(R.string.discovery_suite_type_desc_waterfall_books)
        DiscoverySuiteWidgetType.HorizontalBooks.value -> stringResource(R.string.discovery_suite_type_desc_horizontal_books)
        else -> stringResource(R.string.discovery_suite_type_desc_random_books)
    }
}

@Composable
private fun widgetTypeConstraintLabel(constraint: WidgetTargetConstraint): String {
    return when {
        constraint.min == 1 && constraint.max == 1 ->
            stringResource(R.string.discovery_suite_widget_single_target)
        constraint.min > 1 ->
            stringResource(
                R.string.discovery_suite_widget_rank_targets_range,
                constraint.min,
                constraint.max
            )
        else -> stringResource(R.string.discovery_suite_widget_targets_min)
    }
}

/** 静态布局骨架：用面板色块示意该类型的排布，零网络零状态。 */
@Composable
private fun WidgetTypeSkeleton(
    type: String,
    renderConfig: BookshelfListRenderConfig
) {
    val palette = renderConfig.palette
    // 骨架块取「次级文字色 + 低透明度」而非面板色：模拟器实测 rowPressedColor 与面板底几乎同色 ⇒ 骨架不可见
    val block = palette.secondaryText.copy(alpha = 0.22f)
    when (DiscoverySuiteWidgetType.sanitize(type)) {
        DiscoverySuiteWidgetType.HorizontalBooks.value -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(4) {
                SkeletonBlock(block, Modifier.width(34.dp).height(46.dp))
            }
        }

        DiscoverySuiteWidgetType.TagBar.value -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(5) {
                SkeletonBlock(block, Modifier.width(46.dp).height(24.dp))
            }
        }

        DiscoverySuiteWidgetType.RankButtons.value -> Column(
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(3) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    repeat(3) {
                        SkeletonBlock(block, Modifier.weight(1f).height(30.dp))
                    }
                }
            }
        }

        DiscoverySuiteWidgetType.RankedList.value -> Column(
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(4) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SkeletonBlock(block, Modifier.width(22.dp).height(28.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SkeletonBlock(block, Modifier.fillMaxWidth(0.6f).height(8.dp))
                        SkeletonBlock(block, Modifier.fillMaxWidth(0.35f).height(8.dp))
                    }
                }
            }
        }

        DiscoverySuiteWidgetType.WaterfallBooks.value -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SkeletonBlock(block, Modifier.fillMaxWidth().height(56.dp))
                SkeletonBlock(block, Modifier.fillMaxWidth().height(38.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SkeletonBlock(block, Modifier.fillMaxWidth().height(38.dp))
                SkeletonBlock(block, Modifier.fillMaxWidth().height(56.dp))
            }
        }

        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    repeat(3) {
                        SkeletonBlock(block, Modifier.weight(1f).height(46.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonBlock(
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
    )
}
