package io.legado.app.ui.book.read

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.compose.BookCoverImage
import io.legado.app.ui.widget.image.CoverImageView
import splitties.init.appCtx

/**
 * 沉浸舞台的视觉策略（纯函数，供单测锁定）。
 *
 * 与 UI 解耦的原因：动效闸门/尺寸分档/粒子数量是"可测的行为"，抽出来才能在 JVM 单测里断言，
 * 避免把性能与降级逻辑散落在 Composable 内部无法回归。
 */
internal object ImmersiveStagePolicy {

    /**
     * 唱片直径：矮屏优先保控件，不能按原封面尺寸硬套。
     * 2026-11-02 用户反馈封面转动区域偏小 ⇒ 常屏 208→264dp、矮屏 172→196dp 上调；
     * 极端矮屏维持 156dp，避免压住标题与正文。
     */
    fun discDiameter(veryShort: Boolean, short: Boolean): Dp = when {
        veryShort -> 156.dp
        short -> 196.dp
        else -> 264.dp
    }

    /** 横屏舞台（左右分栏）的唱片直径（横屏高度受限，取能放下的最大值） */
    fun landscapeDiscDiameter(short: Boolean): Dp = if (short) 176.dp else 216.dp

    /**
     * 中心封面占唱片直径的比例：黑胶盘面在深色背景上几乎不可见，
     * 用户感知的"转动区域"实际是封面 ⇒ 从 0.52 提到 0.62 直接放大可感知区域。
     */
    const val COVER_RATIO = 0.62f

    /**
     * 中央播放键形态：
     * 播放中必须显示暂停双杠（用户铁要求"播放时不转圈"）；仅未播放且加载/准备中才显示进度环。
     */
    enum class TransportIcon { Pause, Busy, Play }

    fun transportIcon(playing: Boolean, busy: Boolean): TransportIcon = when {
        playing -> TransportIcon.Pause
        busy -> TransportIcon.Busy
        else -> TransportIcon.Play
    }

    /** 旋转与粒子的统一闸门：仅"前台 + 正在播放 + 非电子墨水"才跑帧 */
    fun shouldAnimate(playing: Boolean, foreground: Boolean, eInk: Boolean): Boolean =
        playing && foreground && !eInk

    /**
     * 粒子数量：停帧或低内存设备为 0（降级关闭），否则 26 个。
     * 上限刻意压在小值：老设备上粒子与朗读合成分帧竞争，宁可少不可卡。
     */
    fun particleCount(lowRam: Boolean, animate: Boolean): Int = when {
        !animate -> 0
        lowRam -> 0
        else -> 26
    }

    /** 黑胶纹理环数量：低内存设备减半，降低每帧 drawCircle 次数 */
    fun vinylGrooveCount(lowRam: Boolean): Int = if (lowRam) 7 else 14

    /**
     * 低配闸门：低内存设备或 API < 26（本项目 minSdk 23，老机 GPU 余量更少）统一降级。
     * 降级动作 = 关粒子 + 纹理环减半，但保留唱片旋转（核心视觉不丢）。
     */
    fun shouldReduceMotion(lowRam: Boolean, sdkInt: Int): Boolean = lowRam || sdkInt < 26

    /**
     * 封面缺失时的中心占位字：取书名首个非空白字符，书名也空则用「书」。
     * 目的：唱片中心不能出现空白黑洞（spec 要求的异常回退）。
     */
    fun coverFallbackLabel(bookName: String): String {
        val first = bookName.trim().firstOrNull()?.toString().orEmpty()
        return first.ifBlank { "书" }
    }

    /**
     * 确定性伪随机相位（0..1）：同一 seed/index 恒定，保证重进面板粒子布局不跳变，
     * 也让单测可以断言"可复现"。
     */
    fun particlePhase(seed: Int, index: Int): Float {
        var x = seed xor (index * -1640531527)
        x = x xor (x ushr 16)
        x *= 0x7feb352d
        x = x xor (x ushr 15)
        x *= -0x7b935960
        x = x xor (x ushr 16)
        val raw = x ushr 8
        return raw.toFloat() / (1 shl 24).toFloat()
    }

