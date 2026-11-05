package io.legado.app.ui.replace

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.ai.AiRuleAssistant
import io.legado.app.help.ai.AiRuleAssistant.Confidence
import io.legado.app.help.ai.AiRuleAssistant.Kind
import io.legado.app.help.ai.AiRuleAssistant.Suggestion
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.characterBookKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.llm.LlmClient
import io.legado.app.help.llm.LlmModelManager
import io.legado.app.help.readaloud.casting.AiTaskRouter
import io.legado.app.help.readaloud.role.ClientScriptLlm
import io.legado.app.model.ReadBook
import io.legado.app.service.kernel.ReplaceRuleKernel
import io.legado.app.ui.book.read.config.HighlightRule
import io.legado.app.ui.book.read.config.HighlightRuleDraftStore
import io.legado.app.ui.book.read.config.HighlightRuleGroupStore
import io.legado.app.ui.book.read.config.HighlightRuleStore
import io.legado.app.ui.highlight.HighlightRuleAiFilter
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 6.2 AI 规则助手（design AD-09 / R9）。
 *
 * 三条硬约束：
 * 1. **草稿先行**：所有候选先展示（含样章命中预览），勾选后才进草稿区并 `apply`；
 * 2. **默认只勾确定性规则**：AI 语义类一律不默认勾选，命中正文句读/删除比例过高会强制降级；
 * 3. **必有回滚**：应用后可一键回滚到应用前，用户拒绝过的 pattern 写入书级黑名单不再建议。
 */
