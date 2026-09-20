package io.legado.app.ui.qrcode

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.widget.compose.AppUiTokens

/**
 * 扫码页覆盖层（qrcode/qrcode 优化 F188/F189 + 合规修复 A3-1/A3-2/A3-3）。
 *
 * 覆盖在相机预览之上的一层控制面，承载：扫描引导与全屏识别说明、解码等待/成功微确认、
 * 解码失败提示条（可重试）、相机权限受限兜底卡（去授权 / 从相册识别）。
 *
 * 取色说明（沉浸特例）：预览层是硬件画面、不随主题明暗变化，直接压在预览上的**文字/图标**
 * 必须恒为「浅色压深底」（蓝图 preview-optimized.html 明确 rgba(255,255,255,α) + 文字投影），
 * 故 [OnPreviewPrimary]/[OnPreviewSecondary]/[OnPreviewSurface]/[OnPreviewStroke] 为**本页私有沉浸色**；
 * 浮层内的**卡片**（加载/失败/权限）仍走主题 token（surface/onSurface/onSurfaceVariant），
 * 语义色统一走 [AppUiTokens]（架构铁律 1 登记豁免点）。
 */
@Composable
internal fun QrCodeOverlay(
    decoding: Boolean,
    decodeSucceeded: Boolean,
    decodeFailed: Boolean,
    cameraBlocked: Boolean,
    onGalleryClick: () -> Unit,
    onRetryClick: () -> Unit,
    onGrantClick: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 底部栈：失败提示条 → 扫描引导 → 相册入口（自下而上对应蓝图 bottom:180/588/104 的层次）
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (decodeFailed) {
                DecodeFailedBanner(onRetryClick = onRetryClick)
                Spacer(Modifier.height(16.dp))
            }
            // 相机不可用时不再给「对准取景框」引导（此态下引导语自相矛盾）
            if (!cameraBlocked) {
                ScanHint()
                Spacer(Modifier.height(18.dp))
            }
            GalleryEntry(onClick = onGalleryClick)
        }

        if (cameraBlocked) {
            PermissionCard(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 88.dp, start = 26.dp, end = 26.dp),
                onGalleryClick = onGalleryClick,
                onGrantClick = onGrantClick,
            )
        }

        if (decoding) {
            // 遮罩兼触摸拦截：解码期间防重复选图（置灰之外再加一层硬防护）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PreviewScrim)
                    .pointerInput(Unit) { detectTapGestures { } },
            )
            DecodingCard(
                succeeded = decodeSucceeded,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/* ============================ 沉浸色（仅本文件：压在相机预览上的浅色元素） ============================ */

private val OnPreviewPrimary = Color.White
private val OnPreviewSecondary = Color.White.copy(alpha = 0.80f)
private val OnPreviewSurface = Color.White.copy(alpha = 0.14f)
private val OnPreviewStroke = Color.White.copy(alpha = 0.20f)
private val PreviewScrim = Color.Black.copy(alpha = 0.55f)

/** 压在预览上的文字需要投影才可读（蓝图 text-shadow: 0 1px 4px rgba(0,0,0,0.8)） */
private val OnPreviewShadow = Shadow(
    color = Color.Black.copy(alpha = 0.80f),
    offset = Offset(0f, 1f),
    blurRadius = 4f,
)

/* ============================ 局部组件 ============================ */

/** 扫描引导：主文案 + 「支持全屏识别」说明徽标（消除取景框「仅框内有效」的视觉误导，A3-2） */
@Composable
private fun ScanHint() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.qr_scan_hint),
            color = OnPreviewPrimary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            style = TextStyle(shadow = OnPreviewShadow),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.qr_scan_full_area),
            color = OnPreviewPrimary,
            fontSize = 11.sp,
            style = TextStyle(shadow = OnPreviewShadow),
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                    shape = CircleShape,
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                    shape = CircleShape,
                )
                .padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

/** 相册识别大入口：与顶栏相册图标同源能力（复用同一 selectQrImage 回调链），把替代路径摆到手指处 */
@Composable
private fun GalleryEntry(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(color = OnPreviewSurface, shape = CircleShape)
                .border(width = 1.dp, color = OnPreviewStroke, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.PhotoLibrary,
                contentDescription = null,
                tint = OnPreviewPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.qr_gallery_entry),
            color = OnPreviewSecondary,
            fontSize = 11.sp,
            style = TextStyle(shadow = OnPreviewShadow),
        )
    }
}

/** 解码失败提示条：danger 语义 + 更换建议 + 重试入口（A3-3 禁止静默失败） */
@Composable
private fun DecodeFailedBanner(onRetryClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                shape = RoundedCornerShape(12.dp),
            )
            .border(
                width = 1.dp,
                color = AppUiTokens.danger.copy(alpha = 0.45f),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(color = AppUiTokens.danger.copy(alpha = 0.13f), shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = AppUiTokens.danger,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.qr_decode_failed_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(R.string.qr_decode_failed_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.retry),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clickable(onClick = onRetryClick)
                .padding(horizontal = 4.dp, vertical = 6.dp),
        )
    }
}

/** 解码等待卡：识别中（转圈 + 说明）→ 成功微确认（✓ 已识别），让长等待与「收到了」都可感知（F188） */
@Composable
private fun DecodingCard(succeeded: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 26.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (succeeded) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.qr_decode_succeeded),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(30.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.qr_decoding),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.qr_decoding_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
            )
        }
    }
}

/** 相机权限受限兜底卡：死态转活态——两个出口全部复用既有实现（跳系统授权 + 相册识别）（F189） */
@Composable
private fun PermissionCard(
    modifier: Modifier,
    onGalleryClick: () -> Unit,
    onGrantClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                shape = RoundedCornerShape(18.dp),
            )
            .padding(horizontal = 20.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.PhotoCamera,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(38.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.qr_camera_blocked_title),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.qr_camera_blocked_desc),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val accent = MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(color = accent, shape = RoundedCornerShape(12.dp))
                    .clickable(onClick = onGalleryClick)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.qr_gallery_entry),
                    color = AppUiTokens.onAccent(accent),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable(onClick = onGrantClick)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.qr_camera_blocked_grant),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}