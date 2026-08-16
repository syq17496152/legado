package io.legado.app.ui.widget.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 登录态辅助 Chip（fork F2，任务 12.3C）。
 *
 * M3 `AssistChip`，chip **18dp** 圆角；`labelMedium`；
 * 已登录 `primaryContainer` / 未登录 `outlined` 无底（配 loginUrl 才显示）；
 * 点击区 ≥48dp（`sizeIn(minHeight=48dp)` 兜底）。
 * 规格：ui-standards §3.4 `AssistChip`（task 12.3C，from MoRealm F2）。
 */
@Composable
fun LoginAssistChip(
    label: String,
    icon: ImageVector,
    loggedIn: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = if (loggedIn) {
        AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val border = if (!loggedIn) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    } else {
        null
    }
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.sizeIn(maxWidth = 18.dp, maxHeight = 18.dp),
            )
        },
        colors = colors,
        border = border,
        modifier = modifier.sizeIn(minHeight = 48.dp),
    )
}