package io.legado.app.ui.widget.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.compose.rememberAppSettingPalette

/**
 * 可选中胶囊 chip（筛选/开关类单源实现，M4 9-10/11 收口）。
 *
 * 取色与 XML 侧 `SearchActivity.createSourceGroupChip` 同口径：未选中 = 次级表面底 + 面板描边 + 主文本色；
 * 选中 = accent 16%（夜间 28%）叠加 + accent 描边与字色。同一「范围/开关 chip」语义不得再出现第二套视觉。
 *
 * @param selected 是否选中；只表达状态，切换语义由 [onClick] 调用方决定
 */
@Composable
fun AppFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = rememberAppSettingPalette()
    val selectedAlpha = if (AppConfig.isNightTheme) 0.28f else 0.16f
    // 未选中底/描边取「次级表面 + 面板描边」token（border 为可空 Int，缺省退化为透明描边）
    val idleBg = Color(palette.rowPressed)
    val idleBorder = palette.border?.let { Color(it) } ?: Color.Transparent
    Text(
        text = text,
        color = if (selected) palette.accent else palette.primaryText,
        fontSize = MaterialTheme.typography.bodySmall.fontSize,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(AppShapes.Capsule)
            .background(if (selected) palette.accent.copy(alpha = selectedAlpha) else idleBg)
            .border(
                width = 1.dp,
                color = if (selected) palette.accent else idleBorder,
                shape = AppShapes.Capsule
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    )
}