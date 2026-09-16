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
import io.legado.app.model.VideoPlay
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppSettingPalette
import io.legado.app.ui.widget.compose.rememberAppSettingPalette

/**
 * add-dlna-cast AD-16：**投屏缓存与预取设置**（面板内视图，不跳独立页面）。
 *
 * 设计要点：
 *  - 参数**必须用户可配**（用户裁决 2026-09-16：性能参数取决于设备能力，禁止写死保守值）
 *  - 形态与取色复用弹框族：`AppDialogFrame` + `rememberAppSettingPalette()`，
 *    **禁止硬编码色号/自造组件**（见 `docs/project-flow/ui-standards/architecture.md` 三条铁律）
 *  - 改档**立即持久化**；缓存/预取参数在**下一次投屏会话**生效（会话创建时读取偏好）
 */
@Composable
fun DlnaCastSettingsPanel(onDismiss: () -> Unit) {
    val palette = rememberAppSettingPalette()

    // 初值取当前偏好；改动即写回（SharedPreferences 持久化）
    var cacheMb by remember { mutableStateOf(VideoPlay.dlnaCacheMb) }
    var prefetchWindow by remember { mutableStateOf(VideoPlay.dlnaPrefetchWindow) }
    var prefetchConcurrency by remember { mutableStateOf(VideoPlay.dlnaPrefetchConcurrency) }
    var diskCacheMb by remember { mutableStateOf(VideoPlay.dlnaDiskCacheMb) }
    var preferSmooth by remember { mutableStateOf(VideoPlay.dlnaPreferSmooth) }

    AppDialogFrame(
        title = stringResource(R.string.dlna_cast_settings),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OptionRow(
                    title = stringResource(R.string.dlna_setting_cache_mb),
                    options = CACHE_MB_OPTIONS,
                    current = cacheMb,
                    unit = stringResource(R.string.dlna_setting_unit_mb),
                    palette = palette
                ) {
                    cacheMb = it
                    VideoPlay.dlnaCacheMb = it
                }
                OptionRow(
                    title = stringResource(R.string.dlna_setting_prefetch_window),
                    options = WINDOW_OPTIONS,
                    current = prefetchWindow,
                    unit = stringResource(R.string.dlna_setting_unit_segment),
                    palette = palette
                ) {
                    prefetchWindow = it
                    VideoPlay.dlnaPrefetchWindow = it
                }
                OptionRow(
                    title = stringResource(R.string.dlna_setting_prefetch_concurrency),
                    options = CONCURRENCY_OPTIONS,
                    current = prefetchConcurrency,
                    unit = "",
                    palette = palette
                ) {
                    prefetchConcurrency = it
                    VideoPlay.dlnaPrefetchConcurrency = it
                }
                OptionRow(
                    title = stringResource(R.string.dlna_setting_disk_cache_mb),
                    options = DISK_CACHE_MB_OPTIONS,
                    current = diskCacheMb,
                    unit = stringResource(R.string.dlna_setting_unit_mb),
                    palette = palette,
                    zeroAsOff = true
                ) {
                    diskCacheMb = it
                    VideoPlay.dlnaDiskCacheMb = it
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
            }
        },
        actions = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

/** 档位选择行：一行按钮组，选中项用强调色 */
@Composable
private fun OptionRow(
    title: String,
    options: List<Int>,
    current: Int,
    unit: String,
    palette: AppSettingPalette,
    zeroAsOff: Boolean = false,
    onSelect: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.primaryText
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val selected = option == current
                val label = if (zeroAsOff && option == 0) {
                    stringResource(R.string.dlna_setting_off)
                } else {
                    option.toString() + unit
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) palette.onAccent else palette.primaryText,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) palette.accent else Color(palette.row))
                        .clickable { onSelect(option) }
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

/** 内存缓存上限档位（MB） */
private val CACHE_MB_OPTIONS = listOf(32, 64, 128, 256)

/** 预取窗口档位（片） */
private val WINDOW_OPTIONS = listOf(3, 5, 10)

/** 预取并发档位 */
private val CONCURRENCY_OPTIONS = listOf(2, 3, 4)

/** 磁盘缓存档位（MB；0 = 关闭） */
private val DISK_CACHE_MB_OPTIONS = listOf(0, 128, 256, 512)