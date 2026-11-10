package io.legado.app.ui.book.read.config

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import io.legado.app.help.readaloud.casting.VoiceSwitchCandidates
import io.legado.app.help.tts.builtin.BuiltinEngineState
import io.legado.app.help.tts.builtin.BuiltinVoice
import io.legado.app.help.tts.builtin.BuiltinVoiceFilter
import io.legado.app.help.tts.builtin.ReferenceAudioRecorder
import io.legado.app.utils.startActivity

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
    val cacheWatermark: String = "",
    /** 端侧 NER 模型（AD-16）是否已就绪 */
    val nerModelReady: Boolean = false,
    /** 深链页签（4.5）：空=默认引擎页；见 [PageTab.key] */
    val initialTabKey: String = ""
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
    val recognized: Boolean,
    /** 角色名（段级试听据此解析该角色音色；旁白/未识别为空） */
    val characterName: String = "",
    /** 该段真实原文（试听念它，而不是被截断的展示片段） */
    val speechText: String = ""
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
    /** T5.5 段级试听：念该段真实文本（voiceName 由该段角色音色解析，空=引擎默认） */
    data class PreviewSegment(val text: String, val voiceName: String) : PageAction
    data object AssignCasting : PageAction
    data object IdentifyRoles : PageAction
    data object EnableMultiRole : PageAction
    data object QuickEnable : PageAction
    /** AD-16：导入 LAC-ONNX NER 模型包（ZIP，模型不进 APK） */
    data object ImportNerModel : PageAction
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
    /** 2026-11-10 用户裁定：允许手动补录角色（识别不全时可自己加） */
    data object AddCharacter : PageAction
    /** 2026-11-10 用户裁定：角色管理页（新增/编辑/删除/关系网一站入口） */
    data object ManageCharacters : PageAction
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

internal enum class PageTab(val key: String, val labelRes: Int) {
    ENGINE("engine", R.string.builtin_tts_tab_engine),
    VOICES("voices", R.string.builtin_tts_tab_voices),
    ROLES("roles", R.string.builtin_tts_tab_roles),
    WIZARD("wizard", R.string.builtin_tts_tab_wizard),
    STORYBOARD("storyboard", R.string.builtin_tts_tab_storyboard);

    companion object {
        /** 深链：听书中心「高级」区分镜/诊断 chip 直接落到对应页签（4.5） */
        fun ofKey(key: String?): PageTab? = entries.firstOrNull { it.key == key }
    }
}

