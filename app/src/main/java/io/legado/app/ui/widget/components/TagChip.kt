package io.legado.app.ui.widget.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 小型文本标签（原 ExploreShowScreen/ImportBookScreen 同名私有 TagChip 收敛，task 12.1A）。
 *
 * 语义：secondaryContainer 底 + onSecondaryContainer 字，圆角统一 AppShapes.Chip(8dp)，
 * 可通过 [color]/[contentColor] 覆盖默认配色。
 */
@Composable
fun TagChip(
    text: String,
    color: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = AppShapes.Chip,
        color = color,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
