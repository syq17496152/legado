package io.legado.app.ui.book.read.config

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.readaloud.casting.CharacterVoiceAssignService
import io.legado.app.help.tts.builtin.BuiltinEngineState
import io.legado.app.help.tts.builtin.BuiltinVoice
import io.legado.app.help.tts.builtin.BuiltinVoiceFilter
import io.legado.app.help.tts.builtin.ReferenceAudioRecorder

/**
 * 细分隔边框：浅色主题下卡片也能看出"分组"，不用重阴影（页面不发灰）。
 * 取色只借用 onSurface + 低透明度，不引入新的面 token。
 */
@Composable
private fun subtleBorder(): BorderStroke =
    BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))

data class ProfileRow(
    val id: String,
    val title: String,
    val state: BuiltinEngineState,
    val active: Boolean,
    val sampleRate: Int
)

data class StatsRow(
    val voiceCount: Int = 0,
    val cacheBytes: Long = 0,
    val lastRtf: Float = 0f,
    val lastError: String = ""
)

data class BuiltinTtsPageState(
    val profiles: List<ProfileRow> = emptyList(),
    val voices: List<BuiltinVoice> = emptyList(),
    val stats: StatsRow = StatsRow(),
    val storageText: String = "",
    val importDir: String = "",
    val hasBook: Boolean = false,
    val roleSummary: String = "",
    val message: String = "",
    val busy: Boolean = false,
    val progress: Int = 0,
    /** 自录音色（T2.4）：示范文本 / 录音状态 / 进度提示 */
    val recordText: String = ReferenceAudioRecorder.DEFAULT_SAMPLE_TEXT,
    val recording: Boolean = false,
    val recordHint: String = "",
    /** 分镜预览（T5.5）：本章段落角色 + 可改绑的角色列表 */
    val storyboard: List<StoryboardRow> = emptyList(),
    val pickCharacters: List<RolePickRow> = emptyList(),
    val storyboardHint: String = "",
    /** 角色配音页（T5.4）：角色→音色 行 + 撞音音色集合 */
    val roleRows: List<CharacterVoiceAssignService.RoleVoiceRow> = emptyList(),
    val conflictVoices: Set<String> = emptySet(),
    /** 降级矩阵结论（T3.5）：错误/警告/正常 逐条文案 */
    val findingsText: String = ""
)

/** 分镜一行：段落片段 + 当前角色判定（未识别不静默归旁白） */
data class StoryboardRow(
    val paragraphIndex: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val roleLabel: String,
    val confidence: Double,
    val narrator: Boolean,
    val recognized: Boolean
)

data class RolePickRow(val id: Long, val name: String, val gender: String, val roleLevel: Int)

sealed interface PageAction {
    data class DownloadModel(val profileId: String) : PageAction
    data class DeleteModel(val profileId: String) : PageAction
    data class ActivateProfile(val profileId: String) : PageAction
    data object ActivateEngine : PageAction
    data object SelfCheck : PageAction
    data object DiscoverImport : PageAction
    data object ClearCache : PageAction
    data object AutoTag : PageAction
    data class DeleteVoice(val voiceId: String) : PageAction
    data class PreviewVoice(val voiceName: String) : PageAction
    data object PreviewAllVoices : PageAction
    data class UpdateRecordText(val text: String) : PageAction
    data object StartRecord : PageAction
    data object StopRecord : PageAction
    data object ExportVoices : PageAction
    data object AssignCasting : PageAction
    data object IdentifyRoles : PageAction
    data object EnableMultiRole : PageAction
    data object QuickEnable : PageAction
    data class AssignSegment(
        val paragraphIndex: Int,
        val start: Int,
        val end: Int,
        val narrator: Boolean,
        val characterId: Long,
        val characterName: String
    ) : PageAction
    data class AssignVoice(val characterId: Long, val voiceName: String) : PageAction
    data object Reload : PageAction
    data class EditCharacter(val characterId: Long) : PageAction
    /** T6.3：取消打标（已完成部分保留，下次续跑） */
    data object CancelTag : PageAction
    /** T6.4：编辑音色标签（名称/性别/年龄） */
    data class SaveVoiceMeta(
        val voiceId: String,
        val name: String,
        val gender: String,
        val age: String
    ) : PageAction
}

private enum class PageTab(val labelRes: Int) {
    ENGINE(R.string.builtin_tts_tab_engine),
    VOICES(R.string.builtin_tts_tab_voices),
    ROLES(R.string.builtin_tts_tab_roles),
    WIZARD(R.string.builtin_tts_tab_wizard),
    STORYBOARD(R.string.builtin_tts_tab_storyboard)
}

