package io.legado.app.ui.widget.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.theme.labelXSmall

/**
 * 圆点角标（替代 BadgedBox，对应 AD-17 策略）。
 *
 * - count = 0 隐藏
 * - count = -1 纯圆点（无数字，新消息红点形态）
 * - count > 0 圆点 + 数字，超过 99 显示 "99+"
 */
@Composable
fun BadgeDot(
    count: Int,
    contentColor: Color = MaterialTheme.colorScheme.error,
    modifier: Modifier = Modifier
) {
    if (count == 0) return
    Box(
        modifier = modifier.background(contentColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (count > 0) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                color = if (badgeTextBright(contentColor)) Color.Black else Color.White,
                // 角标紧凑字号豁免（比 labelSmall 11sp 更小，刻意不纳入 Typography，避免角标过大）
                fontSize = MaterialTheme.typography.labelXSmall.fontSize,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}

internal fun badgeTextBright(color: Color): Boolean =
    color.red * 0.299f + color.green * 0.587f + color.blue * 0.114f > 0.5f
