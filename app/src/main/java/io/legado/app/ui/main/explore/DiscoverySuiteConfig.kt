package io.legado.app.ui.main.explore

import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import splitties.init.appCtx
import java.util.UUID

// @Keep 铁律（Gson 反射模型）：R8 收窄后泛型签名丢失会使 List<DiscoverySuite> 反序列化成
// LinkedTreeMap ⇒ sanitize() 抛 ClassCastException、整份配置被静默丢弃（曾致「新建套件已落盘但列表读不出」）。
// 项目内所有 Gson 持久化模型（TopBarConfig.Config / MainBottomNavConfig.ItemState 等）一律 @Keep。
@Keep
data class DiscoverySuiteConfig(
    val suites: List<DiscoverySuite> = emptyList()
)

@Keep
data class DiscoverySuite(
    val id: String = "",
    val name: String = "",
    val alias: String = "",
    val opacityMultiplier: Float = 1f,
    val order: Int = 0,
    val widgets: List<DiscoverySuiteWidget> = emptyList()
) {
    val displayName: String
        get() = alias.ifBlank { name }
}

@Keep
data class DiscoverySuiteWidget(
    val id: String = "",
    val type: String = DiscoverySuiteWidgetType.BookList.value,
    val title: String = "",
    val targets: List<DiscoverySuiteWidgetTarget> = emptyList(),
    val sourceUrls: List<String> = emptyList(),
    val tagUrls: List<String> = emptyList(),
    val displayLimit: Int = DEFAULT_WIDGET_DISPLAY_LIMIT,
    val order: Int = 0
)

@Keep
data class DiscoverySuiteWidgetTarget(
    val sourceUrl: String = "",
    val tagUrl: String = "",
    val title: String = ""
)

enum class DiscoverySuiteWidgetType(val value: String) {
    RandomBooks("random_books"),
    TagBar("tag_bar"),
    RankButtons("rank_buttons"),
    BookList("book_list"),
    HorizontalBooks("horizontal_books"),
    RankedList("ranked_list"),
    WaterfallBooks("waterfall_books");

    companion object {
        fun sanitize(value: String): String {
            return when (value) {
                TagBar.value -> TagBar.value
                RankButtons.value -> RankButtons.value
                HorizontalBooks.value -> HorizontalBooks.value
                RankedList.value -> RankedList.value
                WaterfallBooks.value -> WaterfallBooks.value
                RandomBooks.value,
                BookList.value -> RandomBooks.value
                else -> RandomBooks.value
            }
        }
    }
}

object DiscoverySuiteStore {
    private const val MAX_CONFIG_CHARS = MAX_DISCOVERY_SUITE_CONFIG_CHARS
    private const val MAX_SUITES = MAX_DISCOVERY_SUITE_COUNT
    private const val MAX_WIDGETS_PER_SUITE = 50
    private const val MAX_URLS_PER_WIDGET = 30
    private const val MAX_TARGETS_PER_WIDGET = MAX_WIDGET_TARGET_COUNT
    private const val MAX_NAME_CHARS = 40
    private const val MAX_TITLE_CHARS = 60
    private const val MAX_ID_CHARS = 64

    fun load(): DiscoverySuiteConfig {
        val raw = appCtx.getPrefString(PreferKey.discoverySuiteConfig).orEmpty()
        if (raw.isBlank() || raw.length > MAX_CONFIG_CHARS) {
            return DiscoverySuiteConfig()
        }
        return kotlin.runCatching {
            GSON.fromJson(raw, DiscoverySuiteConfig::class.java)?.sanitize()
        }.onFailure {
            // 失败即记（无噪声）：此前 runCatching 静默吞异常，导致「配置落盘但读不出」长期无迹可查
            AppLog.put("套件配置解析失败: ${it.localizedMessage}", it)
        }.getOrNull()
            ?: DiscoverySuiteConfig()
    }

    /**
     * 写盘（AD-07）：返回**是否真正落盘**。
     *
     * 原实现超 [MAX_CONFIG_CHARS] 时**静默不写**（无返回、无提示）⇒ 新写入口（一键生成 / 控件上移下移 /
     * 删除控件）会表现为「点了没反应」。此处把写盘结果可判定化，调用方据此读回校验并给出提示。
     * 兼容性：`Unit` → `Boolean` 对既有调用点（忽略返回值的写法）零改动。
     */
    fun save(config: DiscoverySuiteConfig): Boolean {
        val sanitized = config.sanitize()
        val json = GSON.toJson(sanitized)
        if (!isDiscoverySuiteConfigWithinLimit(json)) return false
        appCtx.putPrefString(PreferKey.discoverySuiteConfig, json)
        return true
    }

