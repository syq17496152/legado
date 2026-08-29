package io.legado.app.ui.widget.components

import io.legado.app.ui.widget.components.AppShapes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * 书架骨架屏（加载占位，MoRealm shimmer 思路轻量版：呼吸淡入淡出）
 */
@Composable
fun ShelfGridSkeleton(
    columns: Int = 3,
    itemCount: Int = 9,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )

    val base = MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        for (i in 0 until itemCount step columns) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                for (j in 0 until columns) {
                    if (i + j >= itemCount) break
                    SkeletonItem(alpha = alpha, base = base, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SkeletonItem(
    alpha: Float,
    base: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.alpha(alpha)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(AppShapes.IconContainer)
                .background(base)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(14.dp)
                .clip(AppShapes.Tiny)
                .background(base)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(12.dp)
                .clip(AppShapes.Tiny)
                .background(base)
        )
    }
}

/** 书架列表样式骨架屏（与单列/紧凑列表布局匹配，避免加载闪烁） */
@Composable
fun ShelfListSkeleton(
    compact: Boolean = false,
    itemCount: Int = 6,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "skeletonList")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonListAlpha"
    )

    val base = MaterialTheme.colorScheme.surfaceVariant
    val coverSize = if (compact) 48.dp else 60.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(itemCount) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 76.dp else 96.dp)
                    .alpha(alpha),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(coverSize)
                        .clip(AppShapes.Chip)
                        .background(base)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(16.dp)
                            .clip(AppShapes.Tiny)
                            .background(base)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .height(12.dp)
                            .clip(AppShapes.Tiny)
                            .background(base)
                    )
                }
            }
        }
    }
}