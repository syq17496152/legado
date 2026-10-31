package io.legado.app.ui.book.read.config

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.tts.builtin.BuiltinVoice
import io.legado.app.help.tts.builtin.BuiltinVoiceFilter

/**
 * 音色页：导入/打标 → 自录（折叠）→ 检索 → 列表。
 *
 * 视觉口径：主操作最多两个实心/描边按钮，其余为次要文字按钮；
 * 音色行用"首字头像 + 名称/元信息 + 试听/删除图标按钮"的紧凑结构。
 */
@Composable
internal fun VoicesPanel(
    state: BuiltinTtsPageState,
    onPickVoiceZip: () -> Unit,
    onAction: (PageAction) -> Unit
) {
    val context = LocalContext.current
    // 检索状态是纯展示态，放 Composable 本地即可（不污染页面状态）
    var query by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf(BuiltinVoiceFilter.Gender.ALL) }
    var untaggedOnly by remember { mutableStateOf(false) }
    var poolId by remember { mutableStateOf("") }
    // T6.4：点音色行打开标签编辑（名称/性别/年龄），只影响索引不改音频
    var editing by remember { mutableStateOf<BuiltinVoice?>(null) }
    // 自录区默认折叠：音色管理的主任务是"看列表"，录音是低频操作
    var recordOpen by remember { mutableStateOf(false) }
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onPickVoiceZip) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(context.getString(R.string.builtin_tts_voice_import_zip))
            }
            OutlinedButton(onClick = { onAction(PageAction.AutoTag) }) {
                Text(context.getString(R.string.builtin_tts_voice_auto_tag))
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { onAction(PageAction.ExportVoices) },
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) { Text("导出音色包") }
            TextButton(
                onClick = { onAction(PageAction.PreviewAllVoices) },
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) { Text("连续试听") }
            TextButton(
                onClick = { onAction(PageAction.CancelTag) },
                contentPadding = PaddingValues(horizontal = 10.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = mutedColor)
            ) { Text("取消打标") }
        }
        RecordSection(
            state = state,
            expanded = recordOpen,
            onToggle = { recordOpen = !recordOpen },
            onAction = onAction
        )
        val filtered = BuiltinVoiceFilter.apply(
            state.voices,
            BuiltinVoiceFilter.Criteria(
                query = query,
                gender = gender,
                untaggedOnly = untaggedOnly,
                poolId = poolId
            )
        )
        if (state.voices.isNotEmpty()) {
            // 音色库检索（T6.4）：名称/标签搜索 + 性别筛选 + 未打标
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索音色（名称 / 标签）", style = MaterialTheme.typography.bodySmall) },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = mutedColor
                    )
                },
                singleLine = true
            )
            val poolNames = BuiltinTtsUiSupport.visiblePoolNames(state.voices)
            if (poolNames.isNotEmpty()) {
                // 声音池筛选（T8.16）：池名来自 tag 自动聚合
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = poolId.isBlank(),
                        onClick = { poolId = "" },
                        label = { Text("全部池") }
                    )
                    poolNames.forEach { name ->
                        FilterChip(
                            selected = poolId == name,
                            onClick = { poolId = name },
                            label = { Text(name) }
                        )
                    }
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    BuiltinVoiceFilter.Gender.ALL to "全部",
                    BuiltinVoiceFilter.Gender.MALE to "男",
                    BuiltinVoiceFilter.Gender.FEMALE to "女",
                    BuiltinVoiceFilter.Gender.UNKNOWN to "未定"
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = gender == value,
                        onClick = { gender = value },
                        label = { Text(label) }
                    )
                }
                FilterChip(
                    selected = untaggedOnly,
                    onClick = { untaggedOnly = !untaggedOnly },
                    label = { Text("未打标") }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = BuiltinVoiceFilter.summary(state.voices.size, filtered.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor
                )
                Spacer(Modifier.width(8.dp))
                // T6.5：明确音色包的资产边界（内置只读，不进导出包）
                Text(
                    text = BuiltinTtsUiSupport.voiceExportHint(),
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor,
                    maxLines = 1
                )
            }
        }
        if (state.voices.isEmpty()) {
            AppCard {
                Text(
                    text = context.getString(R.string.builtin_tts_voice_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColor
                )
            }
            return@Column
        }
        LazyColumn(Modifier.weight(1f)) {
            items(filtered, key = { it.id }) { voice ->
                AppCard(
                    modifier = Modifier.padding(vertical = 3.dp),
                    onClick = { editing = voice }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VoiceAvatar(BuiltinTtsUiSupport.voiceTitle(voice.displayName))
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(
                                text = BuiltinTtsUiSupport.voiceTitle(voice.displayName),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            Text(
                                text = BuiltinTtsUiSupport.voiceMetaText(voice),
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedColor,
                                maxLines = 1
                            )
                        }
                        IconButton(onClick = { onAction(PageAction.PreviewVoice(voice.voiceName)) }) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = context.getString(R.string.builtin_tts_voice_preview),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        if (!voice.readOnly) {
                            IconButton(onClick = { onAction(PageAction.DeleteVoice(voice.id)) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "删除音色",
                                    tint = mutedColor,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { voice ->
        VoiceMetaDialog(
            voice = voice,
            onDismiss = { editing = null },
            onSave = { name, genderText, ageText ->
                onAction(PageAction.SaveVoiceMeta(voice.id, name, genderText, ageText))
                editing = null
            }
        )
    }
}

/** 自录音色（T2.4）：折叠卡，展开后给示范句 + 录音按钮；录音中强制展开 */
@Composable
private fun RecordSection(
    state: BuiltinTtsPageState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAction: (PageAction) -> Unit
) {
    val open = expanded || state.recording
    AppCard(onClick = onToggle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                color = if (state.recording) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                }
            )
            Text(
                text = "自录音色",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 10.dp)
            )
            Spacer(Modifier.weight(1f))
            StatusPill("3~15 秒", StatusTone.NEUTRAL)
            Text(
                text = if (open) "收起" else "展开",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
        if (open) {
            Text(
                text = "照着下面这句话读；录音会作为参考音色（需与文本逐字一致）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            OutlinedTextField(
                value = state.recordText,
                onValueChange = { onAction(PageAction.UpdateRecordText(it)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("参考文本", style = MaterialTheme.typography.bodySmall) },
                enabled = !state.recording,
                maxLines = 2
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.recording) {
                    Button(onClick = { onAction(PageAction.StopRecord) }) { Text("停止并保存") }
                } else {
                    Button(onClick = { onAction(PageAction.StartRecord) }) { Text("开始录音") }
                }
                if (state.recordHint.isNotBlank()) {
                    Text(
                        text = state.recordHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 音色标签编辑（T6.4）：名称/性别/年龄只改索引，不动音频；性别用 chips 避免手打英文。
 */
@Composable
private fun VoiceMetaDialog(
    voice: BuiltinVoice,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(voice.displayName) }
    var gender by remember { mutableStateOf(voice.gender) }
    var age by remember { mutableStateOf(voice.age) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑音色标签") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("音色名称") },
                    singleLine = true
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("" to "未定", "male" to "男", "female" to "女").forEach { (value, label) ->
                        FilterChip(
                            selected = gender == value,
                            onClick = { gender = value },
                            label = { Text(label) }
                        )
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("" to "未定", "teen" to "少年", "youth" to "青年", "aged" to "老年")
                        .forEach { (value, label) ->
                            FilterChip(
                                selected = age == value,
                                onClick = { age = value },
                                label = { Text(label) }
                            )
                        }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), gender, age) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