    fun selectedSuiteId(): String {
        return appCtx.getPrefString(PreferKey.selectedDiscoverySuiteId).orEmpty()
    }

    fun setSelectedSuiteId(id: String) {
        appCtx.putPrefString(PreferKey.selectedDiscoverySuiteId, id.take(MAX_ID_CHARS))
    }

    fun newSuite(name: String): DiscoverySuite {
        return DiscoverySuite(
            id = newId("suite"),
            name = name.cleanName().ifBlank { "Suite" },
            order = Int.MAX_VALUE
        )
    }

    fun newBookWidget(
        title: String,
        type: String = DiscoverySuiteWidgetType.RandomBooks.value
    ): DiscoverySuiteWidget {
        val sanitizedType = DiscoverySuiteWidgetType.sanitize(type)
        return DiscoverySuiteWidget(
            id = newId("widget"),
            title = title.cleanTitle().ifBlank { "Books" },
            type = sanitizedType,
            displayLimit = when (sanitizedType) {
                DiscoverySuiteWidgetType.TagBar.value -> 30
                DiscoverySuiteWidgetType.RankButtons.value -> 9
                DiscoverySuiteWidgetType.HorizontalBooks.value -> DEFAULT_WIDGET_DISPLAY_LIMIT
                DiscoverySuiteWidgetType.RankedList.value -> DEFAULT_RANKED_WIDGET_BOOK_COUNT
                DiscoverySuiteWidgetType.WaterfallBooks.value -> DEFAULT_WATERFALL_WIDGET_BOOK_COUNT
                else -> DEFAULT_RANDOM_WIDGET_POOL_LIMIT
            },
            order = Int.MAX_VALUE
        )
    }

    private fun DiscoverySuiteConfig.sanitize(): DiscoverySuiteConfig {
        val suites = suites.orEmpty()
            .asSequence()
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .sortedBy { it.order }
            .take(MAX_SUITES)
            .mapIndexed { index, suite ->
                suite.copy(
                    id = suite.id.take(MAX_ID_CHARS),
                    name = suite.name.cleanName().ifBlank { "Suite ${index + 1}" },
                    alias = suite.alias.cleanName(),
                    opacityMultiplier = suite.opacityMultiplier.coerceIn(1f, 4f),
                    order = index,
                    widgets = suite.widgets.orEmpty()
                        .asSequence()
                        .filter { it.id.isNotBlank() }
                        .distinctBy { it.id }
                        .sortedBy { it.order }
                        .take(MAX_WIDGETS_PER_SUITE)
                        .mapIndexed { widgetIndex, widget ->
                            val cleanType = DiscoverySuiteWidgetType.sanitize(widget.type)
                            widget.copy(
                                id = widget.id.take(MAX_ID_CHARS),
                                type = cleanType,
                                title = widget.title.cleanTitle()
                                    .ifBlank { "Books ${widgetIndex + 1}" },
                                targets = widget.targets.orEmpty().cleanTargets(),
                                sourceUrls = widget.sourceUrls.orEmpty().cleanUrls(),
                                tagUrls = widget.tagUrls.orEmpty().cleanUrls(),
                                displayLimit = when (cleanType) {
                                    DiscoverySuiteWidgetType.TagBar.value -> widget.displayLimit.coerceIn(1, 30)
                                    DiscoverySuiteWidgetType.RankButtons.value -> widget.displayLimit.coerceIn(3, 9)
                                    DiscoverySuiteWidgetType.RankedList.value -> widget.displayLimit.coerceIn(4, 8)
                                    DiscoverySuiteWidgetType.WaterfallBooks.value -> widget.displayLimit.coerceIn(8, 40)
                                    else -> widget.displayLimit.coerceIn(4, 60)
                                },
                                order = widgetIndex
                            )
                        }
                        .toList()
                        // 瀑布流置底单源（AD-06）：原内联 filterNot+filter 与本文件底部扩展同语义，统一调用
                        .withWaterfallPinnedBottom()
                        .mapIndexed { sortedIndex, widget ->
                            widget.copy(order = sortedIndex)
                        }
                )
            }
            .toList()
        return DiscoverySuiteConfig(suites)
    }

