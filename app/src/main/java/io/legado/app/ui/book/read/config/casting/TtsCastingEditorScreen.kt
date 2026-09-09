package io.legado.app.ui.book.read.config.casting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.readaloud.casting.CastingMatchType
import io.legado.app.help.readaloud.casting.CastingProsody
import io.legado.app.help.readaloud.casting.CastingRule
import io.legado.app.help.readaloud.casting.CastingTag
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.help.readaloud.speech.SpeechVoiceCatalogRepository
import io.legado.app.ui.book.read.config.SpeechVoiceRoutePickerDialog
import io.legado.app.ui.widget.compose.rememberAppDialogStyle

/**
 * 选角模板编辑器（EDITOR 态，AD-11）：
 * 直接操作 CastingRule 模型；规则行增删/上下移（数组顺序=首命中优先级）；
 * 同通道/保留字/regex 预编译/pattern 限长校验由 Fragment 保存链执行（错误定位到具体规则行）
 */
@Composable
fun TtsCastingEditorScreen(
    state: TtsCastingManageFragment.EditorState,
    httpTtsList: List<HttpTTS>,
    error: String?,
    previewState: TtsPreviewState?,
    onNameChange: (String) -> Unit,
    onRulesChange: (List<CastingRule>) -> Unit,
    onFallbackChange: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onPreview: (SpeechRoute) -> Unit,
    onStopPreview: () -> Unit
) {
    val style = rememberAppDialogStyle()
    val context = LocalContext.current
    var pickerRuleIndex by remember { mutableStateOf<Int?>(null) }
    var pickerFallback by remember { mutableStateOf(false) }

    // 音色分组目录：系统引擎组+http(type=2) voices 目录组（复用既有归一化链，禁重写）
    val groups = remember(httpTtsList) {
        SpeechVoiceCatalogRepository.systemGroups(context) +
            SpeechVoiceCatalogRepository.httpGroups(httpTtsList)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(style.surface)
    ) {
        // 顶栏：返回 + 名称输入 + 保存
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.back), color = style.accent)
            }
            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = !state.readOnly,
                placeholder = { Text(stringResource(R.string.tts_casting_name_hint), style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodyLarge
            )
            TextButton(onClick = onSave, enabled = !state.readOnly) {
                Text(stringResource(R.string.tts_casting_saved), color = style.accent)
            }
        }
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = style.accent,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        if (state.readOnly) {
            Text(
                text = stringResource(R.string.tts_casting_readonly_hint),
                style = MaterialTheme.typography.bodySmall,
                color = style.secondaryText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            itemsIndexed(state.rules, key = { index, _ -> index }) { index, rule ->
                TtsCastingRuleRow(
                    index = index,
                    rule = rule,
                    groups = groups,
                    readOnly = state.readOnly,
                    previewState = previewState,
                    onRuleChange = { newRule ->
                        onRulesChange(
                            state.rules.toMutableList().apply { set(index, newRule) }
                        )
                    },
                    onDelete = {
                        onRulesChange(
                            state.rules.toMutableList().apply { removeAt(index) }
                        )
                    },
                    onMoveUp = {
                        if (index > 0) {
                            onRulesChange(
                                state.rules.toMutableList().apply {
                                    val tmp = removeAt(index)
                                    add(index - 1, tmp)
                                }
                            )
                        }
                    },
                    onMoveDown = {
                        if (index < state.rules.size - 1) {
                            onRulesChange(
                                state.rules.toMutableList().apply {
                                    val tmp = removeAt(index)
                                    add(index + 1, tmp)
                                }
                            )
                        }
                    },
                    onPickSource = { pickerRuleIndex = index },
                    onPreview = onPreview,
                    onStopPreview = onStopPreview
                )
            }
            // fallbackSource 行（全局兜底声源）
            item {
                TtsCastingSourceRow(
                    label = stringResource(R.string.tts_casting_fallback_source),
                    route = SpeechRoute.fromJson(state.fallbackSourceJson.ifBlank {
                        SpeechRoute(engineType = SpeechRoute.ENGINE_DEFAULT).toJson()
                    }),
                    groups = groups,
                    readOnly = state.readOnly,
                    previewState = previewState,
                    onRouteChange = { onFallbackChange(it.toJson()) },
                    onPreview = onPreview,
                    onStopPreview = onStopPreview
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    // 声源选择弹层（两段式选择器复用，签名对齐既有先例）
    pickerRuleIndex?.let { index ->
        val rule = state.rules.getOrNull(index) ?: return
        SpeechVoiceRoutePickerDialog(
            title = stringResource(R.string.tts_casting_pick_source, index + 1),
            groups = groups,
            currentRoute = rule.source,
            onDismiss = { pickerRuleIndex = null },
            onRouteSelected = { route ->
                onRulesChange(
                    state.rules.toMutableList().apply {
                        set(index, rule.copy(sourceJson = route.toJson()))
                    }
                )
                pickerRuleIndex = null
            }
        )
    }
    if (pickerFallback) {
        val current = SpeechRoute.fromJson(state.fallbackSourceJson.ifBlank {
            SpeechRoute(engineType = SpeechRoute.ENGINE_DEFAULT).toJson()
        })
        SpeechVoiceRoutePickerDialog(
            title = stringResource(R.string.tts_casting_fallback_source),
            groups = groups,
            currentRoute = current,
            onDismiss = { pickerFallback = false },
            onRouteSelected = { route ->
                onFallbackChange(route.toJson())
                pickerFallback = false
            }
        )
    }
}

/**
 * 单条规则行组件（独立文件级组件，红队 R2-P2-13 拆分指引）：
 * tag（固定项+自定义 ai: 角色）× match（builtin_quote/regex/keyword）× pattern × 声源行 × prosody 三滑条
 */
@Composable
private fun TtsCastingRuleRow(
    index: Int,
    rule: CastingRule,
    groups: List<io.legado.app.help.readaloud.speech.SpeechVoiceEngineGroup>,
    readOnly: Boolean,
    previewState: TtsPreviewState?,
    onRuleChange: (CastingRule) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onPickSource: () -> Unit,
    onPreview: (SpeechRoute) -> Unit,
    onStopPreview: () -> Unit
) {
    val style = rememberAppDialogStyle()
    var tagMenuOpen by remember { mutableStateOf(false) }
    var matchMenuOpen by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // 行头：序号 + tag + match + 操作
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = style.secondaryText
            )
            Spacer(Modifier.size(8.dp))
            // tag 选择（AppDropdownMenu 视觉基线）
            Box {
                Text(
                    text = rule.tag,
                    style = MaterialTheme.typography.bodyMedium,
                    color = style.accent,
                    modifier = Modifier.clickable(enabled = !readOnly) { tagMenuOpen = true },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                io.legado.app.ui.widget.components.AppDropdownMenu(
                    expanded = tagMenuOpen,
                    onDismiss = { tagMenuOpen = false },
                    actions = tagActions(rule, onRuleChange)
                )
            }
            Spacer(Modifier.size(12.dp))
            // match 类型切换
            Box {
                Text(
                    text = matchLabel(rule.matchType),
                    style = MaterialTheme.typography.bodyMedium,
                    color = style.secondaryText,
                    modifier = Modifier.clickable(enabled = !readOnly) { matchMenuOpen = true }
                )
                io.legado.app.ui.widget.components.AppDropdownMenu(
                    expanded = matchMenuOpen,
                    onDismiss = { matchMenuOpen = false },
                    actions = listOf(
                        CastingMatchType.BUILTIN_QUOTE,
                        CastingMatchType.REGEX,
                        CastingMatchType.KEYWORD
                    ).map { type ->
                        io.legado.app.ui.widget.components.MenuAction(
                            title = matchLabel(type),
                            checked = rule.matchType == type,
                            onClick = { onRuleChange(rule.copy(matchType = type)) }
                        )
                    }
                )
            }
            Spacer(Modifier.weight(1f))
            if (!readOnly) {
                Text(
                    text = "↑",
                    color = style.secondaryText,
                    modifier = Modifier
                        .clickable(onClick = onMoveUp)
                        .padding(4.dp)
                )
                Text(
                    text = "↓",
                    color = style.secondaryText,
                    modifier = Modifier
                        .clickable(onClick = onMoveDown)
                        .padding(4.dp)
                )
                Text(
                    text = stringResource(R.string.tts_casting_delete),
                    color = style.accent,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .clickable(onClick = onDelete)
                        .padding(4.dp)
                )
            }
        }
        // pattern 输入（builtin_quote 忽略 pattern）
        if (rule.matchType != CastingMatchType.BUILTIN_QUOTE) {
            OutlinedTextField(
                value = rule.pattern,
                onValueChange = { onRuleChange(rule.copy(pattern = it)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !readOnly,
                singleLine = true,
                placeholder = {
                    Text(
                        text = if (rule.matchType == CastingMatchType.REGEX) {
                            stringResource(R.string.tts_casting_regex_hint)
                        } else {
                            stringResource(R.string.tts_casting_keyword_hint)
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                textStyle = MaterialTheme.typography.bodySmall
            )
        }
        // 声源行（摘要+选择+试听）
        TtsCastingSourceRow(
            label = stringResource(R.string.tts_casting_rule_source),
            route = rule.source,
            groups = groups,
            readOnly = readOnly,
            previewState = previewState,
            onRouteChange = { onRuleChange(rule.copy(sourceJson = it.toJson())) },
            onPreview = onPreview,
            onStopPreview = onStopPreview
        )
        // prosody 三滑条（0=跟随全局不下发，0.5~2.0 有效域）
        ProsodySlider(
            label = stringResource(R.string.tts_casting_prosody_rate),
            value = rule.prosody.rate,
            enabled = !readOnly,
            onChange = { onRuleChange(rule.copy(prosody = rule.prosody.copy(rate = it))) }
        )
        ProsodySlider(
            label = stringResource(R.string.tts_casting_prosody_pitch),
            value = rule.prosody.pitch,
            enabled = !readOnly,
            onChange = { onRuleChange(rule.copy(prosody = rule.prosody.copy(pitch = it))) }
        )
        ProsodySlider(
            label = stringResource(R.string.tts_casting_prosody_volume),
            value = rule.prosody.volume,
            enabled = !readOnly,
            onChange = { onRuleChange(rule.copy(prosody = rule.prosody.copy(volume = it))) }
        )
    }
}

/** tag 候选动作（固定项+保留字语义；自定义角色由"编辑角色"入口改，此处仅列出固定项） */
private fun tagActions(
    rule: CastingRule,
    onRuleChange: (CastingRule) -> Unit
): List<io.legado.app.ui.widget.components.MenuAction> {
    val fixed = listOf(CastingTag.NARRATION, CastingTag.DIALOGUE, CastingTag.DIALOGUE_MALE, CastingTag.DIALOGUE_FEMALE)
    return fixed.map { tag ->
        io.legado.app.ui.widget.components.MenuAction(
            title = tag,
            checked = rule.tag == tag,
            onClick = { onRuleChange(rule.copy(tag = tag)) }
        )
    }
}

private fun matchLabel(matchType: String): String = when (matchType) {
    CastingMatchType.REGEX -> "regex"
    CastingMatchType.KEYWORD -> "keyword"
    else -> "quote"
}

/**
 * 声源行（规则声源/兜底声源共用）：摘要显示+点击选择（两段式选择器）+试听按钮
 */
@Composable
private fun TtsCastingSourceRow(
    label: String,
    route: SpeechRoute,
    groups: List<io.legado.app.help.readaloud.speech.SpeechVoiceEngineGroup>,
    readOnly: Boolean,
    previewState: TtsPreviewState?,
    onRouteChange: (SpeechRoute) -> Unit,
    onPreview: (SpeechRoute) -> Unit,
    onStopPreview: () -> Unit
) {
    val style = rememberAppDialogStyle()
    var picking by remember { mutableStateOf(false) }
    val summary = io.legado.app.ui.book.read.config.speechRouteSummary(
        route, groups, stringResource(R.string.tts_casting_source_default)
    )
    val previewing = previewState?.phase != TtsPreviewState.Phase.IDLE
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = style.secondaryText
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = style.primaryText,
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = !readOnly) { picking = true },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (previewing && previewState?.key != null) {
            TextButton(onClick = onStopPreview) {
                Text(
                    if (previewState.phase == TtsPreviewState.Phase.LOADING) {
                        stringResource(R.string.tts_preview_loading)
                    } else {
                        stringResource(R.string.tts_preview_stop)
                    },
                    color = style.accent
                )
            }
        } else {
            TextButton(onClick = { onPreview(route) }) {
                Text(stringResource(R.string.tts_preview_action), color = style.accent)
            }
        }
    }
    if (picking) {
        SpeechVoiceRoutePickerDialog(
            title = label,
            groups = groups,
            currentRoute = route,
            onDismiss = { picking = false },
            onRouteSelected = { selected ->
                onRouteChange(selected)
                picking = false
            }
        )
    }
}