@Composable
internal fun BuiltinTtsScreen(
    state: BuiltinTtsPageState,
    onPickModel: (String) -> Unit,
    onPickVoiceZip: () -> Unit,
    onAction: (PageAction) -> Unit
) {
    var tab by remember { mutableStateOf(PageTab.ENGINE) }
    LaunchedEffect(state.message) { /* 触发重组展示提示 */ }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = BuiltinTtsUiSupport.engineSubtitle(),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PageTab.entries.forEach { item ->
                FilterChip(
                    selected = tab == item,
                    onClick = { tab = item },
                    label = { Text(androidx.compose.ui.platform.LocalContext.current.getString(item.labelRes)) }
                )
            }
        }
        if (state.busy) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (state.message.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        when (tab) {
            PageTab.ENGINE -> EnginePanel(state, onPickModel, onAction)
            PageTab.VOICES -> VoicesPanel(state, onPickVoiceZip, onAction)
            PageTab.ROLES -> RolesPanel(state, onAction)
            PageTab.WIZARD -> WizardPanel(state, onAction)
            PageTab.STORYBOARD -> RoleStoryboardPanel(state, onAction)
        }
    }
}

@Composable
private fun EnginePanel(
    state: BuiltinTtsPageState,
    onPickModel: (String) -> Unit,
    onAction: (PageAction) -> Unit
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("语音包", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        state.profiles.forEach { profile ->
            Card(modifier = Modifier.fillMaxWidth(), border = subtleBorder()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = if (profile.id == "fast") "极速单声档" else "高质量克隆档",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = when (profile.state) {
                                    BuiltinEngineState.READY -> "已就绪 · ${profile.sampleRate}Hz"
                                    BuiltinEngineState.CORRUPT -> "已损坏，请重新下载或导入"
                                    BuiltinEngineState.DOWNLOADING -> "下载中"
                                    BuiltinEngineState.LOW_MEM -> "内存不足"
                                    BuiltinEngineState.NONE -> "未下载"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = when (profile.state) {
                                    BuiltinEngineState.CORRUPT, BuiltinEngineState.LOW_MEM ->
                                        MaterialTheme.colorScheme.error
                                    BuiltinEngineState.READY -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                        if (profile.active) {
                            Text(
                                "当前",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (profile.state != BuiltinEngineState.READY) {
                            Button(onClick = { onAction(PageAction.DownloadModel(profile.id)) }) {
                                Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_model_download))
                            }
                            OutlinedButton(onClick = { onPickModel(profile.id) }) {
                                Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_model_import))
                            }
                        } else if (!profile.active) {
                            OutlinedButton(onClick = { onAction(PageAction.ActivateProfile(profile.id)) }) {
                                Text("设为当前档")
                            }
                            TextButton(onClick = { onAction(PageAction.DeleteModel(profile.id)) }) {
                                Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_model_delete))
                            }
                        } else {
                            TextButton(onClick = { onAction(PageAction.DeleteModel(profile.id)) }) {
                                Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_model_delete))
                            }
                        }
                    }
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), border = subtleBorder()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("系统朗读引擎", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "设为系统引擎后，阅读页与原「跟随系统」入口都会用离线音色",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onAction(PageAction.ActivateEngine) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_activate_engine))
                    }
                    OutlinedButton(onClick = { onAction(PageAction.SelfCheck) }) {
                        Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_self_check))
                    }
                }
                TextButton(
                    onClick = { onAction(PageAction.DiscoverImport) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_model_discover))
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), border = subtleBorder()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("运行状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("音色 ${state.stats.voiceCount} · 缓存 ${formatBytes(state.stats.cacheBytes)} · RTF ${"%.2f".format(state.stats.lastRtf)}")
        if (state.storageText.isNotBlank()) {
            Text(state.storageText, style = MaterialTheme.typography.bodySmall)
        }
        Text("最近错误：${state.stats.lastError}", style = MaterialTheme.typography.bodySmall)
        if (state.findingsText.isNotBlank()) {
            // 降级矩阵结论（T3.5）：错误/警告/正常 + 可操作动作
            Text("诊断", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(state.findingsText, style = MaterialTheme.typography.bodySmall)
        }
                TextButton(
                    onClick = { onAction(PageAction.ClearCache) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_clear_cache))
                }
            }
        }
        if (state.importDir.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth(), border = subtleBorder()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("模型导入目录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(state.importDir, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "把语音包压缩包放进该目录后，点上方「扫描导入目录」即可",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun VoicesPanel(
    state: BuiltinTtsPageState,
    onPickVoiceZip: () -> Unit,
    onAction: (PageAction) -> Unit
) {
    // 检索状态是纯展示态，放 Composable 本地即可（不污染页面状态）
    var query by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf(BuiltinVoiceFilter.Gender.ALL) }
    var untaggedOnly by remember { mutableStateOf(false) }
    var poolId by remember { mutableStateOf("") }
    // T6.4：点音色行打开标签编辑（名称/性别/年龄），只影响索引不改音频
    var editing by remember { mutableStateOf<BuiltinVoice?>(null) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onPickVoiceZip) {
                Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_voice_import_zip))
            }
            OutlinedButton(onClick = { onAction(PageAction.AutoTag) }) {
                Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_voice_auto_tag))
            }
            TextButton(onClick = { onAction(PageAction.CancelTag) }) {
                Text("取消打标")
            }
            OutlinedButton(onClick = { onAction(PageAction.ExportVoices) }) {
                Text("导出音色包")
            }
            OutlinedButton(onClick = { onAction(PageAction.PreviewAllVoices) }) {
                Text("连续试听")
            }
        }
        Spacer(Modifier.height(8.dp))
        // 自录音色（T2.4）：先给示范句（参考文本必须与录音逐字一致），再录 3~15 秒
        OutlinedTextField(
            value = state.recordText,
            onValueChange = { onAction(PageAction.UpdateRecordText(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("照着这句话读（参考文本）") },
            enabled = !state.recording,
            maxLines = 3
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.recording) {
                Button(onClick = { onAction(PageAction.StopRecord) }) { Text("停止并保存") }
            } else {
                Button(onClick = { onAction(PageAction.StartRecord) }) { Text("自录音色") }
            }
            if (state.recordHint.isNotBlank()) {
                Text(state.recordHint, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(8.dp))
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
                label = { Text("搜索音色（名称/标签）") },
                singleLine = true
            )
            val poolNames = BuiltinTtsUiSupport.visiblePoolNames(state.voices)
            if (poolNames.isNotEmpty()) {
                // 声音池筛选（T8.16）：池名来自 tag 自动聚合
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            Text(
                BuiltinVoiceFilter.summary(state.voices.size, filtered.size),
                style = MaterialTheme.typography.bodySmall
            )
            // T6.5：明确音色包的资产边界（内置只读，不进导出包）
            Text(
                BuiltinTtsUiSupport.voiceExportHint(),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (state.voices.isEmpty()) {
            Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_voice_empty))
            return@Column
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }) { voice ->
                Card(border = subtleBorder(), modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clickable { editing = voice }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(voice.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            val meta = BuiltinTtsUiSupport.voiceMetaText(voice)
                            Text(meta, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onAction(PageAction.PreviewVoice(voice.voiceName)) }) {
                            Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_voice_preview))
                        }
                        if (!voice.readOnly) {
                            TextButton(onClick = { onAction(PageAction.DeleteVoice(voice.id)) }) {
                                Text("删除")
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("" to "未定", "male" to "男", "female" to "女").forEach { (value, label) ->
                        FilterChip(
                            selected = gender == value,
                            onClick = { gender = value },
                            label = { Text(label) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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

@Composable
private fun RolesPanel(state: BuiltinTtsPageState, onAction: (PageAction) -> Unit) {
    var selected by remember { mutableStateOf<CharacterVoiceAssignService.RoleVoiceRow?>(null) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(state.roleSummary, style = MaterialTheme.typography.bodyMedium)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { onAction(PageAction.IdentifyRoles) }, enabled = state.hasBook) {
                Text("识别当前章角色")
            }
            OutlinedButton(onClick = { onAction(PageAction.AssignCasting) }, enabled = state.hasBook) {
                Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_roles_assign_all))
            }
        }
        Spacer(Modifier.height(8.dp))
        if (state.roleRows.isNotEmpty()) {
            // T5.4：角色→音色一览（撞音标红提示，可单角色换音色）
            LazyColumn(Modifier.weight(1f)) {
                items(state.roleRows, key = { it.characterId }) { row ->
                    Card(
                        border = subtleBorder(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                val genderText = if (row.gender.isNotBlank()) "（${row.gender}）" else ""
                                val conflictText =
                                    if (row.voiceName in state.conflictVoices) " · 撞音" else ""
                                Text("${row.name}$genderText", fontWeight = FontWeight.SemiBold)
                                Text(
                                    (if (row.configured) row.voiceDisplayName else "未配声") + conflictText,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            TextButton(onClick = { selected = row }) { Text("换音色") }
                        }
                    }
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                "还没有角色卡：先点「识别当前章角色」或到「开书向导」一键开书。",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "说明：离线识别只做“人名 + 引号邻接 + 轮转/对话锁”，置信不足一律标未识别，不会乱猜。",
            style = MaterialTheme.typography.bodySmall
        )
    }
    val target = selected
    if (target != null) {
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("给「${target.name}」换个音色") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    state.voices.forEach { voice ->
                        val isCurrent = voice.voiceName == target.voiceName
                        TextButton(onClick = {
                            onAction(PageAction.AssignVoice(target.characterId, voice.voiceName))
                            selected = null
                        }) {
                            val suffix = buildString {
                                if (isCurrent) append("（当前）")
                                if (voice.voiceName in state.conflictVoices) append(" · 撞音")
                            }
                            Text(voice.displayName + suffix)
                        }
                    }
                    if (state.voices.isEmpty()) {
                        Text("还没有可用音色：先到「音色」页导入或自录", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { selected = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun WizardPanel(state: BuiltinTtsPageState, onAction: (PageAction) -> Unit) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 首次启用一键卡（T4.1）：模型就绪后一次点击完成「切引擎 + 多角色 + 自检」
        Button(onClick = { onAction(PageAction.QuickEnable) }, modifier = Modifier.fillMaxWidth()) {
            Text("一键启用（引擎 + 多角色 + 自检）")
        }
        Spacer(Modifier.height(8.dp))
        Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_wizard_step1), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = { onAction(PageAction.IdentifyRoles) }, enabled = state.hasBook) {
            Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_wizard_run))
        }
        Spacer(Modifier.height(6.dp))
        Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_wizard_step2), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = { onAction(PageAction.AssignCasting) }, enabled = state.hasBook) {
            Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_wizard_run))
        }
        Spacer(Modifier.height(6.dp))
        Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.builtin_tts_wizard_step3), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text("回到阅读页点「听书」即可；未识别段会用模板对白音色（并在分镜里标红）。")
        Spacer(Modifier.height(8.dp))
        // 一键开书（T5.3）：角色发现 + 配声 + 激活多角色模板，缺任一步都会退化成单声
        Button(onClick = { onAction(PageAction.EnableMultiRole) }, enabled = state.hasBook) {
            Text("一键开书（识别 + 配声 + 启用多角色）")
        }
        Spacer(Modifier.height(10.dp))
        Text(state.roleSummary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RoleStoryboardPanel(state: BuiltinTtsPageState, onAction: (PageAction) -> Unit) {
    var selected by remember { mutableStateOf<StoryboardRow?>(null) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(state.storyboardHint, style = MaterialTheme.typography.bodySmall)
        if (state.storyboard.isEmpty()) return@Column
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(
                state.storyboard,
                key = { "${it.paragraphIndex}:${it.start}:${it.end}" }
            ) { row ->
                Card(
                    border = subtleBorder(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("P${row.paragraphIndex + 1} · ${row.roleLabel}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(row.text, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { selected = row }) { Text("改角色") }
                    }
                }
            }
        }
    }
    val target = selected
    if (target != null) {
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("这段话由谁来说？") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(target.text, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        onAction(
                            PageAction.AssignSegment(
                                target.paragraphIndex, target.start, target.end, true, 0L, ""
                            )
                        )
                        selected = null
                    }) { Text("旁白") }
                    state.pickCharacters.forEach { character ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                onAction(
                                    PageAction.AssignSegment(
                                        target.paragraphIndex,
                                        target.start,
                                        target.end,
                                        false,
                                        character.id,
                                        character.name
                                    )
                                )
                                selected = null
                            }) {
                                Text(
                                    character.name +
                                            if (character.gender.isNotBlank()) "（${character.gender}）" else ""
                                )
                            }
                            // T8.11：行内编辑角色卡（改性别/别名后返回自动刷新，可重算配声）
                            TextButton(onClick = { onAction(PageAction.EditCharacter(character.id)) }) {
                                Text("编辑", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (state.pickCharacters.isEmpty()) {
                        Text("还没有角色卡：请先回到「角色配音」点识别角色", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { selected = null }) { Text("取消") } }
        )
    }
}

private fun formatBytes(bytes: Long): String = BuiltinTtsUiSupport.formatBytes(bytes)

