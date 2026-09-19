package io.legado.app.ui.widget.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.theme.bodyTertiary
import io.legado.app.ui.widget.compose.AppUiTokens

/** 页内批量任务条状态（AD-09 只定语义，载体见 A2.5.2）。 */
enum class InlineTaskState {
    /** 无任务：不渲染（零占位） */
    Idle,

    /** 进行中：转圈 + 进度文案 + 可选取消 */
    Running,

    /** 已结束：仅结果文案（如「已取消（N/M 本已完成）」）+ 可选关闭 */
    Done,
}

/**
 * 页内批量任务条（A2.4.5）。
 *
 * 由书源管理页原私有 `CheckProgressBanner` 提升泛化而来（批D：原 Snackbar 改 Compose 状态驱动横幅），
 * 消除私有重复实现、避免同语义组件并存（ui-standards `architecture.md` 铁律 3）。
 * 视觉与取色沿用原实现（`palette.settings.accent` / `secondaryText`），**无渲染回归**。
 *
 * **取消语义（AD-09）**：取消 = 停止发起新请求，**已完成结果保留、不回滚**，由调用方保证；
 * 本组件不持有任何业务状态，只回调 [onCancel]。结束时调用方把 [state] 置 [InlineTaskState.Done]
 * 并给出结果文案。
 *
 * 取色：走 [AppUiTokens.managementPalette] 的 `settings` 槽（禁止 M3 派生色，H9/H11 教训）。
 */
@Composable
fun InlineTaskBar(
    state: InlineTaskState,
    text: String,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    actionLabel: String = stringResource(R.string.cancel),
) {
    if (state == InlineTaskState.Idle || text.isBlank()) return
    val palette = AppUiTokens.managementPalette()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
    ) {
        if (state == InlineTaskState.Running) {
            CircularProgressIndicator(
                color = palette.settings.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = text,
            color = palette.settings.secondaryText,
            style = MaterialTheme.typography.bodyTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        )
        if (onCancel != null) {
            Text(
                text = actionLabel,
                color = palette.settings.accent,
                style = MaterialTheme.typography.bodyTertiary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
    }
}
