package io.legado.app.ui.widget.components

import io.legado.app.ui.widget.components.AppShapes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.legado.app.ui.widget.compose.rememberAppSettingPalette

/**
 * 卡片容器（含 extra 插槽，MoRealm SettingsCard 思路）：标题行（可带 extra 动作）+ 内容。
 */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    extraSlot: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = rememberAppSettingPalette()
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(
            // H9: 直色体系（palette.row = UiCorner.surfaceColor(themeUiPalette.cardColor)），替代 M3 surfaceVariant
                    containerColor = Color(palette.row)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            if (title != null || extraSlot != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 12.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (title != null) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.accent,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    if (extraSlot != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        extraSlot()
                    }
                }
            }
            content()
        }
    }
}