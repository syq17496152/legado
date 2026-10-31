package io.legado.app.ui.book.read.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 内置离线朗读管理页的视觉基线（第三轮视觉重做）。
 *
 * 取色纪律（G-02）：只用主题直通键 `primary / onPrimary / primaryContainer / onSurface /
 * onSurfaceVariant / error`；禁用 M3 lerp 派生键（surface / surfaceVariant / outline /
 * secondaryContainer），避免换主题色后偏色。
 *
 * 组件化目的：卡片、状态胶囊、指标格、分段页签、音色头像各只有一处实现，
 * 页面代码只负责"放什么"，不再各自拼 Row/Text 样式（此前 5 页签各写一套 ⇒ 观感割裂）。
 */

/** 状态胶囊语义：正向=主题色，警告/错误=危险色，中性=次要文字色 */
internal enum class StatusTone { POSITIVE, NEUTRAL, WARNING, ERROR }

@Composable
private fun toneForeground(tone: StatusTone): Color = when (tone) {
    StatusTone.POSITIVE -> MaterialTheme.colorScheme.primary
    StatusTone.WARNING, StatusTone.ERROR -> MaterialTheme.colorScheme.error
    StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun toneBackground(tone: StatusTone): Color = when (tone) {
    StatusTone.POSITIVE -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    StatusTone.WARNING, StatusTone.ERROR -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
    StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
}

/** 状态胶囊：短标签（已就绪 / 当前 / 推荐 / 未配声），一眼看出状态而不抢标题 */
@Composable
internal fun StatusPill(
    text: String,
    tone: StatusTone = StatusTone.NEUTRAL,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(toneBackground(tone))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = toneForeground(tone),
            maxLines = 1
        )
    }
}

/** 状态圆点：卡片左侧的"档位灯"，浅色主题下比色块更克制 */
@Composable
internal fun StatusDot(color: Color, size: Int = 9) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/** 分区标题（"语音包/运行状态"这类），统一字号与上下留白 */
@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
    )
}

/** 页面主标题 + 副标题（管理页与场景卡共用同一口径） */
@Composable
internal fun ScreenHeader(title: String, subtitle: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * 通用卡片：细边框分组（不重阴影，浅色主题不发灰）。
 * @param selected 选中态用主题色细描边 + 主题浅容器
 */
@Composable
internal fun AppCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val border = if (selected) {
        androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    } else {
        androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
        )
    }
    val colors = if (selected) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Card(
        modifier = modifier.fillMaxWidth().then(clickable),
        shape = shape,
        border = border,
        colors = colors
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

/** 卡片内的横向分隔线（指标格之间），比 Divider 更细、不占高度 */
@Composable
internal fun HairlineV(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(1.dp)
            .height(28.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    )
}

/** 指标格：大数字 + 小标签（运行状态卡三连格） */
@Composable
internal fun MetricCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/**
 * 分段页签（替换 5 个 FilterChip）：选中项实心主题色，未选中为浅底，
 * 横滚保证窄屏也能一次看到全部入口。
 */
@Composable
internal fun TabStrip(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                        }
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1
                )
            }
        }
    }
}

/** 音色头像：取名字首字，省掉图标资源也能一眼分辨条目 */
@Composable
internal fun VoiceAvatar(name: String) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = BuiltinTtsUiSupport.voiceInitial(name),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