    /**
     * 流体背景层的透明度呼吸（0.90..1.00）：shift ∈ [-0.10, 0.10] 线性映射。
     *
     * 性能要点（2026-11-03 真机取证：播放中 janky 92%~99%、50th 20~22ms；暂停后 0 帧；
     * `Slow UI thread` 很低而 `Janky` 高 ⇒ 瓶颈在 GPU 合成，不是绘制）：
     * 旧实现每帧重绘「全屏 linearGradient + 3 个全屏大半径 radialGradient」；
     * 若再叠加全屏平移/放大又会让 GPU 每帧重采样整屏（实测反而更卡）。
     * 最终选择「渐变只画一次 + 每帧仅改 layer alpha」⇒ 无重绘、无重采样，只多一次混合。
     */
    fun fluidLayerAlpha(shift: Float): Float {
        val clamped = shift.coerceIn(-0.10f, 0.10f)
        return 0.90f + (clamped + 0.10f) / 0.20f * 0.10f
    }
}

private fun isReducedMotionDevice(): Boolean {
    val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val lowRam = am?.isLowRamDevice == true
    return ImmersiveStagePolicy.shouldReduceMotion(lowRam, Build.VERSION.SDK_INT)
}

/**
 * 沉浸舞台「黑胶唱片机」：外圈唱片 + 中心封面 + 唱针，全部代码化绘制（零图片素材）。
 *
 * - 唱片与中心封面共用同一自转角（视觉上封面"长"在唱片里）；
 * - 唱针为静态装饰，不随盘面旋转；
 * - 停转由 [ImmersiveStagePolicy.shouldAnimate] 统一判定，暂停后保留当前角度。
 */
@Composable
internal fun ImmersiveVinylDisc(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    diameter: Dp,
    modifier: Modifier = Modifier
) {
    val animate = ImmersiveStagePolicy.shouldAnimate(
        playing = state.playing,
        foreground = state.foregroundActive,
        eInk = AppConfig.isEInkMode
    )
    val lowRam = remember { isReducedMotionDevice() }
    val grooves = ImmersiveStagePolicy.vinylGrooveCount(lowRam)
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        while (true) {
            val start = rotation.value % 360f
            rotation.snapTo(start)
            rotation.animateTo(
                targetValue = start + 360f,
                animationSpec = tween(durationMillis = 16000, easing = LinearEasing)
            )
        }
    }
    val discBase = lerp(colors.panelStrong, colors.background, 0.42f)
    val grooveColor = colors.primaryText.copy(alpha = if (colors.night) 0.16f else 0.10f)
    Box(
        modifier = modifier.size(diameter),
        contentAlignment = Alignment.Center
    ) {
        // 盘面：径向渐变底 + 旋转纹理环
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotation.value }
        ) {
            drawVinylFace(colors, discBase, grooveColor, grooves)
        }
        // 中心封面：随盘面同角旋转；封面缺失时用书名首字占位（不允许空白黑洞）
        val hasCover = !state.coverUrl.isNullOrBlank()
        Box(
            modifier = Modifier
                .size(diameter * ImmersiveStagePolicy.COVER_RATIO)
                .graphicsLayer { rotationZ = rotation.value }
                .clip(CircleShape)
                .background(discBase),
            contentAlignment = Alignment.Center
        ) {
            if (hasCover) {
                BookCoverImage(
                    path = state.coverUrl,
                    name = state.bookName,
                    author = state.author,
                    sourceOrigin = state.sourceOrigin,
                    modifier = Modifier.fillMaxSize(),
                    style = CoverImageView.CoverStyle.FLAT,
                    loadOnlyWifi = false,
                    preferThumb = true,
                    forcePath = state.coverForcePath,
                    allowNameOverlay = state.coverAllowNameOverlay,
                    fillBounds = true
                )
            } else {
                Text(
                    text = ImmersiveStagePolicy.coverFallbackLabel(state.bookName),
                    color = colors.primaryText,
                    fontSize = (diameter.value * 0.20f).sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        // 表层：中心环 + 外缘描边（夜间可辨）+ 静态唱针
        Canvas(modifier = Modifier.fillMaxSize()) {
            val edge = size.minDimension / 2f
            drawCircle(
                color = colors.primaryText.copy(alpha = 0.12f),
                radius = edge * 0.985f,
                style = Stroke(width = 1.dp.toPx())
            )
            drawCircle(
                color = grooveColor,
                radius = edge * 0.66f,
                style = Stroke(width = 1.dp.toPx())
            )
            drawTonearm(colors, edge)
        }
    }
}

/** 盘面：径向渐变 + 同心纹理环（无图片素材，颜色全部由主题色派生） */
private fun DrawScope.drawVinylFace(
    colors: PlayerColors,
    discBase: Color,
    grooveColor: Color,
    grooves: Int
) {
    val radius = size.minDimension / 2f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                colors.fluidA.copy(alpha = 0.10f),
                discBase,
                lerp(discBase, colors.panelBorder, if (colors.night) 0.45f else 0.60f)
            ),
            center = center,
            radius = radius
        ),
        radius = radius
    )
    if (grooves <= 0) return
    val outer = 0.94f
    // 内圈从 0.56 外移到 0.68，避免纹理环压进放大后的封面
    val inner = 0.68f
    for (i in 1..grooves) {
        val ratio = outer - (outer - inner) * (i - 1) / (grooves - 1).coerceAtLeast(1)
        drawCircle(
            color = grooveColor.copy(alpha = grooveColor.alpha * (1f - i.toFloat() / (grooves + 4))),
            radius = radius * ratio,
            style = Stroke(width = 0.8.dp.toPx())
        )
    }
}

