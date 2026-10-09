package io.legado.app.ui.main.explore

/**
 * 一键生成套件的**纯函数生成规则**（AD-01）。
 *
 * 职责边界：只把「探测结果」翻译成可落盘的 [DiscoverySuite]，**不做 IO、不碰 SharedPreferences**
 * ⇒ 可 JVM 单测、可重跑、可复用（发现页空态与管理页空态共用同一入口）。
 * 探测侧见 [probeStarterSources]（有界 + 限流 + 超时 + 可取消）。
 *
 * 生成口径（固定，见 spec「一键生成套件」）：
 * 1. 取探测成功的前 [PREFERRED_PROBE_SOURCES] 个源（[probes] 顺序即优先级）；
 * 2. Tag 导航栏：每个源贡献其首个标签，合计不超过 [MAX_TAG_BAR_TARGETS]；
 * 3. 横排滑动：第 1 个源的 1 个标签；
 * 4. 瀑布流：第 1 个源的标签，不超过 [MAX_WATERFALL_TARGETS]（瀑布流天然置底）。
 */
object DiscoverySuiteStarter {

    /** 生成时用到的本地化标题（由调用方从 `strings.xml` 取，保持本对象无 Context 依赖）。 */
    data class Titles(
        val suiteName: String,
        val tagBar: String,
        val horizontalBooks: String,
        val waterfallBooks: String
    )

    /** 取探测成功的前 3 个源参与生成。 */
    const val PREFERRED_PROBE_SOURCES = 3

    /** Tag 导航栏最多贡献的标签数。 */
    const val MAX_TAG_BAR_TARGETS = 9

    /** 瀑布流最多绑定的标签数。 */
    const val MAX_WATERFALL_TARGETS = 4

    /**
     * 构建预置套件。
     *
     * @param titles 本地化标题
     * @param probes 探测结果（无可用标签的源会被忽略）
     * @param existingSuiteNames 既有套件名（用于重名追加序号）
     * @return 生成的套件；**无任何可用标签时返回 null**（禁止生成空壳套件，spec「全部书源均无可用发现标签」）
     */
    fun build(
        titles: Titles,
        probes: List<StarterSourceProbe>,
        existingSuiteNames: Set<String> = emptySet()
    ): DiscoverySuite? {
        val usable = probes.filter { it.targets.isNotEmpty() }
        if (usable.isEmpty()) return null

        val primary = usable.first()
        val tagBarTargets = usable
            .take(PREFERRED_PROBE_SOURCES)
            .mapNotNull { it.targets.firstOrNull() }
            .distinctBy { "${it.sourceUrl}\n${it.tagUrl}" }
            .take(MAX_TAG_BAR_TARGETS)
        val horizontalTargets = primary.targets.take(1)
        val waterfallTargets = primary.targets.take(MAX_WATERFALL_TARGETS)

        val widgets = buildList {
            if (tagBarTargets.isNotEmpty()) {
                add(widget(titles.tagBar, DiscoverySuiteWidgetType.TagBar.value, tagBarTargets, 0))
            }
            if (horizontalTargets.isNotEmpty()) {
                add(
                    widget(
                        titles.horizontalBooks,
                        DiscoverySuiteWidgetType.HorizontalBooks.value,
                        horizontalTargets,
                        1
                    )
                )
            }
            if (waterfallTargets.isNotEmpty()) {
                add(
                    widget(
                        titles.waterfallBooks,
                        DiscoverySuiteWidgetType.WaterfallBooks.value,
                        waterfallTargets,
                        2
                    )
                )
            }
        }
        if (widgets.isEmpty()) return null

        val suite = DiscoverySuiteStore.newSuite(uniqueName(titles.suiteName, existingSuiteNames))
        return suite.copy(widgets = widgets)
    }

    /** 用 Store 的默认控件构造（保证 `displayLimit` 等默认值与手工创建完全一致），再覆写目标与标题。 */
    private fun widget(
        title: String,
        type: String,
        targets: List<DiscoverySuiteWidgetTarget>,
        order: Int
    ): DiscoverySuiteWidget {
        return DiscoverySuiteStore.newBookWidget(title, type).copy(
            title = title,
            targets = targets,
            order = order
        )
    }

    /** 重名时追加「 2」「 3」…（与既有套件名比较）。 */
    private fun uniqueName(rawName: String, existing: Set<String>): String {
        val base = rawName.trim().ifBlank { "Suite" }
        if (base !in existing) return base
        var index = 2
        while ("$base $index" in existing) {
            index++
        }
        return "$base $index"
    }
}

/** 一键生成的进度（源计数），供 UI 显示「正在扫描书源 done/total…」。 */
data class StarterSuiteProgress(val done: Int, val total: Int)

/** 一键生成的结果（供调用方给出准确反馈）。 */
sealed interface StarterSuiteResult {
    /** 生成并落盘成功（且已读回校验）。 */
    data class Created(
        val suite: DiscoverySuite,
        val probeTotal: Int,
        val probeUsable: Int
    ) : StarterSuiteResult

    /** 没有任何可用的发现标签（禁止生成空壳套件）。 */
    data object NoUsableSource : StarterSuiteResult

    /** 套件数量已达上限。 */
    data object SuiteLimitReached : StarterSuiteResult

    /** 写盘未生效（如配置超 96KB 上限）——AD-07 读回校验失败。 */
    data object SaveFailed : StarterSuiteResult
}
