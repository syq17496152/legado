package io.legado.app.ui.association

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.widget.components.AppShapes
import io.legado.app.ui.widget.compose.AppUiTokens
import io.legado.app.utils.ConvertUtils

/**
 * 在线导入阶段（F354）：下载 → 预检校验 → 导入确认。
 * 透明壳此前**全程零反馈**（弱网下像"点了没反应"），本卡把三步状态显式化。
 */
enum class OnlineImportStage { DOWNLOAD, INSPECT, CONFIRM }

/**
 * 进度卡状态。
 *
 * [downloaded] / [total] 来自下载器进度回调；`total == 0` 表示服务端未声明长度
 * （进度条退化为不确定态、不显示假百分比）；[bytesPerSecond] 为 0 时不显示速率。
 */
data class OnlineImportProgressState(
    val stage: OnlineImportStage,
    val sourceHost: String,
    val downloaded: Long = 0L,
    val total: Long = 0L,
    val bytesPerSecond: Long = 0L
)

/**
 * F354：下载/校验进度卡（透明壳居中槽位，替代此前的零反馈）。
 *
 * 取色走 [AppUiTokens.settingPalette]（禁止页内自建取色链）；完成步态复用 `R.color.success`。
 * 进度条自绘（track + fill）而非 M3 `LinearProgressIndicator`：后者在本项目无既有用例，
 * 且 M3 版本间 `progress` 形参签名有破坏性变更，自绘可规避版本耦合（F354 登记）。
 */
@Composable
fun OnlineImportProgressCard(
    state: OnlineImportProgressState,
    onCancel: () -> Unit
) {
    val palette = AppUiTokens.settingPalette()
    val knownTotal = state.total > 0L
    val fraction = if (knownTotal) {
        (state.downloaded.toFloat() / state.total.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Surface(
        color = Color(palette.row),
        shape = AppShapes.Card,
        modifier = Modifier.padding(horizontal = 24.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_download_line),
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = stringResource(stageTitleRes(state.stage)),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.primaryText
                    )
                    if (state.sourceHost.isNotBlank()) {
                        Text(
                            text = state.sourceHost,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.secondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            ProgressTrack(
                fraction = fraction,
                indeterminate = state.stage == OnlineImportStage.DOWNLOAD && !knownTotal,
                fillColor = palette.accent,
                trackColor = palette.divider
            )
            Spacer(modifier = Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (knownTotal) {
                    Text(
                        text = "${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = palette.accent
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = progressDetailText(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.cancel),
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.secondaryText,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(palette.rowPressed))
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StageChip(
                    text = stringResource(R.string.online_import_step_download),
                    done = state.stage != OnlineImportStage.DOWNLOAD,
                    current = state.stage == OnlineImportStage.DOWNLOAD,
                    accent = palette.accent
                )
                StageChip(
                    text = stringResource(R.string.online_import_step_inspect),
                    done = state.stage == OnlineImportStage.CONFIRM,
                    current = state.stage == OnlineImportStage.INSPECT,
                    accent = palette.accent
                )
                StageChip(
                    text = stringResource(R.string.online_import_step_confirm),
                    done = false,
                    current = state.stage == OnlineImportStage.CONFIRM,
                    accent = palette.accent
                )
            }
        }
    }
}

/** 自绘进度条：确定态按 fraction 填充，不确定态跑循环滑块（服务端未给长度时不显示假百分比） */
@Composable
private fun ProgressTrack(
    fraction: Float,
    indeterminate: Boolean,
    fillColor: Color,
    trackColor: Color
) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(shape)
            .background(trackColor)
    ) {
        if (!indeterminate) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(5.dp)
                    .clip(shape)
                    .background(fillColor)
            )
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val trackWidth = maxWidth
                val sliderWidth = trackWidth * 0.3f
                val transition = rememberInfiniteTransition(label = "onlineImportProgress")
                val sliderOffset by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 1200, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "onlineImportProgressOffset"
                )
                Box(
                    modifier = Modifier
                        .offset(x = (trackWidth + sliderWidth) * sliderOffset - sliderWidth)
                        .width(sliderWidth)
                        .height(5.dp)
                        .clip(shape)
                        .background(fillColor)
                )
            }
        }
    }
}

@Composable
private fun StageChip(text: String, done: Boolean, current: Boolean, accent: Color) {
    val background = when {
        current -> accent.copy(alpha = 0.14f)
        done -> colorResource(R.color.success).copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    val foreground = when {
        current -> accent
        done -> colorResource(R.color.success)
        else -> AppUiTokens.settingPalette().disabledText
    }
    Text(
        text = if (done) "✓ $text" else text,
        style = MaterialTheme.typography.labelSmall,
        color = foreground,
        modifier = Modifier
            .background(color = background, shape = RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
private fun progressDetailText(state: OnlineImportProgressState): String {
    if (state.stage != OnlineImportStage.DOWNLOAD) return ""
    val downloaded = ConvertUtils.formatFileSize(state.downloaded)
    val speed = if (state.bytesPerSecond > 0L) {
        " · ${ConvertUtils.formatFileSize(state.bytesPerSecond)}/s"
    } else {
        ""
    }
    if (state.total > 0L) {
        return "$downloaded / ${ConvertUtils.formatFileSize(state.total)}$speed"
    }
    return downloaded + speed
}

private fun stageTitleRes(stage: OnlineImportStage): Int = when (stage) {
    OnlineImportStage.DOWNLOAD -> R.string.online_import_stage_download_title
    OnlineImportStage.INSPECT -> R.string.online_import_stage_inspect_title
    OnlineImportStage.CONFIRM -> R.string.online_import_stage_confirm_title
}