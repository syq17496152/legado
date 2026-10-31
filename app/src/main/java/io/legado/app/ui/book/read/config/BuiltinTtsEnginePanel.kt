package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.tts.builtin.BuiltinEngineState

/** 引擎页（语音包 / 系统引擎 / 运行状态 / 导入目录）：分区卡片，不再是"一锅平铺文本" */
@Composable
internal fun EnginePanel(
    state: BuiltinTtsPageState,
    onPickModel: (String) -> Unit,
    onAction: (PageAction) -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionTitle("语音包")
        state.profiles.forEach { profile ->
            ProfileCard(profile = profile, onPickModel = onPickModel, onAction = onAction)
        }
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("系统朗读引擎")
                Spacer(Modifier.width(8.dp))
                StatusPill("推荐", StatusTone.POSITIVE)
            }
            Text(
                text = "设为系统引擎后，阅读页与原「跟随系统」入口都会用离线音色",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { onAction(PageAction.ActivateEngine) },
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(context.getString(R.string.builtin_tts_activate_engine))
                }
                OutlinedButton(
                    onClick = { onAction(PageAction.SelfCheck) },
                    modifier = Modifier.height(44.dp)
                ) {
                    Text(context.getString(R.string.builtin_tts_self_check))
                }
            }
            TextButton(
                onClick = { onAction(PageAction.DiscoverImport) },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(context.getString(R.string.builtin_tts_model_discover))
            }
        }
        StatusCard(state = state, onAction = onAction)
        if (state.importDir.isNotBlank()) {
            AppCard {
                SectionTitle("模型导入目录")
                Text(
                    text = state.importDir,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = "把语音包压缩包放进该目录后，点上方「扫描导入目录」即可",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 单个语音包档位：状态灯 + 标题/胶囊 + 副文案 + 右对齐操作行 */
@Composable
private fun ProfileCard(
    profile: ProfileRow,
    onPickModel: (String) -> Unit,
    onAction: (PageAction) -> Unit
) {
    val context = LocalContext.current
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                color = when (profile.state) {
                    BuiltinEngineState.READY -> MaterialTheme.colorScheme.primary
                    BuiltinEngineState.CORRUPT, BuiltinEngineState.LOW_MEM ->
                        MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                }
            )
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = BuiltinTtsUiSupport.profileTitle(profile.id),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (profile.active) StatusPill("当前", StatusTone.POSITIVE)
                }
                Text(
                    text = BuiltinTtsUiSupport.profileSubtitle(profile.sampleRate, profile.active),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusPill(
                text = BuiltinTtsUiSupport.profileStateText(profile.state),
                tone = when (profile.state) {
                    BuiltinEngineState.READY -> StatusTone.POSITIVE
                    BuiltinEngineState.CORRUPT, BuiltinEngineState.LOW_MEM -> StatusTone.ERROR
                    BuiltinEngineState.DOWNLOADING -> StatusTone.WARNING
                    BuiltinEngineState.NONE -> StatusTone.NEUTRAL
                }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (profile.state != BuiltinEngineState.READY) {
                Button(onClick = { onAction(PageAction.DownloadModel(profile.id)) }) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(context.getString(R.string.builtin_tts_model_download))
                }
                TextButton(onClick = { onPickModel(profile.id) }) {
                    Text(context.getString(R.string.builtin_tts_model_import))
                }
            } else {
                if (!profile.active) {
                    OutlinedButton(onClick = { onAction(PageAction.ActivateProfile(profile.id)) }) {
                        Text("设为当前档")
                    }
                }
                // 删除是破坏性操作：降级为次要文字色，避免整页红色按钮喧宾夺主
                TextButton(
                    onClick = { onAction(PageAction.DeleteModel(profile.id)) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(context.getString(R.string.builtin_tts_model_delete))
                }
            }
        }
    }
}

/** 运行状态：三连指标格 + 存储/错误/诊断 + 清理缓存（次要操作） */
@Composable
private fun StatusCard(state: BuiltinTtsPageState, onAction: (PageAction) -> Unit) {
    AppCard {
        SectionTitle("运行状态")
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MetricCell(
                value = "${state.stats.voiceCount}",
                label = "可用音色",
                modifier = Modifier.weight(1f)
            )
            HairlineV()
            MetricCell(
                value = BuiltinTtsUiSupport.formatBytes(state.stats.cacheBytes),
                label = "合成缓存",
                modifier = Modifier.weight(1f).padding(start = 14.dp)
            )
            HairlineV()
            MetricCell(
                value = BuiltinTtsUiSupport.rtfText(state.stats.lastRtf),
                label = "实时率 RTF",
                modifier = Modifier.weight(1f).padding(start = 14.dp)
            )
        }
        if (state.storageText.isNotBlank()) {
            Text(
                text = state.storageText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        if (state.cacheWatermark.isNotBlank()) {
            // T3.4：缓存水位（用量/上限/百分比），超限提示按 LRU 清理
            Text(
                text = state.cacheWatermark,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "最近错误：${state.stats.lastError}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state.findingsText.isNotBlank()) {
            // 降级矩阵结论（T3.5）：错误/警告/正常 + 可操作动作
            Text(
                text = "诊断：${state.findingsText}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = { onAction(PageAction.ClearCache) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(LocalContext.current.getString(R.string.builtin_tts_clear_cache))
            }
        }
    }
}
