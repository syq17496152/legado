package io.legado.app.ui.book.source.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.model.Debug
import io.legado.app.ui.source.debug.DebugEntryCard
import io.legado.app.ui.source.debug.DebugFilter
import io.legado.app.ui.source.debug.DebugFilterChips
import io.legado.app.ui.source.debug.DebugChipRow
import io.legado.app.ui.source.debug.toEntryUi
import io.legado.app.ui.widget.components.EmptyStatePlaceholder

/**
 * debug-page-redesign：书源调试页 Compose Screen（对标 MD3阅读）。
 * 目标 Chips 替代魔法前缀（++/--/::），过滤 Chips + 着色卡片 + 耗时/时间戳 + 点击全文 + FAB 启停。
 * 状态机：Idle → Running →(错误 -1) Failed / (完成 1000) Success / (用户停止) Cancelled。
 */

/** 调试目标（key 拼装规则见 [buildKey]；魔法前缀只出现在此边界，AD-04） */
enum class BookDebugTarget(val title: String, val hint: String) {
    SEARCH("搜索", "搜索关键字"),
    EXPLORE("发现", "发现 URL"),
    INFO("详情", "详情页 URL"),
    TOC("目录", "目录页 URL"),
    CONTENT("正文", "正文页 URL"),
}

/** 示例快捷项（checkKeyWord/我的/系统/发现分类） */
data class DebugExample(val label: String, val target: BookDebugTarget, val value: String)

private enum class DebugPhase { IDLE, RUNNING, FAILED, SUCCESS, CANCELLED }

private fun BookDebugTarget.buildKey(query: String): String = when (this) {
    BookDebugTarget.SEARCH -> query
    // Debug.startDebug 约定：contains("::") → 发现页（name::url），name 仅用于展示
    BookDebugTarget.EXPLORE -> "发现::${query.removePrefix("发现::")}"
    BookDebugTarget.INFO -> query
    BookDebugTarget.TOC -> "++${query.removePrefix("++")}"
    BookDebugTarget.CONTENT -> "--${query.removePrefix("--")}"
}

private fun kindTitle(kind: Int): String = when (kind) {
    -1 -> "错误"
    10 -> "搜索/发现响应"
    20 -> "详情响应"
    30 -> "目录响应"
    40 -> "正文响应"
    1000 -> "完成"
    else -> "过程"
}

@Composable
fun BookSourceDebugScreen(
    sourceName: String,
    examples: List<DebugExample>,
    onStart: (String) -> Unit,
    onCancel: () -> Unit,
    onShowFull: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val events by Debug.events.collectAsState()
    var query by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(BookDebugTarget.SEARCH) }
    var filter by remember { mutableStateOf(DebugFilter.ALL) }
    var phase by remember { mutableStateOf(DebugPhase.IDLE) }

    val entries = remember(events) {
        events.mapIndexed { index, event -> event.toEntryUi(index.toLong()) }
    }
    val visibleEntries = remember(entries, filter) {
        entries.filter { filter.matches(it.kind) }
    }

    // 终态驱动：错误/完成事件到达时收敛状态机
    LaunchedEffect(events.size) {
        val last = events.lastOrNull() ?: return@LaunchedEffect
        if (phase == DebugPhase.RUNNING) {
            when (last.kind) {
                -1 -> phase = DebugPhase.FAILED
                1000 -> phase = DebugPhase.SUCCESS
            }
        }
    }
    // 自动滚动到底（仅 Running，学 MD3）
    val listState = rememberLazyListState()
    LaunchedEffect(visibleEntries.size, phase) {
        if (phase == DebugPhase.RUNNING && visibleEntries.isNotEmpty()) {
            listState.animateScrollToItem(visibleEntries.lastIndex)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 控件卡：源名 + 目标 Chips + 输入 + 示例 Chips + 过滤 Chips
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    sourceName,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                DebugChipRow {
                    items(BookDebugTarget.entries, key = { it.name }) { t ->
                        FilterChip(
                            selected = target == t,
                            onClick = { target = t },
                            label = { Text(t.title) },
                        )
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(target.hint) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = phase != DebugPhase.RUNNING,
                    maxLines = 3,
                )
                if (examples.isNotEmpty()) {
                    DebugChipRow {
                        items(examples, key = { "${it.target.name}:${it.value}" }) { example ->
                            FilterChip(
                                selected = target == example.target && query == example.value,
                                onClick = {
                                    target = example.target
                                    query = example.value
                                },
                                label = { Text(example.label) },
                            )
                        }
                    }
                }
                DebugFilterChips(selected = filter, onSelect = { filter = it })
            }

            if (visibleEntries.isEmpty()) {
                EmptyStatePlaceholder(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    title = if (phase == DebugPhase.RUNNING) "等待调试日志…" else "输入内容并开始调试",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp,
                        top = 4.dp, bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(visibleEntries, key = { it.id }) { entry ->
                        DebugEntryCard(
                            entry = entry,
                            kindTitle = kindTitle(entry.kind),
                            onClick = { onShowFull(kindTitle(entry.kind), entry.message) },
                        )
                    }
                }
            }
        }

        // FAB：开始/停止（学 MD3；Running 时可中途取消，已产出日志保留可导出）
        FloatingActionButton(
            onClick = {
                when (phase) {
                    DebugPhase.RUNNING -> {
                        phase = DebugPhase.CANCELLED
                        onCancel()
                    }
                    else -> {
                        if (query.isBlank()) return@FloatingActionButton
                        phase = DebugPhase.RUNNING
                        onStart(target.buildKey(query))
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Icon(
                imageVector = if (phase == DebugPhase.RUNNING) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = if (phase == DebugPhase.RUNNING) "停止" else "开始调试",
            )
        }
    }
}
