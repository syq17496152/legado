package io.legado.app.ui.widget.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.widget.compose.AppUiTokens

/**
 * 一次性引导条（表单页首屏教育入口的**非阻断**形态）。
 *
 * 语义边界：`EmptyStatePlaceholder` 是**空态**的出路、`InlineTaskBar` 是**任务进度**，
 * 本组件是**首次进入的教育入口**——把「进页即弹全屏帮助」降级为「页内一行可点引导 + 可关闭」，
 * 教育入口保留、编辑流不被打断。首个落地页：书源编辑（`book/source-edit` 优化 2）。
 *
 * 一次性由**调用方**持用（页面侧的「已消费/已关闭」判定），本组件不读任何偏好、不持业务状态；
 * 点按整行 = [onAction]，点按右侧 × = [onDismiss]。取色走 [AppUiTokens.settingPalette]
 * （禁止页内自建取色链）；图标走矢量资产（selector 会崩整页，F267）。
 */
@Composable
fun InlineGuideBar(
    text: String,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = AppUiTokens.settingPalette()
    Surface(
        color = palette.accent.copy(alpha = 0.10f),
        shape = AppShapes.Card,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 40.dp)
                .clickable(onClick = onAction)
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_help),
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = palette.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(R.drawable.ic_close_x),
                contentDescription = stringResource(R.string.close),
                tint = palette.secondaryText,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(8.dp)
                    .size(16.dp),
            )
        }
    }
}