package io.legado.app.ui.main.explore

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.source.exploreKinds
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * 套件「源 → 发现标签」派生与探测的**共享单源**。
 *
 * 原实现内联在 `DiscoverySuiteManageActivity`（编辑器）中；一键生成也需要同一份派生规则，
 * 若各自实现必然漂移 ⇒ 抽到此处两处共用（设计与 tasks 的「禁止第二实现源」口径）。
 */

/** 一个书源的发现标签集合（分组信息已落在每个标签上）。 */
data class DiscoverySuiteSourceTagOptions(
    val sourceName: String,
    val sourceUrl: String,
    val tags: List<DiscoverySuiteTagOption>
)

/** 源站发现页上的一个可绑定标签（分组信息来自 `ExploreKind` 的全宽分组行）。 */
data class DiscoverySuiteTagOption(
    val sourceName: String,
    val sourceUrl: String,
    val tagTitle: String,
    val tagUrl: String,
    val group: String = ""
) {
    val key: String
        get() = "$sourceUrl\n$tagUrl"

    fun toTarget(): DiscoverySuiteWidgetTarget {
        return DiscoverySuiteWidgetTarget(
            sourceUrl = sourceUrl,
            tagUrl = tagUrl,
            title = "$sourceName - $tagTitle"
        )
    }
}

/**
 * 由源的发现规则派生标签选择项。失败时回落空标签（**不抛**），保证调用方 UI 不崩。
 *
 * @param otherGroupLabel 无分组标签的归组文案（由调用方传入本地化字符串，使本函数不依赖 Context）
 */
suspend fun BookSourcePart.buildSourceTagOptions(
    otherGroupLabel: String
): DiscoverySuiteSourceTagOptions {
    return kotlin.runCatching {
        deriveSourceTagOptions(otherGroupLabel)
    }.getOrElse {
        DiscoverySuiteSourceTagOptions(
            sourceName = bookSourceName,
            sourceUrl = bookSourceUrl,
            tags = emptyList()
        )
    }
}

private suspend fun BookSourcePart.deriveSourceTagOptions(
    otherGroupLabel: String
): DiscoverySuiteSourceTagOptions {
    val result = ArrayList<DiscoverySuiteTagOption>()
    var currentGroup = ""
    val kinds = exploreKinds()
    kinds.forEachIndexed { index, kind ->
        if (index == 0 && kind.isSuiteLeadingBlankPlaceholder()) {
            return@forEachIndexed
        }
        val url = kind.normalizedSuiteDiscoverUrl()
        val action = kind.action?.takeIf { it.isNotBlank() }
        if (url.isNullOrBlank() && action.isNullOrBlank() && kind.isSuiteDiscoverGroupKind()) {
            currentGroup = kind.suiteDiscoverGroupTitle()
            return@forEachIndexed
        }
        if (!url.isNullOrBlank()) {
            val tagTitle = kind.suiteDiscoverTagText()
            // 引擎错误串伪装成标签的拦截（铁证 2026-10-09 模拟器实测）：exploreUrl 的 JS 求值失败时，
            // 错误文本会被当作 kind 标题返回并落进候选标签 ⇒ 用户可选中一个永远加载不出书的"假标签"。
            if (tagTitle.looksLikeEngineError()) {
                return@forEachIndexed
            }
            result += DiscoverySuiteTagOption(
                sourceName = bookSourceName,
                sourceUrl = bookSourceUrl,
                tagTitle = tagTitle,
                tagUrl = url,
                group = currentGroup
            )
        }
    }
    val tags = if (result.any { it.group.isNotBlank() }) {
        result.map { if (it.group.isBlank()) it.copy(group = otherGroupLabel) else it }
    } else {
        result
    }
    return DiscoverySuiteSourceTagOptions(
        sourceName = bookSourceName,
        sourceUrl = bookSourceUrl,
        tags = tags.distinctBy { it.key }
    )
}

/** 一键生成的探测结果：一个源及其可用标签目标（纯数据，供 [DiscoverySuiteStarter] 消费）。 */
data class StarterSourceProbe(
    val sourceName: String,
    val targets: List<DiscoverySuiteWidgetTarget>
)

