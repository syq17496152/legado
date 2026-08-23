package io.legado.app.ui.book.read.config

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.speech.SpeechEmotion
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.help.readaloud.speech.SpeechVoiceEngineGroup
import io.legado.app.help.readaloud.speech.SpeechVoiceOption
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composePanelRadius
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.uiTypeface

@Composable
fun SpeechVoiceRoutePickerDialog(
    title: String,
    groups: List<SpeechVoiceEngineGroup>,
    currentRoute: SpeechRoute,
    initialGroupKey: String? = null,
    onDismiss: () -> Unit,
    onRouteSelected: (SpeechRoute) -> Unit,
    onLogin: ((SpeechVoiceEngineGroup) -> Unit)? = null
) {
    val colors = rememberSpeechVoicePickerColors()
    val initialKey = initialGroupKey?.takeIf { key -> groups.any { it.key == key } }
    var selectedGroupKey by remember(groups, initialKey) { mutableStateOf(initialKey) }
    val selectedGroup = selectedGroupKey?.let { key -> groups.firstOrNull { it.key == key } }
    var selectedEmotion by remember(selectedGroupKey, currentRoute.emotionTag) {
        mutableStateOf(selectedGroup?.emotions?.firstOrNull { it.emotionTag == currentRoute.emotionTag })
    }
    Dialog(onDismissRequest = onDismiss) {
        val context = LocalContext.current
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = FontFamily(context.uiTypeface()))
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp),
                color = colors.page,
                shape = RoundedCornerShape(context.composePanelRadius()),
                border = BorderStroke(1.dp, colors.stroke)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                if (selectedGroup == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            color = colors.text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onDismiss) {
                            Text("关闭", color = colors.subText)
                        }
                    }
                }
                if (selectedGroup == null) {
                    EnginePickerList(
                        groups = groups,
                        currentRoute = currentRoute,
                        colors = colors,
                        onGroupClick = { selectedGroupKey = it.key }
                    )
                } else {
                    SpeakerPickerList(
                        group = selectedGroup,
                        currentRoute = currentRoute,
                        selectedEmotion = selectedEmotion,
                        onEmotionChange = { selectedEmotion = it },
                        colors = colors,
                        onRouteSelected = onRouteSelected,
                        onLogin = onLogin
                    )
                }
                }
            }
        }
    }
}

fun speechRouteSummary(
    route: SpeechRoute,
    groups: List<SpeechVoiceEngineGroup>,
    defaultText: String = "使用默认朗读引擎"
): String {
    if (!route.isConfigured) return defaultText
    val group = groups.firstOrNull {
        it.engineType == route.engineType && it.engineValue == route.engineValue
    }
    if (route.engineType == SpeechRoute.ENGINE_HTTP && group == null) return defaultText
    if (route.toneID.isNotBlank() && group?.options?.none { it.toneID == route.toneID } == true) {
        return defaultText
    }
    val engineName = group?.title
    val parts = buildList {
        add(engineName ?: when (route.engineType) {
            SpeechRoute.ENGINE_HTTP -> "HTTP TTS"
            SpeechRoute.ENGINE_SYSTEM -> "系统 TTS"
            else -> "默认朗读"
        })
        route.speakerName.takeIf { it.isNotBlank() }?.let(::add)
        route.emotionName.takeIf { it.isNotBlank() }?.let(::add)
    }
    return parts.distinct().joinToString(" · ")
}

fun routeMatchesGroup(route: SpeechRoute, group: SpeechVoiceEngineGroup): Boolean {
    return route.engineType == group.engineType && route.engineValue == group.engineValue
}

private fun routeMatchesOption(route: SpeechRoute, option: SpeechVoiceOption): Boolean {
    return route.engineType == option.engineType &&
            route.engineValue == option.engineValue &&
            route.toneID == option.toneID &&
            route.speakerName == option.speakerName
}

@Composable
private fun EnginePickerList(
    groups: List<SpeechVoiceEngineGroup>,
    currentRoute: SpeechRoute,
    colors: SpeechVoicePickerColors,
    onGroupClick: (SpeechVoiceEngineGroup) -> Unit
) {
    if (groups.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("暂无朗读引擎", color = colors.subText, fontSize = 14.sp)
        }
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 460.dp)
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(groups, key = { it.key }) { group ->
            val selected = routeMatchesGroup(currentRoute, group)
            PickerRow(
                title = group.title,
                subtitle = group.subtitle,
                selected = selected,
                colors = colors,
                trailing = "发言人",
                onClick = { onGroupClick(group) }
            )
        }
    }
}

