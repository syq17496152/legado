package io.legado.app.ui.main.explore

import io.legado.app.constant.PreferKey
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import splitties.init.appCtx
import java.util.UUID

data class DiscoverySuiteConfig(
    val suites: List<DiscoverySuite> = emptyList()
)

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
    private const val MAX_CONFIG_CHARS = 96 * 1024
    private const val MAX_SUITES = 20
    private const val MAX_WIDGETS_PER_SUITE = 50
    private const val MAX_URLS_PER_WIDGET = 30
    private const val MAX_TARGETS_PER_WIDGET = 30
    private const val MAX_NAME_CHARS = 40
    private const val MAX_TITLE_CHARS = 60
    private const val MAX_ID_CHARS = 64

    fun load(): DiscoverySuiteConfig {
        val raw = appCtx.getPrefString(PreferKey.discoverySuiteConfig).orEmpty()
        if (raw.isBlank() || raw.length > MAX_CONFIG_CHARS) {
            return DiscoverySuiteConfig()
        }
        return runCatching {
            GSON.fromJson(raw, DiscoverySuiteConfig::class.java)?.sanitize()
        }.getOrNull()
            ?: DiscoverySuiteConfig()
    }

    fun save(config: DiscoverySuiteConfig) {
        val sanitized = config.sanitize()
        val json = GSON.toJson(sanitized)
        if (json.length <= MAX_CONFIG_CHARS) {
            appCtx.putPrefString(PreferKey.discoverySuiteConfig, json)
        }
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
                        .let { widgets ->
                            val bottomWidgets = widgets.filter { it.type == DiscoverySuiteWidgetType.WaterfallBooks.value }
                            val regularWidgets = widgets.filterNot { it.type == DiscoverySuiteWidgetType.WaterfallBooks.value }
                            (regularWidgets + bottomWidgets).mapIndexed { sortedIndex, widget ->
                                widget.copy(order = sortedIndex)
                            }
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