/**
 * 一键生成的**有界探测**（AD-01 / P6）：在候选源里限流 [parallelism] 并发、单源超时
 * [timeoutMs] 即跳过，最多取回 [maxUsableSources] 个**可用**源。
 *
 * P6 修正（2026-10-09 真实源真机实测）：原实现先 `take(5)` 再探测，**无排序也无可用性筛选**，
 * 真实库里常出现"5 个候选全部无标签/不可用" ⇒ 生成出的套件什么都没有，第一印象=功能不可用。
 * 现改为：候选池由调用方给足（见 [STARTER_CANDIDATE_POOL]），**只把"解析出标签"的源计入结果**，
 * 凑满 [maxUsableSources] 个即止。
 *
 * 单源异常/超时**不中断**整体（spec E16）；整体可取消（调用方挂在生命周期 scope 上）。
 * 返回按输入顺序排列的、**至少有 1 个标签**的源（无可用标签的源被剔除）。
 */
suspend fun probeStarterSources(
    sources: List<BookSourcePart>,
    otherGroupLabel: String,
    maxUsableSources: Int = STARTER_MAX_USABLE_SOURCES,
    parallelism: Int = STARTER_PROBE_PARALLELISM,
    timeoutMs: Long = STARTER_PROBE_TIMEOUT_MS,
    onProgress: ((StarterSuiteProgress) -> Unit)? = null
): List<StarterSourceProbe> {
    if (sources.isEmpty() || maxUsableSources <= 0) return emptyList()
    val semaphore = Semaphore(parallelism.coerceAtLeast(1))
    val candidates = sources
    val total = candidates.size
    val done = AtomicInteger(0)
    onProgress?.invoke(StarterSuiteProgress(0, total))
    return coroutineScope {
        candidates.map { source ->
            async {
                semaphore.withPermit {
                    val options = withTimeoutOrNull(timeoutMs) {
                        withContext(IO) { source.buildSourceTagOptions(otherGroupLabel) }
                    }
                    onProgress?.invoke(StarterSuiteProgress(done.incrementAndGet(), total))
                    if (options == null) return@withPermit null
                    val targets = options.tags
                        .map { it.toTarget() }
                        .distinctBy { "${it.sourceUrl}\n${it.tagUrl}" }
                    targets.takeIf { it.isNotEmpty() }?.let {
                        StarterSourceProbe(sourceName = options.sourceName, targets = it)
                    }
                }
            }
        }.awaitAll().filterNotNull().take(maxUsableSources)
    }
}

/**
 * 一键生成全流程：**探测 → 构建 → 落盘 → 读回校验**（AD-01 / AD-07）。
 *
 * 调用方负责：①把本函数挂在生命周期 scope 上（离开页面即取消，spec E3）；
 * ②完成后调用自身的配置刷新（spec E15：生成后主动刷新一次，保证 UI 与写盘一致）。
 * 不在此处弹提示，便于两处入口（发现页空态 / 管理页空态）各自决定反馈形式。
 */
suspend fun runStarterSuite(
    titles: DiscoverySuiteStarter.Titles,
    otherGroupLabel: String,
    onProgress: ((StarterSuiteProgress) -> Unit)? = null
): StarterSuiteResult {
    val config = DiscoverySuiteStore.load()
    if (config.suites.size >= MAX_DISCOVERY_SUITE_COUNT) {
        return StarterSuiteResult.SuiteLimitReached
    }
    // P6：候选池给足 —— 真实库里"排在前面的几个源恰好无标签/不可用"是常态，
    // 只给 5 个候选常出现"一个可用的都凑不齐" ⇒ 生成出空套件。真正的可用性筛选在
    // probeStarterSources 内完成（只计入解析出标签的源，凑满 STARTER_MAX_USABLE_SOURCES 即止）。
    val sources = withContext(IO) {
        appDb.bookSourceDao.allEnabledPart
            .filter { it.enabledExplore && it.hasExploreUrl }
            .take(STARTER_CANDIDATE_POOL)
    }
    if (sources.isEmpty()) return StarterSuiteResult.NoUsableSource
    val probes = probeStarterSources(
        sources = sources,
        otherGroupLabel = otherGroupLabel,
        onProgress = onProgress
    )
    val suite = DiscoverySuiteStarter.build(
        titles = titles,
        probes = probes,
        existingSuiteNames = config.suites.map { it.displayName }.toSet()
    ) ?: return StarterSuiteResult.NoUsableSource

    if (!DiscoverySuiteStore.save(config.copy(suites = config.suites + suite))) {
        return StarterSuiteResult.SaveFailed
    }
    // 读回校验（AD-07）：写盘"成功返回"仍可能是静默失败的历史分支，必须核实真的能读出来
    val reloaded = DiscoverySuiteStore.load()
    if (reloaded.suites.none { it.id == suite.id }) return StarterSuiteResult.SaveFailed
    DiscoverySuiteStore.setSelectedSuiteId(suite.id)
    return StarterSuiteResult.Created(
        suite = suite,
        probeTotal = sources.size,
        probeUsable = probes.size
    )
}