@Composable
private fun SpeakerPickerList(
    group: SpeechVoiceEngineGroup,
    currentRoute: SpeechRoute,
    selectedEmotion: SpeechEmotion?,
    onEmotionChange: (SpeechEmotion?) -> Unit,
    colors: SpeechVoicePickerColors,
    onRouteSelected: (SpeechRoute) -> Unit,
    onLogin: ((SpeechVoiceEngineGroup) -> Unit)?
) {
    var query by remember(group.key) { mutableStateOf("") }
    val filteredOptions = remember(group, query) {
        val keyword = query.trim()
        if (keyword.isBlank()) {
            group.options
        } else {
            group.options.filter { option ->
                option.matchesKeyword(keyword) ||
                        group.title.contains(keyword, ignoreCase = true) ||
                        group.subtitle.contains(keyword, ignoreCase = true) ||
                        group.emotions.any {
                            it.emotionName.contains(keyword, ignoreCase = true) ||
                                    it.emotionTag.contains(keyword, ignoreCase = true)
                        }
            }
        }
    }
    Column(modifier = Modifier.padding(top = 10.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            singleLine = true,
            placeholder = {
                Text("搜索发言人 / toneID / 情绪", color = colors.subText, fontSize = 13.sp)
            }
        )
        if (group.warning.isNotBlank()) {
            Text(
                text = group.warning,
                color = colors.danger,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        if (group.emotions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EmotionChip(
                    text = "无情绪",
                    selected = selectedEmotion == null,
                    colors = colors,
                    onClick = { onEmotionChange(null) }
                )
                group.emotions.forEach { emotion ->
                    EmotionChip(
                        text = emotion.emotionName,
                        selected = selectedEmotion?.emotionTag == emotion.emotionTag,
                        colors = colors,
                        onClick = { onEmotionChange(emotion) }
                    )
                }
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 150.dp, max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredOptions, key = { it.key }) { option ->
                PickerRow(
                    title = option.speakerName,
                    subtitle = listOf(option.groupName, option.toneID)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                        .ifBlank { group.title },
                    selected = routeMatchesOption(currentRoute, option),
                    colors = colors,
                    trailing = if (option.explicitSpeaker) "选择" else "默认",
                    onClick = { onRouteSelected(option.toRoute(selectedEmotion)) }
                )
            }
        }
        if (!group.loginUrl.isNullOrBlank() && onLogin != null) {
            Surface(
                onClick = { onLogin(group) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                color = colors.accent.copy(alpha = 0.11f),
                shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
                border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.28f))
            ) {
                Text(
                    text = "登录 / 刷新授权",
                    color = colors.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
                )
            }
        }
    }
}

private fun SpeechVoiceOption.matchesKeyword(keyword: String): Boolean {
    return speakerName.contains(keyword, ignoreCase = true) ||
            groupName.contains(keyword, ignoreCase = true) ||
            toneID.contains(keyword, ignoreCase = true) ||
            engineName.contains(keyword, ignoreCase = true)
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    colors: SpeechVoicePickerColors,
    trailing: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = if (selected) colors.accent.copy(alpha = 0.14f) else colors.card,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        border = BorderStroke(1.dp, if (selected) colors.accent else colors.stroke)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = colors.text,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        color = colors.subText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
            Text(
                text = if (selected) "当前" else trailing,
                color = if (selected) colors.accent else colors.subText,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun EmotionChip(
    text: String,
    selected: Boolean,
    colors: SpeechVoicePickerColors,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (selected) colors.accent.copy(alpha = 0.14f) else colors.card,
        shape = RoundedCornerShape(LocalContext.current.composeActionRadius()),
        border = BorderStroke(1.dp, if (selected) colors.accent else colors.stroke)
    ) {
        Text(
            text = text,
            color = if (selected) colors.accent else colors.text,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

private data class SpeechVoicePickerColors(
    val page: Color,
    val card: Color,
    val text: Color,
    val subText: Color,
    val stroke: Color,
    val accent: Color,
    val danger: Color
)

@Composable
private fun rememberSpeechVoicePickerColors(): SpeechVoicePickerColors {
    val context = LocalContext.current
    val night = AppConfig.isNightTheme
    return SpeechVoicePickerColors(
        page = Color(if (night) 0xff15171b.toInt() else 0xffffffff.toInt()),
        card = Color(if (night) 0xff20242a.toInt() else 0xfff6f7fa.toInt()),
        text = Color(context.primaryTextColor),
        subText = Color(context.secondaryTextColor),
        stroke = Color(if (night) 0x26ffffff else 0x18000000),
        accent = Color(context.accentColor),
        danger = Color(0xffff5555.toInt())
    )
}
