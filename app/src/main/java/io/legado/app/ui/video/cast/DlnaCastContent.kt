package io.legado.app.ui.video.cast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import io.legado.app.R
import io.legado.app.help.dlna.CastError
import io.legado.app.help.dlna.CastPhase
import io.legado.app.help.dlna.DlnaCastUiState
import io.legado.app.help.dlna.DlnaDevice
import io.legado.app.model.VideoPlay

/**
 * add-dlna-cast：投屏面板内容（REQ-03 / REQ-05 / REQ-11）。
 *
 * 设计约束（design「UI 实现约定」）：
 *  - 状态**唯一来源**是 `DlnaCastManager.state`；本组件是纯渲染者、不持有会话状态，
 *    **由壳 [DlnaCastDialog] 负责收集 StateFlow（`collectAsState()`）并传入 `state`**。
 *    这样面板关掉再打开、Activity 重建，都能恢复到同一状态（REQ-03）。
 *  - 文案一律走 string 资源（禁硬编码中文），失败原因由 [CastError] 枚举映射而来 ——
 *    Manager 是长生命周期单例，不持有 Context 取文案（会泄漏）。
 *  - 本组件不直接调用 `DlnaCastManager`，所有动作经回调上抛：便于日后单测与预览。
 */
@Composable
fun DlnaCastContent(
    state: DlnaCastUiState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (DlnaDevice) -> Unit,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Int) -> Unit,
    onSwitchDevice: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.dlna_cast_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(12.dp))

        when (state.phase) {
            CastPhase.CASTING, CastPhase.CONNECTING -> CastControlPanel(
                state = state,
                onTogglePause = onTogglePause,
                onStop = onStop,
                onSeek = onSeek,
                onVolume = onVolume,
                onSwitchDevice = onSwitchDevice
            )

            CastPhase.DISCOVERING -> SearchingRow()

            else -> DeviceListPanel(
                state = state,
                onSelect = onSelect,
                onRetry = onRetry
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

/** 搜索中 */
@Composable
private fun SearchingRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.dlna_searching_devices),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** 设备列表态（含"上次投屏"快捷项与失败提示） */
@Composable
private fun DeviceListPanel(
    state: DlnaCastUiState,
    onSelect: (DlnaDevice) -> Unit,
    onRetry: () -> Unit
) {
    val lastUdn = VideoPlay.dlnaLastDeviceUdn
    val lastDevice = state.devices.firstOrNull { it.udn == lastUdn }

    // 上次设备在线 → 置顶快捷项（REQ-10）
    lastDevice?.let { device ->
        DeviceRow(
            title = stringResource(R.string.dlna_last_device, device.displayName),
            subtitle = device.modelName,
            onClick = { onSelect(device) }
        )
        Spacer(Modifier.height(4.dp))
    }
    // 上次设备不在线 → 仍展示但标注（REQ-03 Scenario「历史设备不在线」）
    if (lastDevice == null && !state.lastDeviceName.isNullOrBlank()) {
        DeviceRow(
            title = stringResource(R.string.dlna_last_device, state.lastDeviceName),
            subtitle = stringResource(R.string.dlna_last_device_offline),
            enabled = false,
            onClick = {}
        )
        Spacer(Modifier.height(4.dp))
    }

    if (state.devices.isNotEmpty()) {
        Text(
            text = stringResource(R.string.dlna_devices_found_count, state.devices.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
            items(state.devices, key = { it.udn }) { device ->
                DeviceRow(
                    title = device.displayName,
                    subtitle = device.modelName,
                    onClick = { onSelect(device) }
                )
            }
        }
    } else if (state.phase != CastPhase.DISCOVERING) {
        // 未发现设备 / 发现失败
        Text(
            text = state.error?.let { errorText(it) } ?: stringResource(R.string.dlna_no_device_found),
            style = MaterialTheme.typography.bodyMedium
        )
        if (state.error == CastError.NO_DEVICE_FOUND || state.error == CastError.NO_WIFI) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.dlna_no_device_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.dlna_retry_search))
        }
    }

    // 多网卡提示（AD-13）：只在拿不准时出现，避免打扰单网卡用户
    state.multiAddressHint?.let { address ->
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.dlna_error_multi_ip, address),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (state.subnetMismatchHint) {
        Text(
            text = stringResource(R.string.dlna_error_maybe_different_subnet),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 单台设备行 */
@Composable
private fun DeviceRow(
    title: String,
    subtitle: String?,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val titleColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 控制态（REQ-05） */
@Composable
private fun CastControlPanel(
    state: DlnaCastUiState,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Int) -> Unit,
    onSwitchDevice: () -> Unit
) {
    // 进度条用本地态驱动，拖动中不被轮询回写打断（轮询 2s 一次会拉回手指）
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }

    val deviceName = state.currentDevice?.displayName.orEmpty()
    Text(
        text = stringResource(R.string.dlna_casting_to, deviceName),
        style = MaterialTheme.typography.bodyLarge
    )
    state.title?.takeIf { it.isNotBlank() }?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (state.usingProxy) {
        Text(
            text = stringResource(R.string.dlna_proxy_relaying),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // 设备不上报时长 / Seek 被拒 → 隐藏进度条并说明（REQ-05 Scenario）
    if (state.supportSeek && state.durationMs > 0L) {
        Spacer(Modifier.height(8.dp))
        Slider(
            value = if (dragging) dragValue else {
                (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
            },
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                dragging = false
                onSeek((dragValue * state.durationMs).toLong())
            }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatHms(state.positionMs), style = MaterialTheme.typography.labelSmall)
            Text(formatHms(state.durationMs), style = MaterialTheme.typography.labelSmall)
        }
    } else if (state.phase == CastPhase.CASTING) {
        Text(
            text = stringResource(R.string.dlna_seek_unsupported),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onTogglePause) {
            Icon(
                imageVector = if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = stringResource(
                    if (state.paused) R.string.dlna_resume else R.string.dlna_pause
                )
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onSwitchDevice) {
            Text(stringResource(R.string.dlna_switch_device))
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onStop) {
            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.dlna_stop_cast))
        }
    }

    // 音量：设备无 RenderingControl 时 volume 恒为 null → 整块隐藏（REQ-05 Scenario）
    state.volume?.let { volume ->
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.dlna_volume),
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.width(8.dp))
            Slider(
                modifier = Modifier.weight(1f),
                value = volume.toFloat(),
                valueRange = 0f..100f,
                onValueChange = { onVolume(it.toInt()) }
            )
        }
    }

    state.error?.let { error ->
        Spacer(Modifier.height(4.dp))
        Text(
            text = errorText(error),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    // 多网卡 / 跨网段提示同样在控制态可见，便于定位"投上了但连不上"
    state.multiAddressHint?.let { address ->
        Text(
            text = stringResource(R.string.dlna_error_multi_ip, address),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 失败原因 → 文案（REQ-11 八条之一） */
@Composable
private fun errorText(error: CastError): String = when (error) {
    CastError.NO_WIFI -> stringResource(R.string.dlna_error_no_wifi)
    CastError.NO_LAN_IP -> stringResource(R.string.dlna_error_no_ip)
    CastError.NO_DEVICE_FOUND -> stringResource(R.string.dlna_no_device_found)
    CastError.CONNECT_FAILED -> stringResource(R.string.dlna_error_connect_failed)
    CastError.DEVICE_REJECTED -> stringResource(R.string.dlna_error_device_rejected)
    CastError.DEVICE_LOST -> stringResource(R.string.dlna_error_device_lost)
    CastError.NETWORK_CHANGED -> stringResource(R.string.dlna_error_network_changed)
    CastError.LOCAL_FILE_MISSING -> stringResource(R.string.dlna_error_local_file_missing)
    CastError.START_FAILED -> stringResource(R.string.dlna_error_start_failed)
}

/** 毫秒 → HH:MM:SS（UI 侧展示用，与 SOAP 侧的 REL_TIME 格式一致） */
private fun formatHms(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    return "%02d:%02d:%02d".format(
        totalSeconds / 3600,
        (totalSeconds % 3600) / 60,
        totalSeconds % 60
    )
}
