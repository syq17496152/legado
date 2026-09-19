package io.legado.app.ui.widget.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.legado.app.ui.widget.compose.AppUiTokens

/**
 * 空态动作（文案 + 回调），供 [EmptyStatePlaceholder] 的 [EmptyStatePlaceholder.primaryAction]
 * 与 [EmptyStatePlaceholder.secondaryActions] 使用。
 */
@Immutable
data class EmptyStateAction(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * 统一空态占位（ui-standards §5：图标 48dp + 主文案 + 次文案 + 可选动作区）。
 * 全站列表/网格页空态统一使用，禁止页面各自实现。
 *
 * 取色基线（ui-standards `architecture.md` 铁律 2）：文字/图标一律走
 * [AppUiTokens.settingPalette] 直色（ThemeStore 链）；**禁止** `MaterialTheme.colorScheme`
 * 的 `onSurfaceVariant`/`outline` 等 M3 派生色——用户自定义主题背景后派生色会明显偏色
 * （H9/H11 教训）。
 *
 * 动作区（A2.4.2 收敛）：单槽 [primaryAction]（实心主按钮）+ 多槽 [secondaryActions]
 * （`palette.row` 底次按钮），**替代**此前的 `actionLabel`/`onAction` 双参 API——
 * 原 API 存在「只给 label 不给回调 → 静默不出按钮」的不一致态。
 */
@Composable
fun EmptyStatePlaceholder(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    primaryAction: EmptyStateAction? = null,
    secondaryActions: List<EmptyStateAction> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val palette = AppUiTokens.settingPalette()
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = palette.secondaryText,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = palette.primaryText,
            textAlign = TextAlign.Center,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.secondaryText,
                textAlign = TextAlign.Center,
            )
        }
        if (primaryAction != null || secondaryActions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                secondaryActions.forEach { action ->
                    // 次动作底走 palette.row（theme-tokens §11.1：禁止用 mutedColor 当按钮底）
                    Surface(
                        onClick = action.onClick,
                        shape = AppShapes.Button,
                        color = Color(palette.row),
                        contentColor = palette.primaryText,
                        tonalElevation = 0.dp,
                    ) {
                        Text(
                            text = action.label,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                if (primaryAction != null) {
                    Button(
                        onClick = primaryAction.onClick,
                        shape = AppShapes.Button,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.accent,
                            contentColor = palette.onAccent,
                        ),
                    ) {
                        Text(text = primaryAction.label)
                    }
                }
            }
        }
    }
}
