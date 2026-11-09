package io.legado.app.ui.book.read.config

import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.help.tts.builtin.BuiltinVoice

/**
 * 首次配置引导的三屏 UI（design §1.10）：只渲染，所有判定与动作由
 * [OfflineTtsOnboardingActivity] 提供，保证"文案与行为同源"。
 */
@Composable
internal fun OfflineTtsOnboardingScreen(
    ui: OnboardingUi,
    onSelectProfile: (String) -> Unit,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onImportModel: () -> Unit,
    onImportVoicePack: () -> Unit,
    onSelectVoice: (String) -> Unit,
    onPreviewVoice: (String) -> Unit,
    onShuffleVoices: () -> Unit,
    onOpenAllVoices: () -> Unit,
    onSelectCard: (String) -> Unit,
    onHealthAction: (String) -> Unit,
    onConfirmMobileDownload: () -> Unit,
    onDismissMobileConfirm: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ScreenHeader(
                title = "配置离线听书",
                subtitle = "三步就能听，随时可以跳过",
                trailing = {
                    TextButton(onClick = onSkip) { Text("跳过") }
                }
            )
            StepIndicator(step = ui.step)
            if (ui.message.isNotBlank()) {
                Text(
                    text = ui.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (ui.error.isNotBlank()) {
                Text(
                    text = ui.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            when (ui.step) {
                1 -> Step1Profiles(
                    ui = ui,
                    onSelectProfile = onSelectProfile,
                    onImportModel = onImportModel
                )
                2 -> Step2Voices(
                    ui = ui,
                    onSelectVoice = onSelectVoice,
                    onPreviewVoice = onPreviewVoice,
                    onShuffleVoices = onShuffleVoices,
                    onOpenAllVoices = onOpenAllVoices,
                    onImportVoicePack = onImportVoicePack
                )
                else -> Step3Cards(ui = ui, onSelectCard = onSelectCard, onHealthAction = onHealthAction)
            }
            if (ui.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
        }
        BottomBar(ui = ui, onDownload = onDownload, onPause = onPause, onImportModel = onImportModel,
            onOpenAllVoices = onOpenAllVoices, onNext = onNext, onBack = onBack, onSkip = onSkip)
    }
    // S18：蜂窝网络下载前确认（取消不下载，绝不吃用户流量）
    if (ui.showMobileConfirm) {
        AlertDialog(
            onDismissRequest = onDismissMobileConfirm,
            title = { Text("正在使用移动网络") },
            text = { Text("语音包体积较大，建议连 Wi-Fi 下载。仍要用流量继续吗？") },
            confirmButton = { TextButton(onClick = onConfirmMobileDownload) { Text("仍要下载") } },
            dismissButton = { TextButton(onClick = onDismissMobileConfirm) { Text("取消") } }
        )
    }
}

@Composable
private fun StepIndicator(step: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        listOf("1 音质档", "2 音色", "3 开始听").forEachIndexed { index, label ->
            val active = index + 1 <= step
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (index < 2) {
                Text(
                    text = "  →  ",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

// ---------------------------------------------------------------
// 第 1 步：音质档（6 字段固定）
// ---------------------------------------------------------------

@Composable
private fun Step1Profiles(
    ui: OnboardingUi,
    onSelectProfile: (String) -> Unit,
    onImportModel: () -> Unit
) {
    Text(
        text = "选一个音质档。下载一次后离线可用，之后随时能在「听书设置」里换。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    ui.capabilities.forEach { capability ->
        val selected = capability.profileId == ui.profileId
        // 已就绪的档位直接标「已下载」：真机铁证 2026-11-09，用户会以为必须再下一遍
        val downloaded = capability.profileId in ui.readyProfileIds
        AppCard(selected = selected, onClick = { onSelectProfile(capability.profileId) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle(capability.label)
                        if (capability.recommended) {
                            StatusPill("推荐", StatusTone.POSITIVE, Modifier.padding(start = 8.dp))
                        }
                        if (downloaded) {
                            StatusPill("已下载", StatusTone.POSITIVE, Modifier.padding(start = 6.dp))
                        }
                        if (capability.profileId == ui.profileId) {
                            StatusPill("已选择", StatusTone.NEUTRAL, Modifier.padding(start = 6.dp))
                        }
                    }
                    Text(
                        text = capabilityLabel(capability),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = "约 ${capability.sizeMb}MB · ${capability.sampleRate / 1000}kHz",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Text(
                        text = suitableFor(capability) + if (
                            ui.lowEnd && capability.profileId == OnboardingCapability.PROFILE_HQ
                        ) {
                            "（本机可能较慢，推荐极速档）"
                        } else {
                            ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            // 状态与操作：所选档在下载中/暂停/断点时给对应按钮，其余档只提示"先选它"
            Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    !selected -> AssistChip(onClick = { onSelectProfile(capability.profileId) }, label = { Text("选这个") })
                    capability.needsVoicePack -> AssistChip(onClick = onImportModel, label = { Text("导入音色包") })
                    ui.downloading -> Text(
                        text = "下载中 ${ui.progress}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    else -> Text(
                        text = when {
                            ui.paused -> "已暂停"
                            downloaded -> "已下载，点底部按钮启用"
                            else -> "点底部按钮开始"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (selected && !downloaded && ui.resumePercent > 0 && !ui.downloading) {
                    Text(
                        text = "已下载 ${ui.resumePercent}%，可继续",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
    if (ui.capabilities.isEmpty()) {
        // 防呆 1：永不出现空结果页 —— 没有档案时也给一个能点的下一步
        AppCard {
            SectionTitle("暂时读不到音质档")
            Text(
                text = "可以先导入本地语音包，或稍后在「声音」里再配置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            AssistChip(
                onClick = onImportModel,
                label = { Text("导入本地语音包") },
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

private fun capabilityLabel(capability: OnboardingCapability): String = when {
    capability.needsVoicePack -> "克隆 · 需导入声音包"
    capability.multiSpeaker -> "多音色 · ${capability.speakerCount} 声"
    else -> "单声"
}

private fun suitableFor(capability: OnboardingCapability): String = when (capability.profileId) {
    OnboardingCapability.PROFILE_HQ -> "默认推荐，音质最好"
    OnboardingCapability.PROFILE_MULTI -> "空间紧张、能接受一般音质"
    OnboardingCapability.PROFILE_FAST -> "只求能听、低端机"
    OnboardingCapability.PROFILE_CLONE -> "想用自己的 / 导入的音色"
    else -> ""
}

// ---------------------------------------------------------------
// 第 2 步：音色（按档位分支）
// ---------------------------------------------------------------

@Composable
private fun Step2Voices(
    ui: OnboardingUi,
    onSelectVoice: (String) -> Unit,
    onPreviewVoice: (String) -> Unit,
    onShuffleVoices: () -> Unit,
    onOpenAllVoices: () -> Unit,
    onImportVoicePack: () -> Unit
) {
    val capability = ui.capabilities.firstOrNull { it.profileId == ui.profileId }
    if (capability?.needsVoicePack == true) {
        Text(
            text = "这个档位需要先导入声音包（zip），导入后就能挑音色试用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AppCard {
            SectionTitle("导入声音包")
            AssistChip(
                onClick = onImportVoicePack,
                label = { Text("选择声音包文件") },
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    } else {
        Text(
            text = "选一个你喜欢的声音，点「试听」听一句。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    ui.voices.forEach { voice ->
        val selected = voice.id == ui.selectedVoiceId
        AppCard(selected = selected, onClick = { onSelectVoice(voice.id) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = voice.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = listOf(voice.gender, voice.age).filter { it.isNotBlank() }.joinToString(" · ")
                            .ifBlank { "内置音色" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                TextButton(onClick = { onPreviewVoice(voice.id) }) { Text("试听") }
            }
            if (selected) {
                StatusPill("当前使用", StatusTone.POSITIVE, Modifier.padding(top = 6.dp))
            }
        }
    }
    if (ui.voices.isEmpty()) {
        // 防呆 1：无可用音色时不空跑，直接给两条可执行的路
        AppCard {
            SectionTitle("还没有可用音色")
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onImportVoicePack, label = { Text("导入声音包") })
                AssistChip(onClick = onOpenAllVoices, label = { Text("全部音色") })
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = onShuffleVoices, label = { Text("换一批") })
        AssistChip(onClick = onOpenAllVoices, label = { Text("全部音色") })
        if (capability?.needsVoicePack == true) {
            AssistChip(onClick = onImportVoicePack, label = { Text("导入声音包") })
        }
    }
}

// ---------------------------------------------------------------
// 第 3 步：演播方式（按档位能力置灰）
// ---------------------------------------------------------------

@Composable
private fun Step3Cards(
    ui: OnboardingUi,
    onSelectCard: (String) -> Unit,
    onHealthAction: (String) -> Unit
) {
    val capability = ui.capabilities.firstOrNull { it.profileId == ui.profileId }
    Text(
        text = "选一种演播方式，之后随时能换。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    sceneCards(capability).forEach { (id, title, subtitle) ->
        val available = capability == null || OnboardingCapability.cardAvailable(capability, id)
        val reason = capability?.let { OnboardingCapability.disabledReason(it, id) }
        AppCard(
            selected = ui.cardId == id,
            onClick = if (available) ({ onSelectCard(id) }) else null
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (available) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    )
                    Text(
                        text = if (available) subtitle else reason.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (available) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (ui.cardId == id && available) {
                    StatusPill("当前使用", StatusTone.POSITIVE)
                }
            }
        }
    }
    Text(
        text = "普通朗读 = 一个声音念全书；双声对读 = 旁白 + 对白；沉浸多角色 = 每个角色有自己的声音。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    // 完成前体检（R21）：只提示、可跳过；未完成项给一个能点的动作
    if (ui.health.isNotEmpty()) {
        AppCard {
            SectionTitle("开始前检查（可跳过）")
            val pending = ui.health.count { !it.ok }
            if (pending == 0) {
                Text(
                    text = "都准备好了，直接开始听书吧。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            ui.health.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusDot(
                        if (item.ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        size = 7
                    )
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    )
                    if (item.action.isNotBlank()) {
                        TextButton(onClick = { onHealthAction(item.id) }) { Text(item.action) }
                    }
                }
            }
        }
    }
}

private fun sceneCards(capability: OnboardingCapability?): List<Triple<String, String, String>> = listOf(
    Triple(CastSceneState.CARD_MONO, "普通朗读", "一个声音念全书，最省资源"),
    Triple(CastSceneState.CARD_DUAL, "双声对读", "旁白 + 对白两个音色"),
    Triple(CastSceneState.CARD_MULTI, "沉浸多角色", "每个角色有自己的声音（推荐）")
)

// ---------------------------------------------------------------
// 底部：一屏只允许一个主按钮
// ---------------------------------------------------------------

@Composable
private fun BottomBar(
    ui: OnboardingUi,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onImportModel: () -> Unit,
    onOpenAllVoices: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit
) {
    val capability = ui.capabilities.firstOrNull { it.profileId == ui.profileId }
    Column(
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val primaryText = when (ui.step) {
            1 -> OfflineTtsOnboardingState.step1PrimaryLabel(
                profileReady = ui.profileId in ui.readyProfileIds,
                needsVoicePack = capability?.needsVoicePack == true,
                downloading = ui.downloading,
                progress = ui.progress,
                resumePercent = ui.resumePercent
            )
            2 -> "下一步"
            else -> "开始听书"
        }
        Button(
            onClick = when (ui.step) {
                1 -> when {
                    ui.downloading -> onPause
                    capability?.needsVoicePack == true -> onImportModel
                    else -> onDownload
                }
                else -> onNext
            },
            enabled = !ui.busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(primaryText, style = MaterialTheme.typography.titleSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ui.step > 1) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("上一步") }
            }
            when (ui.step) {
                1 -> OutlinedButton(onClick = onImportModel, modifier = Modifier.weight(1f)) {
                    Text("我有本地包，去导入")
                }
                2 -> OutlinedButton(onClick = onOpenAllVoices, modifier = Modifier.weight(1f)) {
                    Text("全部音色")
                }
                else -> OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) {
                    Text("稍后再说")
                }
            }
        }
        // 跳过不改主按钮语义：每步都能"先用系统语音听"
        if (ui.step != 3) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "先用系统语音听",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onSkip) { Text("跳过配置") }
            }
        }
    }
}