class AiRuleAssistantDialog @JvmOverloads constructor(
    private val kind: Kind = Kind.REPLACE,
    private val onApplied: () -> Unit = {},
) : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management
    override val dialogGravity: Int = Gravity.BOTTOM

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                val context = LocalContext.current
                val scope = rememberCoroutineScope()

                var loading by remember { mutableStateOf(true) }
                var busy by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf("") }
                var suggestions by remember { mutableStateOf(listOf<Suggestion>()) }
                var selected by remember { mutableStateOf(setOf<String>()) }
                var expanded by remember { mutableStateOf(setOf<String>()) }
                var blacklist by remember { mutableStateOf(setOf<String>()) }
                var existingPatterns by remember { mutableStateOf(setOf<String>()) }
                var sample by remember { mutableStateOf("") }
                var chapterTexts by remember { mutableStateOf(listOf<String>()) }
                var bookKey by remember { mutableStateOf("") }

                LaunchedEffect(Unit) {
                    val bundle = withContext(Dispatchers.IO) { loadSamples() }
                    if (bundle == null) {
                        message = "请先在阅读页打开一本书，再来生成本书专用的规则"
                        loading = false
                        return@LaunchedEffect
                    }
                    sample = bundle.chapters.joinToString("\n\n")
                    chapterTexts = bundle.chapters
                    bookKey = bundle.bookKey
                    val file = blacklistFile(context, bundle.bookKey)
                    blacklist = withContext(Dispatchers.IO) { AiRuleAssistant.loadBlacklist(file) }
                    existingPatterns = withContext(Dispatchers.IO) {
                        if (kind == Kind.HIGHLIGHT) {
                            HighlightRuleStore.load(context).map { it.pattern }.toSet()
                        } else {
                            emptySet()
                        }
                    }
                    val detected = withContext(Dispatchers.Default) {
                        AiRuleAssistant.detectDeterministic(kind, sample, chapterTexts)
                    }
                    suggestions = filterSuggestions(detected, blacklist, existingPatterns)
                    selected = AiRuleSelection.defaultSelected(suggestions)
                    message = if (suggestions.isEmpty()) {
                        "样章里没发现确定性噪声，可试试本地 AI（语义规则需你逐条确认）"
                    } else {
                        "已扫描 ${chapterTexts.size} 章：${suggestions.size} 条候选，高置信已默认勾选"
                    }
                    loading = false
                }

                AppDialogFrame(
                    title = if (kind == Kind.REPLACE) "AI 规则助手 · 替换规则" else "AI 规则助手 · 高亮规则",
                    message = "生成结果只是草稿：先看命中预览，确认后才生效，可一键回滚。",
                    scrollContent = false,
                    content = {
                        Column(modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
                            Text(
                                text = message,
                                fontSize = 12.sp,
                                color = style.secondaryText,
                                modifier = androidx.compose.ui.Modifier.padding(horizontal = 12.dp)
                            )
                            Spacer(androidx.compose.ui.Modifier.height(6.dp))
                            LazyColumn(
                                modifier = androidx.compose.ui.Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 400.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(suggestions, key = { it.stableKey }) { item ->
                                    SuggestionRow(
                                        item = item,
                                        checked = item.stableKey in selected,
                                        expanded = item.stableKey in expanded,
                                        onCheckedChange = { checked ->
                                            selected = AiRuleSelection.toggle(
                                                selected, item.stableKey, checked
                                            )
                                        },
                                        onTogglePreview = {
                                            expanded = if (item.stableKey in expanded) {
                                                expanded - item.stableKey
                                            } else {
                                                expanded + item.stableKey
                                            }
                                        },
                                        secondaryText = style.secondaryText
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        Row(
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LegadoMiuixActionButton(
                                text = "本地 AI 生成",
                                enabled = !loading && !busy && bookKey.isNotBlank(),
                                palette = style.toMiuixPalette(),
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        message = "本地 AI 正在生成候选…"
                                        val outcome = generateWithAi(context, sample)
                                        message = outcome.second
                                        val filteredAi = filterSuggestions(
                                            outcome.first, blacklist, existingPatterns
                                        )
                                        if (filteredAi.isNotEmpty()) {
                                            suggestions =
                                                AiRuleAssistant.merge(suggestions + filteredAi)
                                            selected = selected +
                                                AiRuleSelection.defaultSelected(filteredAi)
                                        }
                                        busy = false
                                    }
                                }
                            )
                            LegadoMiuixActionButton(
                                text = "应用所选",
                                enabled = !loading && !busy && selected.isNotEmpty(),
                                palette = style.toMiuixPalette(),
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        val chosen = AiRuleSelection.chosen(suggestions, selected)
                                        val result = runCatching {
                                            applySelected(context, chosen)
                                        }
                                        message = result.fold(
                                            onSuccess = { it },
                                            onFailure = { "应用失败：${it.message ?: "未知原因"}" }
                                        )
                                        result.getOrNull()?.let { summary ->
                                            // AD-09：被拒绝的候选写入书级黑名单，下次不再建议
                                            val rejected = AiRuleSelection.rejectedPatterns(
                                                suggestions, chosen
                                            )
                                            withContext(Dispatchers.IO) {
                                                AiRuleAssistant.saveBlacklist(
                                                    blacklistFile(context, bookKey),
                                                    blacklist + rejected
                                                )
                                            }
                                            logApplied(kind, chosen, summary)
                                            onApplied()
                                            dismissAllowingStateLoss()
                                        }
                                        busy = false
                                    }
                                }
                            )
                            LegadoMiuixActionButton(
                                text = "回滚上次",
                                enabled = !loading && !busy,
                                palette = style.toMiuixPalette(),
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        message = runCatching { rollbackLast(context) }
                                            .getOrElse { "回滚失败：${it.message ?: "未知原因"}" }
                                        onApplied()
                                        busy = false
                                    }
                                }
                            )
                            LegadoMiuixActionButton(
                                text = "关闭",
                                palette = style.toMiuixPalette(),
                                onClick = { dismissAllowingStateLoss() }
                            )
                        }
                    }
                )
            }
        }
    }

    // ── 数据装载 ────────────────────────────────────────────────────────────

    private data class SampleBundle(val bookKey: String, val chapters: List<String>)

    /** 取当前书当前章起连续 6 章（正文过短/未缓存时自动跳过，不做网络请求） */
    private suspend fun loadSamples(): SampleBundle? = withContext(Dispatchers.IO) {
        val book = ReadBook.book ?: return@withContext null
        val start = ReadBook.durChapterIndex
        val chapters = (start until start + 6).mapNotNull { index ->
            appDb.bookChapterDao.getChapter(book.bookUrl, index)?.let { chapter ->
                BookHelp.getContent(book, chapter)?.takeIf { it.isNotBlank() }
            }
        }
        if (chapters.isEmpty()) return@withContext null
        SampleBundle(book.characterBookKey(), chapters)
    }

    // ── AI 生成 ─────────────────────────────────────────────────────────────

    /** @return 候选列表 to 面向用户的结果文案 */
    private suspend fun generateWithAi(
        context: Context,
        sample: String
    ): Pair<List<Suggestion>, String> {
        if (sample.isBlank()) return emptyList<Suggestion>() to "样章为空，无法生成"
        val route = AiTaskRouter.routeFor(onlineConfigured = false)
        if (route.executor != AiTaskRouter.Executor.LOCAL) {
            return emptyList<Suggestion>() to "本地 AI 不可用：${route.reason}"
        }
        val client = LlmClient.forContext(context)
        if (!client.ensureConnected()) {
            return emptyList<Suggestion>() to "本地 AI 连接失败，可稍后重试"
        }
        if (!client.status().ready) {
            val id = AppConfig.localLlmProfileId
            val path = runCatching {
                LlmModelManager.forApp(context).find(id)?.modelFile?.absolutePath
            }.getOrNull()
            if (path == null) {
                return emptyList<Suggestion>() to "还没导入本地模型：请到「朗读设置 → 本地 AI」导入"
            }
            val load = client.load(path)
            if (!load.ok) {
                return emptyList<Suggestion>() to "模型加载失败（${load.errorCode}），请重试或改用规则扫描"
            }
        }
        val ai = AiRuleAssistant.aiSuggestions(ClientScriptLlm(client), kind, sample)
        return if (ai.isEmpty()) {
            emptyList<Suggestion>() to AiRuleAssistant.aiFailureHint(null)
        } else {
            ai to "本地 AI 生成 ${ai.size} 条语义候选（默认不勾选，请看命中预览后决定）"
        }
    }

    // ── 应用 / 回滚 ─────────────────────────────────────────────────────────

    private suspend fun applySelected(context: Context, chosen: List<Suggestion>): String {
        if (chosen.isEmpty()) return "没有勾选任何规则"
        return if (kind == Kind.REPLACE) {
            val base = System.currentTimeMillis()
            chosen.forEachIndexed { index, item ->
                ReplaceRuleKernel.draftUpsert(
                    ReplaceRule(
                        id = base + index,
                        name = item.name,
                        group = AI_GROUP,
                        pattern = item.pattern,
                        replacement = item.replacement,
                        isRegex = item.isRegex,
                        scopeContent = true,
                        scopeTitle = false,
                        isEnabled = true
                    )
                )
            }
            val applied = ReplaceRuleKernel.draftApply()
            val count = (applied["applied"] as? Int) ?: chosen.size
            AiRuleSelection.applySummary(kind, count)
        } else {
            val base = System.currentTimeMillis()
            chosen.forEachIndexed { index, item ->
                HighlightRuleDraftStore.upsert(
                    HighlightRule(
                        id = "ai_${base}_$index",
                        name = item.name,
                        pattern = item.pattern,
                        isRegex = item.isRegex,
                        replacement = item.replacement,
                        group = HighlightRuleGroupStore.DEFAULT_GROUP,
                        enabled = true
                    )
                )
            }
            val result = HighlightRuleDraftStore.apply(context)
            AiRuleSelection.applySummary(kind, result.applied)
        }
    }

    private suspend fun rollbackLast(context: Context): String = if (kind == Kind.REPLACE) {
        val result = ReplaceRuleKernel.draftRollback()
        if (result.containsKey("reason")) {
            "没有可回滚的应用（${result["reason"]}）"
        } else {
            AiRuleSelection.rollbackSummary(
                (result["restored"] as? Int) ?: 0,
                (result["deleted"] as? Int) ?: 0
            )
        }
    } else {
        val result = HighlightRuleDraftStore.rollback(context)
        if (result.restored == 0 && result.deleted == 0) {
            "没有可回滚的应用"
        } else {
            AiRuleSelection.rollbackSummary(result.restored, result.deleted)
        }
    }

    /** AD-09：命中审计（规则 id/尺寸 + 命中章节行号），便于真机复现"到底是哪条删的" */
    private fun logApplied(kind: Kind, chosen: List<Suggestion>, result: String) {
        AppLog.put("AI规则助手应用 kind=$kind 条数=${chosen.size} 结果=$result")
        chosen.forEach { item ->
            val lines = item.hits.joinToString(",") { it.lineNo.toString() }
            AppLog.put("AI规则 patternLen=${item.pattern.length} 置信=${item.confidence} 命中行=$lines")
        }
    }

    private fun blacklistFile(context: Context, bookKey: String): File =
        AiRuleAssistant.blacklistFile(File(context.filesDir, BLACKLIST_DIR), bookKey)

    /** 书级黑名单 + 高亮既有规则双重去重（高亮重复建议会让同一段文本被装饰多次） */
    private fun filterSuggestions(
        items: List<Suggestion>,
        blacklist: Set<String>,
        existingPatterns: Set<String>
    ): List<Suggestion> {
        val kept = AiRuleAssistant.filterBlacklisted(items, blacklist)
        return if (kind == Kind.HIGHLIGHT) {
            HighlightRuleAiFilter.dropExisting(kept, existingPatterns)
        } else {
            kept
        }
    }

    // ── 行渲染 ─────────────────────────────────────────────────────────────

    @Composable
    private fun SuggestionRow(
        item: Suggestion,
        checked: Boolean,
        expanded: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        onTogglePreview: () -> Unit,
        secondaryText: androidx.compose.ui.graphics.Color
    ) {
        Row(
            modifier = androidx.compose.ui.Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.Top
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Column(modifier = androidx.compose.ui.Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = androidx.compose.ui.Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = " · ${confidenceLabel(item.confidence)}置信 · 命中${item.hitCount}",
                        fontSize = 11.sp,
                        color = secondaryText
                    )
                }
                Text(
                    text = item.pattern,
                    fontSize = 11.sp,
                    color = secondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.reason.isNotBlank()) {
                    Text(text = item.reason, fontSize = 11.sp, color = secondaryText)
                }
                item.risk?.let {
                    Text(text = "⚠ $it", fontSize = 11.sp, color = secondaryText)
                }
                if (expanded) {
                    item.hits.forEach { hit ->
                        Text(
                            text = "第${hit.lineNo}行：${hit.matched}" +
                                if (hit.replacement.isEmpty()) " → （删除）" else " → ${hit.replacement}",
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (item.hits.isNotEmpty()) {
                    TextButton(onClick = onTogglePreview) {
                        Text(text = if (expanded) "收起预览" else "查看命中预览", fontSize = 11.sp)
                    }
                }
            }
        }
    }

    private fun confidenceLabel(confidence: Confidence): String = when (confidence) {
        Confidence.HIGH -> "高"
        Confidence.MEDIUM -> "中"
        Confidence.LOW -> "低"
    }

    companion object {
        private const val AI_GROUP = "AI 建议"
        private const val BLACKLIST_DIR = "rule_assistant"
    }
}