/** 唱针：静态装饰（摇臂 + 支点 + 针头），使用主题色而非固定灰 */
private fun DrawScope.drawTonearm(colors: PlayerColors, edge: Float) {
    val armColor = colors.primaryText.copy(alpha = 0.55f)
    val pivot = Offset(edge * 0.80f, edge * 0.16f)
    val head = Offset(edge * 0.56f, edge * 0.60f)
    drawCircle(color = armColor, radius = edge * 0.075f, center = pivot)
    drawCircle(
        color = colors.panelBorder.copy(alpha = 0.9f),
        radius = edge * 0.075f,
        center = pivot,
        style = Stroke(width = 1.5.dp.toPx())
    )
    drawLine(
        color = armColor,
        start = pivot,
        end = head,
        strokeWidth = 2.dp.toPx()
    )
    drawCircle(color = armColor, radius = edge * 0.022f, center = head)
}

/**
 * 氛围粒子点缀层（雪/尘）：铺满父容器，绘制在内容之下。
 *
 * 停帧/低内存时 count=0 直接不组合 Canvas，避免无意义的重绘与耗电；
 * 播放中位置随 progress 线性下落并循环，相位由书籍/章节派生 ⇒ 跨章稳定。
 */
@Composable
internal fun ImmersiveParticleLayer(
    state: ReadAloudPlayerPanel.PlayerUiState,
    colors: PlayerColors,
    modifier: Modifier = Modifier
) {
    val animate = ImmersiveStagePolicy.shouldAnimate(
        playing = state.playing,
        foreground = state.foregroundActive,
        eInk = AppConfig.isEInkMode
    )
    val lowRam = remember { isReducedMotionDevice() }
    val count = ImmersiveStagePolicy.particleCount(lowRam, animate)
    if (count <= 0) return
    val seed = remember(state.bookName, state.paragraphKey) {
        state.bookName.hashCode() xor state.paragraphKey.hashCode()
    }
    val transition = rememberInfiniteTransition(label = "immersiveParticles")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14000, easing = LinearEasing)
        ),
        label = "immersiveParticleProgress"
    )
    // 粒子保持**单层**每帧重绘：双层平移看起来更省，实则多一路全屏合成层，
    // 低端机上 GPU 合成是瓶颈（2026-11-03 实测双层版本 janky 反而升到 98.8%）。
    Canvas(modifier = modifier.fillMaxSize()) {
        repeat(count) { i ->
            val phaseX = ImmersiveStagePolicy.particlePhase(seed, i * 3)
            val phaseY = ImmersiveStagePolicy.particlePhase(seed, i * 3 + 1)
            val phaseA = ImmersiveStagePolicy.particlePhase(seed, i * 3 + 2)
            val radius = (1.2f + phaseA * 1.8f).dp.toPx()
            val x = phaseX * size.width
            val y = ((phaseY + progress) % 1f) * size.height
            drawCircle(
                color = colors.primaryText.copy(alpha = 0.10f + phaseA * 0.10f),
                radius = radius,
                center = Offset(x, y)
            )
        }
    }
}
