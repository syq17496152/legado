package io.legado.app.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import io.legado.app.ui.widget.components.AppShapes
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.model.VideoPlay
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.components.SettingsClickRow
import io.legado.app.ui.widget.components.SettingsToggleRow
import io.legado.app.ui.widget.components.SingleChoiceDialog
import kotlin.math.roundToInt

/**
 * R3 阶段5：视频设置面板（Compose 化内容，task 12.4B L-D9 视频设置弹框改造）
 *
 * 由 [VideoSettingsPanel]（BottomSheetDialogFragment 壳）通过 ComposeView 承载。
 * 100% 保留旧 View 面板全部功能：播放控制 / 播放信息 / 功能菜单 / 调试日志 / 播放设置 / 播放器优化。
 * 仅依赖 [VideoPlay] 全局配置与外部回调，无 View 依赖。
 *
 * video-player-ux-fixes P3：取色同源治理（color.md 门禁）——MaterialTheme.colorScheme.* 全部替换
 * 为 rememberAppDialogStyle()（themeUiPalette 同源），Dialog 壳/BottomSheet 壳双入口视觉统一。
 * [showDragHandle]：拖拽手柄为 BottomSheet 专属视觉，Dialog 壳（SettingsDialog）传 false 隐藏。
 */
@Composable
fun VideoSettingsPanelContent(
    videoUrl: String?,
    description: String?,
    showLogin: Boolean,
    debugLog: String,
    pressSpeedSummary: String,
    onDismissRequest: () -> Unit,
    onSkip: (Long) -> Unit,
    onRatio: () -> Unit,
    onAudioTrack: () -> Unit,
    onCopyUrl: () -> Unit,
    onFloatWindow: () -> Unit,
    onOtherPlayer: () -> Unit,
    onEditSource: () -> Unit,
    onLogin: () -> Unit,
    onLog: () -> Unit,
    onPickPressSpeed: () -> Unit,
    showDragHandle: Boolean = true,
) {
    // 播放设置开关状态（初值读自 VideoPlay，变更即写回）
    var autoPlay by remember { mutableStateOf(VideoPlay.autoPlay) }
    var startFull by remember { mutableStateOf(VideoPlay.startFull) }
    var fullBottomProgress by remember { mutableStateOf(VideoPlay.fullBottomProgressBar) }
    var muteOnStart by remember { mutableStateOf(VideoPlay.muteOnStart) }
    var skipTime by remember { mutableStateOf(VideoPlay.videoSkipTime) }
    var cacheSize by remember { mutableStateOf(VideoPlay.videoCacheSize) }
    var cachePlay by remember { mutableStateOf(VideoPlay.videoCache) }
    var playerType by remember { mutableStateOf(VideoPlay.playerType) }
    var seekSensitivity by remember { mutableStateOf(VideoPlay.seekSensitivity) }
    // video-player-image-enhance A2.2: 画质增强状态（初值读自 VideoPlay，变更即写回+实时预览）
    var enhanceEnabled by remember { mutableStateOf(VideoPlay.enhanceEnabled) }
    var enhanceBrightness by remember { mutableStateOf(VideoPlay.enhanceBrightness) }
    var enhanceContrast by remember { mutableStateOf(VideoPlay.enhanceContrast) }
    var enhanceSaturation by remember { mutableStateOf(VideoPlay.enhanceSaturation) }
    var enhanceColorTemp by remember { mutableStateOf(VideoPlay.enhanceColorTemp) }
    var enhancePreset by remember { mutableStateOf(VideoPlay.enhancePreset) }
    // video-player-image-enhance B 批：锐化/降噪档位（0 关 / 1 轻 / 2 中 / 3 强）
    var enhanceSharpenLevel by remember { mutableStateOf(VideoPlay.enhanceSharpenLevel) }
    var enhanceDenoiseLevel by remember { mutableStateOf(VideoPlay.enhanceDenoiseLevel) }
    var firstFramePreload by remember { mutableStateOf(VideoPlay.playerFirstFramePreload) }
    var bufferStrategy by remember { mutableStateOf(VideoPlay.playerBufferStrategy) }
    var historyEnabled by remember { mutableStateOf(VideoPlay.playerHistoryEnabled) }
    var errorTip by remember { mutableStateOf(VideoPlay.playerErrorTip) }
    var autoReconnect by remember { mutableStateOf(VideoPlay.playerAutoReconnect) }
    var showDebug by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<PanelSelection?>(null) }
    // 取色同源（P3）：与 AppDialogFrame 规范壳共用一套色板
    val style = rememberAppDialogStyle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 540.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp)
    ) {
        // 拖拽手柄（BottomSheet 专属，Dialog 壳隐藏）
        if (showDragHandle) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .background(
                            color = style.stroke,
                            shape = AppShapes.Tiny
                        )
                )
            }
        }

        // ====== 播放控制 ======
        SectionHeader(stringResource(R.string.video_play_control))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PanelButton("←30s") { onSkip(-30000) }
            PanelButton("←10s") { onSkip(-10000) }
            PanelButton("10s→") { onSkip(10000) }
            PanelButton("30s→") { onSkip(30000) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PanelButton(stringResource(R.string.video_ratio)) { onRatio() }
            PanelButton(stringResource(R.string.video_audio_track)) { onAudioTrack() }
        }

        // ====== 播放信息 ======
        SectionHeader(stringResource(R.string.video_play_info))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.video_play_url_format, videoUrl ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = style.secondaryText,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            PanelButton(stringResource(R.string.copy_text)) { onCopyUrl() }
        }
        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = style.secondaryText,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // ====== 功能 ======
        SectionHeader(stringResource(R.string.video_function))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PanelButton(stringResource(R.string.float_window)) { onFloatWindow() }
            PanelButton(stringResource(R.string.open_other_video_player)) { onOtherPlayer() }
            PanelButton(stringResource(R.string.edit_source)) { onEditSource() }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showLogin) {
                PanelButton(stringResource(R.string.login)) { onLogin() }
            }
            PanelButton(stringResource(R.string.log)) { onLog() }
            PanelButton(stringResource(R.string.debug)) { showDebug = !showDebug }
        }
        if (showDebug) {
            Text(
                text = debugLog,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = style.primaryText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .background(
                        color = style.fieldSurface,
                        shape = AppShapes.Chip
                    )
                    .padding(8.dp)
            )
        }

        // ====== 播放设置 ======
        SectionHeader(stringResource(R.string.video_play_setting))
        SettingsToggleRow(
            icon = null,
            title = stringResource(R.string.auto_play),
            checked = autoPlay,
            onCheckedChange = {
                autoPlay = it
                VideoPlay.autoPlay = it
            }
        )
        if (autoPlay) {
            SettingsToggleRow(
                icon = null,
                title = stringResource(R.string.start_full),
                checked = startFull,
                onCheckedChange = {
                    startFull = it
                    VideoPlay.startFull = it
                }
            )
        }
        SettingsToggleRow(
            icon = null,
            title = stringResource(R.string.full_bottom_progress),
            checked = fullBottomProgress,
            onCheckedChange = {
                fullBottomProgress = it
                VideoPlay.fullBottomProgressBar = it
            }
        )
        SettingsToggleRow(
            icon = null,
            title = stringResource(R.string.mute_on_start),
            checked = muteOnStart,
            onCheckedChange = {
                muteOnStart = it
                VideoPlay.muteOnStart = it
            }
        )
        SettingsClickRow(
            icon = null,
            title = stringResource(R.string.press_speed),
            value = pressSpeedSummary,
            onClick = onPickPressSpeed
        )
        // video-player-ux-fixes P2: 滑动快进灵敏度（5 档，即时生效）
        SettingsClickRow(
            icon = null,
            title = stringResource(R.string.seek_sensitivity),
            value = stringResource(R.string.seek_sensitivity_multiplier_format, seekSensitivity / 10f),
            onClick = { selection = PanelSelection.SeekSensitivity }
        )
        SettingsClickRow(
            icon = null,
            title = stringResource(R.string.video_skip_time),
            value = stringResource(R.string.video_seconds_format, skipTime),
            onClick = { selection = PanelSelection.SkipTime }
        )
        SettingsToggleRow(
            icon = null,
            title = stringResource(R.string.cache_play),
            checked = cachePlay,
            onCheckedChange = {
                cachePlay = it
                VideoPlay.videoCache = it
            }
        )
        SettingsClickRow(
            icon = null,
            title = stringResource(R.string.video_cache_size),
            value = stringResource(R.string.video_cache_size_summary, cacheSize),
            onClick = { selection = PanelSelection.CacheSize }
        )
        SettingsClickRow(
            icon = null,
            title = stringResource(R.string.player_type),
            value = playerTypeLabel(playerType),
            onClick = { selection = PanelSelection.PlayerType }
        )

        // ====== 画质增强（video-player-image-enhance A2.2） ======
        SectionHeader(stringResource(R.string.image_enhance))
        Text(
            text = stringResource(R.string.image_enhance_note),
            style = MaterialTheme.typography.bodySmall,
            color = style.secondaryText,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )
        SettingsToggleRow(
            icon = null,
            title = stringResource(R.string.image_enhance_enable),
            checked = enhanceEnabled,
            onCheckedChange = {
                enhanceEnabled = it
                VideoPlay.enhanceEnabled = it
                ImageEnhanceController.applyToRegistered()
            }
        )
        if (enhanceEnabled) {
            EnhanceSliderRow(
                label = stringResource(R.string.image_enhance_brightness),
                value = enhanceBrightness / 10f,
                valueRange = -50f..50f,
                onCommit = {
                    enhanceBrightness = it
                    VideoPlay.enhanceBrightness = it
                    ImageEnhanceController.applyToRegistered()
                }
            )
            EnhanceSliderRow(
                label = stringResource(R.string.image_enhance_contrast),
                value = enhanceContrast / 10f,
                valueRange = -50f..50f,
                onCommit = {
                    enhanceContrast = it
                    VideoPlay.enhanceContrast = it
                    ImageEnhanceController.applyToRegistered()
                }
            )
            EnhanceSliderRow(
                label = stringResource(R.string.image_enhance_saturation),
                value = enhanceSaturation / 10f,
                valueRange = -100f..100f,
                onCommit = {
                    enhanceSaturation = it
                    VideoPlay.enhanceSaturation = it
                    ImageEnhanceController.applyToRegistered()
                }
            )
            EnhanceSliderRow(
                label = stringResource(R.string.image_enhance_color_temp),
                value = enhanceColorTemp / 10f,
                valueRange = -50f..50f,
                onCommit = {
                    enhanceColorTemp = it
                    VideoPlay.enhanceColorTemp = it
                    ImageEnhanceController.applyToRegistered()
                }
            )
            SettingsClickRow(
                icon = null,
                title = stringResource(R.string.image_enhance_preset),
                value = presetLabel(enhancePreset),
                onClick = { selection = PanelSelection.EnhancePreset }
            )
            // B 批：锐化/降噪档位（进阶档，media3-effect 效果链）
            SettingsClickRow(
                icon = null,
                title = stringResource(R.string.image_enhance_sharpen),
                value = sharpenLabel(enhanceSharpenLevel),
                onClick = { selection = PanelSelection.EnhanceSharpen }
            )
            SettingsClickRow(
                icon = null,
                title = stringResource(R.string.image_enhance_denoise),
                value = denoiseLabel(enhanceDenoiseLevel),
                onClick = { selection = PanelSelection.EnhanceDenoise }
            )
        }

        // ====== 播放器优化 ======
        SectionHeader(stringResource(R.string.video_player_optimization))
        SettingsToggleRow(
            icon = null,
            title = stringResource(R.string.player_first_frame_preload),
            checked = firstFramePreload,
            onCheckedChange = {
                firstFramePreload = it
                VideoPlay.playerFirstFramePreload = it
            }
        )
        SettingsClickRow(
            icon = null,
            title = stringResource(R.string.player_buffer_strategy),
            value = bufferStrategyLabel(bufferStrategy),
            onClick = { selection = PanelSelection.BufferStrategy }
        )
        SettingsToggleRow(
            icon = null,
            title = stringResource(R.string.player_history_enabled),
            checked = historyEnabled,
            onCheckedChange = {
                historyEnabled = it
                VideoPlay.playerHistoryEnabled = it
            }
        )
        SettingsToggleRow(
            icon = null,
            title = stringResource(R.string.player_error_tip),
            checked = errorTip,
            onCheckedChange = {
                errorTip = it
                VideoPlay.playerErrorTip = it
            }
        )
        SettingsToggleRow(
            icon = null,
            title = stringResource(R.string.player_auto_reconnect),
            checked = autoReconnect,
            onCheckedChange = {
                autoReconnect = it
                VideoPlay.playerAutoReconnect = it
            }
        )
    }

    // ====== 下拉选择弹窗（替代旧 Spinner） ======
    when (val sel = selection) {
        PanelSelection.SkipTime -> {
            val values = intArrayOf(10, 30, 60, 90, 120)
            val options = values.map { stringResource(R.string.video_seconds_format, it) }
            val index = values.indexOfFirst { it == skipTime }.coerceAtLeast(0)
            SingleChoiceDialog(
                title = stringResource(R.string.video_skip_time),
                options = options,
                selectedIndex = index,
                onSelect = {
                    skipTime = values[it]
                    VideoPlay.videoSkipTime = values[it]
                    selection = null
                },
                onDismiss = { selection = null }
            )
        }
        PanelSelection.CacheSize -> {
            val values = intArrayOf(50, 100, 200, 500)
            val options = values.map { stringResource(R.string.video_cache_size_summary, it) }
            val index = values.indexOfFirst { it == cacheSize }.coerceAtLeast(0)
            SingleChoiceDialog(
                title = stringResource(R.string.video_cache_size),
                options = options,
                selectedIndex = index,
                onSelect = {
                    cacheSize = values[it]
                    VideoPlay.videoCacheSize = values[it]
                    selection = null
                },
                onDismiss = { selection = null }
            )
        }
        PanelSelection.PlayerType -> {
            val options = listOf(
                stringResource(R.string.player_type_auto),
                stringResource(R.string.player_type_exo),
                stringResource(R.string.player_type_webview)
            )
            SingleChoiceDialog(
                title = stringResource(R.string.player_type),
                options = options,
                selectedIndex = playerType.coerceIn(0, 2),
                onSelect = {
                    playerType = it
                    VideoPlay.playerType = it
                    selection = null
                },
                onDismiss = { selection = null }
            )
        }
        // video-player-ux-fixes P2: 滑动快进灵敏度 5 档（5=0.5x/7=0.7x/10=1.0x/15=1.5x/20=2.0x）
        PanelSelection.SeekSensitivity -> {
            val values = intArrayOf(5, 7, 10, 15, 20)
            val options = values.map { stringResource(R.string.seek_sensitivity_multiplier_format, it / 10f) }
            val index = values.indexOfFirst { it == seekSensitivity }.coerceAtLeast(0)
            SingleChoiceDialog(
                title = stringResource(R.string.seek_sensitivity),
                options = options,
                selectedIndex = index,
                onSelect = {
                    seekSensitivity = values[it]
                    VideoPlay.seekSensitivity = values[it]
                    selection = null
                },
                onDismiss = { selection = null }
            )
        }
        // video-player-image-enhance A2.2: 预设选择（应用后写回四参数并联动滑条）
        PanelSelection.EnhancePreset -> {
            val options = listOf(
                stringResource(R.string.image_enhance_preset_original),
                stringResource(R.string.image_enhance_preset_eye_care),
                stringResource(R.string.image_enhance_preset_vivid),
                stringResource(R.string.image_enhance_preset_custom)
            )
            SingleChoiceDialog(
                title = stringResource(R.string.image_enhance_preset),
                options = options,
                selectedIndex = enhancePreset.coerceIn(0, 3),
                onSelect = {
                    enhancePreset = it
                    VideoPlay.enhancePreset = it
                    when (it) {
                        0 -> { enhanceBrightness = 0; enhanceContrast = 0; enhanceSaturation = 0; enhanceColorTemp = 0 }
                        1 -> { enhanceBrightness = -50; enhanceContrast = 0; enhanceSaturation = 0; enhanceColorTemp = 300 }
                        2 -> { enhanceBrightness = 0; enhanceContrast = 120; enhanceSaturation = 300; enhanceColorTemp = 0 }
                    }
                    if (it != 3) {
                        VideoPlay.enhanceBrightness = enhanceBrightness
                        VideoPlay.enhanceContrast = enhanceContrast
                        VideoPlay.enhanceSaturation = enhanceSaturation
                        VideoPlay.enhanceColorTemp = enhanceColorTemp
                        ImageEnhanceController.applyToRegistered()
                    }
                    selection = null
                },
                onDismiss = { selection = null }
            )
        }
        // video-player-image-enhance B 批：锐化档位（0 关 / 1 轻 / 2 中 / 3 强）
        PanelSelection.EnhanceSharpen -> {
            val options = listOf(
                stringResource(R.string.image_enhance_level_off),
                stringResource(R.string.image_enhance_level_light),
                stringResource(R.string.image_enhance_level_medium),
                stringResource(R.string.image_enhance_level_strong)
            )
            SingleChoiceDialog(
                title = stringResource(R.string.image_enhance_sharpen),
                options = options,
                selectedIndex = enhanceSharpenLevel.coerceIn(0, 3),
                onSelect = {
                    enhanceSharpenLevel = it
                    VideoPlay.enhanceSharpenLevel = it
                    ImageEnhanceController.applyEffectsToPlayer()
                    selection = null
                },
                onDismiss = { selection = null }
            )
        }
        // video-player-image-enhance B 批：降噪档位（0 关 / 1 轻 / 2 中）
        PanelSelection.EnhanceDenoise -> {
            val options = listOf(
                stringResource(R.string.image_enhance_level_off),
                stringResource(R.string.image_enhance_level_light),
                stringResource(R.string.image_enhance_level_medium)
            )
            SingleChoiceDialog(
                title = stringResource(R.string.image_enhance_denoise),
                options = options,
                selectedIndex = enhanceDenoiseLevel.coerceIn(0, 2),
                onSelect = {
                    enhanceDenoiseLevel = it
                    VideoPlay.enhanceDenoiseLevel = it
                    ImageEnhanceController.applyEffectsToPlayer()
                    selection = null
                },
                onDismiss = { selection = null }
            )
        }
        PanelSelection.BufferStrategy -> {
            val options = listOf(
                stringResource(R.string.player_buffer_strategy_auto),
                stringResource(R.string.player_buffer_strategy_aggressive),
                stringResource(R.string.player_buffer_strategy_balanced),
                stringResource(R.string.player_buffer_strategy_conservative)
            )
            SingleChoiceDialog(
                title = stringResource(R.string.player_buffer_strategy),
                options = options,
                selectedIndex = bufferStrategy.coerceIn(0, 3),
                onSelect = {
                    bufferStrategy = it
                    VideoPlay.playerBufferStrategy = it
                    selection = null
                },
                onDismiss = { selection = null }
            )
        }
        null -> Unit
    }
}

