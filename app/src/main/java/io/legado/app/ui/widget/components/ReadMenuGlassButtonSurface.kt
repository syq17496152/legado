package io.legado.app.ui.widget.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 阅读器顶/底栏按钮原语（S5 阅读器族，P2-reader §3）。
 *
 * 玻璃态（默认）：圆形 `surface α0.8` 底 + 1dp `outlineVariant` 描边，48dp；
 * 普通态：透明无底，视觉 40dp 圆 + 触控 48dp（外层触控层兜底）。
 * 色槽：selected 高亮 `primary` / 常态 `onSurfaceVariant` / 玻璃态底色 `surface α0.8`。
 * 支持长按 + 自定义图标（icon 以 tint 为参的 Composable，兼容 ImageVector 与 drawable）。
 * 规格：ui-standards §3.4 `ReadMenuGlassButtonSurface`（task 12.28，from HapeLee ReadBookMenuBar）。
 */
@Composable
fun ReadMenuGlassButtonSurface(
    icon: @Composable (tint: Color) -> Unit,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    glass: Boolean = true,
    enabled: Boolean = true,
) {
    val tint = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val visualSize = if (glass) 48.dp else 40.dp
    Box(
        // 触控层：恒 48dp（普通态视觉 40dp 时触控仍 ≥48dp）
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
                role = Role.Button,
            )
            .semantics {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            // 视觉层：玻璃态 48dp / 普通态 40dp
            modifier = Modifier
                .size(visualSize)
                .then(
                    if (glass) {
                        Modifier
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            icon(tint)
        }
    }
}
