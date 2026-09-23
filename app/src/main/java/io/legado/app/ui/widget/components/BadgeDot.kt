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
import io.legado.app.ui.widget.compose.AppUiTokens

/**
 * 圆点角标（替代 BadgedBox，对应 AD-17 策略）。
 *
 * - count = 0 隐藏
 * - count = -1 纯圆点（无数字，新消息红点形态）
 * - count > 0 圆点 + 数字，超过 99 显示 "99+"
 *
 * 取色（R28/R31 收口，2026-09-23）：
 * - 底色默认走 **danger 语义色单源** `AppUiTokens.danger`（转发 `AppSemanticColors.Danger`）。
 *   改造前取 M3 的 error 键，而本项目 `ThemeSpec` 中该键本身是**硬编码**的浅红/深红字面值
 *   ⇒ 角标色恒红、不随主题（A1/A2 失守）。
 * - 字色走**对比度兜底单源** `contrastOn`（与 View 侧共用 `ColorUtils.contrastOnColor` 判据）；
 *   改造前为自建的亮度加权公式（三套并存之一，R31 已收敛）。
 */
@Composable
fun BadgeDot(
    count: Int,
    contentColor: Color = AppUiTokens.danger,
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
                color = contrastOn(contentColor),
                // 角标紧凑字号豁免（比 labelSmall 11sp 更小，刻意不纳入 Typography，避免角标过大）
                fontSize = MaterialTheme.typography.labelXSmall.fontSize,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}