    private fun List<String>.cleanUrls(): List<String> {
        return asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_URLS_PER_WIDGET)
            .toList()
    }

    private fun List<DiscoverySuiteWidgetTarget>.cleanTargets(): List<DiscoverySuiteWidgetTarget> {
        return asSequence()
            .map {
                DiscoverySuiteWidgetTarget(
                    sourceUrl = it.sourceUrl.trim(),
                    tagUrl = it.tagUrl.trim(),
                    title = it.title.cleanTitle()
                )
            }
            .filter { it.sourceUrl.isNotEmpty() && it.tagUrl.isNotEmpty() }
            .distinctBy { "${it.sourceUrl}\n${it.tagUrl}" }
            .take(MAX_TARGETS_PER_WIDGET)
            .toList()
    }

    private fun String.cleanName(): String {
        return trim().take(MAX_NAME_CHARS)
    }

    private fun String.cleanTitle(): String {
        return trim().take(MAX_TITLE_CHARS)
    }

    private fun newId(prefix: String): String {
        return "$prefix-${UUID.randomUUID()}"
    }
}

const val DEFAULT_WIDGET_DISPLAY_LIMIT = 12
const val DEFAULT_RANDOM_WIDGET_POOL_LIMIT = 36
const val DEFAULT_RANKED_WIDGET_BOOK_COUNT = 4
const val DEFAULT_WATERFALL_WIDGET_BOOK_COUNT = 24

/** 单个控件允许绑定的标签数上限（与 [DiscoverySuiteStore] 的裁剪口径单源）。 */
const val MAX_WIDGET_TARGET_COUNT = 30

/** 套件配置串的最大安全长度（96KB）。超限即拒绝写盘。 */
const val MAX_DISCOVERY_SUITE_CONFIG_CHARS = 96 * 1024

/** 套件数量上限（与 [DiscoverySuiteStore] 的裁剪口径单源）。 */
const val MAX_DISCOVERY_SUITE_COUNT = 20

/**
 * 配置串是否在上限内（纯函数，供 JVM 单测）。
 * 与 [DiscoverySuiteStore.save] 的写盘判据**同源**，避免"判据与写盘规则分叉"。
 */
fun isDiscoverySuiteConfigWithinLimit(json: String): Boolean {
    return json.length <= MAX_DISCOVERY_SUITE_CONFIG_CHARS
}

/**
 * 控件类型的「标签数量」约束（单源真值）。
 *
 * 编辑器约束徽标与保存校验（`DiscoverySuiteManageActivity.widgetTargetsError`）共用本判据，
 * 避免两处规则各自实现后漂移。
 * 注意：约束的是**标签数**（`targets.size`），与控件的 `displayLimit`（一次展示的书本数）无关。
 */
data class WidgetTargetConstraint(val min: Int, val max: Int) {
    fun accepts(size: Int): Boolean = size in min..max
}

/** 按控件类型取标签数量约束；未知/历史类型回落「至少 1 个、至多 [MAX_WIDGET_TARGET_COUNT]」。 */
fun widgetTargetsConstraint(type: String): WidgetTargetConstraint {
    return when (DiscoverySuiteWidgetType.sanitize(type)) {
        DiscoverySuiteWidgetType.HorizontalBooks.value -> WidgetTargetConstraint(1, 1)
        DiscoverySuiteWidgetType.RankButtons.value,
        DiscoverySuiteWidgetType.RankedList.value -> WidgetTargetConstraint(3, 9)
        else -> WidgetTargetConstraint(1, MAX_WIDGET_TARGET_COUNT)
    }
}

/**
 * 瀑布流置底（单源）：瀑布流控件固定在所有控件底部。
 *
 * 该语义曾被三处各自实现（本文件 `sanitize()` 内联 / 管理页 `reorderWidgets` 的私有扩展 /
 * 本次新增的发现页长按排序）⇒ 统一收敛到此处（AD-06），保证规则单源可测。
 */
fun List<DiscoverySuiteWidget>.withWaterfallPinnedBottom(): List<DiscoverySuiteWidget> {
    return filterNot { it.type == DiscoverySuiteWidgetType.WaterfallBooks.value } +
        filter { it.type == DiscoverySuiteWidgetType.WaterfallBooks.value }
}

/**
 * 切换编辑器里一个标签的选中态（纯函数，供 JVM 单测）。
 *
 * - [singleSelection] = true ⇒ **选中即替换**（横排滑动只允许 1 个标签，spec「横排滑动类型的单选约束」）；
 * - 否则取消选中直接移除；选中时受 [maxCount] 约束（已达上限则忽略，spec「排行榜类型 3-9 约束」）。
 */
fun toggleSuiteTargetKey(
    selectedKeys: Set<String>,
    key: String,
    singleSelection: Boolean,
    maxCount: Int
): Set<String> {
    if (singleSelection) return setOf(key)
    if (key in selectedKeys) return selectedKeys - key
    if (selectedKeys.size >= maxCount) return selectedKeys
    return selectedKeys + key
}
