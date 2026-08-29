package io.legado.app.ui.widget.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.ui.widget.compose.rememberAppSettingPalette

/**
 * 设置点击行（36dp RowIcon 图标块 + 标题 + 副标题 + 尾值 + 右箭头）
 */
@Composable
fun SettingsClickRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: () -> Unit,
    trailingIcon: ImageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null
) {
    val palette = rememberAppSettingPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            // 最小行高 60dp，与 View 体系设置行高度对齐（消除 48~72dp 波动）
            .heightIn(min = 60.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            RowIcon(icon = icon)
            Spacer(modifier = Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.secondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (value != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.secondaryText,
                maxLines = 1
            )
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        } else if (enabled) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = palette.secondaryText,
                modifier = Modifier.width(20.dp)
            )
        }
    }
}