@Composable
internal fun BuiltinTtsScreen(
    state: BuiltinTtsPageState,
    onPickModel: (String) -> Unit,
    onPickVoiceZip: () -> Unit,
    onAction: (PageAction) -> Unit
) {
    var tab by remember { mutableStateOf(PageTab.ofKey(state.initialTabKey) ?: PageTab.ENGINE) }
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
        // 两行按钮：单行横向滚动时「管理角色」会滑出屏幕（真机 1200px 实测不可见）⇒ 拆两行保证都能看见
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onAction(PageAction.IdentifyRoles) }, enabled = state.hasBook) {
                    Text("识别当前章角色")
                }
                OutlinedButton(onClick = { onAction(PageAction.AssignCasting) }, enabled = state.hasBook) {
                    Text(LocalContext.current.getString(R.string.builtin_tts_roles_assign_all))
                }
            }
            // 2026-11-10 用户裁定：识别不全时必须能手动补录 / 修正角色（不被自动清理）
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAction(PageAction.AddCharacter) }, enabled = state.hasBook) {
                    Text("＋ 添加角色")
                }
                OutlinedButton(onClick = { onAction(PageAction.ManageCharacters) }, enabled = state.hasBook) {
                    Text("管理角色")
                }
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
                            // 手动修正角色（改名/性别/别名）；换音色写回同一角色卡绑定
                            TextButton(onClick = { onAction(PageAction.EditCharacter(row.characterId)) }) {
                                Text("编辑")
                            }
                            TextButton(onClick = { selected = row }) { Text("换音色") }
                        }
                    }
                }
            }
        } else {
            AppCard {
                Text(
                    "还没有角色卡：可点「识别当前章角色」自动识别，或点「＋ 添加角色」手动补录（手动角色不会被自动清理）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            "说明：离线识别只做“人名 + 引号邻接 + 轮转/对话锁”，置信不足一律标未识别，不会乱猜。" +
                "识别偏少时：可到「引擎」页导入 LAC 识别模型（约 30MB，离线），或直接「＋ 添加角色」手动补录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    val target = selected
    if (target != null) {
        var mode by remember(target.characterId) { mutableStateOf(VoiceSwitchCandidates.Mode.RECOMMEND) }
        // 候选按画像过滤 + 排序（5.3）；「已占用」以同书其他角色的绑定为准
        val candidates = remember(target, state.voices, state.roleRows, mode) {
            val occupied = state.roleRows
                .filter { it.characterId != target.characterId }
                .map { it.voiceName }
                .filter { it.isNotBlank() }
                .toSet()
            VoiceSwitchCandidates.build(
                voices = state.voices,
                characterGender = target.gender,
                ageHint = target.ageHint,
                currentVoiceName = target.voiceName,
                occupiedVoiceNames = occupied,
                mode = mode
            )
        }
        val hiddenCount = state.voices.size - candidates.size
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("给「${target.name}」换个音色") },
            text = {
                Column {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(
                            VoiceSwitchCandidates.Mode.RECOMMEND to "推荐",
                            VoiceSwitchCandidates.Mode.ALL to "全部音色"
                        ).forEach { (value, label) ->
                            FilterChip(
                                selected = mode == value,
                                onClick = { mode = value },
                                label = { Text(label) }
                            )
                        }
                    }
                    Text(
                        text = when {
                            candidates.isEmpty() -> "还没有可用音色：先到「音色」页导入或自录"
                            mode == VoiceSwitchCandidates.Mode.RECOMMEND && hiddenCount > 0 ->
                                "已按性别过滤 $hiddenCount 个异性音色；要看全部请切到「全部音色」"
                            else -> "点一行即可换上；右侧 ▶ 可先试听"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
                    )
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(candidates, key = { it.voiceName }) { candidate ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                TextButton(onClick = {
                                    onAction(PageAction.AssignVoice(target.characterId, candidate.voiceName))
                                    selected = null
                                }) {
                                    Text(candidate.displayName)
                                }
                                if (candidate.current) StatusPill("当前", StatusTone.NEUTRAL)
                                if (candidate.occupiedElsewhere) StatusPill("已占用", StatusTone.WARNING)
                                Spacer(Modifier.weight(1f))
                                IconButton(
                                    onClick = { onAction(PageAction.PreviewVoice(candidate.voiceName)) }
                                ) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = "试听")
                                }
                            }
                            Text(
                                text = BuiltinTtsUiSupport.voiceMetaText(candidate.voice) +
                                    if (candidate.profile.inferred) " · 画像为推测" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pick = VoiceSwitchCandidates.randomPick(candidates)
                        if (pick != null) {
                            onAction(PageAction.AssignVoice(target.characterId, pick.voiceName))
                            selected = null
                        }
                    },
                    enabled = candidates.isNotEmpty()
                ) { Text("随机换一个") }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun WizardPanel(state: BuiltinTtsPageState, onAction: (PageAction) -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 引导入口唯一化（4.12）：本页只留两个按钮 —— 「首次配置向导」与「一键开书」。
        // 首次配置（选档/下载/选音色）统一由引导页承担，避免本页再拼一套步骤卡。
        Button(
            onClick = {
                context.startActivity<OfflineTtsOnboardingActivity> {
                    putExtra(
                        OfflineTtsOnboardingActivity.EXTRA_FROM,
                        CastSceneActivity.FROM_GUIDE
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("首次配置向导")
        }
        // 一键开书（T5.3）：角色发现 + 配声 + 激活多角色模板，缺任一步都会退化成单声
        Button(onClick = { onAction(PageAction.EnableMultiRole) }, enabled = state.hasBook) {
            Text("一键开书（识别 + 配音 + 启用多角色）")
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
                        // T5.5 段级试听：按该段角色音色念该段真实文本（改绑后立刻能听差异）
                        val previewVoice = BuiltinTtsUiSupport.segmentVoiceName(
                            characterName = row.characterName,
                            roleRows = state.roleRows,
                            fallbackVoiceName = state.voices.firstOrNull()?.voiceName.orEmpty()
                        )
                        IconButton(
                            onClick = {
                                onAction(PageAction.PreviewSegment(row.speechText, previewVoice))
                            }
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "试听该段",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
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
                        Text(
                            "还没有角色卡：请先回到「角色配音」点识别角色，或点「＋ 添加角色」手动补录",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { selected = null }) { Text("取消") } }
        )
    }
}
