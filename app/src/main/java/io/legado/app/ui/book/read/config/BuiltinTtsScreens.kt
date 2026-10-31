package io.legado.app.ui.book.read.config

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.readaloud.casting.CharacterVoiceAssignService
import io.legado.app.help.tts.builtin.BuiltinEngineState
import io.legado.app.help.tts.builtin.BuiltinVoice
import io.legado.app.help.tts.builtin.BuiltinVoiceFilter
import io.legado.app.help.tts.builtin.ReferenceAudioRecorder

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
    val findingsText: String = "",
    /** 缓存水位（T3.4）：用量 / 上限 / 百分比 */
    val cacheWatermark: String = ""
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
    /** T8.5：导出本章音频（WAV，逐段按分镜换声） */
    data object ExportChapterAudio : PageAction
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
    val context = LocalContext.current
    val engineReady = state.profiles.any { it.state == BuiltinEngineState.READY }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(14.dp))
        ScreenHeader(
            title = context.getString(R.string.builtin_tts_title),
            subtitle = BuiltinTtsUiSupport.engineSubtitle(),
            trailing = {
                StatusPill(
                    text = BuiltinTtsUiSupport.readyPill(engineReady),
                    tone = if (engineReady) StatusTone.POSITIVE else StatusTone.WARNING
                )
            }
        )
        Spacer(Modifier.height(12.dp))
        TabStrip(
            labels = PageTab.entries.map { context.getString(it.labelRes) },
            selectedIndex = PageTab.entries.indexOf(tab),
            onSelect = { tab = PageTab.entries[it] }
        )
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
private fun RolesPanel(state: BuiltinTtsPageState, onAction: (PageAction) -> Unit) {
    var selected by remember { mutableStateOf<CharacterVoiceAssignService.RoleVoiceRow?>(null) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = state.roleSummary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { onAction(PageAction.IdentifyRoles) }, enabled = state.hasBook) {
                Text("识别当前章角色")
            }
            OutlinedButton(onClick = { onAction(PageAction.AssignCasting) }, enabled = state.hasBook) {
                Text(LocalContext.current.getString(R.string.builtin_tts_roles_assign_all))
            }
        }
        if (state.roleRows.isNotEmpty()) {
            LazyColumn(Modifier.weight(1f)) {
                items(state.roleRows, key = { it.characterId }) { row ->
                    AppCard(modifier = Modifier.padding(vertical = 3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            VoiceAvatar(row.name)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(
                                    text = row.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (row.gender.isNotBlank()) {
                                        StatusPill(row.gender, StatusTone.NEUTRAL)
                                    }
                                    Text(
                                        text = if (row.configured) row.voiceDisplayName else "未配声",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (row.configured) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        }
                                    )
                                    if (row.voiceName in state.conflictVoices) {
                                        StatusPill("撞音", StatusTone.WARNING)
                                    }
                                }
                            }
                            TextButton(onClick = { selected = row }) { Text("换音色") }
                        }
                    }
                }
            }
        } else {
            AppCard {
                Text(
                    "还没有角色卡：先点「识别当前章角色」或到「开书向导」一键开书。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            "说明：离线识别只做“人名 + 引号邻接 + 轮转/对话锁”，置信不足一律标未识别，不会乱猜。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
        AppCard {
            SectionTitle(LocalContext.current.getString(R.string.builtin_tts_wizard_step1))
            TextButton(onClick = { onAction(PageAction.IdentifyRoles) }, enabled = state.hasBook) {
                Text(LocalContext.current.getString(R.string.builtin_tts_wizard_run))
            }
            SectionTitle(LocalContext.current.getString(R.string.builtin_tts_wizard_step2))
            TextButton(onClick = { onAction(PageAction.AssignCasting) }, enabled = state.hasBook) {
                Text(LocalContext.current.getString(R.string.builtin_tts_wizard_run))
            }
            SectionTitle(LocalContext.current.getString(R.string.builtin_tts_wizard_step3))
            Text(
                "回到阅读页点「听书」即可；未识别段会用模板对白音色（并在分镜里标红）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 一键开书（T5.3）：角色发现 + 配声 + 激活多角色模板，缺任一步都会退化成单声
        Button(onClick = { onAction(PageAction.EnableMultiRole) }, enabled = state.hasBook) {
            Text("一键开书（识别 + 配声 + 启用多角色）")
        }
        if (state.roleSummary.isNotBlank()) {
            Text(
                state.roleSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RoleStoryboardPanel(state: BuiltinTtsPageState, onAction: (PageAction) -> Unit) {
    var selected by remember { mutableStateOf<StoryboardRow?>(null) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = state.storyboardHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // T8.5：按当前分镜与配声导出整章 WAV（已朗读/预合成过的段落命中缓存，几乎即时）
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { onAction(PageAction.ExportChapterAudio) },
                enabled = BuiltinTtsUiSupport.canExportChapter(state.hasBook, state.storyboard.size)
            ) {
                Text("导出本章音频")
            }
        }
        if (state.storyboard.isEmpty()) return@Column
        LazyColumn(Modifier.fillMaxSize()) {
            items(
                state.storyboard,
                key = { "${it.paragraphIndex}:${it.start}:${it.end}" }
            ) { row ->
                AppCard(modifier = Modifier.padding(vertical = 3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "P${row.paragraphIndex + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                StatusPill(
                                    text = row.roleLabel,
                                    tone = when {
                                        row.narrator -> StatusTone.NEUTRAL
                                        row.recognized -> StatusTone.POSITIVE
                                        else -> StatusTone.WARNING
                                    }
                                )
                            }
                            Text(
                                text = row.text,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                modifier = Modifier.padding(top = 4.dp)
                            )
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
                                Text(character.name)
                            }
                            if (character.gender.isNotBlank()) {
                                StatusPill(character.gender, StatusTone.NEUTRAL)
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
