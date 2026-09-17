package io.legado.app.ui.video.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.dlna.CastTuner
import io.legado.app.help.dlna.DlnaConstants
import io.legado.app.model.VideoPlay
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppSettingPalette
import io.legado.app.ui.widget.compose.rememberAppSettingPalette

/**
 * add-dlna-cast AD-16 + dlna-cast-cache-unify AD-04/AD-08：**投屏缓存与预取设置**。
 *
 * 设计要点：
 *  - 参数**必须用户可配**（用户裁决 2026-09-16：性能参数取决于设备能力，禁止写死保守值）
 *  - **档位模式**（cache-unify AD-04）：自动 = 按设备总内存取推荐档；自定义 = 用户手选。
 *    自动档只做默认值，**不覆盖**用户已选的档位
 *  - **档位列表单源**（AD-08）：全部取自 `DlnaConstants`，本文件不再写任何档位字面量列表
 *  - 形态与取色复用弹框族：`AppDialogFrame` + `rememberAppSettingPalette()`，
 *    **禁止硬编码色号/自造组件**（见 `docs/project-flow/ui-standards/architecture.md` 三条铁律）
 *  - 改档**立即持久化**；缓存/预取参数在**下一次投屏会话**生效（会话创建时读取偏好）
 */
@Composable
fun DlnaCastSettingsPanel(onDismiss: () -> Unit) {
    val palette = rememberAppSettingPalette()

    // 初值取当前偏好；改动即写回（SharedPreferences 持久化）
    var tuningMode by remember { mutableStateOf(VideoPlay.dlnaTuningMode) }
    var cacheMb by remember { mutableStateOf(VideoPlay.dlnaCacheMb) }
    var prefetchWindow by remember { mutableStateOf(VideoPlay.dlnaPrefetchWindow) }
    var prefetchConcurrency by remember { mutableStateOf(VideoPlay.dlnaPrefetchConcurrency) }
    var diskCacheMb by remember { mutableStateOf(VideoPlay.dlnaDiskCacheMb) }
    var preferSmooth by remember { mutableStateOf(VideoPlay.dlnaPreferSmooth) }
    var reusePlayerCache by remember { mutableStateOf(VideoPlay.dlnaReusePlayerCache) }
    var sessionMemoryOnly by remember { mutableStateOf(VideoPlay.dlnaSessionMemoryOnly) }

    val recommended = VideoPlay.dlnaRecommendedTuning
    val effective = VideoPlay.dlnaTuning
    val custom = tuningMode == DlnaConstants.TUNING_MODE_CUSTOM
    val memoryGb = deviceMemoryGb()

    AppDialogFrame(
        title = stringResource(R.string.dlna_cast_settings),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ---- 档位模式（自动 / 自定义）----
                ChoiceChipRow(
                    title = stringResource(R.string.dlna_setting_tuning_mode),
                    labels = listOf(
                        stringResource(R.string.dlna_setting_mode_auto),
                        stringResource(R.string.dlna_setting_mode_custom)
                    ),
                    selectedIndex = if (custom) 1 else 0,
                    palette = palette,
                    enabled = true
                ) { index ->
                    val mode = if (index == 1) DlnaConstants.TUNING_MODE_CUSTOM else DlnaConstants.TUNING_MODE_AUTO
                    tuningMode = mode
                    VideoPlay.dlnaTuningMode = mode
                }
                Text(
                    text = stringResource(
                        R.string.dlna_setting_recommended_fmt,
                        recommended.cacheMb,
                        memoryGb
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.secondaryText
                )

                if (custom) {
                    // 自定义三档：档位列表来自 DlnaConstants（AD-08 单源）
                    OptionRow(
                        title = stringResource(R.string.dlna_setting_cache_mb),
                        options = DlnaConstants.CACHE_MB_TIERS,
                        current = cacheMb,
                        unit = stringResource(R.string.dlna_setting_unit_mb),
                        palette = palette
                    ) {
                        cacheMb = it
                        VideoPlay.dlnaCacheMb = it
                    }
                    OptionRow(
                        title = stringResource(R.string.dlna_setting_prefetch_window),
                        options = DlnaConstants.PREFETCH_WINDOW_TIERS,
                        current = prefetchWindow,
                        unit = stringResource(R.string.dlna_setting_unit_segment),
                        palette = palette
                    ) {
                        prefetchWindow = it
                        VideoPlay.dlnaPrefetchWindow = it
                    }
                    OptionRow(
                        title = stringResource(R.string.dlna_setting_prefetch_concurrency),
                        options = DlnaConstants.PREFETCH_CONCURRENCY_TIERS,
                        current = prefetchConcurrency,
                        unit = "",
                        palette = palette
                    ) {
                        prefetchConcurrency = it
                        VideoPlay.dlnaPrefetchConcurrency = it
                    }
                } else {
                    // 自动档：仅展示生效值（推荐档 + 容量收敛后的真实窗口）
                    Text(
                        text = stringResource(
                            R.string.dlna_setting_effective_fmt,
                            effective.cacheMb,
                            effective.prefetchWindow,
                            effective.prefetchConcurrency
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.secondaryText
                    )
                }

                // 窗口被共享缓存容量收敛时的显式提示（AD-04：宁可说清楚，不让用户以为没生效）
                if (CastTuner.windowClamped(effective.prefetchWindow, VideoPlay.dlnaSharedCacheCapacityBytes())) {
                    Text(
                        text = stringResource(
                            R.string.dlna_setting_window_clamped_fmt,
                            CastTuner.clampWindow(
                                effective.prefetchWindow,
                                VideoPlay.dlnaSharedCacheCapacityBytes()
                            )
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.secondaryText
                    )
                }

                // ---- 缓存层：复用播放器缓存 / 本会话不写入 ----
                SwitchRow(
                    title = stringResource(R.string.dlna_setting_reuse_player_cache),
                    checked = reusePlayerCache,
                    palette = palette
                ) {
                    reusePlayerCache = it
                    VideoPlay.dlnaReusePlayerCache = it
                }
                SwitchRow(
                    title = stringResource(R.string.dlna_setting_session_memory_only),
                    checked = sessionMemoryOnly,
                    palette = palette
                ) {
                    sessionMemoryOnly = it
                    VideoPlay.dlnaSessionMemoryOnly = it
                }

                // 复用开启时磁盘层被播放器缓存接管 → 本项置灰只读（AD-01）
                OptionRow(
                    title = stringResource(R.string.dlna_setting_disk_cache_mb),
                    options = DlnaConstants.DISK_CACHE_MB_TIERS,
                    current = diskCacheMb,
                    unit = stringResource(R.string.dlna_setting_unit_mb),
                    palette = palette,
                    zeroAsOff = true,
                    enabled = !reusePlayerCache
                ) {
                    diskCacheMb = it
                    VideoPlay.dlnaDiskCacheMb = it
                }
                if (reusePlayerCache) {
                    Text(
                        text = stringResource(R.string.dlna_setting_shared_managed_fmt, VideoPlay.videoCacheSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.secondaryText
                    )
                }

                SwitchRow(
                    title = stringResource(R.string.dlna_setting_prefer_smooth),
                    checked = preferSmooth,
                    palette = palette
                ) {
                    preferSmooth = it
                    VideoPlay.dlnaPreferSmooth = it
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.dlna_setting_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.secondaryText
                )
                Text(
                    text = stringResource(R.string.dlna_setting_reuse_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.secondaryText
                )
            }
        },
        actions = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

/** 本机总内存（GB，按整数截断）；读取失败返回 0 */
private fun deviceMemoryGb(): Int =
    (io.legado.app.help.exoplayer.DeviceInfoHelper.totalMemoryMb() / 1024).toInt()

/** 档位选择行（Int 档位；标签 = 数值 + 单位，0 可显示为"关闭"） */
@Composable
private fun OptionRow(
    title: String,
    options: List<Int>,
    current: Int,
    unit: String,
    palette: AppSettingPalette,
    zeroAsOff: Boolean = false,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit
) {
    val offLabel = stringResource(R.string.dlna_setting_off)
    val labels = options.map { if (zeroAsOff && it == 0) offLabel else "$it$unit" }
    ChoiceChipRow(
        title = title,
        labels = labels,
        selectedIndex = options.indexOf(current),
        palette = palette,
        enabled = enabled
    ) { index -> onSelect(options[index]) }
}

/**
 * 通用档位行：一行可点的标签组，选中项用强调色。
 *
 * [enabled] 为 false 时整行置灰且不可点（用于"复用开启后磁盘缓存项由播放器接管"）。
 */
@Composable
private fun ChoiceChipRow(
    title: String,
    labels: List<String>,
    selectedIndex: Int,
    palette: AppSettingPalette,
    enabled: Boolean,
    onSelectIndex: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) palette.primaryText else palette.secondaryText
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        !enabled -> palette.secondaryText
                        selected -> palette.onAccent
                        else -> palette.primaryText
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected && enabled) palette.accent else Color(palette.row))
                        .then(if (enabled) Modifier.clickable { onSelectIndex(index) } else Modifier)
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/** 开关行 */
@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    palette: AppSettingPalette,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.primaryText,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = palette.onAccent,
                checkedTrackColor = palette.accent
            )
        )
    }
}