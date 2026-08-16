package io.legado.app.ui.book.source.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import io.legado.app.ui.widget.components.AppShapes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.ui.widget.components.SwipeActionContainer

/** 校验结果成功标记（对齐存量 BookSourceAdapter.finalMessageRegex「成功|失败」语义） */
private const val SUCCESS_MARK = "成功"

/**
 * 书源列表项（列表视图）：域名分组头 + 封面 + 名称/URL + 校验消息 + 启用开关 + 更多。
 * 左滑露出：编辑 / 调试 / 复制URL。
 */
@Composable
fun BookSourceListItem(
    source: BookSourcePart,
    hostText: String?,
    checkMessage: String?,
    isSelecting: Boolean,
    isChecked: Boolean,
    enabledToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEdit: () -> Unit,
    onDebug: () -> Unit,
    onCopyUrl: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (hostText != null) {
            Text(
                text = hostText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp)
            )
        }
        SwipeActionContainer(
            actionContent = {
                SwipeActionButton(
                    text = stringResource(R.string.edit_source),
                    icon = Icons.Default.Edit,
                    color = MaterialTheme.colorScheme.primary,
                    onClick = onEdit
                )
                SwipeActionButton(
                    text = stringResource(R.string.debug),
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    color = MaterialTheme.colorScheme.tertiary,
                    onClick = onDebug
                )
                SwipeActionButton(
                    text = stringResource(R.string.copy_url),
                    icon = Icons.Default.ContentCopy,
                    color = MaterialTheme.colorScheme.secondary,
                    onClick = onCopyUrl
                )
            }
        ) {
            SourceCardContent(
                source = source,
                checkMessage = checkMessage,
                isSelecting = isSelecting,
                isChecked = isChecked,
                enabledToggle = enabledToggle,
                onClick = onClick,
                onLongClick = onLongClick,
                onMore = onMore
            )
        }
    }
}

/**
 * 书源紧凑列表项：封面(48x64) + 类型徽章 + 名称/URL。
 */
@Composable
fun BookSourceCompactItem(
    source: BookSourcePart,
    isSelecting: Boolean,
    isChecked: Boolean,
    enabledToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .background(
                if (isSelecting && isChecked) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SourceCover(source, width = 48.dp, height = 64.dp)
        Spacer(modifier = Modifier.width(10.dp))
        SourceTypeBadge(type = source.bookSourceType)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.bookSourceName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = source.bookSourceUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!isSelecting) {
            SourceSwitch(enabled = source.enabled, enabledToggle = enabledToggle)
        }
    }
}

/**
 * 书源网格卡片项：封面(3:4) + 名称。
 */
@Composable
fun BookSourceGridItem(
    source: BookSourcePart,
    isSelecting: Boolean,
    isChecked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .clip(AppShapes.Button)
            .background(
                if (isSelecting && isChecked) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SourceCover(source, width = 60.dp, height = 80.dp)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = source.bookSourceName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 列表卡片内容：封面 + 名称/URL/校验消息 + 启用开关 + 更多按钮。
 */
@Composable
private fun SourceCardContent(
    source: BookSourcePart,
    checkMessage: String?,
    isSelecting: Boolean,
    isChecked: Boolean,
    enabledToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(AppShapes.Card)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = AppShapes.Card,
        color = if (isSelecting && isChecked) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SourceCover(source, width = 66.dp, height = 90.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = source.bookSourceName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (source.hasExploreUrl) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (source.enabledExplore) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant
                                )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = source.bookSourceUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (checkMessage != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = checkMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (checkMessage.contains(SUCCESS_MARK))
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!isSelecting) {
                SourceSwitch(enabled = source.enabled, enabledToggle = enabledToggle)
                IconButton(onClick = onMore) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 封面：主题色渐变 + 首字符 + 禁用遮罩。
 * 简化说明：原实现使用写死的蓝灰/棕灰 palette（与主题无关导致视觉不搭），
 * 改为跟随主题色 primary→primaryContainer 渐变，对齐原版 bg_source_folder_cover 的 colorPrimary 底。
 */
@Composable
private fun SourceCover(
    source: BookSourcePart,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp
) {
    Box(
        modifier = Modifier
            .size(width, height)
            .clip(AppShapes.Chip)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primaryContainer
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = source.bookSourceName.take(1).ifEmpty { stringResource(R.string.book_source_cover_fallback) },
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        if (!source.enabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            )
        }
    }
}

/**
 * 类型徽章（紧凑列表）：0文本/1音频/2图片/3文件/4视频。
 */
@Composable
private fun SourceTypeBadge(type: Int) {
    val label = when (type) {
        1 -> stringResource(R.string.type_audio)
        2 -> stringResource(R.string.type_image)
        3 -> stringResource(R.string.type_file)
        4 -> stringResource(R.string.type_video)
        else -> stringResource(R.string.type_text)
    }
    val color = when (type) {
        1 -> Color(0xFF4CAF50)
        2 -> Color(0xFF2196F3)
        3 -> Color(0xFFFF9800)
        4 -> Color(0xFFE53935)
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier
            .clip(AppShapes.Tiny)
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 左滑操作按钮。
 */
@Composable
private fun SwipeActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(96.dp)
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(imageVector = icon, contentDescription = text, tint = color)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1
        )
    }
}

/**
 * 启用开关（Switch 包装，视觉对齐原 ThemeSwitch）。
 */
@Composable
private fun SourceSwitch(enabled: Boolean, enabledToggle: (Boolean) -> Unit) {
    Switch(
        checked = enabled,
        onCheckedChange = enabledToggle,
        colors = SwitchDefaults.colors(
            checkedTrackColor = MaterialTheme.colorScheme.primary
        )
    )
}
