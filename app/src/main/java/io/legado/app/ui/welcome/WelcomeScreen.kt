package io.legado.app.ui.welcome

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R

/**
 * 欢迎/启动页 Compose 展示区（L-C17 欢迎页，S6 弹窗/展示页范式）。
 * 纯展示：左侧 accent 竖线 + 主标题 + 副标题 + 书籍图标（accent tint）+ 底部标语。
 * 文字/图标显隐（日/夜两套）由宿主 Activity 桥接状态驱动。
 */
@Composable
fun WelcomeScreen(
    showTitle: Boolean,
    showSubtitle: Boolean,
    showIcon: Boolean,
    showSlogan: Boolean,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    Box(modifier = modifier.fillMaxSize()) {
        // 标题区（居中偏上）
        if (showTitle || showSubtitle) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = -80.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .height(64.dp)
                            .background(primary)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    if (showTitle) {
                        Text(
                            text = stringResource(R.string.welcome_title),
                            color = primary,
                            fontSize = 49.sp,
                            modifier = Modifier.offset(y = 4.dp)
                        )
                    }
                }
                if (showSubtitle) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.welcome_subtitle),
                        color = primary,
                        // 对齐全局正文样式（bodyLarge=16sp，视觉一致且统一管理）
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.offset(x = 12.dp)
                    )
                }
            }
        }

        // 书籍图标（accent tint）
        if (showIcon) {
            Image(
                painter = painterResource(R.drawable.icon_read_book),
                contentDescription = stringResource(R.string.welcome),
                colorFilter = ColorFilter.tint(primary),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 152.dp)
                    .size(120.dp)
            )
        }

        // 底部标语
        if (showSlogan) {
            Text(
                text = stringResource(R.string.welcome_slogan),
                color = primary,
                // 对齐全局正文样式（bodyLarge=16sp，视觉一致且统一管理）
                style = MaterialTheme.typography.bodyLarge,
                letterSpacing = 1.6.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp)
            )
        }
    }
}
