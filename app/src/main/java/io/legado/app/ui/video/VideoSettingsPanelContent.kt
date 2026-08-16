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
import io.legado.app.ui.widget.components.SettingsClickRow
import io.legado.app.ui.widget.components.SettingsToggleRow
import io.legado.app.ui.widget.components.SingleChoiceDialog

/**
 * R3 阶段5：视频设置面板（Compose 化内容，task 12.4B L-D9 视频设置弹框改造）
 *
 * 由 [VideoSettingsPanel]（BottomSheetDialogFragment 壳）通过 ComposeView 承载。
 * 100% 保留旧 View 面板全部功能：播放控制 / 播放信息 / 功能菜单 / 调试日志 / 播放设置 / 播放器优化。
 * 仅依赖 [VideoPlay] 全局配置与外部回调，无 View 依赖。
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
    var firstFramePreload by remember { mutableStateOf(VideoPlay.playerFirstFramePreload) }
    var bufferStrategy by remember { mutableStateOf(VideoPlay.playerBufferStrategy) }
    var historyEnabled by remember { mutableStateOf(VideoPlay.playerHistoryEnabled) }
    var errorTip by remember { mutableStateOf(VideoPlay.playerErrorTip) }
    var autoReconnect by remember { mutableStateOf(VideoPlay.playerAutoReconnect) }
    var showDebug by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<PanelSelection?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 540.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp)
    ) {
        // 拖拽手柄
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
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = AppShapes.Tiny
                    )
            )
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
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
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

/** 面板紧凑按钮（secondaryContainer 圆角） */
@Composable
private fun PanelButton(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = AppShapes.Chip,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

/** 下拉选择类型 */
private enum class PanelSelection { SkipTime, CacheSize, PlayerType, BufferStrategy }

@Composable
private fun playerTypeLabel(type: Int): String = when (type) {
    1 -> stringResource(R.string.player_type_exo)
    2 -> stringResource(R.string.player_type_webview)
    else -> stringResource(R.string.player_type_auto)
}

@Composable
private fun bufferStrategyLabel(strategy: Int): String = when (strategy) {
    1 -> stringResource(R.string.player_buffer_strategy_aggressive)
    2 -> stringResource(R.string.player_buffer_strategy_balanced)
    3 -> stringResource(R.string.player_buffer_strategy_conservative)
    else -> stringResource(R.string.player_buffer_strategy_auto)
}