/** 分区标题 */
@Composable
private fun SectionHeader(title: String) {
    val style = rememberAppDialogStyle()
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = style.primaryText,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

/** 面板紧凑按钮（fieldSurface 圆角，取色同源 P3） */
@Composable
private fun PanelButton(text: String, onClick: () -> Unit) {
    val style = rememberAppDialogStyle()
    Surface(
        onClick = onClick,
        shape = AppShapes.Chip,
        color = style.fieldSurface,
        contentColor = style.primaryText
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

/** 下拉选择类型 */
private enum class PanelSelection { SkipTime, CacheSize, PlayerType, SeekSensitivity, EnhancePreset, EnhanceSharpen, EnhanceDenoise, BufferStrategy }

@Composable
private fun playerTypeLabel(type: Int): String = when (type) {
    1 -> stringResource(R.string.player_type_exo)
    2 -> stringResource(R.string.player_type_webview)
    else -> stringResource(R.string.player_type_auto)
}

/** video-player-image-enhance A2.2: 预设标签 */
@Composable
private fun presetLabel(preset: Int): String = when (preset) {
    1 -> stringResource(R.string.image_enhance_preset_eye_care)
    2 -> stringResource(R.string.image_enhance_preset_vivid)
    3 -> stringResource(R.string.image_enhance_preset_custom)
    else -> stringResource(R.string.image_enhance_preset_original)
}

/** video-player-image-enhance B 批: 锐化档位标签 */
@Composable
private fun sharpenLabel(level: Int): String = when (level) {
    1 -> stringResource(R.string.image_enhance_level_light)
    2 -> stringResource(R.string.image_enhance_level_medium)
    3 -> stringResource(R.string.image_enhance_level_strong)
    else -> stringResource(R.string.image_enhance_level_off)
}

/** video-player-image-enhance B 批: 降噪档位标签 */
@Composable
private fun denoiseLabel(level: Int): String = when (level) {
    1 -> stringResource(R.string.image_enhance_level_light)
    2 -> stringResource(R.string.image_enhance_level_medium)
    else -> stringResource(R.string.image_enhance_level_off)
}

/**
 * video-player-image-enhance A2.2: 画质增强滑条行（RA2 拖动即时生效）
 * onCommit 收十倍整值（与 VideoPlay 存储一致），拖动过程实时写回+刷新滤镜
 */
@Composable
private fun EnhanceSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onCommit: (Int) -> Unit
) {
    val style = rememberAppDialogStyle()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = style.primaryText,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = String.format("%+.1f", value),
                style = MaterialTheme.typography.bodySmall,
                color = style.secondaryText
            )
        }
        Slider(
            value = value,
            onValueChange = { onCommit((it * 10).roundToInt()) },
            valueRange = valueRange
        )
    }
}

@Composable
private fun bufferStrategyLabel(strategy: Int): String = when (strategy) {
    1 -> stringResource(R.string.player_buffer_strategy_aggressive)
    2 -> stringResource(R.string.player_buffer_strategy_balanced)
    3 -> stringResource(R.string.player_buffer_strategy_conservative)
    else -> stringResource(R.string.player_buffer_strategy_auto)
}
