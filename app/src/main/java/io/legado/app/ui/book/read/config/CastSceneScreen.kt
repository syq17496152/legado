package io.legado.app.ui.book.read.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 听书设置页（原听书中心）：首屏固定 4 段 —— L1 状态条 / L2 三卡 / L3 主按钮 / L4 更多设置。
 *
 * 信息架构来自 design §1.2：原「开通三步（只读）」「使用提示」「高级 9 chip」全部收敛，
 * 能力零损失 —— 9 个入口变成 6 行分组，每一项都还能点到。
 * 状态与动作全部由 [CastSceneActivity] 提供，本文件不持有业务状态、不拼状态文案。
 */
@Composable
internal fun CastSceneScreen(
    snapshot: SceneSnapshot,
    busy: Boolean,
    message: String,
    statusAction: CastSceneState.StatusAction,
    foldGroups: List<SceneAdvancedEntry>,
    bgmEnabled: Boolean,
    bgmTrackCount: Int,
    onSelect: (String) -> Unit,
    onToggleBgm: (Boolean) -> Unit,
    onStatusCta: () -> Unit,
    onRestoreGlobal: () -> Unit,
    onOpenVoices: () -> Unit,
    onOpenBgm: () -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenPrebuild: () -> Unit,
    onOpenAssets: () -> Unit,
    onOpenStoryboard: () -> Unit,
    onOpenTuning: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    /** 4.6：按 `PageTab.key` 深链到二级页（角色配音 / 开书向导） */
    onOpenPage: (String) -> Unit,
    /** 本地 AI（低端机不出现这一行） */
    onOpenAi: () -> Unit,
    /** 3.5：本地 AI 可用时的「生成本章剧本」（手动重算） */
    onGenerateScript: () -> Unit,
    onPrimary: () -> Unit
) {
    // 本章准备默认只在多角色卡选中时展开（design §1.2 映射表）
    var prepareExpanded by remember(snapshot.selectedCard) {
        mutableStateOf(snapshot.selectedCard == CastSceneState.CARD_MULTI)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------- 页面头 ----------
            ScreenHeader(
                title = "听书设置",
                subtitle = "选一种演播方式，点开就能听；更多设置收在下面",
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusPill(
                            text = snapshot.capsule.ifBlank {
                                if (snapshot.engineReady) "离线就绪" else "未就绪"
                            },
                            tone = if (snapshot.engineReady) StatusTone.POSITIVE else StatusTone.WARNING
                        )
                        snapshot.bookOverrideBadge?.let {
                            StatusPill(text = it, tone = StatusTone.NEUTRAL)
                        }
                    }
                }
            )

            // ---------- L1 状态条：一句话 + 一个 CTA（五态文案来自 statusActionOf） ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusDot(
                    if (snapshot.engineReady) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    size = 7
                )
                Text(
                    text = statusAction.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                )
                if (statusAction.cta.isNotBlank()) {
                    TextButton(onClick = onStatusCta) { Text(statusAction.cta) }
                }
            }
            // 书级覆盖：给「恢复全局」——只清本书覆盖，不动全局（J4）
            if (snapshot.bookOverrideBadge != null) {
                TextButton(onClick = onRestoreGlobal) { Text("恢复全局设置") }
            }

            // ---------- L2 三卡：演播方式 ----------
            snapshot.cards.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    pair.forEach { card ->
                        SceneCard(
                            card = card,
                            badge = CastSceneState.badgeOf(snapshot.cards.indexOf(card)),
                            modifier = Modifier.weight(1f),
                            onClick = { onSelect(card.id) }
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            // ---------- L4 更多设置：本章准备 + 6 行分组 ----------
            AppCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { prepareExpanded = !prepareExpanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionTitle("本章准备", Modifier.weight(1f))
                    Text(
                        text = if (prepareExpanded) "收起" else "展开",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (prepareExpanded) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SceneStat("${snapshot.characterCount}", "识别角色", Modifier.weight(1f))
                        SceneStat("${snapshot.assignedCount}", "已配音色", Modifier.weight(1f))
                        SceneStat("${snapshot.storyboardSegments}", "本章分段", Modifier.weight(1f))
                    }
                    if (snapshot.scriptLine.isNotBlank()) {
                        Text(
                            text = snapshot.scriptLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    if (snapshot.canGenerateScript) {
                        AssistChip(
                            onClick = onGenerateScript,
                            label = { Text("生成本章剧本") },
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    if (snapshot.resourceLine.isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = snapshot.resourceLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            AssistChip(onClick = onOpenAssets, label = { Text("声音资源") })
                        }
                    }
                }
            }
            AppCard {
                SectionTitle("更多设置")
                // 分组摘要统一取自唯一归属表，避免 UI 另写一套说法（AD-11）
                val summaries = remember {
                    TtsSettingsOwnership.groups(
                        hasBook = true,
                        lowEndDevice = false,
                        localAiVisible = true
                    ).associate { it.id to it.summary }
                }
                foldGroups.forEach { group ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openFoldGroup(group.id, onOpenVoices, onOpenPage, onOpenStoryboard, onOpenTuning, onOpenAi, onOpenDiagnostics) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = group.label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = summaries[group.id].orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        if (group.id == TtsSettingsOwnership.ID_SEGMENT) {
                            TextButton(onClick = onOpenPrebuild) { Text("提前生成") }
                        }
                        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (group.id == TtsSettingsOwnership.ID_BEHAVIOR) {
                        // 配 BGM 开关并入「朗读行为」组（原独立卡片），仍在同一处可见可改
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("配 BGM", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = CastSceneState.bgmSummary(bgmEnabled, bgmTrackCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = bgmEnabled, onCheckedChange = onToggleBgm)
                            TextButton(onClick = onOpenBgm) { Text("配乐库") }
                        }
                    }
                    if (group.id == TtsSettingsOwnership.ID_ROLES) {
                        TextButton(
                            onClick = onOpenTemplates,
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) { Text("演播模板") }
                    }
                }
            }
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
        }
        // ---------- L3 主按钮：固定底部 ----------
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (message.isNotBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Button(
                onClick = onPrimary,
                enabled = snapshot.primaryEnabled && !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .semantics { contentDescription = snapshot.primaryLabel },
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(snapshot.primaryLabel, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/** 6 行分组 → 既有页面的跳转（不新建功能，只重排入口） */
private fun openFoldGroup(
    id: String,
    onOpenVoices: () -> Unit,
    onOpenPage: (String) -> Unit,
    onOpenStoryboard: () -> Unit,
    onOpenTuning: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    when (id) {
        TtsSettingsOwnership.ID_VOICE -> onOpenVoices()
        TtsSettingsOwnership.ID_ROLES -> onOpenPage("roles")
        TtsSettingsOwnership.ID_SEGMENT -> onOpenStoryboard()
        TtsSettingsOwnership.ID_BEHAVIOR -> onOpenTuning()
        TtsSettingsOwnership.ID_AI -> onOpenAi()
        TtsSettingsOwnership.ID_DIAGNOSTICS -> onOpenDiagnostics()
    }
}
