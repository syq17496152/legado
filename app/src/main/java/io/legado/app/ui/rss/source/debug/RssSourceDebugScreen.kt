package io.legado.app.ui.rss.source.debug

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
import androidx.compose.material.icons.automirrored.filled.Feed
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
import io.legado.app.ui.source.debug.DebugChipRow
import io.legado.app.ui.source.debug.DebugEntryCard
import io.legado.app.ui.source.debug.DebugFilter
import io.legado.app.ui.source.debug.DebugFilterChips
import io.legado.app.ui.source.debug.toEntryUi
import io.legado.app.ui.widget.components.EmptyStatePlaceholder

/**
 * debug-page-redesign：订阅源调试页 Compose Screen（对标 MD3，与书源调试页同构）。
 * 目标：分类（name::url）/ 搜索（关键字）/ 内容（正文/文章 URL）；响应映射：10=列表响应、20=内容响应。
 */

enum class RssDebugTarget(val title: String, val hint: String) {
    CLASSIFY("分类", "分类 key（名称::URL）"),
    SEARCH("搜索", "搜索关键字"),
    CONTENT("内容", "文章 URL"),
}

/** 示例快捷项（分类列表来自 sortUrls） */
data class RssDebugExample(val label: String, val target: RssDebugTarget, val value: String)

private enum class RssDebugPhase { IDLE, RUNNING, FAILED, SUCCESS, CANCELLED }

private fun RssDebugTarget.buildKey(query: String): String = when (this) {
    // Debug.startDebug(rss) 约定：contains("::") → 分类页；isAbsUrl → 内容页；否则 → 搜索关键字
    RssDebugTarget.CLASSIFY -> query
    RssDebugTarget.SEARCH -> query
    RssDebugTarget.CONTENT -> query
}

private fun kindTitle(kind: Int): String = when (kind) {
    -1 -> "错误"
    10 -> "列表响应"
    20 -> "内容响应"
    1000 -> "完成"
    else -> "过程"
}

@Composable
fun RssSourceDebugScreen(
    sourceName: String,
    examples: List<RssDebugExample>,
    onStart: (String) -> Unit,
    onCancel: () -> Unit,
    onShowFull: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val events by Debug.events.collectAsState()
    var query by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(RssDebugTarget.CLASSIFY) }
    var filter by remember { mutableStateOf(DebugFilter.ALL) }
    var phase by remember { mutableStateOf(RssDebugPhase.IDLE) }

    val entries = remember(events) {
        events.mapIndexed { index, event -> event.toEntryUi(index.toLong()) }
    }
    val visibleEntries = remember(entries, filter) {
        entries.filter { filter.matches(it.kind) }
    }

    LaunchedEffect(events.size) {
        val last = events.lastOrNull() ?: return@LaunchedEffect
        if (phase == RssDebugPhase.RUNNING) {
            when (last.kind) {
                -1 -> phase = RssDebugPhase.FAILED
                1000 -> phase = RssDebugPhase.SUCCESS
            }
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(visibleEntries.size, phase) {
        if (phase == RssDebugPhase.RUNNING && visibleEntries.isNotEmpty()) {
            listState.animateScrollToItem(visibleEntries.lastIndex)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                    items(RssDebugTarget.entries, key = { it.name }) { t ->
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
                    enabled = phase != RssDebugPhase.RUNNING,
                    maxLines = 3,
                )
                if (target == RssDebugTarget.CLASSIFY && examples.isNotEmpty()) {
                    DebugChipRow {
                        items(examples, key = { "${it.target.name}:${it.value}" }) { example ->
                            FilterChip(
                                selected = query == example.value,
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
                    icon = Icons.AutoMirrored.Filled.Feed,
                    title = if (phase == RssDebugPhase.RUNNING) "等待调试日志…" else "输入内容并开始调试",
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

        FloatingActionButton(
            onClick = {
                when (phase) {
                    RssDebugPhase.RUNNING -> {
                        phase = RssDebugPhase.CANCELLED
                        onCancel()
                    }
                    else -> {
                        if (query.isBlank()) return@FloatingActionButton
                        phase = RssDebugPhase.RUNNING
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
                imageVector = if (phase == RssDebugPhase.RUNNING) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = if (phase == RssDebugPhase.RUNNING) "停止" else "开始调试",
            )
        }
    }
}