/**
 * 一键生成一次纳入套件的**可用源**上限（凑满即止；P6 前叫 MAX_PROBE_SOURCES，语义已变）。
 * 注意：不是"只探测前 5 个"，而是"从 [STARTER_CANDIDATE_POOL] 里挑出最多 5 个真正解析出标签的源"。
 */
const val STARTER_MAX_USABLE_SOURCES = 5

/**
 * 一键生成的**候选池**大小（P6）：真实库实测大量源无发现标签或规则求值失败，
 * 候选池过小会"一个可用的都凑不齐" ⇒ 生成出空套件（第一印象=功能不可用）。
 * 标签派生是**纯本地规则求值（不联网）**，实测 5 源 <2s ⇒ 24 个候选数秒内可完成，
 * 且有进度回调与取消通道（spec E3）。
 */
const val STARTER_CANDIDATE_POOL = 24

/** 一键生成探测的并发上限。 */
const val STARTER_PROBE_PARALLELISM = 3

/** 一键生成单源探测超时（毫秒）；超时即跳过该源。 */
const val STARTER_PROBE_TIMEOUT_MS = 6_000L

// ---------------------------------------------------------------------------
// ExploreKind 套件派生判据（原在 DiscoverySuiteManageActivity 内 private；抽为 internal 供两处共用）
// ---------------------------------------------------------------------------

/**
 * 引擎错误串伪装成标签的识别（纯函数，可 JVM 单测）。
 *
 * 判据来源（2026-10-09 模拟器实测铁证）：源的 `exploreUrl` 为 JS 规则且求值失败时，
 * Rhino 的错误文本（如 `ERROR:org.mozilla.javascript.EcmaError: TypeError: 无法调用 undefined 的方法 …`）
 * 会被当作 `ExploreKind.title` 返回；该 kind 的 `url` 非空 ⇒ 会落进候选标签，
 * 用户选中后生成一个**永远加载不出书**的控件（正是本轮要消灭的"点了没反应"）。
 */
internal fun String.looksLikeEngineError(): Boolean {
    val value = trim()
    if (value.isEmpty()) return false
    if (value.length > 80) return true
    val lower = value.lowercase()
    return lower.startsWith("error:") ||
        lower.contains("ecmaerror") ||
        lower.contains("evaluatorexception") ||
        lower.contains("referenceerror") ||
        lower.contains("syntaxerror") ||
        lower.contains("typeerror") ||
        value.contains("无法调用")
}

internal fun ExploreKind.normalizedSuiteDiscoverUrl(): String? {
    return url?.trim()?.takeIf {
        it.isNotBlank() && !it.equals("null", ignoreCase = true)
    }
}

internal fun ExploreKind.suiteDiscoverTagText(): String {
    val rawViewName = viewName
    if (!rawViewName.isNullOrBlank() &&
        rawViewName.length in 3..28 &&
        rawViewName.first() == '\'' &&
        rawViewName.last() == '\''
    ) {
        return rawViewName.substring(1, rawViewName.length - 1)
    }
    return title.ifBlank { type }
}

internal fun ExploreKind.suiteDiscoverGroupTitle(): String {
    val raw = suiteDiscoverTagText().trim()
    if (raw.isBlank()) return "分类"
    val normalized = raw
        .replace(Regex("[\\p{So}\\p{Sk}\\uFE0F]+"), " ")
        .replace(Regex("[\\uFF1A:|/\\\\]+"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
    return normalized.ifBlank { raw }
}

internal fun ExploreKind.isSuiteDiscoverGroupKind(): Boolean {
    if (!normalizedSuiteDiscoverUrl().isNullOrBlank()) return false
    if (!action.isNullOrBlank()) return false
    if (!isSuiteFullWidthKind()) return false
    return type == ExploreKind.Type.toggle || action.isNullOrBlank()
}

internal fun ExploreKind.isSuiteLeadingBlankPlaceholder(): Boolean {
    if (!isSuiteFullWidthKind()) return false
    if (!normalizedSuiteDiscoverUrl().isNullOrBlank()) return false
    if (!action.isNullOrBlank()) return false
    val text = suiteDiscoverTagText().trim()
    if (text.isNotBlank() && text != ExploreKind.Type.button) return false
    return type == ExploreKind.Type.button || action.isNullOrBlank()
}

internal fun ExploreKind.isSuiteFullWidthKind(): Boolean {
    val style = style()
    return style.layout_flexBasisPercent >= 0.95f ||
        (style.layout_flexGrow >= 1f && style.layout_flexBasisPercent < 0f)
}

